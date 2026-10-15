package com.glslplayground.app;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 用大模型把助手回复改写成「短口播稿」，交给 TTS 念。
 *
 * 刻意做成**最简**：
 *   · 只在开启「朗读回答」时才走这条路（不开朗读 = 完全不发请求，DSH 零影响）；
 *   · 每次都发一次请求，不做"值不值得改写"的判断；
 *   · 提示词要求输出 100 字以内的口播稿 —— 朗读本来就不需要念全文，
 *     短了既好念又省钱，比在代码里截断更自然；
 *   · 任何失败（没填 key / 断网 / 超时 / 返回空）都回落本地规则清洗
 *     （{@link SiriAssistant#toSpeechText}），朗读不会因此停摆。
 *
 * 不碰 DSH：不改它的代码、不占它的会话、不读它的凭据；key 只存在本 App 的首选项里。
 */
public final class SpeechRewriter {

    private static final String TAG = "SpeechRewrite";
    private static final String ENDPOINT = "https://api.deepseek.com/chat/completions";
    private static final String MODEL = "deepseek-chat";
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int MAX_INPUT_CHARS = 1200;      // 裁剪输入：朗读不需要全文
    private static final int MAX_OUTPUT_TOKENS = 300;     // 100 字口播稿的余量

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    private SpeechRewriter() { }

    public interface Cb {
        /** 最终要念的稿子；无论成功失败都会回调一次（失败给的是规则清洗版）。 */
        void onReady(String spoken, boolean byModel);
    }

    /** 异步产出朗读稿；apiKey 为空时直接用规则清洗版。 */
    public static void rewrite(final String raw, final String apiKey, final Cb cb) {
        final String fallback = SiriAssistant.toSpeechText(raw);
        if (apiKey == null || apiKey.trim().isEmpty()) {   // 没填 key：用规则清洗版
            cb.onReady(fallback, false);
            return;
        }
        POOL.execute(new Runnable() {
            @Override public void run() {
                String out = null;
                try {
                    out = callModel(raw, apiKey.trim());
                } catch (Throwable e) {
                    Log.i(TAG, "改写失败，回落规则清洗: " + e);
                }
                if (out != null && !out.trim().isEmpty()) {
                    cb.onReady(SiriAssistant.toSpeechText(out), true);
                } else {
                    cb.onReady(fallback, false);
                }
            }
        });
    }

    private static String callModel(String raw, String apiKey) throws Exception {
        String input = raw.length() > MAX_INPUT_CHARS ? raw.substring(0, MAX_INPUT_CHARS) : raw;

        JSONObject body = new JSONObject();
        body.put("model", MODEL);
        body.put("temperature", 0.2);
        body.put("max_tokens", MAX_OUTPUT_TOKENS);
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject()
                .put("role", "system")
                .put("content",
                        "把下面的助手回复改写成一句到三句的中文口播稿，供语音朗读。"
                        + "要求：总长不超过 100 字；口语化；只讲结论和关键信息；"
                        + "不要 markdown、不要代码、不要表格、不要链接、不要 emoji、不要序号符号；"
                        + "数字、单位、文件名保留；只输出正文，不要任何解释或引号。"));
        msgs.put(new JSONObject().put("role", "user").put("content", input));
        body.put("messages", msgs);

        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(in);
            if (code < 200 || code >= 300) {
                Log.i(TAG, "HTTP " + code + ": "
                        + (text == null ? "" : text.substring(0, Math.min(120, text.length()))));
                return null;
            }
            JSONObject obj = new JSONObject(text);
            JSONArray choices = obj.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            return msg == null ? null : msg.optString("content", null);
        } finally {
            try { conn.disconnect(); } catch (Throwable ignored) { }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return null;
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close();
        return sb.toString();
    }
}
