package com.pj.voicemap;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

/**
 * 一个按钮 + 一个输入框。
 *
 * 按住按钮 = 按住输入法空格键（长按空格触发输入法自己的语音输入）；
 * 输入法界面全程不可见（由 LSPosed 模块把该进程窗口 alpha 置 0），
 * 识别结果由输入法自己 commitText 到本输入框。
 *
 * 首次运行会自动让模块做一次「空格键位置校准」：模块在键盘上试按几个候选点，
 * 直到真的把输入法的录音拉起来，然后把命中点存盘 —— 换输入法不用改代码。
 */
public class MainActivity extends Activity {

    static final String IME_BROADCAST_TARGET = "com.sohu.inputmethod.sogouoem";
    static final String ACTION_TRIGGER = "com.pj.voicemap.TRIGGER";
    static final String ACTION_ACK = "com.pj.voicemap.ACK";

    /** "touch" = 在真实空格键上注入长按；"method" = 直接调搜狗内部语音入口 */
    static final String STRATEGY = "touch";

    final Handler ui = new Handler(Looper.getMainLooper());
    EditText input;
    Button talk;
    boolean holding;

    final BroadcastReceiver ack = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("msg");
            if (msg == null) {
                return;
            }
            if (msg.startsWith("calibrated")) {
                prefs().edit().putBoolean("cal", true).apply();
                input.setText("");
                toast("已校准：" + msg);
            } else if (msg.startsWith("calibrate FAILED")) {
                input.setText("");
                toast("校准失败，可继续正常使用或手动指定坐标");
            }
        }
    };

    SharedPreferences prefs() {
        return getSharedPreferences("app", MODE_PRIVATE);
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        input = findViewById(R.id.input);
        talk = findViewById(R.id.talk);

        input.requestFocus();
        input.setCursorVisible(true);

        talk.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        holding = true;
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        send("down");
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (holding) {
                            holding = false;
                            send("up");
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(ACTION_ACK);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(ack, f, 2 /* RECEIVER_EXPORTED */);
        } else {
            registerReceiver(ack, f);
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                showIme();
                if (!prefs().getBoolean("cal", false)) {
                    sendRaw("calibrate");
                }
            }
        }, 400);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(ack);
        } catch (Throwable ignore) {
        }
    }

    void showIme() {
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    void send(String cmd) {
        Intent i = new Intent(ACTION_TRIGGER);
        i.setPackage(IME_BROADCAST_TARGET);
        i.putExtra("cmd", cmd);
        i.putExtra("strategy", STRATEGY);
        sendBroadcast(i);
    }

    void sendRaw(String cmd) {
        Intent i = new Intent(ACTION_TRIGGER);
        i.setPackage(IME_BROADCAST_TARGET);
        i.putExtra("cmd", cmd);
        i.putExtra("pkg", getPackageName());
        sendBroadcast(i);
    }
}
