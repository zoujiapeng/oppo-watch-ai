package com.glslplayground.app;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * VoiceMap（github.com/zoujiapeng/voicemap）控制面。
 *
 * 设置页（settings.html 的「语音按键 · VoiceMap」分组）→ window.Watch.vm* →
 * 这里 → su → assets/vmctl.sh（运行期释放到私有目录）。
 *
 * 为什么不把逻辑写死在 Java 里：
 *  - LSPosed 的模块开关只存在于它自己的 sqlite 库（/data/adb/lspd/config/*.db），
 *    Java 进程没有 root，写不了；解释器 + 脚本这条路最稳，也最好在现场改。
 *  - 模块的所有命令本来就是明文广播（com.pj.voicemap.TRIGGER），shell 一行就够。
 *
 * 线程模型：所有 root 调用都在单线程池里排队，UI 线程只读 statusJson() 的缓存，
 * 所以按住说话（down/up 连发）也不会卡 WebView。
 */
public final class VoiceMapControl {

    private static final String TAG = "VoiceMapCtl";

    /** 触发 App（一个按钮 + 一个输入框）。 */
    public static final String PKG_APP = "com.pj.voicemap";
    /** LSPosed 模块（注入输入法进程，做隐身 + 触摸注入）。 */
    public static final String PKG_MOD = "com.pj.voicemap.ime";

    private static final String LSPD_ZIP_URL =
            "https://github.com/LSPosed/LSPosed/releases/download/v1.9.2/"
            + "LSPosed-v1.9.2-7024-zygisk-release.zip";

    private static VoiceMapControl sInst;

    public static synchronized VoiceMapControl get(Context c) {
        if (sInst == null) sInst = new VoiceMapControl(c.getApplicationContext());
        return sInst;
    }

    private final Context app;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile String status = "{}";
    private volatile String lastMsg = "";

    private VoiceMapControl(Context c) {
        this.app = c;
        refresh();
    }

    // ------------------------------------------------------------------ 对外

    /** 设置页每秒读一次这个缓存（不触发新进程，不卡 UI）。 */
    public String statusJson() {
        return status;
    }

    /** 上一次动作回给页面的一句话结果。 */
    public String lastMessage() {
        return lastMsg;
    }

    /** 重新自检（装了没 / LSPosed 在不在 / 模块启用没有 / 作用域 / 进程）。 */
    public void refresh() {
        io.execute(new Runnable() {
            @Override public void run() {
                status = buildStatus();
            }
        });
    }

    /** 从 assets 装两个 APK（-r -g）。 */
    public void install() {
        io.execute(new Runnable() {
            @Override public void run() {
                File a = asset("voicemap.apk");
                File m = asset("voicemap-ime.apk");
                if (a == null || m == null) {
                    lastMsg = "APK 释放失败（assets 里没有 voicemap.apk / voicemap-ime.apk）";
                    return;
                }
                String out = sh("install " + q(a.getAbsolutePath()) + " " + q(m.getAbsolutePath()));
                lastMsg = last(out);
                status = buildStatus();
            }
        });
    }

    public void uninstall() {
        io.execute(new Runnable() {
            @Override public void run() {
                lastMsg = last(sh("uninstall"));
                status = buildStatus();
            }
        });
    }

    /** 在 LSPosed 里启用模块并把作用域设成这台输入法（传空 = 用当前默认输入法）。 */
    public void enable(final String ime) {
        io.execute(new Runnable() {
            @Override public void run() {
                String out = sh("enable " + q(ime == null ? "" : ime.trim()));
                lastMsg = last(out);
                status = buildStatus();
            }
        });
    }

    public void disable() {
        io.execute(new Runnable() {
            @Override public void run() {
                lastMsg = last(sh("disable"));
                status = buildStatus();
            }
        });
    }

    /** 杀掉输入法进程：下次弹键盘时进程重启，LSPosed 才会把模块注进去。 */
    public void restartIme() {
        io.execute(new Runnable() {
            @Override public void run() {
                lastMsg = last(sh("restart"));
                status = buildStatus();
            }
        });
    }

    /** calibrate / dump / clearcal / stealthon / stealthoff —— 原样转给模块。 */
    public void cmd(final String c) {
        io.execute(new Runnable() {
            @Override public void run() {
                lastMsg = last(sh("cmd " + c));
                status = buildStatus();
            }
        });
    }

    /** 把本 App 设成"隐身 + 可触发"的目标（模块的 arm_pkg）。 */
    public void registerSelf() {
        io.execute(new Runnable() {
            @Override public void run() {
                lastMsg = last(sh("cmd register com.glslplayground.app"));
                status = buildStatus();
            }
        });
    }

    /** 按住说话：down 开始录音，up 结束出字。 */
    public void trigger(final String dir) {
        io.execute(new Runnable() {
            @Override public void run() {
                String out = last(sh("trigger " + ("up".equals(dir) ? "up" : "down")));
                if (out != null && out.length() > 0) lastMsg = out;
            }
        });
    }

