package com.glslplayground.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 「添附件」用的取文件壳子（= DSH 前端输入框那个 `uV2eYG_add`）。
 *
 * 为什么必须有一个 Activity：悬浮球是 **Service**，系统文件选择器（SAF）只把结果交给
 * Activity 的 `onActivityResult`。所以这里是一个**看不见的透明 Activity**：
 *   onCreate → 拉起 `ACTION_OPEN_DOCUMENT`
 *   → 选完把内容复制到 App 自己的 cache 里（SAF 的 Uri 权限只在本次回调有效）
 *   → 回调 {@link FloatingBallService} → 自己 finish()。
 * 用户视角就是"点了附件，弹出系统文件选择器"，没有任何多余界面。
 */
public class BallAttachPickerActivity extends Activity {

    private static final String TAG = "BallAttach";
    private static final int REQ_PICK = 4101;

    interface Result {
        void onPicked(File file, String name);
    }

    private static Result pending;

    static void pick(Context c, Result r) {
        pending = r;
        Intent i = new Intent(c, BallAttachPickerActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            Log.w(TAG, "open document failed: " + t);
            finish();
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_PICK) { finish(); return; }
        if (result != RESULT_OK || data == null || data.getData() == null) {
            Log.i(TAG, "用户取消选文件");
            finish();
            return;
        }
        Uri uri = data.getData();
        String name = displayName(uri);
        try {
            File dir = new File(getCacheDir(), "attach");
            if (!dir.exists()) dir.mkdirs();
            String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            File out = new File(dir, System.currentTimeMillis() + "-" + safe);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(out)) {
                if (in == null) throw new Exception("打不开这个文件");
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
            Log.i(TAG, "picked " + name + " -> " + out.getAbsolutePath()
                    + " (" + out.length() + " 字节)");
            Result r = pending;
            pending = null;
            if (r != null) r.onPicked(out, name);
            // 选完必须回到状态 2，并且**框里能看到这个附件**（用户明确要求）
            FloatingBallService.onAttachmentPicked(out, name);
        } catch (Throwable t) {
            Log.w(TAG, "copy failed: " + t);
        }
        finish();
    }

    private String displayName(Uri uri) {
        String name = null;
        try (android.database.Cursor c =
                     getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Throwable ignored) { }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
            if (name == null) name = "file";
        }
        return name;
    }

    @Override protected void onDestroy() {
        pending = null;
        super.onDestroy();
    }
}
