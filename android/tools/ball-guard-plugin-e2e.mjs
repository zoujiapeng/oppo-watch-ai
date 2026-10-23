#!/usr/bin/env node
/**
 * 插件侧「球让路」代码的真机贯通测试（不需要重启 DSH）。
 *
 * 为什么要有它：DSH 正在跑的插件是内存里的旧代码，重启 DSH 才能生效（用户自己选时机）。
 * 但插件里那段协议代码本身可以**原样**跑起来验：这个脚本从
 * ~/dsh-preset-mobile-use/lib/index.js 里把 `resolveBallGuardDir` + `waitForBallStandDown`
 * 整段切出来（不是抄一份，是同一份源码），在真机上调用它，同时用 dumpsys 观察
 * **真实的悬浮球**是否在同一时刻退出命中测试。
 *
 * 期望结果：
 *   1) waitForBallStandDown("click") 在 ~100ms 内拿到球的 ACK（ok=true）
 *   2) 就在这一刻，球的代理触摸窗口 dumpsys 里出现 NOT_TOUCHABLE
 *   3) 1.5s 迟滞过后自动恢复 touchable（用户又能点/拖）
 *   4) 球不在/不回 ACK 时，最多等 500ms 就 fail-open 返回
 *
 * 用法：node android/tools/ball-guard-plugin-e2e.mjs
 */

import fs from "node:fs";
import { execFileSync } from "node:child_process";

const PLUGIN = "/data/data/com.termux/files/home/dsh-preset-mobile-use/lib/index.js";
const PKG = "com.glslplayground.app";

// ── 1) 从真实插件源码里切出协议代码 ──────────────────────────────────────────
const src = fs.readFileSync(PLUGIN, "utf8");
const begin = src.indexOf("function resolveBallGuardDir()");
const end = src.indexOf("function planScreenshotDelivery(");
if (begin < 0 || end < 0 || end <= begin) {
	console.error("[FAIL] 切不出插件里的协议代码（插件结构变了？）");
	process.exit(2);
}
// 整段（含常量）原样取出 → 测的就是插件里跑的那份代码
const block = src.slice(begin, end);

// 同一份源码，在一个临时模块里求值
const modPath = "/data/local/tmp/ball-guard-plugin-block.mjs";
fs.writeFileSync(
	modPath,
	`import fs from "node:fs";\n` +
		// 常量声明在 block 起点之前，这里补上（值与插件一致）
		`const BALL_GUARD_DIR =\n\tprocess.env.DSH_BALL_GUARD_DIR || "/sdcard/Android/data/com.glslplayground.app/files";\n` +
		block +
		`export { resolveBallGuardDir, waitForBallStandDown, BALL_GUARD_DIR, BALL_GUARD_ACK_TIMEOUT_MS };\n`,
);
const { resolveBallGuardDir, waitForBallStandDown } = await import(modPath);

// ── 2) 观察真实球窗口 ────────────────────────────────────────────────────────
const ACTIONS = new Set(["click", "swipe", "key"]);
const appAct = process.argv[2] || "click";
if (!ACTIONS.has(appAct)) {
	console.error(`[FAIL] 参数只能是 click/swipe/key（收到 ${appAct}）`);
	process.exit(2);
}

// 复用同一个判定口径：按窗口名找代理层（见 ball-proxy-state.py）
function proxyState() {
	return execFileSync("python3", ["/data/data/com.termux/files/home/oppo-watch-ai/android/tools/ball-proxy-state.py"], {
		encoding: "utf8",
	}).trim();
}

async function waitState(want, timeoutMs) {
	const until = Date.now() + timeoutMs;
	let last = "";
	while (Date.now() < until) {
		last = proxyState();
		if (last.startsWith(want)) return last;
		await new Promise((r) => setTimeout(r, 50));
	}
	return last;
}

const alive = execFileSync("sh", ["-c", `pidof ${PKG} || true`], { encoding: "utf8" }).trim();
if (!alive) {
	console.error(`[FAIL] ${PKG} 没在跑：先在手机上打开 App 并点「悬浮于桌面」`);
	process.exit(2);
}

let failed = 0;
const ok = (m) => console.log(`[ok] ${m}`);
const bad = (m) => { failed++; console.log(`[FAIL] ${m}`); };