    /** 打开 VoiceMap 自己的「一个按钮 + 一个输入框」界面。 */
    public void openApp() {
        try {
            Intent i = app.getPackageManager().getLaunchIntentForPackage(PKG_APP);
            if (i == null) {
                lastMsg = "没装 " + PKG_APP;
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
        } catch (Throwable t) {
            lastMsg = "启动失败：" + t;
        }
    }

    /** 最近一段 VoiceMap 日志（验收注入是否真的发生在输入法进程里）。 */
    public String log() {
        return sh("log");
    }

    /**
     * 装 LSPosed（这是模块能跑起来的前提）：把 zygisk 版 zip 丢给 magisk 装入，
     * 重启后生效。只在本机没有 /data/adb/lspd 时才有意义。
     */
    public void installLsposed() {
        io.execute(new Runnable() {
            @Override public void run() {
                File zip = new File(app.getFilesDir(), "lsposed-zygisk.zip");
                String err = download(LSPD_ZIP_URL, zip);
                if (err != null) {
                    lastMsg = "LSPosed 下载失败：" + err;
                    return;
                }
                String out = sh("lspd-install " + q(zip.getAbsolutePath()));
                lastMsg = last(out) + "（" + zip.length() + " 字节，需重启生效）";
                status = buildStatus();
            }
        });
    }

    // ------------------------------------------------------------- 内部实现

    private String buildStatus() {
        String raw = sh("status");
        Map<String, String> kv = new LinkedHashMap<String, String>();
        StringBuilder logTail = new StringBuilder();
        if (raw != null) {
            for (String line : raw.split("\n")) {
                String s = line.trim();
                if (s.length() == 0) continue;
                int e = s.indexOf('=');
                if (e <= 0) { logTail.append(s).append(' '); continue; }
                kv.put(s.substring(0, e), s.substring(e + 1));
            }
        }
        StringBuilder j = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> en : kv.entrySet()) {
            if (!first) j.append(',');
            first = false;
            j.append('"').append(esc(en.getKey())).append("\":\"").append(esc(en.getValue())).append('"');
        }
        j.append(",\"raw\":\"").append(esc(trim(logTail.toString(), 300))).append("\"}");
        return j.toString();
    }

    /** 释放 assets 里的脚本 / apk，返回私有目录里的文件（root 也读得到）。 */
    private File asset(String name) {
        try {
            File f = new File(app.getFilesDir(), name);
            InputStream in = app.getAssets().open(name);
            FileOutputStream out = new FileOutputStream(f);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            f.setReadable(true, false);
            f.setExecutable(true, false);
            return f;
        } catch (Throwable t) {
            Log.w(TAG, "asset(" + name + ") failed: " + t);
            return null;
        }
    }

    /** 下载到私有目录（不走 shell，免得依赖 wget/curl）。成功返回 null，失败返回原因。 */
    private String download(String url, File dst) {
        java.io.InputStream in = null;
        FileOutputStream out = null;
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "glslplayground/VoiceMap");
            int code = c.getResponseCode();
            if (code != 200) return "HTTP " + code;
            in = c.getInputStream();
            out = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return null;
        } catch (Throwable t) {
            return String.valueOf(t);
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
    }

    /** 跑 vmctl.sh 的一个子命令（脚本 + 依赖每次都重释放，升级 APK 后立刻生效）。 */
    private String sh(String args) {
        File s = asset("vmctl.sh");
        asset("vm_lspd.py");
        if (s == null) return "ERR 脚本释放失败";
        return shRaw("sh " + q(s.getAbsolutePath()) + " " + args);
    }

    /** 跑一条 root 命令，返回全部输出。 */
    private String shRaw(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            if (!p.waitFor(25, TimeUnit.SECONDS)) {
                try { p.destroyForcibly(); } catch (Throwable ignored) { }
                Log.w(TAG, "su timeout: " + cmd);
                return sb + "\nERR 超时（su 没响应？）";
            }
            String out = sb.toString();
            Log.i(TAG, "su[" + cmd + "] -> "
                    + (out.length() > 400 ? out.substring(0, 400) + "…" : out));
            if (out.trim().length() == 0) Log.w(TAG, "su[" + cmd + "] 返回空：root 被拒？");
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "sh(" + cmd + ") failed: " + t);
            return "ERR " + t;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
        }
    }

    private static String last(String out) {
        if (out == null) return "";
        String[] lines = out.trim().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String s = lines[i].trim();
            if (s.length() > 0) return s;
        }
        return "";
    }

    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': break;
                case '\t': b.append(' '); break;
                default:
                    if (c < 0x20) b.append(' ');
                    else b.append(c);
            }
        }
        return b.toString();
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
