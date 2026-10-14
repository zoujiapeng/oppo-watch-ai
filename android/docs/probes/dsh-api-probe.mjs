#!/usr/bin/env node
/**
 * dsh-api-probe.mjs — zero-dependency proof that a local non-browser process can
 * drive the RUNNING `dsh web` server (Transport A) end to end.
 *
 * Proves, against http://127.0.0.1:3080:
 *   1. session/list       (unary RPC over HTTP POST /api/<ns>/<method>)
 *   2. session/create     (new session)
 *   3. session/follow     (WebSocket /api/remote.mux, multiplexed logical stream)
 *   4. session/prompt     (unary RPC; prompt text exactly `只回复：协议正常`)
 *   5. assistant text deltas as they stream + final `assistant/message`
 *
 * Run:  node dsh-api-probe.mjs
 * No npm dependencies. Requires Node >= 22 (global fetch + global WebSocket).
 */

import { randomUUID } from "node:crypto";

const ORIGIN = process.env.DSH_ORIGIN ?? "http://127.0.0.1:3080";
const WS_ORIGIN = ORIGIN.replace(/^http/, "ws");
const MUX_PATH = "/api/remote.mux";
const PROMPT_TEXT = "只回复：协议正常";
const TURN_TIMEOUT_MS = 90_000;

const log = (...a) => console.log(...a);

/* ------------------------------------------------------------------ *
 * Unary RPC carrier                                                   *
 * ------------------------------------------------------------------ */

