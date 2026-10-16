/**
 * dsh-plugin-speech —— 把助手的**最终回复**改写成「短口播稿」，写给悬浮球去做 TTS。
 *
 * 为什么做成 DSH 插件：直接复用 DSH 自己已经配好的模型通道（provider/model 从
 * assistant/message 事件的 source 里取），**用户不需要第二份 API key**，
 * 也不用改 DSH 的任何代码 —— 它只是又一个插件。
 *
 * ── v2（2026-10-17）改了什么、为什么 ────────────────────────────────
 * 球侧改成「一条助手消息念一次」之后，插件只负责**最终回复**那一条
 * （判据：这条消息里没有 tool-call 块 = 本回合的收尾回复）：
 *   · 处理中的旁白又短又干净，球直接念原文就行，走一次模型纯属浪费延迟；
 *   · 旧版按「本回合最长的正文」改写，会把中间那段旁白当成最终回复（内容对不上）。
 *
 * 输出两份文件（都在球的私有目录里）：
 *   · spoken.json = {"v":2,"turn":T,"step":S,"text":"..."}  ← 带身份，球按 (turn,step) 对号
 *       text 为空串 = 「这条我没稿子，你念你本地那版」——**包括改写失败/太短**，
 *       这样球不用干等到超时才回落（旧版就是这么每次都等满 15 秒的）。
 *   · spoken.txt  = 纯文本稿子（老版球的兜底通道，保留兼容）
 *
 * 还有 **心跳文件** speech-plugin.alive：球靠它判断"插件到底在不在跑"。
 * 没有心跳（比如插件没被加载、profile 被重写丢了登记）球就一秒都不等 ——
 * 这正是 10-16 那次「每次朗读都莫名其妙停 15 秒」的根因，见 README。
 *
 * 失败一律静默：写不出文件时悬浮球会走本地规则，朗读不会因此停摆。
 *
 * ── 踩过的坑（都靠"直接落盘"的日志才查出来）──
 * 1. `session.append(type, data)` 的事件是 `{type, seq, time, data}`，
 *    正文在 **`event.data.message`**；写成 `event.message` 会永远取不到（静默失效）。
 * 2. 模型默认带推理，不关的话 `max_tokens` 会被 reasoning 块吃光、正文一个字都没有
 *    → 必须 `reasoningEffort: "off"`。
 * 3. `assistant/message` **一个回合会来多条**（DSH 一步一条），最后一条才是最终回复；
 *    逐条判断"够长就改写"会改写错对象 → 必须按「有没有 tool-call 块」挑出收尾那条。
 * 4. `ctx.logger` 的输出在设备上看不到，排查必须 `fs.appendFileSync` 到 files/ 下。
 */
import fs from "node:fs";
import { BlockAssembler, createUserMessage, ReasoningEffortId } from "@deepseek-ai/dsh-llm";

const DIR = process.env.DSH_SPEECH_DIR
  || "/sdcard/Android/data/com.glslplayground.app/files";
const OUT = process.env.DSH_SPEECH_OUT || `${DIR}/spoken.txt`;
const OUT_JSON = process.env.DSH_SPEECH_OUT_JSON || `${DIR}/spoken.json`;
/** 心跳：球用它判断插件在不在跑（球那边 45 秒算过期，这里 15 秒跳一次）。 */
const ALIVE = `${DIR}/speech-plugin.alive`;
const LOGF = `${DIR}/speech-plugin.log`;
const MAX_INPUT = 1200;
const TIMEOUT_MS = 5000;
/** 短于这个长度就别麻烦模型了（本来就没几个字，本地规则清洗一样好听）。 */
const SHORT_TEXT = 60;

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

/** 取一条 assistant/message 的内容块（见坑 1）。 */
function blocksOf(event) {
  const msg = event?.data?.message ?? event?.message;
  const content = msg?.content;
  return Array.isArray(content) ? content : [];
}

function textOf(event) {
  return blocksOf(event).filter((b) => b?.type === "text").map((b) => b.text ?? "").join("");
}

function sourceOf(event) {
  return (event?.data?.message ?? event?.message)?.source ?? {};
}

/** 带 tool-call 块 = 处理中的旁白（球自己念原文），不带 = 本回合的最终回复。 */
function hasToolCall(event) {
  return blocksOf(event).some((b) => b?.type === "tool-call");
}

