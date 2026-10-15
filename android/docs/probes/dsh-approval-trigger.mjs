#!/usr/bin/env node
/**
 * dsh-approval-trigger.mjs — 造一次真实的 `approval/request`，用于验证应答链路。
 *
 * 做法（零依赖，Node >= 22）：
 *   1. 新建一条**测试会话**（不动用户正在聊的那条）
 *   2. 把它的权限预设切成 workspace-write（= sandbox: workspace-write + approval: ask）
 *      —— 用 `/permission <preset>` 这条 slash 命令，和浏览器里点权限菜单是同一条路径
 *   3. 给它发一条会让 agent 调 bash 并请求提权的 prompt
 *   4. 等 `approval/request` 出现（由调用方的 $events 订阅观察）
 *
 * 用法：
 *   node dsh-approval-trigger.mjs create                 # 建会话 + 切成 ask，打印 sessionId
 *   node dsh-approval-trigger.mjs prompt <sessionId>     # 发提权 prompt
 *   node dsh-approval-trigger.mjs kick <sessionId> [text]
 *   node dsh-approval-trigger.mjs events <sessionId>     # 打印该会话最近事件（找 approval/*）
 *   node dsh-approval-trigger.mjs cleanup <sessionId>
 */

import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";

const ORIGIN = process.env.DSH_ORIGIN ?? "http://127.0.0.1:3080";
const CWD = process.env.DSH_CWD ?? "/data/data/com.termux/files/home/oppo-watch-ai";
const TOKEN_LOGS = [
  process.env.DSH_LOG,
  "/data/data/com.termux/files/home/dsh.log",
  "/data/data/com.termux/files/home/dsh-restart.log",
].filter(Boolean);

const log = (...a) => console.log(...a);

function launchToken() {
  for (const path of TOKEN_LOGS) {
    let text;
    try { text = readFileSync(path, "utf8"); } catch { continue; }
    for (const m of text.matchAll(/token=([A-Za-z0-9_-]{16,})/g)) return m[1];
  }
  throw new Error("找不到 DSH 启动 token");
}

let cookie = null;
async function authenticate() {
  const res = await fetch(`${ORIGIN}/?token=${encodeURIComponent(launchToken())}`, { redirect: "manual" });
  const set = res.headers.get("set-cookie");
  if (!set) throw new Error(`DSH 未下发 cookie (HTTP ${res.status})`);
  cookie = set.split(";")[0];
}

async function rpc(endpoint, args) {
  const body = { type: "client-request", rpcId: randomUUID(), method: endpoint, payload: { args } };
  const res = await fetch(`${ORIGIN}/api/${endpoint}`, {
    method: "POST",
    headers: { "content-type": "application/json", cookie },
    body: JSON.stringify(body),
  });
  const decoded = JSON.parse(await res.text());
  if (decoded.result?.ok !== true) throw new Error(`${endpoint} → ${JSON.stringify(decoded.result?.error ?? decoded)}`);
  return decoded.result.value;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function create() {
  const created = await rpc("session/create", { request: { cwd: CWD } });
  const sessionId = created.sessionId;
  log("created", sessionId);
  return sessionId;
}

async function permission(sessionId, preset) {
  const out = await rpc("session/prompt", {
    request: {
      requestId: `perm-${Date.now()}`,
      sessionId,
      mode: "queue",
      content: [{ type: "text", text: `/permission ${preset}` }],
    },
  });
  log("permission →", preset, JSON.stringify(out));
}

async function kick(sessionId, text) {
  const out = await rpc("session/prompt", {
    request: {
      requestId: `kick-${Date.now()}`,
      sessionId,
      mode: "queue",
      content: [{ type: "text", text }],
    },
  });
  log("prompt ack:", JSON.stringify(out));
}

async function events(sessionId, maxMessages = 60) {
  const list = await rpc("session/list", { _request: {} });
  const row = (list.items ?? []).find((r) => r.sessionId === sessionId);
  const cursor = row?.projections?.asOfSeq ?? 0;
  const page = await rpc("session/page", {
    request: {
      address: { kind: "session", sessionId },
      throughSeq: cursor,
      maxMessages,
    },
  });
  for (const rec of page.records ?? []) {
    const ev = rec.event ?? rec;
    if (/^(assistant|user|tool|turn|approval|permission)/.test(String(ev.type ?? ""))) {
      log(ev.seq, ev.type, JSON.stringify(ev.data).slice(0, 220));
    }
  }
  return page;
}

const PROMPT = [
  "请调用 bash 工具，参数里带上 sandbox_permissions=\"danger-full-access\" 和 justification=\"probe\"，",
  "执行命令：echo approval-probe-ok",
  "这条命令在只读沙箱下会被拒，需要用户审批提权。",
  "只做这一件事，然后简短汇报结果。",
].join("");

const [cmd, ...rest] = process.argv.slice(2);
await authenticate();

if (cmd === "create") {
  const sessionId = await create();
  await sleep(400);
  await permission(sessionId, "workspace-write");
  await sleep(600);
  log("--- session events ---");
  await events(sessionId, 30);
  log("\nSESSION=" + sessionId);
} else if (cmd === "prompt") {
  await kick(rest[0], PROMPT);
} else if (cmd === "kick") {
  await kick(rest[0], rest.slice(1).join(" ") || PROMPT);
} else if (cmd === "events") {
  await events(rest[0], Number(rest[1] ?? 60));
} else if (cmd === "permission") {
  await permission(rest[0], rest[1]);
} else {
  console.log("用法: create | prompt <sid> | kick <sid> [text] | events <sid> [n] | permission <sid> <preset>");
}
