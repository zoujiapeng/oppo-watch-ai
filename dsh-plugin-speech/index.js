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
import { BlockAssembler, createUserMessage } from "@deepseek-ai/dsh-llm";

const OUT = process.env.DSH_SPEECH_OUT
  || "/sdcard/Android/data/com.glslplayground.app/files/spoken.txt";
const MAX_INPUT = 1200;
const TIMEOUT_MS = 8000;

const SYSTEM =
  "把下面的助手回复改写成一句到三句的中文口播稿，供语音朗读。"
  + "要求：总长不超过 100 字；口语化；只讲结论和关键信息；"
  + "不要 markdown、不要代码、不要表格、不要链接、不要 emoji、不要序号符号；"
  + "数字、单位、文件名保留；只输出正文，不要任何解释或引号。";

/** 取一条 assistant/message 里的纯文本。 */
function textOf(event) {
  const content = event?.message?.content ?? event?.content;
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

export function apply(ctx) {
  ctx.on("session/event", (session, event) => {
    let type;
    try { type = event?.type; } catch (_) { return; }
    if (type !== "assistant/message") return;

    const text = textOf(event).trim();
    if (!text) return;
    const provider = event?.message?.source?.provider;
    const model = event?.message?.source?.model;
    if (!provider || !model) return;

    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), TIMEOUT_MS);
    (async () => {
      try {
        const assembler = new BlockAssembler();
        for await (const chunk of ctx.llm.stream({
          provider,
          model,
          system: SYSTEM,
          messages: [createUserMessage({
            content: [{ type: "text", text: text.slice(0, MAX_INPUT) }],
            source: { kind: "dsh-plugin-speech" },
          })],
          maxTokens: 300,
          sessionId: session?.id,
          purpose: "speech-rewrite",
          signal: ac.signal,
        })) {
          assembler.push(chunk);
        }
        const spoken = assembler.blocks()
          .filter((b) => b.type === "text").map((b) => b.text ?? "").join("").trim();
        if (!spoken) return;
        writeAtomic(OUT, spoken);
        ctx.logger?.info?.(`speech: 口播稿 ${spoken.length} 字已写入`);
      } catch (e) {
        ctx.logger?.info?.(`speech: 改写失败（悬浮球会用本地规则）: ${e?.message ?? e}`);
      } finally {
        clearTimeout(timer);
      }
    })();
  });
}
