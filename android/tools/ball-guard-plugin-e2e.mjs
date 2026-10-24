#!/usr/bin/env node
/**
 * 插件侧「球让路」真机贯通测试（不需要重启 DSH）。
 *
 * 为什么要有它：DSH 正在跑的插件是内存里的旧代码，重启 DSH 才能生效（用户自己选时机）。
 * 但插件里那段协议代码本身可以**原样**跑起来验：这个脚本从
 * ~/dsh-preset-mobile-use/lib/index.js 里把整段「球让路协议」切出来（不是抄一份），
 * 在真机上调用，同时用 dumpsys 观察**真实的悬浮球**是否在同一时刻退出命中测试。
 *
 * 2026-10-24 起测三条通道（换手机排错的关键）：
 *   1) 回环 TCP（主通道）：PING → YIELD → 球窗口 notTouchable → RELEASE → 恢复
 *   2) 文件通道：把 TCP 指向空端口后仍能靠信号文件握手（老 APK / vd CLI 补丁）
 *   3) 三条通道全断时：ok=false **且 reason 里逐条写明**（绝不静默 —— 这正是
 *      「换台手机就误触玻璃却毫无提示」的病根）
 *
 * 用法：node android/tools/ball-guard-plugin-e2e.mjs [click|swipe|key]
 */

import fs from "node:fs";
import { execFileSync } from "node:child_process";

const PLUGIN = process.env.PLUGIN || "/data/data/com.termux/files/home/dsh-preset-mobile-use/lib/index.js";
const PKG = "com.glslplayground.app";
const PROXY_PY = "/data/data/com.termux/files/home/oppo-watch-ai/android/tools/ball-proxy-state.py";

// ── 1) 从真实插件源码里切出协议代码 ──────────────────────────────────────────
const src = fs.readFileSync(PLUGIN, "utf8");
const begin = src.indexOf("/**\n * 球让路协议");
const end = src.indexOf("function planScreenshotDelivery(");
if (begin < 0 || end < 0 || end <= begin) {
	console.error("[FAIL] 切不出插件里的协议代码（插件结构变了？先看 install-ball-guard-plugin.sh）");
	process.exit(2);
}
const block = src.slice(begin, end);
const modPath = "/data/data/com.termux/files/usr/tmp/ball-guard-plugin-block.mjs";
// 测试自己的日志单独放：别把 e2e 的"故意失败"用例写进真实的 ~/dsh-ball-guard.log
// （否则 doctor 环 9 会把测试记录当成"最近一次动作失败"）。
const TEST_LOG = `${process.env.TMPDIR || "/data/data/com.termux/files/usr/tmp"}/bg-e2e-guard.log`;
process.env.DSH_BALL_GUARD_LOG = TEST_LOG;
fs.writeFileSync(
	modPath,
	`import fs from "node:fs";\nimport net from "node:net";\nimport { execFileSync } from "node:child_process";\n` +
		block +
		`\nexport { waitForBallStandDown, releaseBallStandDown, BALL_GUARD_DIRS, BALL_GATE_PORTS };\n`,
);
const { waitForBallStandDown, releaseBallStandDown, BALL_GUARD_DIRS, BALL_GATE_PORTS } = await import(modPath);

const appAct = process.argv[2] || "click";
if (!["click", "swipe", "key"].includes(appAct)) {
	console.error(`[FAIL] 参数只能是 click/swipe/key（收到 ${appAct}）`);
	process.exit(2);
}

