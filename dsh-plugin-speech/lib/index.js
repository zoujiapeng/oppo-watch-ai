/**
 * dsh-plugin-speech —— 把助手回复改写成「短口播稿」，写给悬浮球去做 TTS。
 *
 * 为什么做成 DSH 插件：直接复用 DSH 自己已经配好的模型通道（provider/model 从
 * assistant/message 事件的 source 里取），**用户不需要第二份 API key**，
 * 也不用改 DSH 的任何代码 —— 它只是又一个插件。
 *
 * 数据流：
 *   assistant/message 事件 → 取正文 + source.{provider,model}
 *   → ctx.llm.stream(一句到三句的中文口播稿，≤100 字)
 *   → 原子写 /sdcard/Android/data/com.glslplayground.app/files/spoken.txt
 *   → 悬浮球朗读前读这个文件（没有/过期就退回本地规则清洗）
 *
 * 失败一律静默：写不出文件时悬浮球会走本地规则，朗读不会因此停摆。
 */
import fs from "node:fs";
import { BlockAssembler, createUserMessage, ReasoningEffortId } from "@deepseek-ai/dsh-llm";

// 改写是个纯格式化任务，不需要推理。**必须显式关掉**：
// 实测不关的话，模型把 max_tokens 全花在 reasoning 块上，正文一个字都没输出就被截断
// （日志：`chunks=304 blocks=reasoning 文本=0 字`）→ 插件永远写不出稿子。
const NO_REASONING = ReasoningEffortId("off");

const OUT = process.env.DSH_SPEECH_OUT
  || "/sdcard/Android/data/com.glslplayground.app/files/spoken.txt";
const MAX_INPUT = 1200;
const TIMEOUT_MS = 8000;

const SYSTEM =
  "把下面的助手回复改写成一句到三句的中文口播稿，供语音朗读。"
  + "要求：总长不超过 100 字；口语化；只讲结论和关键信息；"
  + "不要 markdown、不要代码、不要表格、不要链接、不要 emoji、不要序号符号；"
  + "数字、单位、文件名保留；只输出正文，不要任何解释或引号。";

/**
 * 取 assistant/message 里的消息体。
 *
 * ⚠️ 坑（踩过）：session.append(type, data) 产生的事件是 `{type, seq, time, data}`，
 * 消息体在 **`event.data.message`**，不是直接在 event 上 —— 写成 `event.message` 会永远拿不到，
 * 表现是插件静默不工作（不报错、也不写文件）。
 * 这里按 data.message → message → data 的顺序兜底，并保留两层取值以防版本差异。
 */
function messageOf(event) {
  return event?.data?.message ?? event?.message ?? event?.data ?? null;
}

/** 取一条 assistant/message 里的纯文本。 */
function textOf(event) {
  const content = messageOf(event)?.content;
  if (!Array.isArray(content)) return "";
  return content.filter((b) => b && b.type === "text").map((b) => b.text ?? "").join("");
}

function writeAtomic(p, body) {
  const tmp = `${p}.tmp`;
  fs.writeFileSync(tmp, body, "utf8");
  fs.renameSync(tmp, p);
}

export const name = "speech";
export const inject = ["llm"];

const PROBE = "/sdcard/Android/data/com.glslplayground.app/files/speech-probe.txt";
const LOGF = "/sdcard/Android/data/com.glslplayground.app/files/speech-plugin.log";

/** 直接落盘的日志：不依赖 ctx.logger 是否可见（排查期用）。 */
function note(msg) {
  try { fs.appendFileSync(LOGF, `${new Date().toISOString().slice(11, 19)} ${msg}\n`); } catch (_) {}
}
const DEBUG = process.env.DSH_SPEECH_DEBUG !== "0";   // 排查期默认开

