#!/usr/bin/env node
/**
 * approval-debug-server.mjs — 审批球的调试回路（安卓端 DshApprovalClient 的对面）。
 *
 * 为什么需要它：Termux 与应用在这台机器上是**不同的挂载命名空间**
 * （Termux 里连 /data/user 都不存在，写进 /data/data/... 应用看不见），
 * 所以「喂一条假审批」不能靠文件，改走 127.0.0.1 上这个 HTTP 小服务。
 *
 *   GET  /ballapproval         → 200 + 一条假请求 JSON（队列空则 204）
 *   POST /ballapproval/answer  → 球上被点了哪个选项，原样打印
 *   GET  /healthz              → 200 ok
 *
 * 端口 = DSH 端口 + 1（默认 3081），与安卓端 debugBase() 的算法一致。
 *
 * 用法：
 *   node approval-debug-server.mjs                 # 起服务，交互式喂
 *   node approval-debug-server.mjs --auto '{"toolName":"bash","reason":"..."}'
 *   node approval-debug-server.mjs --auto-ask      # 喂一条「提问」形态（选项式）
 * 起来之后在另一个终端按回车 = 把 --pending 的那条推进队列；也可以直接
 * 往 stdin 写一行 JSON 再回车，那一行就会被当成假请求发出去。
 */

import { createServer } from "node:http";

const PORT = Number(process.env.APPROVAL_DEBUG_PORT ?? 3081);
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

/** 待发的假请求队列（GET 时取一条）。 */
const queue = [];
let lastAnswer = null;
let lastAnswerAt = 0;

const DEFAULT_APPROVAL = {
  toolName: "bash",
  reason: "这个命令要在沙箱之外执行：rm -rf /data/local/tmp/probe。批准就放行，拒绝就拦下。",
};

/** 「提问」形态：选项式（验收球上的多选项布局）。 */
const DEFAULT_QUESTION = {
  event: "user-questions/request",
  header: "选择运行模式",
  reason: "打开 QQ 需要操作屏幕，请选择运行模式：",
  options: [
    "前台 Foreground（推荐）",
    "后台 Background",
    "我先自己看看",
  ],
};

const argv = process.argv.slice(2);
const autoIdx = argv.indexOf("--auto");
if (argv.includes("--auto-ask")) queue.push(DEFAULT_QUESTION);
else if (autoIdx >= 0 && argv[autoIdx + 1]) queue.push(JSON.parse(argv[autoIdx + 1]));
else queue.push(DEFAULT_APPROVAL);

const server = createServer((req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  if (req.method === "GET" && url.pathname === "/ballapproval") {
    if (queue.length === 0) {
      res.writeHead(204).end();
      return;
    }
    const payload = queue.shift();
    const body = JSON.stringify(payload);
    log("→ 喂出假请求：", body.slice(0, 120));
    res.writeHead(200, { "content-type": "application/json; charset=utf-8" }).end(body);
    return;
  }
  if (req.method === "POST" && url.pathname === "/ballapproval/answer") {
    let raw = "";
    req.on("data", (c) => { raw += c; });
    req.on("end", () => {
      lastAnswer = raw.trim();
      lastAnswerAt = Date.now();
      log("★ 球上点了：", JSON.stringify(lastAnswer));
      res.writeHead(200).end("ok");
    });
    return;
  }
  if (req.method === "POST" && url.pathname === "/ballapproval/push") {
    let raw = "";
    req.on("data", (c) => { raw += c; });
    req.on("end", () => {
      try {
        const payload = raw.trim() === "" ? DEFAULT_APPROVAL : JSON.parse(raw);
        queue.push(payload);
        log("＋ 入队假请求：", JSON.stringify(payload).slice(0, 120));
        res.writeHead(200).end("queued");
      } catch (e) {
        res.writeHead(400).end(String(e));
      }
    });
    return;
  }
  if (req.method === "GET" && url.pathname === "/healthz") {
    res.writeHead(200).end("ok");
    return;
  }
  if (req.method === "GET" && url.pathname === "/last") {
    res.writeHead(200, { "content-type": "application/json; charset=utf-8" })
      .end(JSON.stringify({ answer: lastAnswer, at: lastAnswerAt, queued: queue.length }));
    return;
  }
  res.writeHead(404).end("no");
});

server.listen(PORT, "127.0.0.1", () => {
  log(`审批调试回路在 http://127.0.0.1:${PORT}`);
  log(`  GET  /ballapproval        取一条假请求（队列 ${queue.length} 条）`);
  log(`  POST /ballapproval/answer 收球上的点击`);
  log("回车 = 再喂一条默认审批；或粘贴一行 JSON 后回车 = 喂那一条");
});

/* 交互：回车推默认审批，或粘贴 JSON 推自定义 */
process.stdin.setEncoding("utf8");
let buf = "";
process.stdin.on("data", (chunk) => {
  buf += chunk;
  let nl;
  while ((nl = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, nl).trim();
    buf = buf.slice(nl + 1);
    if (line === "") {
      queue.push(argv.includes("--auto-ask") ? DEFAULT_QUESTION : DEFAULT_APPROVAL);
      log("入队一条默认假请求（等球来取）");
      continue;
    }
    try {
      queue.push(JSON.parse(line));
      log("入队自定义假请求");
    } catch (e) {
      log("这行不是合法 JSON，忽略：", line.slice(0, 60));
    }
  }
});
