package com.glslplayground.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 「弹出初始状态就开始听」的那一段。
 *
 * 两条引擎，按可用性自动选：
 *
 *  ① **系统语音识别（首选）** {@link AndroidAsr}：带 partial results，
 *     "话音刚落"由系统 `onEndOfSpeech` 判，点一下可以立刻拿走已经听出来的字；
 *  ② **录音 + DSH 转写**（{@link VoiceCapture} + `speech/transcribe`）：
 *     系统识别不可用（没网 / 没离线模型 / 没装语音服务）时退回这条路，
 *     用 RMS 端点检测（静音 900ms）当"话音刚落"。
 *
 * 两条出口（用户要的交互）：
 *   · **话音刚落** → 自动停 → {@link Cb#onHeard(String, boolean)} 第二参 = true
 *     → 调用方把这句话**发到新会话**；
 *   · **识别过程中被点一下** → 立刻停 → 第二参 = false
 *     → 调用方把文字**留在状态 2 的输入框里**（不发送）。
 *
 * 所有回调都在主线程。
 */
final class BallVoice {

    private static final String TAG = "BallVoice";

    interface Cb {
        void onListening();
        void onLevel(float level);
        void onTranscribing();
        void onHeard(String text, boolean auto);
        void onError(String message);
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DshApi api;

    private AndroidAsr asr;
    private VoiceCapture cap;
    private Cb cb;
    private volatile boolean listening;
    private volatile boolean transcribing;
    private String partial = "";

    BallVoice(Context c) {
        app = c.getApplicationContext();
        api = DshApi.get(app);
    }

    boolean listening() { return listening; }
    boolean busy() { return listening || transcribing; }

    void start(final Cb callback) {
        if (busy()) return;
        cb = callback;
        partial = "";
        if (AndroidAsr.available(app)) {
            startAsr();
        } else {
            Log.i(TAG, "系统语音识别不可用 → 走录音 + DSH 转写");
            startRecorder();
        }
    }

    // ── 引擎 ①：系统识别 ────────────────────────────────────────────────

    private void startAsr() {
        asr = new AndroidAsr(app);
        asr.start(new AndroidAsr.Cb() {
            @Override public void onReady() {
                listening = true;
                final Cb c = cb;
                if (c != null) c.onListening();
            }

            @Override public void onLevel(float level) {
                final Cb c = cb;
                if (c != null) c.onLevel(level);
            }

            @Override public void onPartial(String text) {
                partial = text == null ? "" : text;
                Log.i(TAG, "ASR partial：「" + partial + "」");
            }

            @Override public void onSpeechEnd() {
                final Cb c = cb;
                if (c != null) c.onTranscribing();
            }

            @Override public void onFinal(String text, boolean byUser) {
                listening = false;
                final String t = text == null ? "" : text.trim();
                Log.i(TAG, "ASR final「" + t + "」byUser=" + byUser);
                final Cb c = cb;
                if (c != null) c.onHeard(t, !byUser);      // 系统自己收的尾 = 话音刚落 → 自动发
            }

            @Override public void onFail(String reason, boolean fatal) {
                listening = false;
                Log.w(TAG, "ASR 不可用（" + reason + "，fatal=" + fatal + "）");
                if (!partial.trim().isEmpty()) {
                    final Cb c = cb;
                    if (c != null) c.onHeard(partial.trim(), false);
                    return;
                }
                // 系统识别没给出任何字（没网 / 没离线模型 / 服务超时）：退回"录音 + DSH 转写"，
                // 而不是把"空结果"当成用户点了一下 —— 那会把状态 2 无故弹出来。
                startRecorder();
            }
        });
    }

    // ── 引擎 ②：录音 + DSH 转写 ─────────────────────────────────────────

    private void startRecorder() {
        if (cap != null) return;
        cap = new VoiceCapture();
        final boolean ok = cap.start(new VoiceCapture.Listener() {
            @Override public void onLevel(float rms) {
                final Cb c = cb;
                if (c != null) main.post(new Runnable() {
                    @Override public void run() { c.onLevel(rms); }
                });
            }

            @Override public void onError(final String reason) {
                listening = false;
                final Cb c = cb;
                if (c != null) main.post(new Runnable() {
                    @Override public void run() { c.onError(reason); }
                });
            }

            @Override public void onSpeechEnd(float voicedSeconds) {
                Log.i(TAG, "VAD: 话音刚落（有效语音 "
                        + String.format("%.2f", voicedSeconds) + "s）→ 自动停");
                final Cb c = cb;
                if (c != null) main.post(new Runnable() {
                    @Override public void run() { c.onTranscribing(); }
                });
                finishRecorderAndTranscribe(true);
            }
        });
        if (!ok) { listening = false; return; }
        listening = true;
        final Cb c = cb;
        if (c != null) c.onListening();
    }

    void tapStop() {
        AndroidAsr a = asr;
        if (a != null && listening) {
            Log.i(TAG, "点击打断 → 系统识别 stopListening");
            a.stopByUser();
            return;
        }
        if (!listening) return;
        Log.i(TAG, "点击打断 → 停止录音，转写已采到的音频（进状态 2，不发送）");
        final Cb c = cb;
        if (c != null) c.onTranscribing();
        finishRecorderAndTranscribe(false);
    }

    private void finishRecorderAndTranscribe(final boolean auto) {
        final VoiceCapture c = cap;
        cap = null;
        listening = false;
        final byte[] wav;
        try {
            wav = c == null ? null : c.stop();
        } catch (Throwable t) {
            transcribing = false;
            emit("", auto);
            return;
        }
        if (wav == null || wav.length <= 44) { transcribing = false; emit("", auto); return; }
        transcribing = true;
        new Thread(new Runnable() {
            @Override public void run() {
                String text = "";
                String err = null;
                try {
                    text = api.transcribe(wav);
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.toString() : t.getMessage();
                    Log.w(TAG, "transcribe failed: " + err);
                }
                transcribing = false;
                final String t2 = text;
                final String e2 = err;
                final Cb c2 = cb;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (c2 == null) return;
                        if (e2 != null && t2.isEmpty()) {
                            c2.onHeard("", auto);
                            c2.onError("转写失败：" + e2);
                        } else {
                            c2.onHeard(t2, auto);
                        }
                    }
                });
            }
        }, "dsh-voice-transcribe").start();
    }

    private void emit(final String text, final boolean auto) {
        final Cb c = cb;
        if (c == null) return;
        main.post(new Runnable() {
            @Override public void run() { c.onHeard(text, auto); }
        });
    }

    void cancel() {
        if (asr != null) { asr.cancel(); asr = null; }
        VoiceCapture c = cap;
        cap = null;
        listening = false;
        transcribing = false;
        if (c != null) c.cancel();
        cb = null;
    }
}