/** 探针：把 assistant/message 的**真实结构**写成一行摘要，用来定位取值为空的原因。 */
function probe(event) {
  try {
    const t = event?.type ?? "(no-type)";
    // 只在 assistant/message 上留详细摘要（其它类型只记名字，避免刷屏）
    let line;
    if (t === "assistant/message") {
      const msg = event?.data?.message ?? event?.message ?? null;
      const content = msg?.content;
      const textLen = Array.isArray(content)
        ? content.filter((b) => b?.type === "text").map((b) => b.text ?? "").join("").length
        : -1;
      line = `[${t}] keys=${Object.keys(event ?? {}).join(",")}`
        + ` msgKeys=${msg ? Object.keys(msg).join(",") : "null"}`
        + ` textLen=${textLen}`
        + ` source=${JSON.stringify(msg?.source ?? null)}`
        + ` blockTypes=${Array.isArray(content) ? content.map((b) => b?.type).join("|") : "n/a"}`;
    } else {
      line = `[${t}]`;
    }
    fs.appendFileSync(PROBE, line + "\n");
  } catch (_) {}
}

export function apply(ctx) {
  ctx.logger?.info?.("speech 插件已加载（诊断探针开启）");
  if (DEBUG) {
    ctx.on("session/event", (_session, event) => probe(event));
  }

  ctx.on("session/event", (session, event) => {
    let type;
    try { type = event?.type; } catch (_) { return; }
    if (type !== "assistant/message") return;

    const text = textOf(event).trim();
    if (!text) { note("收到 assistant/message 但取不到正文"); return; }
    const source = messageOf(event)?.source ?? {};
    const provider = source.provider;
    const model = source.model;
    if (!provider || !model) {
      note(`没有 provider/model（source=${JSON.stringify(source)}），跳过`);
      return;
    }
    note(`收到 assistant/message：正文 ${text.length} 字，${provider}/${model}`);

    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), TIMEOUT_MS);
    (async () => {
      try {
        note("开始调用 ctx.llm.stream …");
        const assembler = new BlockAssembler();
        let chunks = 0;
        for await (const chunk of ctx.llm.stream({
          provider,
          model,
          system: SYSTEM,
          messages: [createUserMessage({
            content: [{ type: "text", text: text.slice(0, MAX_INPUT) }],
            source: { kind: "dsh-plugin-speech" },
          })],
          reasoningEffort: NO_REASONING,
          maxTokens: 400,
          sessionId: session?.id ?? session?.header?.id,
          purpose: "speech-rewrite",
          signal: ac.signal,
        })) {
          assembler.push(chunk);
          chunks++;
        }
        const blocks = assembler.blocks();
        const spoken = blocks.filter((b) => b.type === "text").map((b) => b.text ?? "").join("").trim();
        note(`模型返回 chunks=${chunks} blocks=${blocks.map((b) => b.type).join("|")} 文本=${spoken.length} 字`);
        if (!spoken) {
          note("模型没给文本（可能又被推理占满），提高额度重试一次");
          const asm2 = new BlockAssembler();
          for await (const c of ctx.llm.stream({
            provider, model, system: SYSTEM,
            messages: [createUserMessage({
              content: [{ type: "text", text: text.slice(0, MAX_INPUT) }],
              source: { kind: "dsh-plugin-speech" },
            })],
            reasoningEffort: NO_REASONING, maxTokens: 1200,
            sessionId: session?.id ?? session?.header?.id,
            purpose: "speech-rewrite", signal: ac.signal,
          })) asm2.push(c);
          const retry = asm2.blocks().filter((b) => b.type === "text").map((b) => b.text ?? "").join("").trim();
          if (!retry) { note("重试也没文本，跳过（球用规则清洗）"); return; }
          writeAtomic(OUT, retry);
          note(`口播稿（重试成功）${retry.length} 字已写入`);
          return;
        }
        writeAtomic(OUT, spoken);
        note(`口播稿 ${spoken.length} 字已写入`);
      } catch (e) {
        note(`改写失败: ${e?.name ?? ""} ${e?.message ?? e}`);
      } finally {
        clearTimeout(timer);
      }
    })();
  });
}
