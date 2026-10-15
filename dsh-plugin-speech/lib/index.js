/**
 * dsh-plugin-speech —— 把助手回复改写成「短口播稿」，写给悬浮球去做 TTS。
 *
 * 为什么做成 DSH 插件：直接复用 DSH 自己已经配好的模型通道（provider/model 从
 * assistant/message 事件的 source 里取），**用户不需要第二份 API key**，
 * 也不用改 DSH 的任何代码 —— 它只是又一个插件。
 *
 * 数据流：
 *   assistant/message 事件（**流式追加，会来很多条**）→ 按会话累积正文
 *   → turn/end 时用最后一条的 provider/model，请求一次「≤100 字中文口播稿」
 *   → 原子写 /sdcard/Android/data/com.glslplayground.app/files/spoken.txt
 *   → 悬浮球朗读前会等这个文件（等不到就用本地规则清洗）
 *
 * 失败一律静默：写不出文件时悬浮球会走本地规则，朗读不会因此停摆。
 *
 * ── 踩过的坑（都靠"直接落盘"的日志才查出来）──
 * 1. `session.append(type, data)` 的事件是 `{type, seq, time, data}`，
 *    正文在 **`event.data.message`**；写成 `event.message` 会永远取不到（静默失效）。
 * 2. 模型默认带推理，不关的话 `max_tokens` 会被 reasoning 块吃光、正文一个字都没有
 *    → 必须 `reasoningEffort: "off"`。
 * 3. **`assistant/message` 是流式追加的**：一个回合会来很多条，正文逐步变长，
 *    而**最后一条经常很短**（实测 0 → 20 → 26 字）。
 *    所以不能"逐条判断正文够长就改写"——那样永远不触发；
 *    正确做法是**累积到 turn/end 再改写一次**，且**不设最小长度门槛**。
 * 4. `ctx.logger` 的输出在设备上看不到，排查必须 `fs.appendFileSync` 到 files/ 下。
 */
import fs from "node:fs";
import { BlockAssembler, createUserMessage, ReasoningEffortId } from "@deepseek-ai/dsh-llm";

const OUT = process.env.DSH_SPEECH_OUT
  || "/sdcard/Android/data/com.glslplayground.app/files/spoken.txt";
const LOGF = "/sdcard/Android/data/com.glslplayground.app/files/speech-plugin.log";
const MAX_INPUT = 1200;
const TIMEOUT_MS = 5000;

/** 改写是纯格式化任务，不需要推理（见坑 2）。 */
const NO_REASONING = ReasoningEffortId("off");

const SYSTEM =
  "把下面的助手回复改写成一句到三句的中文口播稿，供语音朗读。"
  + "要求：总长不超过 100 字；口语化；只讲结论和关键信息；"
  + "不要 markdown、不要代码、不要表格、不要链接、不要 emoji、不要序号符号；"
  + "数字、单位、文件名保留；只输出正文，不要任何解释或引号。";

/** 直接落盘的日志：不依赖 ctx.logger（见坑 4）。 */
function note(msg) {
  try { fs.appendFileSync(LOGF, `${new Date().toISOString().slice(11, 19)} ${msg}\n`); } catch (_) {}
}

function writeAtomic(p, body) {
  const tmp = `${p}.tmp`;
  fs.writeFileSync(tmp, body, "utf8");
  fs.renameSync(tmp, p);
}

/** 取一条 assistant/message 的纯文本（见坑 1）。 */
function textOf(event) {
  const msg = event?.data?.message ?? event?.message;
  const content = msg?.content;
  if (!Array.isArray(content)) return "";
  return content.filter((b) => b?.type === "text").map((b) => b.text ?? "").join("");
}

function sourceOf(event) {
  return (event?.data?.message ?? event?.message)?.source ?? {};
}

export const name = "speech";
export const inject = ["llm"];

export function apply(ctx) {
  /** 每个会话累积本回合的助手正文（流式追加，取最长的那次为准）。 */
  const acc = new Map();   // sessionId -> { text, provider, model, timer }

  /**
   * 发动改写。带 400ms 防抖：正文安静下来就先开跑（不必等 turn/end），
   * 让球那边的等待更容易等到；turn/end 到达时若还没跑就立刻跑，并取消防抖。
   */
  const flush = (sid) => {
    const rec = acc.get(sid);
    if (!rec) return;
    acc.delete(sid);
    if (rec.timer) clearTimeout(rec.timer);
    if (!rec.text) return;
    if (!rec.provider || !rec.model) { note("没有 provider/model，跳过"); return; }
    note(`开始改写：本回合正文 ${rec.text.length} 字（${rec.provider}/${rec.model}）`);
    rewrite(ctx, rec.text, rec.provider, rec.model, sid);
  };

  ctx.on("session/event", (session, event) => {
    const type = event?.type;
    const sid = session?.id ?? session?.header?.id ?? "?";

    if (type === "assistant/message") {
      const t = textOf(event).trim();
      if (!t) return;                                   // 只有 reasoning/tool-call 的步骤
      const src = sourceOf(event);
      const prev = acc.get(sid);
      const text = prev && prev.text.length > t.length ? prev.text : t;
      const provider = src.provider ?? prev?.provider;
      const model = src.model ?? prev?.model;
      if (prev?.timer) clearTimeout(prev.timer);
      const timer = setTimeout(() => flush(sid), 400);
      acc.set(sid, { text, provider, model, timer });
      if (acc.size > 50) {                       // 简单防泄漏
        for (const v of acc.values()) clearTimeout(v.timer);
        acc.clear();
      }
      return;
    }

    if (type === "turn/end") flush(sid);
  });
}

/** 一次改写：关推理 + 额度按正文给；失败只记日志，不影响 DSH。 */
function rewrite(ctx, text, provider, model, sid) {
  const input = text.length > MAX_INPUT ? text.slice(0, MAX_INPUT) : text;
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), TIMEOUT_MS + 2000);
  (async () => {
    try {
      const call = async (maxTokens) => {
        const asm = new BlockAssembler();
        for await (const chunk of ctx.llm.stream({
          provider, model, system: SYSTEM,
          messages: [createUserMessage({
            content: [{ type: "text", text: input }],
            source: { kind: "dsh-plugin-speech" },
          })],
          reasoningEffort: NO_REASONING,
          maxTokens,
          sessionId: sid,
          purpose: "speech-rewrite",
          signal: ac.signal,
        })) asm.push(chunk);
        return asm.blocks().filter((b) => b.type === "text").map((b) => b.text ?? "").join("").trim();
      };

      let spoken = await call(text.length > 300 ? 400 : 250);
      if (!spoken) {
        note("第一次没拿到文本（可能被推理占满），用 800 token 重试");
        spoken = await call(800);
      }
      if (!spoken) { note("重试也没文本，跳过（球用规则清洗）"); return; }
      writeAtomic(OUT, spoken);
      note(`口播稿 ${spoken.length} 字已写入`);
    } catch (e) {
      note(`改写失败: ${e?.name ?? ""} ${e?.message ?? e}`);
    } finally {
      clearTimeout(timer);
    }
  })();
}