export const name = "speech";
export const inject = ["llm"];

export function apply(ctx) {
  /** 每个会话当前待改写的「最终回复」。 */
  const acc = new Map();   // sessionId -> { text, turn, step, provider, model, timer }

  // 心跳：球看不到这个文件就不等口播稿（没它就得盲等，历史上是 15 秒）
  const beat = () => { try { writeAtomic(ALIVE, String(Date.now())); } catch (_) {} };
  beat();
  try {
    const hb = setInterval(beat, 15000);
    if (typeof hb.unref === "function") hb.unref();
    ctx.on?.("dispose", () => clearInterval(hb));
  } catch (_) {}
  note(`插件已加载（v2），心跳 ${ALIVE}`);

  /**
   * 发动改写。带 400ms 防抖：正文安静下来就先开跑（不必等 turn/end），
   * 让球那边的等待更容易等到；turn/end 到达时若还没跑就立刻跑，并取消防抖。
   */
  const flush = (sid) => {
    const rec = acc.get(sid);
    if (!rec) return;
    acc.delete(sid);
    if (rec.timer) clearTimeout(rec.timer);
    const text = (rec.text ?? "").trim();
    if (!text) return;
    const id = { v: 2, turn: rec.turn ?? -1, step: rec.step ?? -1, text: "" };
    if (!rec.provider || !rec.model) { note("没有 provider/model，跳过"); writeAtomic(OUT_JSON, JSON.stringify(id)); return; }
    if (text.length < SHORT_TEXT) {
      // 太短就不叫模型了，直接告诉球「念你本地那版」——球会立刻出声，不用等到超时
      note(`正文只有 ${text.length} 字（<${SHORT_TEXT}），不改写`);
      writeAtomic(OUT_JSON, JSON.stringify(id));
      return;
    }
    note(`开始改写：最终回复 ${text.length} 字（turn=${id.turn} step=${id.step}，${rec.provider}/${rec.model}）`);
    rewrite(ctx, text, rec.provider, rec.model, sid, id);
  };

  ctx.on("session/event", (session, event) => {
    const type = event?.type;
    const sid = session?.id ?? session?.header?.id ?? "?";

    if (type === "assistant/message") {
      if (hasToolCall(event)) return;                  // 处理中的旁白：不需要稿子
      const t = textOf(event).trim();
      if (!t) return;                                  // 空消息（纯工具调用）也一样跳过
      const d = event?.data ?? {};
      const src = sourceOf(event);
      const prev = acc.get(sid);
      if (prev?.timer) clearTimeout(prev.timer);
      const timer = setTimeout(() => flush(sid), 400);
      acc.set(sid, {
        text: t,
        turn: d.turn ?? -1,
        step: d.step ?? -1,
        provider: src.provider ?? prev?.provider,
        model: src.model ?? prev?.model,
        timer,
      });
      if (acc.size > 50) {                             // 简单防泄漏
        for (const v of acc.values()) clearTimeout(v.timer);
        acc.clear();
      }
      return;
    }

    if (type === "turn/end") flush(sid);
  });
}

/** 一次改写：关推理 + 额度按正文给；失败只记日志，不影响 DSH。 */
function rewrite(ctx, text, provider, model, sid, id) {
  const input = text.length > MAX_INPUT ? text.slice(0, MAX_INPUT) : text;
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), TIMEOUT_MS + 2000);
  /** 改写没成也要给球一个交代：空稿 = 「念你本地那版」，别让它干等。 */
  const giveUp = (why) => {
    note(`${why} → 写空稿让球立刻用本地规则版`);
    try { writeAtomic(OUT_JSON, JSON.stringify({ ...id, text: "" })); } catch (_) {}
  };
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
      if (!spoken) { giveUp("重试也没文本"); return; }
      writeAtomic(OUT_JSON, JSON.stringify({ ...id, text: spoken }));   // 带身份：球按 (turn,step) 取
      writeAtomic(OUT, spoken);                                        // 老版球的兜底通道
      note(`口播稿 ${spoken.length} 字已写入（turn=${id.turn} step=${id.step}）`);
    } catch (e) {
      giveUp(`改写失败: ${e?.name ?? ""} ${e?.message ?? e}`);
    } finally {
      clearTimeout(timer);
    }
  })();
}
