#!/usr/bin/env python3
"""给 DSH 真正加载的那份 mobile 预设插件打「球让路」补丁（ACT/DONE over 127.0.0.1:38721）。

为什么是这份：DSH 的 mobile 工具用的是 .agent-presets 里的副本，不是
~/dsh-preset-mobile-use/lib/index.js —— 改后者没有效果（实测：点击后
~/.cache/ball-plugin.log 一直是空的）。

用法：
    python3 patches/patch-mobile-preset.py                 # 默认目标
    python3 patches/patch-mobile-preset.py <path-to-mobile_plugin.js>

幂等：已经打过就什么都不做。改完要重启 DSH（预设插件在进程启动时加载）。
"""
import io
import sys

DEFAULT = "/data/data/com.termux/files/home/.dsh/.agent-presets/mobile-use/mobile_plugin.js"
P = sys.argv[1] if len(sys.argv) > 1 else DEFAULT
s = io.open(P, encoding="utf-8").read()

if "BALL_GATE_PORT" in s:
    print("[skip] 已经打过补丁:", P)
    sys.exit(0)

if 'node:net' not in s:
    s = s.replace('import http from "node:http";',
                  'import http from "node:http";\nimport net from "node:net";', 1)

HELPER = '''
// ── 球让路（和悬浮球 App 的 FloatingBallService 配对）──────────────────────────
// mobile 发 click/swipe 的那一下：先让球把触摸区挪到屏外（ACT→ACK），注入完立刻还回（DONE）。
// 走 127.0.0.1:38721 回环 socket —— Termux 读写不了别的包 /sdcard/Android/data（分区存储，实测 EACCES）。
const BALL_GATE_PORT = Number(process.env.DSH_BALL_PORT || 38721);
const BALL_LOG = `${process.env.HOME || "/data/data/com.termux/files/home"}/.cache/ball-plugin.log`;
let ballSeq = 0;
function ballLog(msg) {
	try { fs.appendFileSync(BALL_LOG, `${new Date().toISOString()} ${msg}\\n`); } catch (_) { }
}
function signalBall(action) {
	return new Promise((resolve) => {
		const nonce = `${Date.now()}-${process.pid}-${++ballSeq}-${action}`;
		let settled = false;
		const sock = net.connect({ host: "127.0.0.1", port: BALL_GATE_PORT });
		const timer = setTimeout(() => finish(null), 500);
		function finish(v) { if (settled) return; settled = true; clearTimeout(timer); resolve(v); }
		let buf = "";
		sock.setNoDelay(true);
		sock.on("connect", () => { ballLog(`act start ${nonce}`); sock.write(`ACT ${nonce}\\n`); });
		sock.on("data", (d) => {
			buf += d.toString("utf8");
			if (buf.includes(`ACK ${nonce}`)) { ballLog(`ack ok ${nonce}`); finish({ sock, nonce }); }
		});
		sock.on("error", (e) => { ballLog(`act error ${e.message}`); finish(null); });
		sock.on("close", () => finish(null));
	});
}
function ballDone(handle) {
	if (!handle || !handle.sock) return;
	try { handle.sock.write(`DONE ${handle.nonce}\\n`); handle.sock.end(); } catch (_) { }
}
'''

anchor = 'const SERVER_BASE = process.env.AGENT_VD_SERVER || "http://127.0.0.1:3070";'
if anchor not in s:
    sys.exit("[fail] 找不到 SERVER_BASE 锚点，插件结构可能变了")
s = s.replace(anchor, HELPER + "\n" + anchor, 1)

old = '''						try {
							const res = await postJson("/api/action", args);'''
new = '''						let ballHandle = null;
						if (args.action === "click" || args.action === "swipe") {
							ballHandle = await signalBall(args.action);
						}
						try {
							const res = await postJson("/api/action", args);'''
if old not in s:
    sys.exit("[fail] 找不到 /api/action 调用点")
s = s.replace(old, new, 1)

old2 = '''						} catch (err) {
							return { message: `Execution error (${args.action}): ${err.message}` };
						}'''
new2 = '''						} catch (err) {
							return { message: `Execution error (${args.action}): ${err.message}` };
						} finally {
							ballDone(ballHandle);
						}'''
if old2 not in s:
    sys.exit("[fail] 找不到 catch 收尾")
s = s.replace(old2, new2, 1)

io.open(P, "w", encoding="utf-8", newline="\n").write(s)
print("[ok] patched:", P, "（重启 DSH 生效）")