function proxyState() {
	try {
		return execFileSync("python3", [PROXY_PY], { encoding: "utf8" }).trim();
	} catch (e) {
		return String(e.stdout || "").trim() || "(读不到 dumpsys)";
	}
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
console.log(`[ball] TCP 端口候选 = ${BALL_GATE_PORTS.join("/")}`);
console.log(`[ball] 文件目录候选 = ${BALL_GUARD_DIRS.join(", ")}`);
console.log(`[ball] 信号前：${proxyState()}`);

// ── 2) TCP 主通道 ────────────────────────────────────────────────────────────
console.log(`\n== 1) TCP 主通道：waitForBallStandDown('${appAct}') ==`);
const t0 = Date.now();
const res = await waitForBallStandDown(appAct, undefined);
const dt = Date.now() - t0;
console.log(`[ball] 返回 ok=${res.ok} via=${res.via || "-"} reason=${res.reason || "-"} nonce=${res.nonce || "-"} 用时=${dt}ms`);
if (res.ok) ok(`球确认让路（${res.via || "?"}，${dt}ms）`);
else bad(`没拿到确认：${res.reason}`);

if (res.ok) {
	console.log("\n== 2) 就在这一刻，真实球窗口应当已退出命中测试 ==");
	const now = proxyState();
	console.log(`[ball] 现场状态：${now}`);
	if (now.startsWith("notTouchable") || now.includes("<empty>")) ok("球此刻不接触摸 → agent 的触摸不会落到球上");
	else bad("球仍可触摸（握手成功却没让路？看 logcat -s FloatingBall）");

	console.log("\n== 3) RELEASE 之后应立刻恢复（用户照常点/拖）==");
	await releaseBallStandDown(res.nonce);
	const after = await waitState("touchable", 3000);
	console.log(`[ball] 恢复后：${after}`);
	if (after.startsWith("touchable")) ok("球恢复可触摸");
	else bad(`没有恢复：${after}`);
}

// ── 3) 文件通道（把 TCP 指向空端口，逼它走文件 / su 中继）──────────────────────
const runChild = (probe, env) => {
	try {
		return execFileSync(process.execPath, ["--input-type=module", "-e", probe], {
			encoding: "utf8",
			env: { ...process.env, DSH_BALL_GUARD_LOG: TEST_LOG, ...env },
			timeout: 20000,
		}).trim();
	} catch (e) {
		return String(e.stdout || "").trim();
	}
};
const probeFor = (act) => `
	import("${modPath}").then(async (m) => {
		const t = Date.now();
		const r = await m.waitForBallStandDown("${act}", undefined);
		console.log(JSON.stringify({ ok: r.ok, via: r.via || "", reason: r.reason || "", dt: Date.now() - t }));
	});
`;

console.log("\n== 4) 文件通道（DSH_BALL_GATE_PORT=59999 逼它走文件）==");
const fileOut = runChild(probeFor(appAct), { DSH_BALL_GATE_PORT: "59999" });
let fileParsed = null;
try { fileParsed = JSON.parse(fileOut.split("\n").pop()); } catch (_) {}
console.log(`[ball] ${fileOut || "(空)"}`);
if (fileParsed && fileParsed.ok && String(fileParsed.via).startsWith("file")) {
	ok(`文件通道可用（${fileParsed.dt}ms）—— 老 APK / vd CLI 补丁仍然吃得下`);
} else if (fileParsed && !fileParsed.ok) {
	console.log(`[??] 文件通道不可用（${fileParsed.reason}）—— 换机时这是正常现象，靠 TCP 通道即可`);
} else {
	bad(`文件通道测试异常：${fileOut || "(无输出)"}`);
}

// ── 4) 三条通道全断：必须显式报错（不许静默）────────────────────────────────
console.log("\n== 5) 全断时的诊断行（「换机误触却毫无提示」的疫苗）==");
const deadOut = runChild(probeFor(appAct), {
	DSH_BALL_GATE_PORT: "59998",
	DSH_BALL_GUARD_DIRS: "/sdcard/no-such-dir-a:/sdcard/no-such-dir-b",
	PATH: "/nonexistent-bin",
});
let deadParsed = null;
try { deadParsed = JSON.parse(deadOut.split("\n").pop()); } catch (_) {}
console.log(`[ball] ${deadOut || "(空)"}`);
if (deadParsed && deadParsed.ok === false && deadParsed.reason && deadParsed.dt <= 1500) {
	ok(`全断时 1.5s 内放行，且 reason 逐条写明（${deadParsed.dt}ms）`);
} else {
	bad(`全断时行为不对：${deadOut || "(无输出)"}`);
}

// 收尾：别把信号文件留在那儿
for (const d of BALL_GUARD_DIRS) {
	for (const f of ["dsh-ball-act", "dsh-ball-ack"]) {
		try { fs.unlinkSync(`${d}/${f}`); } catch (_) {}
	}
}

console.log(`\n${failed === 0 ? "★ 全部通过" : `✗ ${failed} 项未过`}`);
process.exit(failed === 0 ? 0 : 1);
