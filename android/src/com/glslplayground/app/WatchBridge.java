package com.glslplayground.app;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Vibrator;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

public class WatchBridge {

    private final MainActivity act;

    public WatchBridge(MainActivity activity) {
        this.act = activity;
    }

    private void ui(Runnable r) {
        act.runOnUiThread(r);
    }

    @JavascriptInterface
    public String getInfo() {
        int level = -1;
        boolean charging = false;
        try {
            BatteryManager bm = (BatteryManager) act.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }
            Intent b = act.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int s = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = s == BatteryManager.BATTERY_STATUS_CHARGING
                        || s == BatteryManager.BATTERY_STATUS_FULL;
            }
        } catch (Exception ignored) { }
        return "{\"model\":\"" + Build.MODEL + "\""
                + ",\"android\":" + Build.VERSION.SDK_INT
                + ",\"battery\":" + level
                + ",\"charging\":" + charging + "}";
    }

    @JavascriptInterface
    public void setBrightness(final float v) {
        ui(new Runnable() {
            public void run() {
                try {
                    Window w = act.getWindow();
                    WindowManager.LayoutParams lp = w.getAttributes();
                    lp.screenBrightness = Math.max(0.01f, Math.min(1f, v));
                    w.setAttributes(lp);
                } catch (Exception ignored) { }
            }
        });
    }

    @JavascriptInterface
    public void vibrate(final int ms) {
        try {
            Vibrator v = (Vibrator) act.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(ms);
        } catch (Exception ignored) { }
    }

    @JavascriptInterface
    public void toast(final String msg) {
        ui(new Runnable() {
            public void run() {
                Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @JavascriptInterface
    public void openUrl(final String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception ignored) { }
    }

    @JavascriptInterface
    public void launch(final String pkg) {
        try {
            Intent i = act.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
            }
        } catch (Exception ignored) { }
    }

    @JavascriptInterface
    public boolean isAdmin() {
        return act.isDeviceOwner();
    }

    @JavascriptInterface
    public boolean isKiosk() {
        return act.isKiosk();
    }

    @JavascriptInterface
    public void startKiosk() {
        ui(new Runnable() {
            public void run() {
                act.enableKiosk();
            }
        });
    }

    @JavascriptInterface
    public void stopKiosk() {
        ui(new Runnable() {
            public void run() {
                act.disableKiosk();
            }
        });
    }

    @JavascriptInterface
    public boolean getAutoKiosk() {
        return act.isAutoKiosk();
    }

    @JavascriptInterface
    public void setAutoKiosk(final boolean v) {
        ui(new Runnable() {
            public void run() {
                act.setAutoKiosk(v);
            }
        });
    }

    @JavascriptInterface
    public boolean getKeepScreenOn() {
        return act.isKeepScreenOn();
    }

    @JavascriptInterface
    public void setKeepScreenOn(final boolean v) {
        ui(new Runnable() {
            public void run() {
                act.setKeepScreenOn(v);
            }
        });
    }

    // ---------- 悬浮水晶球 ----------
    @JavascriptInterface
    public void startFloat() {
        ui(new Runnable() {
            public void run() { act.requestFloat(); }
        });
    }

    @JavascriptInterface
    public void stopFloat() {
        ui(new Runnable() {
            public void run() { act.stopFloat(); }
        });
    }

    @JavascriptInterface
    public boolean isFloating() {
        return act.isFloating();
    }

    @JavascriptInterface
    public void setFloatParams(final String json) {
        ui(new Runnable() {
            public void run() { act.applyFloatParams(json); }
        });
    }

    @JavascriptInterface
    public String getFloatParams() {
        return act.getFloatParams();
    }

    @JavascriptInterface
    public void setFloatMode(final int mode) {
        ui(new Runnable() {
            public void run() { act.setFloatMode(mode); }
        });
    }

    @JavascriptInterface
    public int getFloatMode() {
        return act.getFloatMode();
    }

    @JavascriptInterface
    public int getFloatCaptureSrc() {
        return act.getFloatCaptureSrc();
    }

    @JavascriptInterface
    public void setFloatCaptureSrc(final int src) {
        ui(new Runnable() {
            public void run() { act.setFloatCaptureSrc(src); }
        });
    }

    @JavascriptInterface
    public int getFloatFps() {
        return act.getFloatFps();
    }

    @JavascriptInterface
    public void setFloatFps(final int fps) {
        ui(new Runnable() {
            public void run() { act.setFloatFps(fps); }
        });
    }

    @JavascriptInterface
    public boolean getFloatLowLat() {
        return act.getFloatLowLat();
    }

    @JavascriptInterface
    public void setFloatLowLat(final boolean v) {
        ui(new Runnable() {
            public void run() { act.setFloatLowLat(v); }
        });
    }

    @JavascriptInterface
    public int getFloatCapScale() {
        return act.getFloatCapScale();
    }

    @JavascriptInterface
    public void setFloatCapScale(final int s) {
        ui(new Runnable() {
            public void run() { act.setFloatCapScale(s); }
        });
    }

    // ---------- Siri 模式 ----------
    @JavascriptInterface
    public boolean getSiriEnabled() {
        return act.getSiriEnabled();
    }

    @JavascriptInterface
    public void setSiriEnabled(final boolean v) {
        ui(new Runnable() {
            public void run() { act.setSiriEnabled(v); }
        });
    }

    @JavascriptInterface
    public void setSiriText(final String text) {
        ui(new Runnable() {
            public void run() { act.sendSiriText(text); }
        });
    }

    @JavascriptInterface
    public int getSiriTextDy() {
        return act.getSiriTextDy();
    }

    @JavascriptInterface
    public void setSiriTextDy(final int dy) {
        ui(new Runnable() {
            public void run() { act.setSiriTextDy(dy); }
        });
    }

    @JavascriptInterface
    public int getSiriTextSp() {
        return act.getSiriTextSp();
    }

    @JavascriptInterface
    public void setSiriTextSp(final int sp) {
        ui(new Runnable() {
            public void run() { act.setSiriTextSp(sp); }
        });
    }

    /** 球内富文本正文字号比例（百分数，25 = 0.25）。 */
    @JavascriptInterface
    public int getMdFontRatioPct() {
        return act.getMdFontRatioPct();
    }

    @JavascriptInterface
    public void setMdFontRatioPct(final int pct) {
        ui(new Runnable() {
            public void run() { act.setMdFontRatioPct(pct); }
        });
    }

    /** 审批面板整体缩放（百分数，55 = 0.55）。 */
    @JavascriptInterface
    public int getApScalePct() {
        return act.getApScalePct();
    }

    @JavascriptInterface
    public void setApScalePct(final int pct) {
        ui(new Runnable() {
            public void run() { act.setApScalePct(pct); }
        });
    }

    /** 离线预览球内排版 / 结束预览。 */
    @JavascriptInterface
    public void previewSiriText() {
        ui(new Runnable() {
            public void run() { act.previewSiriText(); }
        });
    }

    @JavascriptInterface
    public void previewSiriTextStop() {
        ui(new Runnable() {
            public void run() { act.previewSiriTextStop(); }
        });
    }

    // ── 开发者选项里的"覆盖层"开关（用户要求能在 App 里关掉）───────────────
    //   pointer_location：屏幕顶部那条坐标/速度/压力条 + 十字准线
    //   show_touches    ：触摸轨迹的彩色十字线（截图里那些绿/品红线就是它）
    // 这两个是系统设置（归属 package=android，开发者选项），不是本 App 画的。
    // 写它需要 WRITE_SETTINGS：有权限就直接写；没有就退回 root/shell 的 `settings` 命令。

    @JavascriptInterface
    public boolean hasOverlayWritePermission() {
        try {
            return android.provider.Settings.System.canWrite(act);
        } catch (Throwable t) {
            return false;
        }
    }

    @JavascriptInterface
    public void requestOverlayWritePermission() {
        ui(new Runnable() {
            public void run() {
                try {
                    android.content.Intent i = new android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    i.setData(android.net.Uri.parse("package:" + act.getPackageName()));
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(i);
                } catch (Throwable t) {
                    Toast.makeText(act, "打不开授权页，请手动到 设置→应用→特殊权限→修改系统设置",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** 读"调试覆盖层"是否开着（任一开着就算开）。 */
    @JavascriptInterface
    public boolean getDebugOverlay() {
        return sysGet("pointer_location") == 1 || sysGet("show_touches") == 1;
    }

    /** 一次性开关两个覆盖层。 */
    @JavascriptInterface
    public void setDebugOverlay(final boolean on) {
        ui(new Runnable() {
            public void run() {
                boolean ok1 = sysPut("pointer_location", on ? 1 : 0);
                boolean ok2 = sysPut("show_touches", on ? 1 : 0);
                if (!ok1 && !ok2) {
                    Toast.makeText(act, "写系统设置被拒绝：点下面的「授权」按钮给一次权限",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private int sysGet(String key) {
        try {
            return android.provider.Settings.System.getInt(act.getContentResolver(), key, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 先试正常写入，失败退回 root/shell 的 `settings put`（本机有 root，App 别处也这么用）。 */
    private boolean sysPut(String key, int value) {
        try {
            if (android.provider.Settings.System.canWrite(act)) {
                android.provider.Settings.System.putInt(act.getContentResolver(), key, value);
                if (sysGet(key) == value) return true;
            }
        } catch (Throwable ignored) { }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "settings put system " + key + " " + value});
            p.waitFor();
            return sysGet(key) == value;
        } catch (Throwable t) {
            return false;
        }
    }

    @JavascriptInterface
    public int getSiriBlackStep() {
        return act.getSiriBlackStep();
    }

    @JavascriptInterface
    public void setSiriBlackStep(final int v) {
        ui(new Runnable() {
            public void run() { act.setSiriBlackStep(v); }
        });
    }

    /** 思考中（流体圆点）的环半径：屏幕像素。 */
    @JavascriptInterface
    public int getOrbRadius() {
        return FloatingBallService.getOrbRadius();
    }

    @JavascriptInterface
    public void setOrbRadius(final int px) {
        ui(new Runnable() {
            public void run() { FloatingBallService.setOrbRadius(px); }
        });
    }

    /** 思考中（流体圆点）的亮度增益：百分之一。 */
    @JavascriptInterface
    public int getOrbGain() {
        return FloatingBallService.getOrbGain();
    }

    @JavascriptInterface
    public void setOrbGain(final int pct) {
        ui(new Runnable() {
            public void run() { FloatingBallService.setOrbGain(pct); }
        });
    }

    /** 思考中圆点带起的边缘辉光：百分之一。 */
    @JavascriptInterface
    public int getOrbRim() {
        return FloatingBallService.getOrbRim();
    }

    @JavascriptInterface
    public void setOrbRim(final int pct) {
        ui(new Runnable() {
            public void run() { FloatingBallService.setOrbRim(pct); }
        });
    }

    // ─────────── DSH 文本助手（悬浮球 ↔ 本机 DSH）───────────

    @JavascriptInterface
    public boolean getAssistantEnabled() {
        return SiriAssistant.get(act).enabled();
    }

    @JavascriptInterface
    public void setAssistantEnabled(final boolean v) {
        ui(new Runnable() {
            public void run() { SiriAssistant.get(act).setEnabled(v); }
        });
    }

    @JavascriptInterface
    public String getAssistantEndpoint() {
        return SiriAssistant.get(act).endpoint();
    }

    @JavascriptInterface
    public void setAssistantEndpoint(final String url) {
        ui(new Runnable() {
            public void run() { SiriAssistant.get(act).setEndpoint(url); }
        });
    }

    /** 「问一句」：直接把文本交给 DSH，回答出现在球的 Siri 模式里。 */
    @JavascriptInterface
    public void assistantAsk(final String text) {
        ui(new Runnable() {
            public void run() {
                FloatingBallService.ensureSiriForAssistant();
                SiriAssistant.get(act).ask(text);
            }
        });
    }

    /** 打开搜索胶囊（双指长按的等价入口；调版式 / 验收用，不必真做那个手势）。 */
    @JavascriptInterface
    public void openSearchBox() {
        FloatingBallService.openSearchBox("设置页");
    }

    @JavascriptInterface
    public void closeSearchBox() {
        FloatingBallService.closeSearchBox("设置页");
    }

    /** 搜索胶囊是不是开着（含进出场动画）。 */
    @JavascriptInterface
    public boolean isSearchBoxOpen() {
        return FloatingBallService.isSearchBoxOpen();
    }

    @JavascriptInterface
    public String getAssistantState() {
        return SiriAssistant.get(act).state().name();
    }

    /** 球内此刻的 Siri 文字（设置页据此确认 DSH 的回答已经写进球里）。 */
    @JavascriptInterface
    public String getBallSiriText() {
        return FloatingBallService.siriTextNow();
    }

    @JavascriptInterface
    public boolean isBallSiriOn() {
        return FloatingBallService.isSiriOn();
    }

    /** 会话镜像开关：DSH 里 AI 一有输出就往球里打字。 */
    @JavascriptInterface
    public boolean getMirrorEnabled() {
        return SiriAssistant.get(act).mirrorEnabled();
    }

    @JavascriptInterface
    public void setMirrorEnabled(final boolean v) {
        ui(new Runnable() {
            public void run() { SiriAssistant.get(act).setMirrorEnabled(v); }
        });
    }

    @JavascriptInterface
    public String getMirrorStatus() {
        return SiriAssistant.get(act).mirrorStatus();
    }

    // ─────────── 审批球（DSH 要你点批准/拒绝时弹的那颗新球）───────────

    @JavascriptInterface
    public boolean getApprovalOrb() {
        return SiriAssistant.get(act).approvalEnabled();
    }

    @JavascriptInterface
    public void setApprovalOrb(final boolean v) {
        ui(new Runnable() {
            public void run() { SiriAssistant.get(act).setApprovalEnabled(v); }
        });
    }

    @JavascriptInterface
    public String getApprovalStatus() {
        return SiriAssistant.get(act).approvalStatus();
    }

    /** 球上正挂着什么审批（空串 = 没有）。 */
    @JavascriptInterface
    public String getApprovalPending() {
        return FloatingBallService.approvalSummary();
    }

    /** 调试：叫一条假审批出来，对着调球的版式与点击（不需要真触发 DSH 审批）。 */
    @JavascriptInterface
    public void debugApproval(final String toolName, final String reason) {
        ui(new Runnable() {
            public void run() { FloatingBallService.debugApproval(toolName, reason); }
        });
    }

    /** 调试：叫一条「多选项 + 自由输入」的假提问，验收自适应版式。 */
    @JavascriptInterface
    public void debugQuestion() {
        ui(new Runnable() {
            public void run() { FloatingBallService.debugQuestion(); }
        });
    }

    /** 调试：把球上那条假审批撤掉。 */
    @JavascriptInterface
    public void dismissApproval() {
        FloatingBallService.dismissApprovalIfAny();
    }

    /** 把球体这一帧存成 PNG（调试用），返回文件路径。 */
    @JavascriptInterface
    public String captureBall() {
        return FloatingBallService.captureBallPng();
    }
}
