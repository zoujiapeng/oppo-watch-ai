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
}
