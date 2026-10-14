package com.glslplayground.app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Base64;

import java.io.ByteArrayOutputStream;

/**
 * 语音采集：16 kHz 单声道 PCM16 —— 与 DSH 语音插件发给宿主 speech/transcribe 的载荷一致。
 *
 * 采到的原始 PCM 在这里直接封成 canonical WAV（44 字节头 + little-endian PCM），
 * 再 base64 交给 DSH；PCM 与 WAV 的分辨率不需要额外重采样，因为 AudioRecord
 * 直接就按 16 kHz 采集。
 *
 * 采集过程中按 50 ms 上报一次 RMS（0..1），用来驱动水晶球的 Siri 波振幅。
 */
public final class VoiceCapture {

    public interface Listener {
        /** @param rms 归一化音量，0..1 */
        void onLevel(float rms);
        /** @param reason 采集失败原因，已本地化 */
        void onError(String reason);
    }

    public static final int SAMPLE_RATE = 16000;
    /** DSH 宿主对 audioBytes 的上限是 4 MiB；16 kHz PCM16 约 32 KB/s，留足余量。 */
    private static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final int MAX_SECONDS = 90;

    private AudioRecord record;
    private Thread worker;
    private volatile boolean running;
    private volatile boolean aborted;
    private ByteArrayOutputStream pcm;
    private Listener listener;
    private long startedAt;

    public boolean isRunning() { return running; }

    /** 当前已采集时长（秒）。 */
    public float seconds() {
        return running ? (System.currentTimeMillis() - startedAt) / 1000f : 0f;
    }

    /**
     * 打开麦克风并开始采集。失败时不抛异常，通过 {@link Listener#onError} 上报，
     * 返回 false，调用方据此提示用户（权限被拒 / 麦克风被占用）。
     */
    public boolean start(Listener cb) {
        if (running) return true;
        listener = cb;
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = SAMPLE_RATE / 2;
        final int bufSize = Math.max(minBuf, SAMPLE_RATE / 5);   // ≥200 ms

        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize * 2);
        } catch (Throwable t) {
            record = null;
        }
        if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
            release();
            if (listener != null) listener.onError("麦克风不可用（可能被其他应用占用）");
            return false;
        }

        pcm = new ByteArrayOutputStream(SAMPLE_RATE * 2 * 8);
        aborted = false;
        running = true;
        startedAt = System.currentTimeMillis();

        worker = new Thread(new Runnable() {
            @Override public void run() {
                final short[] buf = new short[Math.max(1024, bufSize / 2)];
                long lastReport = 0;
                try {
                    record.startRecording();
                    while (running) {
                        int n = record.read(buf, 0, buf.length);
                        if (n <= 0) {
                            if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) break;
                            continue;
                        }
                        if (!aborted) {
                            byte[] bytes = new byte[n * 2];
                            for (int i = 0; i < n; i++) {
                                short s = buf[i];
                                bytes[i * 2] = (byte) (s & 0xFF);
                                bytes[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
                            }
                            pcm.write(bytes, 0, bytes.length);
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastReport >= 50) {
                            lastReport = now;
                            double sum = 0;
                            for (int i = 0; i < n; i++) {
                                double v = buf[i] / 32768.0;
                                sum += v * v;
                            }
                            float rms = (float) Math.sqrt(sum / Math.max(1, n));
                            // 语音大致落在 0.02..0.25 RMS：做一点感知曲线
                            final float shaped = Math.min(1f, (float) Math.pow(rms * 4.2, 0.75));
                            if (listener != null) listener.onLevel(shaped);
                        }
                        if (pcm.size() >= MAX_BYTES
                                || (now - startedAt) / 1000f >= MAX_SECONDS) {
                            running = false;      // 到上限自动收尾，交给 stop()
                        }
                    }
                } catch (Throwable t) {
                    if (listener != null && running) listener.onError("录音中断：" + t.getMessage());
                } finally {
                    running = false;
                }
            }
        }, "dsh-voice-capture");
        worker.start();
        return true;
    }

    /** 结束采集并返回 WAV 字节；没有有效音频时返回 null。 */
    public byte[] stop() {
        running = false;
        Thread w = worker;
        worker = null;
        if (w != null) {
            try { w.join(1200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        byte[] body = null;
        if (pcm != null && !aborted) {
            byte[] raw = pcm.toByteArray();
            if (raw.length >= SAMPLE_RATE / 2) body = wavFromPcm(raw, SAMPLE_RATE);   // ≥0.25 s
        }
        release();
        if (listener != null) listener.onLevel(0f);
        return body;
    }

    /** 放弃本次采集。 */
    public void cancel() {
        aborted = true;
        running = false;
        Thread w = worker;
        worker = null;
        if (w != null) {
            try { w.join(600); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        release();
        if (listener != null) listener.onLevel(0f);
    }

    private void release() {
        if (record != null) {
            try { if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop(); } catch (Throwable ignored) {}
            try { record.release(); } catch (Throwable ignored) {}
            record = null;
        }
        pcm = null;
    }

    /** canonical 16 kHz mono PCM16 WAV。 */
    public static byte[] wavFromPcm(byte[] pcm, int rate) {
        int len = pcm.length;
        byte[] out = new byte[44 + len];
        int p = 0;
        p = str(out, p, "RIFF");
        p = u32(out, p, 36 + len);
        p = str(out, p, "WAVE");
        p = str(out, p, "fmt ");
        p = u32(out, p, 16);
        p = u16(out, p, 1);            // PCM
        p = u16(out, p, 1);            // mono
        p = u32(out, p, rate);
        p = u32(out, p, rate * 2);     // byte rate
        p = u16(out, p, 2);            // block align
        p = u16(out, p, 16);           // bits
        p = str(out, p, "data");
        u32(out, p, len);
        System.arraycopy(pcm, 0, out, 44, len);
        return out;
    }

    public static String toBase64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private static int str(byte[] b, int p, String s) {
        for (int i = 0; i < s.length(); i++) b[p + i] = (byte) s.charAt(i);
        return p + s.length();
    }

    private static int u32(byte[] b, int p, int v) {
        b[p] = (byte) (v & 0xFF);
        b[p + 1] = (byte) ((v >> 8) & 0xFF);
        b[p + 2] = (byte) ((v >> 16) & 0xFF);
        b[p + 3] = (byte) ((v >> 24) & 0xFF);
        return p + 4;
    }

    private static int u16(byte[] b, int p, int v) {
        b[p] = (byte) (v & 0xFF);
        b[p + 1] = (byte) ((v >> 8) & 0xFF);
        return p + 2;
    }
}
