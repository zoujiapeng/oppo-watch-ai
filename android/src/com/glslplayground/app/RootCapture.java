package com.glslplayground.app;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * root 直采：以 su 身份循环调用系统 `screencap` 输出原始 RGBA 帧，
 * 直接喂给 GlassRenderer 的 2D 纹理。不经过 MediaProjection，无录屏弹窗与录制标记。
 * 需要设备已 root（Magisk/SU）。
 */
public class RootCapture {

    private static final String TAG = "RootCapture";
    // screencap 若因管道断开被 SIGPIPE 杀死（非 0 退出），循环即结束，避免残留进程
    private static final String CMD = "while screencap; do :; done 2>/dev/null";

    public interface ErrorListener { void onError(String msg); }

    private volatile boolean running = false;
    private Process proc;
    private Thread thread;
    private ErrorListener listener;

    public void setErrorListener(ErrorListener l) { listener = l; }

    public boolean isRunning() { return running; }

    public void start(final GlassRenderer renderer) {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(renderer); }
        }, "root-capture");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        Process p = proc;
        if (p != null) { try { p.destroyForcibly(); } catch (Exception ignored) { } }
        Thread t = thread;
        if (t != null) { t.interrupt(); }
        thread = null;
    }

    private void loop(GlassRenderer renderer) {
        while (running) {
            DataInputStream in = null;
            try {
                proc = new ProcessBuilder("su", "-c", CMD).start();
                in = new DataInputStream(new BufferedInputStream(proc.getInputStream(), 1 << 20));

                while (running) {
                    int w = readIntLE(in);
                    int h = readIntLE(in);
                    readIntLE(in);   // format（screencap 默认 RGBA_8888）
                    readIntLE(in);   // colorSpace
                    if (w <= 0 || h <= 0 || w > 8192 || h > 8192) {
                        throw new IOException("bad frame header " + w + "x" + h);
                    }
                    int need = w * h * 4;
                    byte[] buf = renderer.takeFrameBuffer(need);
                    readFully(in, buf, 0, need);   // screencap 已是自上而下，行序与纹理 v=0=顶部一致，无需翻转
                    renderer.submitFrame(buf, w, h);
                }
            } catch (Exception e) {
                if (!running) break;
                Log.w(TAG, "capture loop error: " + e);
                if (listener != null) listener.onError("root 采集失败：" + e.getMessage());
                sleep(1000);
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) { }
                Process p = proc;
                proc = null;
                if (p != null) { try { p.destroyForcibly(); } catch (Exception ignored) { } }
            }
        }
    }

    private static int readIntLE(DataInputStream in) throws IOException {
        int b0 = in.read(), b1 = in.read(), b2 = in.read(), b3 = in.read();
        if ((b0 | b1 | b2 | b3) < 0) throw new IOException("eof");
        return (b0 & 0xFF) | ((b1 & 0xFF) << 8) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 24);
    }

    private static void readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(b, off + read, len - read);
            if (n < 0) throw new IOException("eof");
            read += n;
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