console.log("== 0) 前置 ==");
console.log(`[ball] pid=${alive}`);
console.log(`[ball] 球让路代码目录 = ${resolveBallGuardDir()}`);
console.log(`[ball] 信号前：${proxyState()}`);

console.log("\n== 1) 调用插件真实代码 waitForBallStandDown('" + appAct + "') ==");
const t0 = Date.now();
const res = await waitForBallStandDown(appAct, undefined);
const dt = Date.now() - t0;
console.log(`[ball] 返回 ok=${res.ok} reason=${res.reason || "-"} nonce=${res.nonce || "-"} 用时=${dt}ms`);
if (res.ok) ok(`球回了 ACK（握手成功，${dt}ms）`);
else bad(`没拿到 ACK：${res.reason}`);

console.log("\n== 2) 就在这一刻，真实球窗口应当已退出命中测试 ==");
const now = proxyState();
console.log(`[ball] 现场状态：${now}`);
if (now.startsWith("notTouchable")) ok("球此刻不接触摸 → agent 的触摸不会落到球上");
else bad("球仍可触摸（握手成功却没让路？）");

console.log("\n== 3) 1.5s 迟滞过后自动恢复可触摸 ==");
const after = await waitState("touchable", 4000);
console.log(`[ball] 恢复后：${after}`);
if (after.startsWith("touchable")) ok("球恢复可触摸（用户手指能点/拖）");
else bad(`没有恢复：${after}`);

console.log("\n== 4) 球不在/写不进信号时，必须快速放行（fail-open）==");
// 指向一个不存在的目录来模拟「球没装/目录没了」：
// 注意不能靠"把 ACK 改名"——球 20ms 就会把新 ACK 写回来，根本挡不住。
// 模块常量在 import 时就定下来了，所以用子进程 + 环境变量来切目录
const bogusDir = "/sdcard/Android/data/com.glslplayground.app/files/e2e-no-such-dir";
const probe = `
	import("${modPath}").then(async (m) => {
		const t = Date.now();
		const r = await m.waitForBallStandDown("${appAct}", undefined);
		console.log(JSON.stringify({ ok: r.ok, reason: r.reason || "", dt: Date.now() - t }));
	});
`;
const t1 = Date.now();
let childOut = "";
try {
	childOut = execFileSync(process.execPath, ["--input-type=module", "-e", probe], {
		encoding: "utf8",
		env: { ...process.env, DSH_BALL_GUARD_DIR: bogusDir, LD_PRELOAD: "" },
		timeout: 15000,
	}).trim();
} catch (e) {
	childOut = String(e.stdout || "").trim();
}
const dt2 = Date.now() - t1;
let parsed = null;
try { parsed = JSON.parse(childOut.split("\n").pop()); } catch (_) {}
console.log(`[ball] 子进程（信号目录=${bogusDir}）返回 ${childOut || "(空)"}，整体 ${dt2}ms`);
// 2026-10-23 起有两种合法的 fail-open：
//   · 目录里没有心跳（球没在跑 / 目录不对）→ 根本不发信号，ok=true 立刻返回（~0ms）；
//   · 心跳在、但 ACK 迟迟不来（球卡住）→ 最多等 BALL_GUARD_ACK_TIMEOUT_MS(500ms)，ok=false。
// 两种都必须在 1.5s 内放行，调用方不被拖住。
if (parsed && parsed.dt <= 1500) {
	ok(parsed.ok
		? `球没心跳时立刻放行（${parsed.dt}ms，一个字节都没写，调用方照发动作）`
		: `球在跑但不回 ACK 时超时放行（${parsed.dt}ms，调用方照发动作）`);
} else {
	bad(`fail-open 行为不对：${childOut || "(无输出)"}`);
}

// 收尾：别把信号文件留在那儿（留给下一轮测试/下次启动当"陈旧信号"读）
for (const f of ["dsh-ball-act", "dsh-ball-ack"]) {
	try { fs.unlinkSync(`${resolveBallGuardDir()}/${f}`); } catch (_) {}
}

console.log(`\n${failed === 0 ? "★ 全部通过" : `✗ ${failed} 项未过`}`);
process.exit(failed === 0 ? 0 : 1);
