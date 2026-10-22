package com.glslplayground.app;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/**
 * 系统自带的流式语音识别（SpeechRecognizer）—— 初始状态"会听"的首选引擎。
 *
 * 为什么优先用它，而不是直接吃 DSH 的 `speech/transcribe`：
 *   1. 它给 **partial results**（边听边出字），所以"识别过程中被点一下"时手里已经有文字，
 *      可以原样留在状态 2 的输入框里（用户要的那个语义）；
 *   2. 它自己会判"说完了"（`onEndOfSpeech` → 最终结果），正好就是"话音刚落自动停"；
 *   3. 不依赖本机 DSH 是否装了 speech 插件（这个部署的 `speech/*` 路由是 404）。
 *
 * \u26a0 DSH 那条路仍然保留：系统识别不可用（没装语音服务 / 断网）时，
 * {@link BallVoice} 会退回"录音 → `speech/transcribe`"。
 *
 * 全部回调都在主线程。
 */
final class AndroidAsr {

    private static final String TAG = "AndroidAsr";

    interface Cb {
        void onReady();
        void onLevel(float level);
        /** 已经有 partial 文字了（可用作"点击打断"时的保留文本）。 */
        void onPartial(String text);
        /** 系统认为说完了。 */
        void onSpeechEnd();
        /** 最终文本（final=true 表示没有被打断）。 */
        void onFinal(String text, boolean byUser);
        /** 引擎不可用/出错；调用方据此退回录音 + DSH 转写。 */
        void onFail(String reason, boolean fatal);
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer rec;
    private Cb cb;
    private volatile boolean running;
    private boolean userStopped;
    private String lastPartial = "";
    private boolean gotAny;

    AndroidAsr(Context c) { app = c.getApplicationContext(); }

    static boolean available(Context c) {
        try { return SpeechRecognizer.isRecognitionAvailable(c); }
        catch (Throwable t) { return false; }
    }

    boolean running() { return running; }

    void start(Cb callback) {
        if (running) return;
        cb = callback;
        lastPartial = "";
        gotAny = false;
        userStopped = false;
        if (!available(app)) {
            Log.w(TAG, "系统没有可用的语音识别服务");
            if (cb != null) cb.onFail("系统语音识别不可用", true);
            return;
        }
        try {
            rec = SpeechRecognizer.createSpeechRecognizer(app);
            rec.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    running = true;
                    Log.i(TAG, "onReadyForSpeech");
                    final Cb c = cb;
                    if (c != null) c.onReady();
                }

                @Override public void onBeginningOfSpeech() { }

                @Override public void onRmsChanged(float rmsdB) {
                    // rmsdB 大致 -2..10；映射到 0..1
                    final float lv = Math.max(0f, Math.min(1f, (rmsdB + 2f) / 12f));
                    final Cb c = cb;
                    if (c != null) c.onLevel(lv);
                }

                @Override public void onBufferReceived(byte[] buffer) { }

                @Override public void onEndOfSpeech() {
                    Log.i(TAG, "onEndOfSpeech（话音刚落）");
                    final Cb c = cb;
                    if (c != null) c.onSpeechEnd();
                }

                @Override public void onError(int error) {
                    String reason = errorName(error);
                    // 12/13 = ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE
                    // （API 33 才有的常量，这里用字面量，编译期 android.jar 是 30）
                    boolean fatal = error == SpeechRecognizer.ERROR_CLIENT
                            || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                            || error == 12 || error == 13;
                    Log.w(TAG, "onError " + error + " (" + reason + ") gotAny=" + gotAny
                            + " partial=" + lastPartial);
                    running = false;
                    final Cb c = cb;
                    String text = lastPartial.trim();
                    if (!text.isEmpty()) {
                        if (c != null) c.onFinal(text, userStopped);
                        return;
                    }
                    if (c != null) c.onFail(reason, fatal);
                }

                @Override public void onResults(Bundle results) {
                    String text = best(results);
                    running = false;
                    if (text.isEmpty()) text = lastPartial;
                    Log.i(TAG, "onResults「" + text + "」byUser=" + userStopped);
                    final Cb c = cb;
                    if (c != null) c.onFinal(text.trim(), userStopped);
                }

                @Override public void onPartialResults(Bundle partialResults) {
                    String text = best(partialResults);
                    if (text.isEmpty()) return;
                    gotAny = true;
                    lastPartial = text;
                    final Cb c = cb;
                    if (c != null) c.onPartial(text);
                }

                @Override public void onEvent(int eventType, Bundle params) { }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN");
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            // 先试离线识别：这台机器上在线语音服务经常超时/被墙，离线模型有就直接用
            if (Build.VERSION.SDK_INT >= 23) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.getPackageName());
            rec.startListening(i);
            running = true;
            Log.i(TAG, "startListening");
        } catch (Throwable t) {
            Log.w(TAG, "start failed: " + t);
            release();
            if (cb != null) cb.onFail("语音识别启动失败：" + t, true);
        }
    }

    /** 用户点了一下：停止识别，把已经听出来的字交出去（进状态 2）。 */
    void stopByUser() {
        userStopped = true;
        SpeechRecognizer r = rec;
        if (r == null) return;
        Log.i(TAG, "用户点击 → stopListening（partial=「" + lastPartial + "」）");
        try { r.stopListening(); } catch (Throwable ignored) { }
    }

    /** 放弃本次识别（长按进状态 3 / 关面板时用）。 */
    void cancel() {
        running = false;
        SpeechRecognizer r = rec;
        rec = null;
        cb = null;
        if (r != null) {
            try { r.cancel(); } catch (Throwable ignored) { }
            try { r.destroy(); } catch (Throwable ignored) { }
        }
    }

    private void release() {
        SpeechRecognizer r = rec;
        rec = null;
        if (r != null) {
            try { r.cancel(); } catch (Throwable ignored) { }
            try { r.destroy(); } catch (Throwable ignored) { }
        }
    }

    private static String best(Bundle b) {
        if (b == null) return "";
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return "";
        return list.get(0) == null ? "" : list.get(0);
    }

    private static String errorName(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_AUDIO: return "录音出错";
            case SpeechRecognizer.ERROR_CLIENT: return "识别客户端出错";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "没有麦克风权限";
            case SpeechRecognizer.ERROR_NETWORK: return "网络错误";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "网络超时";
            case SpeechRecognizer.ERROR_NO_MATCH: return "没听清";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "识别服务忙";
            case SpeechRecognizer.ERROR_SERVER: return "语音服务端错误";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "长时间没检测到说话";
            default: return "识别错误 " + e;
        }
    }
}