/** POST /api/<endpoint> with the client-request envelope. */
async function rpc(endpoint, args, rpcId = randomUUID()) {
  // `method` must equal the endpoint derived from the URL, i.e. `${namespace}/${method}`.
  const body = {
    type: "client-request",
    rpcId,
    method: endpoint,
    payload: { args },
  };

  const res = await fetch(`${ORIGIN}/api/${endpoint}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`HTTP ${res.status} ${endpoint}: ${text}`);
  const decoded = JSON.parse(text);
  if (decoded.type !== "server-response") throw new Error(`bad envelope: ${text}`);
  if (decoded.result.ok !== true) {
    const { code, message, details } = decoded.result.error;
    throw new Error(`${endpoint} failed: ${code}: ${message} ${JSON.stringify(details)}`);
  }
  return decoded.result.value;
}

/** POST /api/<endpoint> and print the raw request/response for the report. */
async function rpcVerbose(endpoint, args) {
  const rpcId = randomUUID();
  const body = {
    type: "client-request",
    rpcId,
    method: endpoint,
    payload: { args },
  };
  const url = `${ORIGIN}/api/${endpoint}`;
  log(`\n>>> POST ${url}`);
  log(`>>> content-type: application/json`);
  log(`>>> body: ${JSON.stringify(body)}`);
  const res = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  log(`<<< HTTP ${res.status} ${res.headers.get("content-type")}`);
  const trimmed = text.length > 900 ? `${text.slice(0, 900)}…(${text.length} bytes)` : text;
  log(`<<< body: ${trimmed}`);
  return JSON.parse(text);
}

/* ------------------------------------------------------------------ *
 * Stream carrier (WebSocket mux)                                      *
 * ------------------------------------------------------------------ */

class Mux {
  #socket;
  #nextId = 1;
  #streams = new Map();

  static async connect() {
    const mux = new Mux();
    await mux.#open();
    return mux;
  }

  #open() {
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(`${WS_ORIGIN}${MUX_PATH}`);
      this.#socket = socket;
      socket.addEventListener("open", () => resolve(), { once: true });
      socket.addEventListener("error", () => reject(new Error("mux socket error")), { once: true });
      socket.addEventListener("message", (event) => {
        const frame = JSON.parse(event.data);
        const stream = this.#streams.get(frame.streamId);
        if (stream === undefined) return;
        if (frame.type === "item") stream.push(frame.value);
        else if (frame.type === "end") stream.end();
        else if (frame.type === "error") stream.fail(frame.error);
      });
      socket.addEventListener("close", (event) => {
        for (const stream of this.#streams.values()) {
          stream.fail({ code: "local/socket-closed", message: `mux closed ${event.code}`, details: {} });
        }
      });
    });
  }

  /** Open one logical stream and return its async iterator of Host items. */
  openStream(endpoint, args) {
    const streamId = `probe-${this.#nextId++}`;
    const queue = [];
    let wake;
    let failure;
    let ended = false;
    const stream = {
      push: (value) => {
        queue.push(value);
        wake?.();
        wake = undefined;
      },
      end: () => {
        ended = true;
        wake?.();
        wake = undefined;
      },
      fail: (error) => {
        failure = error;
        wake?.();
        wake = undefined;
      },
    };
    this.#streams.set(streamId, stream);
    const frame = { type: "open", streamId, endpoint, payload: { args } };
    log(`\n>>> WS ${WS_ORIGIN}${MUX_PATH}`);
    log(`>>> frame: ${JSON.stringify(frame)}`);
    this.#socket.send(JSON.stringify(frame));

    const socket = this.#socket;
    const streams = this.#streams;
    let cancelled = false;
    const iterator = {
      [Symbol.asyncIterator]() {
        return iterator;
      },
      next: async () => {
        while (queue.length === 0) {
          if (failure !== undefined) {
            await iterator.return();
            throw new Error(`stream ${endpoint} failed: ${failure.code}: ${failure.message}`);
          }
          if (ended) return { value: undefined, done: true };
          await new Promise((resolve) => {
            wake = resolve;
          });
        }
        return { value: queue.shift(), done: false };
      },
      return: async () => {
        if (cancelled) return { value: undefined, done: true };
        cancelled = true;
        ended = true;
        streams.delete(streamId);
        if (socket?.readyState === 1) {
          socket.send(JSON.stringify({ type: "cancel", streamId }));
        }
        return { value: undefined, done: true };
      },
    };
    return iterator;
  }

  close() {
    this.#socket.close(1000, "probe done");
  }
}

/* ------------------------------------------------------------------ *
 * Transcript extraction                                               *
 * ------------------------------------------------------------------ */

/** Pull visible text out of one session event / stream frame. */
function assistantTextFromMessage(message) {
  if (message === undefined || message === null) return "";
  const content = message.content;
  if (!Array.isArray(content)) return "";
  return content
    .filter((block) => block?.type === "text" && typeof block.text === "string")
    .map((block) => block.text)
    .join("");
}

/* ------------------------------------------------------------------ *
 * Main                                                                *
 * ------------------------------------------------------------------ */

async function main() {
  log("=== DSH Transport-A probe ===");
  log(`origin: ${ORIGIN}  node: ${process.version}`);

  /* (5) session list ---------------------------------------------------- */
  const list = await rpcVerbose("session/list", { _request: {} });
  const items = list.result.value.items;
  log(`\n[1] session/list -> ${items.length} sessions`);
  for (const item of items.slice(0, 5)) {
    log(`    ${item.sessionId}  running=${item.running} updatedAt=${item.updatedAt} title=${JSON.stringify(item.projections?.values?.title ?? null).slice(0, 60)}`);
  }

  /* (1) create a session ------------------------------------------------ */
  const created = await rpcVerbose("session/create", { request: { cwd: process.cwd() } });
  const sessionId = created.result.value.sessionId;
  log(`\n[2] session/create -> ${sessionId}`);

  /* (3) follow the session on the WS mux -------------------------------- */
  const mux = await Mux.connect();
  const followArgs = {
    request: {
      address: { kind: "session", sessionId },
      assistantStream: true,
    },
  };
  const stream = mux.openStream("session/follow", followArgs);

  let streamed = "";
  let final = "";
  let snapshotSeen = false;
  const rawSamples = [];
  const rawSeen = new Set();
  const noteRaw = (inner) => {
    const signature = inner.type === "chunk" ? `chunk:${inner.chunk?.type}` : inner.type;
    if (rawSeen.has(signature) || rawSeen.size >= 8) return;
    rawSeen.add(signature);
    rawSamples.push(inner);
  };
  const turnDone = Promise.withResolvers();

  const pump = (async () => {
    for await (const frame of stream) {
      if (frame.type === "snapshot") {
        snapshotSeen = true;
        log(`\n<<< WS item: snapshot cursor=${frame.cursor} header.id=${frame.header.id} records=${frame.records.length} hasMore=${frame.hasMore}`);
        continue;
      }
      if (frame.type === "assistant-stream") {
        const inner = frame.frame;
        noteRaw(inner);
        if (inner.type === "chunk" && inner.chunk?.type === "text-delta") {
          streamed += inner.chunk.text;
          process.stdout.write(inner.chunk.text);
        } else if (inner.type === "start") {
          process.stdout.write(`\n<<< assistant-stream start attempt=${inner.attemptId} turn=${inner.turn} step=${inner.step}\n>>> `);
        } else if (inner.type === "end") {
          process.stdout.write(`\n<<< assistant-stream end outcome=${JSON.stringify(inner.outcome)}\n`);
        }
        continue;
      }
      if (frame.type === "event") {
        const { type, data, seq } = frame.event;
        if (type === "assistant/message") {
          final = assistantTextFromMessage(data.message);
          log(`\n<<< durable event seq=${seq} type=assistant/message turn=${data.turn} step=${data.step}`);
          log(`<<< message.content types: ${JSON.stringify((data.message.content ?? []).map((b) => b.type))}`);
          log(`<<< usage: ${JSON.stringify(data.usage ?? null)}`);
          turnDone.resolve();
        } else if (type === "user/message") {
          log(`<<< durable event seq=${seq} type=user/message`);
        }
      }
    }
  })();

  // Give the follow snapshot a moment to land before prompting.
  await new Promise((resolve) => setTimeout(resolve, 300));
  if (!snapshotSeen) log("(warning: no snapshot seen before prompt)");

  /* (2) send the prompt ------------------------------------------------- */
  const promptRpcId = randomUUID();
  await rpcVerbose("session/prompt", {
    request: {
      requestId: promptRpcId,
      sessionId,
      mode: "queue",
      content: [{ type: "text", text: PROMPT_TEXT }],
    },
  });
  log(`\n[4] session/prompt accepted at ${new Date().toISOString()}`);

  const timeout = setTimeout(() => turnDone.resolve(), TURN_TIMEOUT_MS);
  await turnDone.promise;
  clearTimeout(timeout);

  log(`\n\n===== RESULT =====`);
  log(`sessionId            : ${sessionId}`);
  log(`streamed text deltas : ${JSON.stringify(streamed)}`);
  log(`final assistant text : ${JSON.stringify(final)}`);
  log(`prompt sent          : ${JSON.stringify(PROMPT_TEXT)}`);
  log(`match                : ${final.trim() === "协议正常" ? "YES" : "NO"}`);
  log(`\nraw assistant-stream frames observed (wire values of {"type":"item","value":…}):`);
  for (const sample of rawSamples) log(`  ${JSON.stringify(sample)}`);

  stream.return?.();
  mux.close();
  await pump.catch(() => {});
  process.exit(0);
}

main().catch((error) => {
  console.error("\nPROBE FAILED:", error);
  process.exit(1);
});
