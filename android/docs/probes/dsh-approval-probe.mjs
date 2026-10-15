#!/usr/bin/env node
/**
 * dsh-approval-probe.mjs — 验证「安卓端也能当 DSH 的审批应答方」这条链路。
 *
 * 复现的是浏览器 approval 面板背后的那套协议（零依赖，Node >= 22）：
 *
 *   1. WS /api/remote.mux 上开一条 `$events` 逻辑流（payload 必须是 {"args":{}}）
 *      → 首帧 {"type":"ready","clientId":"…","host":{"home":"…"}}
 *   2. Host 侧的 `approval/request` 瀑布会被转发成
 *      {"type":"waterfall","event":"approval/request","eventId":"…","agentId":"…",
 *       "request":{"toolName":"…","reason":"…","displayReason":{…},"callId":"…"}}
 *   3. 应答走普通 HTTP RPC：POST /api/$events/result
 *      body: {type:"client-response"?,…} —— 实际信封与其它 endpoint 相同，
 *      args = {clientId, eventId, outcome:{kind:"result",value:"allowed-once"|"rejected"}}
 *      旁路：outcome {kind:"next"} 表示「我不管，交给下一个应答方」。
 *   4. Host 结算后会给所有拿到过这条请求的客户端推 {"type":"cancel","eventId":"…"}
 *
 * 用法：
 *   node dsh-approval-probe.mjs listen            # 只订阅，打印收到的审批请求
 *   node dsh-approval-probe.mjs listen --allow    # 收到就自动批准（第一次）
 *   node dsh-approval-probe.mjs listen --reject
 *   node dsh-approval-probe.mjs set-permission <sessionId> <preset>   # 改某会话的权限预设
 *   node dsh-approval-probe.mjs sessions          # 列出会话与权限投影
 */

import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";

const ORIGIN = process.env.DSH_ORIGIN ?? "http://127.0.0.1:3080";
const MUX = "/api/remote.mux";
const TOKEN_LOGS = [
  process.env.DSH_LOG,
  "/data/data/com.termux/files/home/dsh.log",
  "/data/data/com.termux/files/home/dsh-restart.log",
].filter(Boolean);

const log = (...a) => console.log(new Date().toISOString().slice(11, 23), ...a);

/* ── 认证：启动 token → 签名 cookie ─────────────────────────────── */

function launchToken() {
  for (const path of TOKEN_LOGS) {
    let text;
    try {
      text = readFileSync(path, "utf8");
    } catch {
      continue;
    }
    for (const m of text.matchAll(/token=([A-Za-z0-9_-]{16,})/g)) return m[1];
  }
  throw new Error("找不到 DSH 启动 token（dsh.log）");
}

let cookie = null;
async function authenticate() {
  const res = await fetch(`${ORIGIN}/?token=${encodeURIComponent(launchToken())}`, {
    redirect: "manual",
  });
  const set = res.headers.get("set-cookie");
  if (!set) throw new Error(`DSH 未下发 cookie (HTTP ${res.status})`);
  cookie = set.split(";")[0];
  log("authenticated:", cookie.slice(0, 28) + "…");
  return cookie;
}

/* ── unary RPC（与安卓端同一个信封）───────────────────────────── */

async function rpc(endpoint, args) {
  const body = {
    type: "client-request",
    rpcId: randomUUID(),
    method: endpoint,
    payload: { args },
  };
  const res = await fetch(`${ORIGIN}/api/${endpoint}`, {
    method: "POST",
    headers: { "content-type": "application/json", cookie },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  const decoded = JSON.parse(text);
  if (decoded.result?.ok !== true) {
    throw new Error(`${endpoint} → ${JSON.stringify(decoded.result?.error ?? decoded)}`);
  }
  return decoded.result.value;
}

/* ── $events 订阅 ──────────────────────────────────────────────── */

function subscribe({ autoAnswer = null } = {}) {
  const ws = new WebSocket(`${ORIGIN.replace(/^http/, "ws")}${MUX}`, {
    headers: { cookie },
  });
  let clientId = null;
  const streamId = "probe-events";

  ws.addEventListener("open", () => {
    log("ws open → open $events");
    ws.send(
      JSON.stringify({
        type: "open",
        streamId,
        endpoint: "$events",
        payload: { args: {} },
      }),
    );
  });

  ws.addEventListener("message", async (event) => {
    let frame;
    try {
      frame = JSON.parse(event.data);
    } catch {
      return;
    }
    if (frame.type === "error") {
      log("stream error:", JSON.stringify(frame));
      return;
    }
    if (frame.type !== "item") return;
    const value = frame.value;

    if (value?.type === "ready") {
      clientId = value.clientId;
      log("READY clientId=", clientId, "host=", JSON.stringify(value.host));
      return;
    }
    if (value?.type === "cancel") {
      log("CANCEL eventId=", value.eventId);
      return;
    }
    if (value?.type === "emit") {
      log("EMIT", value.event, JSON.stringify(value.args).slice(0, 160));
      return;
    }
    if (value?.type !== "waterfall") {
      log("item:", JSON.stringify(value).slice(0, 200));
      return;
    }

    log("WATERFALL", value.event, "eventId=", value.eventId);
    log("  request =", JSON.stringify(value.request));
    if (value.event !== "approval/request") return;

    let outcome = { kind: "next" };
    if (autoAnswer === "allowed-once" || autoAnswer === "rejected") {
      outcome = { kind: "result", value: autoAnswer };
    }
    try {
      const value2 = await rpc("$events/result", {
        clientId,
        eventId: value.eventId,
        outcome,
      });
      log("  answered", JSON.stringify(outcome), "→ value =", JSON.stringify(value2));
    } catch (error) {
      log("  answer failed:", String(error.message));
    }
  });

  ws.addEventListener("close", (e) => log("ws closed", e.code, e.reason));
  ws.addEventListener("error", (e) => log("ws error", String(e.message ?? e)));
  return ws;
}

/* ── CLI ──────────────────────────────────────────────────────── */

const [cmd, ...rest] = process.argv.slice(2);
await authenticate();

if (cmd === "listen") {
  const allow = rest.includes("--allow");
  const reject = rest.includes("--reject");
  subscribe({ autoAnswer: allow ? "allowed-once" : reject ? "rejected" : null });
  log("listening for forwarded Host events… (Ctrl-C 退出)");
} else if (cmd === "sessions") {
  const list = await rpc("session/list", { _request: {} });
  for (const row of list.items ?? []) {
    log(
      row.sessionId,
      "| origin=", row.origin,
      "| running=", row.running,
      "| perms=", JSON.stringify(row.projections?.permissions ?? null),
      "|", String(row.title ?? "").slice(0, 24),
    );
  }
} else if (cmd === "set-permission") {
  const [sessionId, preset] = rest;
  if (!sessionId || !preset) throw new Error("用法: set-permission <sessionId> <preset>");
  const out = await rpc("session/prompt", {
    request: {
      requestId: `probe-${Date.now()}`,
      sessionId,
      mode: "queue",
      content: [{ type: "text", text: `/permission ${preset}` }],
    },
  });
  log("prompt ack:", JSON.stringify(out));
} else {
  console.log("用法: listen [--allow|--reject] | sessions | set-permission <sessionId> <preset>");
}
