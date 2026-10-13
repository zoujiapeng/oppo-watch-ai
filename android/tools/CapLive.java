import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.IBinder;
import android.view.SurfaceControl;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 常驻截屏采集器（以 root 身份运行）。
 *
 * 直接用 SurfaceControl.captureDisplay 连续抓取显示器，写到 stdout，协议与 `screencap`
 * 完全一致：每帧 [int32 w, int32 h, int32 format, int32 colorSpace] + w*h*4 字节 RGBA。
 * 因为进程只启动一次（省掉每次 screencap 的 ~100ms 动态链接开销），并可用更小分辨率，
 * 帧率/延迟显著优于反复 fork screencap。
 *
 * 该层同样尊重 SKIP_SCREENSHOT，所以被打上标记的悬浮窗不会出现在画面里。
 *
 * 用法： CLASSPATH=<jar> app_process /system/bin CapLive [width height]
 */
public class CapLive {
    public static void main(String[] args) throws Exception {
        int reqW = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int reqH = args.length > 1 ? Integer.parseInt(args[1]) : 0;

        Class<?> sc = SurfaceControl.class;
        IBinder token = (IBinder) sc.getMethod("getInternalDisplayToken").invoke(null);
        Class<?> dca = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs");
        Class<?> bld = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs$Builder");
        Object builder = bld.getConstructor(IBinder.class).newInstance(token);
        if (reqW > 0 && reqH > 0) {
            bld.getMethod("setSize", int.class, int.class).invoke(builder, reqW, reqH);
        }
        bld.getMethod("setCaptureSecureLayers", boolean.class).invoke(builder, false);
        Object cargs = bld.getMethod("build").invoke(builder);

        Method capture = sc.getMethod("captureDisplay", dca);
        Class<?> shbCls = capture.getReturnType();
        Method getHB = shbCls.getMethod("getHardwareBuffer");
        Method getCS = shbCls.getMethod("getColorSpace");

        OutputStream out = new FileOutputStream(FileDescriptor.out);
        ByteBuffer bb = null;

        while (true) {
            Object shb;
            try {
                shb = capture.invoke(null, cargs);
            } catch (Throwable t) {
                break;
            }
            HardwareBuffer hb = (HardwareBuffer) getHB.invoke(shb);
            int w = hb.getWidth();
            int h = hb.getHeight();
            ColorSpace cs = (ColorSpace) getCS.invoke(shb);
            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, cs);
            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
            hw.recycle();

            int need = w * h * 4;
            if (bb == null || bb.capacity() < need) {
                bb = ByteBuffer.allocate(need).order(ByteOrder.LITTLE_ENDIAN);
            }
            bb.clear();
            sw.copyPixelsToBuffer(bb);   // ARGB_8888 软件位图的字节序与 screencap 的 RGBA 一致
            sw.recycle();

            try {
                writeIntLE(out, w);
                writeIntLE(out, h);
                writeIntLE(out, 1);        // format = RGBA_8888
                writeIntLE(out, 0);        // colorSpace（调用方忽略）
                out.write(bb.array(), 0, need);
                out.flush();
            } catch (IOException e) {
                break;                     // 管道断开（App 退出）→ 结束
            }
        }
    }

    private static void writeIntLE(OutputStream o, int v) throws IOException {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
        o.write((v >>> 16) & 0xFF);
        o.write((v >>> 24) & 0xFF);
    }
}
