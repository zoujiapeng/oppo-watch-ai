#!/usr/bin/env node
/**
 * apply-dsh-attachment-fsync-patch.mjs
 *
 * 修掉 DSH 在 Android 上的一个致命 bug：**图片永远存不下来**。
 *
 * 现象
 *   `mobile ... screenshot=true` 和 `read_image` 一律报
 *   `EINVAL: invalid argument, fsync`，于是模型完全失去视觉。
 *
 * 根因
 *   dsh-attachment-local 的 `ensureDurableHome()` 会从 DSH_HOME 一路 fsync
 *   到文件系统根，**包括 `/`**。本机 `/` 是只读 rootfs，它的目录 fsync
 *   返回 EINVAL；而这个调用在 try/catch 之外，所以每次都裸抛。
 *
 * 修法
 *   让 `syncDirectory()` 容忍 EINVAL/ENOTSUP/ENOSYS/EBADF（这些挂载上目录
 *   durability 本就不可用）。丢掉的是「某个目录项的抗崩溃性」，换来的是
 *   整条图片链路能跑 —— 这笔账很划算。
 *
 * 为什么要这个脚本
 *   DSH 装在 node_modules 里，`pnpm install` / 升级会把补丁冲掉。
 *   本脚本幂等，随时重跑即可。
 *
 * 用法
 *   node patches/apply-dsh-attachment-fsync-patch.mjs          # 打补丁（已打则跳过）
 *   node patches/apply-dsh-attachment-fsync-patch.mjs --check  # 只检查，不改动
 *
 * 打完必须重启 DSH 才生效（ESM 模块在启动时加载，DSH 的 HMR 只监听 profile
 * 配置文件、不监听 node_modules）：
 *   pkill -f 'dsh/lib/bin.js' && dshui
 */

import { readFile, writeFile } from "node:fs/promises";
import { glob } from "node:fs/promises";
import { createRequire } from "node:module";

const MARKER = "DIRECTORY_FSYNC_UNSUPPORTED";
const CHECK_ONLY = process.argv.includes("--check");

const ORIGINAL = `async function syncDirectory(path) {
	/* v8 ignore next -- Windows cannot open directory handles; NTFS metadata journaling owns entry durability there. */
	if (process.platform === "win32") return;
	/* v8 ignore start -- Windows cannot exercise directory fsync; POSIX behavior tests enforce this peer. */
	const handle = await open(path, constants.O_RDONLY);
	try {
		await handle.sync();
	} finally {
		await handle.close();
	}
	/* v8 ignore stop */
}`;

const PATCHED = `/**
* LOCAL PATCH (Android/Termux) -- see
* \`oppo-watch-ai/patches/apply-dsh-attachment-fsync-patch.mjs\`.
*
* \`ensureDurableHome\` walks from DSH_HOME up to the filesystem root and fsyncs
* every ancestor, including \`/\`. On this device \`/\` is the read-only rootfs,
* whose fs rejects \`fsync\` on a directory descriptor with EINVAL, so the walk
* throws from outside any try/catch and *every* image attachment fails to
* persist -- \`mobile screenshot=true\` and \`read_image\` alike.
*
* Directory durability is simply unavailable on such a mount. Skipping it
* costs crash-durability of one directory entry; failing the write costs the
* whole image pipeline, so the unsupported codes are tolerated here.
*/
const DIRECTORY_FSYNC_UNSUPPORTED = /* @__PURE__ */ new Set([
	"EINVAL",
	"ENOTSUP",
	"ENOSYS",
	"EBADF"
]);
let warnedDirectoryFsyncUnsupported = false;
async function syncDirectory(path) {
	/* v8 ignore next -- Windows cannot open directory handles; NTFS metadata journaling owns entry durability there. */
	if (process.platform === "win32") return;
	/* v8 ignore start -- Windows cannot exercise directory fsync; POSIX behavior tests enforce this peer. */
	const handle = await open(path, constants.O_RDONLY);
	try {
		await handle.sync();
	} catch (error) {
		if (!(error instanceof Error && "code" in error && DIRECTORY_FSYNC_UNSUPPORTED.has(error.code))) throw error;
		if (!warnedDirectoryFsyncUnsupported) {
			warnedDirectoryFsyncUnsupported = true;
			process.emitWarning(\`directory fsync unsupported on \${path} (\${error.code}); continuing without it\`, { code: "DSH_DIR_FSYNC_UNSUPPORTED" });
		}
	} finally {
		await handle.close();
	}
	/* v8 ignore stop */
}`;

/** 找到所有 dsh-attachment-local 的 lib/index.js（正常情况下只有一个）。 */
async function targets() {
  const roots = [];
  // Termux 全局安装路径优先，其次从 dsh 入口反推
  roots.push("/data/data/com.termux/files/usr/lib/node_modules");
  try {
    const require = createRequire(import.meta.url);
    const bin = require.resolve("@deepseek-ai/dsh/lib/bin.js");
    roots.push(bin.slice(0, bin.indexOf("/@deepseek-ai/dsh/")));
  } catch { /* dsh 不在解析路径里就算了 */ }

  const found = new Set();
  for (const root of roots) {
    for await (const f of glob(
      `${root}/**/dsh-attachment-local/lib/index.js`)) {
      found.add(f);
    }
  }
  return [...found];
}

const files = await targets();
if (files.length === 0) {
  console.error("找不到 dsh-attachment-local/lib/index.js —— DSH 装哪儿了？");
  process.exit(2);
}

let changed = 0;
for (const file of files) {
  const src = await readFile(file, "utf8");
  if (src.includes(MARKER)) {
    console.log(`已打过补丁，跳过：${file}`);
    continue;
  }
  if (!src.includes(ORIGINAL)) {
    console.warn(`⚠️  版本对不上（syncDirectory 原文变了），需人工确认：${file}`);
    process.exitCode = 1;
    continue;
  }
  if (CHECK_ONLY) {
    console.log(`需要打补丁：${file}`);
    process.exitCode = 1;
    continue;
  }
  await writeFile(file, src.replace(ORIGINAL, PATCHED), "utf8");
  console.log(`✅ 已打补丁：${file}`);
  changed++;
}

if (changed > 0) {
  console.log("\n重启 DSH 后生效：");
  console.log("  pkill -f 'dsh/lib/bin.js'; dshui");
}
