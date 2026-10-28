// 门控协议自测（和 mobile_plugin.js 里 prepareBallGate 走的是同一条线）：
//   STAT → 判断"这一点会不会落到球上" → 只在球上时才 ACT/ACK，注入完 DONE。
// 注入用 `cmd input`（**绕过** /system/bin/input 包装器），模拟 vd 自己注入的路径。
//
// 用法：node tools/e2e-gate-protocol.mjs
import net from "node:net";
import { execFileSync } from "node:child_process";

const PORT = Number(process.env.DSH_BALL_PORT || 38721);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function stat(timeoutMs = 400) {
	return new Promise((resolve) => {
		let done = false, buf = "";
		const s = net.connect({ host: "127.0.0.1", port: PORT });
		const t = setTimeout(() => fin(null), timeoutMs);
		function fin(v) { if (done) return; done = true; clearTimeout(t); try { s.destroy(); } catch { } resolve(v); }
		s.setNoDelay(true);
		s.on("connect", () => s.write("STAT\n"));
		s.on("data", (d) => {
			buf += d.toString("utf8");
			const line = buf.split("\n")[0];
			if (!line.startsWith("STAT ")) return;
			const m = /rect=(-?\d+),(-?\d+),(-?\d+),(-?\d+)/.exec(line);
			const b = /blind=(\d)/.exec(line);
			const w = /swallowed=(\d+)/.exec(line);
			fin({ rect: m ? m.slice(1, 5).map(Number) : null, blind: b?.[1] === "1", swallowed: Number(w?.[1] ?? 0) });
		});
		s.on("error", () => fin(null));
		s.on("close", () => fin(null));
	});
}

function act(kind = "tap", timeoutMs = 800) {
	return new Promise((resolve) => {
		const nonce = `test-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
		let done = false, buf = "";
		const s = net.connect({ host: "127.0.0.1", port: PORT });
		const t = setTimeout(() => fin(null), timeoutMs);
		function fin(v) { if (done) return; done = true; clearTimeout(t); resolve(v); }
		s.setNoDelay(true);
		s.on("connect", () => s.write(`ACT ${nonce} ${kind}\n`));
		s.on("data", (d) => {
			buf += d.toString("utf8");
			if (buf.includes(`ACK ${nonce}`)) fin({ sock: s, nonce });
		});
		s.on("error", () => fin(null));
		s.on("close", () => fin(null));
	});
}

function done(h) {
	if (!h) return;
	try { h.sock.write(`DONE ${h.nonce}\n`); h.sock.end(); } catch { }
}

const sh = (cmd) => execFileSync("/system/bin/sh", ["-c", cmd], { encoding: "utf8" }).trim();
const tapRaw = (x, y) => sh(`cmd input tap ${x} ${y}`);     // 绕过包装器
const tapGated = (x, y) => sh(`input tap ${x} ${y}`);       // 走包装器
const dump = () => {
	sh("uiautomator dump /data/local/tmp/e2e/proto.xml >/dev/null 2>&1");
	return sh("grep -o 'text=\"[^\"]*\"' /data/local/tmp/e2e/proto.xml | sort -u");
};
const settings = () => { sh("am force-stop com.android.settings >/dev/null 2>&1; sleep 0.4; am start -a android.settings.SETTINGS >/dev/null 2>&1; sleep 2"); };

let pass = 0, fail = 0;
const ok = (m) => { console.log(`  ✅ ${m}`); pass++; };
const bad = (m) => { console.log(`  ❌ ${m}`); fail++; };

const st = await stat();
if (!st) { console.log("❌ 门控 socket 连不上：球没在跑？"); process.exit(1); }
console.log("STAT:", JSON.stringify(st));

const [ bx, by, bw, bh ] = st.rect;
const cx = bx + (bw >> 1), cy = by + (bh >> 1);

// 1) 球上：先 ACT/ACK，再用"裸注入"打进去（模拟 vd）
settings();
let before = dump();
let h = await act("tap");
h ? ok("ACT → ACK") : bad("ACT 没拿到 ACK");
const s1 = await stat();
s1.blind ? ok("让开状态已生效（STAT blind=1）") : bad("STAT 没报 blind=1");
await sleep(0.15);
tapRaw(cx, cy);
await sleep(0.6);
done(h);
await sleep(0.8);
let after = dump();
before !== after ? ok(`球上裸注入点中了（${cx},${cy}）`) : bad("球上裸注入没生效");
const s2 = await stat();
s2.swallowed === st.swallowed ? ok(`球没吞（swallowed 仍是 ${s2.swallowed}）`) : bad(`球吞了（${st.swallowed} → ${s2.swallowed}）`);

// 2) 球外：STAT 判定为"不用让开"，直接裸注入
settings();
before = dump();
const s3 = await stat();
const inside = cx >= s3.rect[0] && cx < s3.rect[0] + s3.rect[2] && 1150 >= s3.rect[1] && 1150 < s3.rect[1] + s3.rect[3];
!inside ? ok("球外点不在矩形内 → 不需要握手") : bad("矩形判定错了");
tapGated(cx, 1150);
await sleep(1.2);
after = dump();
before !== after ? ok("球外点击生效（走包装器，未握手）") : bad("球外点击没生效");

// 3) 吞掉后自愈：裸注入打在球上 → 球吞一下并自动让开 → 立刻再来一下就过去了
settings();
before = dump();
tapRaw(cx, cy);
await sleep(0.35);
const s4 = await stat();
s4.swallowed > s3.swallowed ? ok(`识别成机器触摸（swallowed ${s3.swallowed} → ${s4.swallowed}）`) : bad("没识别成机器触摸");
await sleep(0.1);
tapRaw(cx, cy);
await sleep(1.0);
after = dump();
before !== after ? ok("自愈窗口内的第二次裸注入穿过去了") : bad("自愈失效");

console.log(`\n=== ${pass} 通过 / ${fail} 失败 ===`);
process.exit(fail ? 1 : 0);
