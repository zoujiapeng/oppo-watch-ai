package com.glslplayground.app;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 悬浮水晶球渲染器：移植 liquid-glass.html 的折射/黑顶/赤道亮线/Siri 波，
 * 抓屏纹理走 OES 外部纹理，球体外 alpha=0 → 悬浮窗透明，露出真实桌面。
 */
public class GlassRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "GlassRenderer";

    public interface OnSurfaceReady { void onReady(SurfaceTexture st); }

    private final int screenW, screenH, winW, winH;
    private OnSurfaceReady readyCb;

    // ── 球体参数（默认值与 liquid-glass.html 滑块一致）──
    public float master = 1.0f, posX = 0.5f, posY = 0.5f;
    public float halfX = 0.37f, halfY = 0.25f, corner = 1.0f;
    public float refraction = 200f, band = 170f, zoom = 1.0f, dome = 0.40f, aberration = 10f;
    public float blur = 1.8f;
    public float topDarken = 0.93f, black0 = -1.0f, black1 = -0.01f, edgeBleed = 0.11f;
    public float waveAmp = 1.90f, waveScale = 0.85f;
    public boolean waveOn = true;

    public volatile float winX, winY;

    // ── GL 资源 ──
    private int capProg, blurProg, compProg, maskProg;
    private int quadBuf;
    private int oesTex = 0;
    private SurfaceTexture surfaceTexture;
    private final float[] stMatrix = new float[16];
    private volatile boolean frameAvailable = false;

    private Fbo full, half, half2;
    private Fbo bg, bg2;     // 干净背景（ping-pong）：每帧用实时抓屏更新，但球体区域保留旧帧 → 无闪烁、无自采样
    private int blurSrcTex = 0;         // 供合成采样的背景纹理（清晰或模糊）
    private boolean bgInit = false;     // 背景是否已初始化
    public int bgMode = 0;              // 0=无缝抠图（不闪） 1=实时刷新（会闪）
    public long refreshMs = 300;        // 模式 1 的刷新间隔
    // 抓屏已排除本悬浮窗（SurfaceControl SKIP_SCREENSHOT）→ full 即干净实时桌面，
    // 直接作为折射源，球内实时更新、无闪烁、无自采样。
    public volatile boolean selfExcluded = false;
    private long lastSnapMs = 0;
    private int gotFrames = 0;

    // ── root 直采（不经 MediaProjection，直接把原始帧上传成 2D 纹理）──
    public volatile int captureSource = 0;    // 0=OES（投影/录屏） 1=root 原始帧
    private volatile boolean sawFrame = false;
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> framePool =
            new java.util.concurrent.ConcurrentLinkedQueue<byte[]>();
    private final java.util.concurrent.atomic.AtomicReference<byte[]> readyFrame =
            new java.util.concurrent.atomic.AtomicReference<byte[]>();

    /** root 采集线程取一个可写缓冲（不足则新建）。 */
    public byte[] takeFrameBuffer(int min) {
        byte[] b = framePool.poll();
        if (b == null || b.length < min) b = new byte[min];
        return b;
    }

    public void recycleFrameBuffer(byte[] b) { if (b != null) framePool.offer(b); }

    /** root 采集线程投递一帧（已转成 RGBA、行序翻转好）。 */
    public void submitFrame(byte[] b, int w, int h) {
        byte[] old = readyFrame.getAndSet(b);
        if (old != null) framePool.offer(old);
    }

    /** 切换背景模式并重置背景初始化（GL 线程调用）。 */
    public void setBgMode(int mode) { bgMode = mode; bgInit = false; gotFrames = 0; }

    private final long t0 = System.currentTimeMillis();

    private final FloatBuffer quad;
    private static final float[] TRI = { -1f, -1f, 3f, -1f, -1f, 3f };

    public GlassRenderer(int screenW, int screenH, int winW, int winH) {
        this.screenW = screenW; this.screenH = screenH;
        this.winW = winW; this.winH = winH;
        quad = ByteBuffer.allocateDirect(TRI.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(TRI).position(0);
    }

    public void setOnSurfaceReady(OnSurfaceReady cb) { readyCb = cb; }
    public SurfaceTexture surfaceTexture() { return surfaceTexture; }

    // ─────────────────────────── shaders ───────────────────────────
    private static final String VS =
        "attribute vec2 aPos; varying vec2 vUV;\n" +
        "void main(){ vUV = aPos*0.5+0.5; gl_Position = vec4(aPos,0.,1.); }\n";

    private static final String FS_CAP =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "uniform samplerExternalOES iCap;\n" +
        "uniform mat4 uST;\n" +
        "uniform vec2 iRes;\n" +
        "uniform float uFlipY;\n" +
        "varying vec2 vUV;\n" +
        "void main(){\n" +
        "  vec2 uv = gl_FragCoord.xy / iRes;\n" +
        "  vec2 st = (uST * vec4(uv, 0.0, 1.0)).xy;\n" +
        "  if(uFlipY > 0.5) st.y = 1.0 - st.y;\n" +
        "  gl_FragColor = vec4(texture2D(iCap, st).rgb, 1.0);\n" +
        "}\n";

    private static final String FS_BLUR =
        "precision highp float;\n" +
        "uniform vec2 iRes; uniform sampler2D iTex; uniform vec2 uDir; uniform float uBlur;\n" +
        "void main(){\n" +
        "  vec2 uv = gl_FragCoord.xy / iRes;\n" +
        "  if(uBlur < 0.05){ gl_FragColor = texture2D(iTex, uv); return; }\n" +
        "  vec2 stepv = uDir * uBlur / iRes;\n" +
        "  float w0=0.227027, w1=0.194595, w2=0.121622, w3=0.054054, w4=0.016216;\n" +
        "  vec4 acc = texture2D(iTex, uv) * w0;\n" +
        "  acc += texture2D(iTex, uv + stepv*1.0)*w1 + texture2D(iTex, uv - stepv*1.0)*w1;\n" +
        "  acc += texture2D(iTex, uv + stepv*2.0)*w2 + texture2D(iTex, uv - stepv*2.0)*w2;\n" +
        "  acc += texture2D(iTex, uv + stepv*3.0)*w3 + texture2D(iTex, uv - stepv*3.0)*w3;\n" +
        "  acc += texture2D(iTex, uv + stepv*4.0)*w4 + texture2D(iTex, uv - stepv*4.0)*w4;\n" +
        "  gl_FragColor = acc;\n" +
        "}\n";

    private static final String FS_COMP =
        "precision highp float;\n" +
        "uniform vec2 iRes;      // 窗口尺寸\n" +
        "uniform vec2 uScreen;   // 屏幕尺寸\n" +
        "uniform vec2 uWinPos;   // 窗口左上角(px, y向下)\n" +
        "uniform sampler2D iBg;  // 清晰抓屏\n" +
        "uniform sampler2D iBlur;// 模糊抓屏\n" +
        "uniform vec2 uHalf, uCenter;\n" +
        "uniform float uCorner, uRefraction, uBand, uDome, uAberration;\n" +
        "uniform float uTopDarken, uBlack0, uBlack1, uEdgeBleed;\n" +
        "uniform float iTime, uWaveAmp, uWaveScale, uZoom, uScale;\n" +
        "float sdRoundRect(vec2 c, vec2 h, float r){\n" +
        "  vec2 cc = abs(c) - (h - vec2(r));\n" +
        "  float outside = length(max(cc,0.0)) - r;\n" +
        "  float inside = min(max(cc.x,cc.y),0.0);\n" +
        "  return outside + inside;\n" +
        "}\n" +
        "vec2 gradSdRoundRect(vec2 c, vec2 h, float r){\n" +
        "  vec2 cc = abs(c) - (h - vec2(r));\n" +
        "  if(cc.x >= 0.0 || cc.y >= 0.0) return sign(c)*normalize(max(cc,0.0)+1e-6);\n" +
        "  float gx = step(cc.y, cc.x); return sign(c)*vec2(gx, 1.0-gx);\n" +
        "}\n" +
        "float circleMap(float x){ return 1.0 - sqrt(max(1.0-x*x,0.0)); }\n" +
        "const float PI = 3.14159265359;\n" +
        "vec3 spectral4(int s){ float x=float(s);\n" +
        "  return clamp(vec3(abs(x-3.0)-1.0, 2.0-abs(x-2.0), 2.0-abs(x-4.0)),0.0,1.0); }\n" +
        "vec3 waveCol(vec2 p, float t, float spread){\n" +
        "  float low =clamp(0.45+0.45*sin(t*0.8)*sin(t*0.37+1.0),0.0,1.0);\n" +
        "  float mid =clamp(0.40+0.40*sin(t*1.7+2.0)*sin(t*0.53),0.0,1.0);\n" +
        "  float high=clamp(0.30+0.30*sin(t*2.9+4.0)*sin(t*0.71+2.0),0.0,1.0);\n" +
        "  float drift=mod(t,20.0*PI)*2.4;\n" +
        "  float env=cos(PI*0.5*min(abs(0.9*p.x),1.0)); env*=env;\n" +
        "  float A1=0.30+0.01*low*6.0;\n" +
        "  float A2=A1+mid*0.05+high*0.06;\n" +
        "  float AB=2.6+mid*0.8+high*0.5;\n" +
        "  float th=0.03*spread, inten=0.01*(2.0+low*1.5), soft=0.01*max(0.0,2.5+mid*0.4)*spread;\n" +
        "  float yMain=A1*env*sin(p.x*1.1+drift);\n" +
        "  float bandAmt=1e-4*26000.0*inten;\n" +
        "  vec3 num=vec3(0.0),den=vec3(0.0);\n" +
        "  for(int s=0;s<4;s++){ vec3 hue=spectral4(s); den+=hue;\n" +
        "    float ab=mix(-AB,AB,float(s)/3.0);\n" +
        "    float yL=A2*env*sin(p.x+drift+ab);\n" +
        "    float d=abs(p.y-yL);\n" +
        "    float line=inten/(sqrt(d*d+soft*soft)+th);\n" +
        "    float lo=min(yMain,yL),hi=max(yMain,yL);\n" +
        "    float dB=max(0.0,max(p.y-hi,lo-p.y));\n" +
        "    float band=bandAmt/(dB+0.08);\n" +
        "    num+=hue*(line+band); }\n" +
        "  vec3 col=num/den;\n" +
        "  float dM=abs(p.y-yMain);\n" +
        "  col+=0.5*inten/(sqrt(dM*dM+soft*soft)+th);\n" +
        "  col=pow(max(col,0.0),vec3(1.5));\n" +
        "  return col*exp(-pow(p.x*1.2,2.0));\n" +
        "}\n" +
        "vec2 screenUv(vec2 fc){\n" +
        "  vec2 top = vec2(fc.x, iRes.y - fc.y);\n" +
        "  return (uWinPos + top) / uScreen;\n" +
        "}\n" +
        "void main(){\n" +
        "  vec2 fc = gl_FragCoord.xy;\n" +
        "  vec2 c = fc - uCenter; c.y = -c.y;\n" +
        "  float sd = sdRoundRect(c, uHalf, uCorner);\n" +
        "  if(sd > 1.0){ gl_FragColor = vec4(0.0); return; }\n" +
        "  sd = min(sd, 0.0);\n" +
        "  float refractionHeight = uBand;\n" +
        "  float x = 1.0 - (-sd / max(refractionHeight, 1.0));\n" +
        "  float d = circleMap(clamp(x, 0.0, 1.0)) * uRefraction;\n" +
        "  float gradRadius = min(uCorner*1.5, min(uHalf.x, uHalf.y));\n" +
        "  float depthEffect = uDome * 1.2;\n" +
        "  vec2 grad = normalize(gradSdRoundRect(c, uHalf, gradRadius) + depthEffect * normalize(c + 1e-5));\n" +
        "  vec2 gradUV = vec2(grad.x, -grad.y);\n" +
        "  vec2 suv = screenUv(fc) + gradUV * d / uScreen;\n" +
        "  vec2 cScreenTop = uWinPos + vec2(uCenter.x, iRes.y - uCenter.y);\n" +
        "  vec2 cuv = cScreenTop / uScreen;\n" +
        "  suv = cuv + (suv - cuv) / uZoom;\n" +
        "  float dispI = uAberration * ((c.x * c.y) / (uHalf.x * uHalf.y)) / 30.0;\n" +
        "  vec2 disp = gradUV * d * dispI / uScreen;\n" +
        "  vec3 col;\n" +
        "  col.r = texture2D(iBlur, suv + disp).r;\n" +
        "  col.g = texture2D(iBlur, suv).g;\n" +
        "  col.b = texture2D(iBlur, suv - disp).b;\n" +
        "  col = mix(col, col*vec3(0.94,0.96,1.0)+vec3(0.01,0.012,0.02), 0.22);\n" +
        "  float ny = -c.y / uHalf.y;\n" +
        "  float blackMask = smoothstep(uBlack0, uBlack1, ny);\n" +
        "  blackMask = blackMask*blackMask*(3.0-2.0*blackMask);\n" +
        "  col = mix(col, vec3(0.012,0.015,0.025), blackMask*uTopDarken);\n" +
        "  vec3 wglow = vec3(0.0);\n" +
        "  if(uWaveAmp > 0.001){\n" +
        "    vec2 cw = (c + vec2(grad.x, -grad.y) * d * 0.5) / uZoom;\n" +
        "    vec2 wp = vec2(cw.x/uHalf.x, -cw.y/uHalf.y - uBlack1) / max(uWaveScale, 0.1);\n" +
        "    float waveVis = 1.0 - blackMask*uTopDarken*0.85;\n" +
        "    wglow = waveCol(wp, iTime, 7.0) * vec3(0.80,0.95,1.25);\n" +
        "    float below = smoothstep(0.7, -0.5, wp.y);\n" +
        "    col += (waveCol(wp, iTime, 1.0) + wglow*(0.25 + 0.6*below)) * uWaveAmp * waveVis;\n" +
        "  }\n" +
        "  float innerEdge = smoothstep(0.,-18.*uScale,sd);\n" +
        "  col *= mix(0.70,1.,innerEdge);\n" +
        "  float mid=(uBlack0+uBlack1)*0.5;\n" +
        "  float eqLine = smoothstep(uBlack0,mid,ny)*smoothstep(uBlack1,mid,ny);\n" +
        "  float horiz = 1.0 - abs(c.x)/uHalf.x*0.3;\n" +
        "  col += vec3(0.95,0.97,1.0)*eqLine*horiz*uEdgeBleed;\n" +
        "  float bottomRim = smoothstep(-14.*uScale,-2.*uScale,sd) * smoothstep(0.0,-0.6,-ny);\n" +
        "  col += vec3(0.85,0.88,0.95) * bottomRim * uEdgeBleed * 0.5;\n" +
        "  col += wglow * uWaveAmp * (eqLine*horiz + bottomRim) * 0.9;\n" +
        "  float shell = smoothstep(3.0*uScale, 0.0, abs(sd + 2.5*uScale));\n" +
        "  float shellLit = 0.30 + 0.70*smoothstep(0.3, -0.9, ny);\n" +
        "  col += (vec3(0.10,0.11,0.13) + wglow*uWaveAmp*1.2) * shell * shellLit;\n" +
        "  float aa = smoothstep(1.5, -1.5, sd);\n" +
        "  gl_FragColor = vec4(col * aa, aa);\n" +   // 预乘 alpha，窗外透明
        "}\n";

    // 抠图更新：用实时抓屏刷新背景，但球体范围内保留上一帧（干净）内容 → 球永远不会被自己采样
    private static final String FS_MASK =
        "precision highp float;\n" +
        "uniform vec2 iRes;        // 屏幕尺寸\n" +
        "uniform sampler2D iFrame; // 本帧实时抓屏\n" +
        "uniform sampler2D iBg;    // 上一帧干净背景\n" +
        "uniform vec2 uCenter;     // 球心（屏幕像素，y 向下）\n" +
        "uniform vec2 uHalf; uniform float uCorner; uniform float uMargin;\n" +
        "float sdRoundRect(vec2 c, vec2 h, float r){\n" +
        "  vec2 cc = abs(c) - (h - vec2(r));\n" +
        "  float outside = length(max(cc,0.0)) - r;\n" +
        "  return outside + min(max(cc.x,cc.y),0.0);\n" +
        "}\n" +
        "void main(){\n" +
        "  vec2 uv = gl_FragCoord.xy / iRes;\n" +
        "  float sd = sdRoundRect(gl_FragCoord.xy - uCenter, uHalf, uCorner);\n" +
        "  vec3 live = texture2D(iFrame, uv).rgb;\n" +
        "  vec3 old  = texture2D(iBg, uv).rgb;\n" +
        "  gl_FragColor = vec4(sd < uMargin ? old : live, 1.0);\n" +
        "}\n";

    // ─────────────────────────── GL 生命周期 ───────────────────────────
    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(0f, 0f, 0f, 0f);

        capProg = build(VS, FS_CAP);
        blurProg = build(VS, FS_BLUR);
        compProg = build(VS, FS_COMP);
        maskProg = build(VS, FS_MASK);

        int[] b = new int[1];
        GLES20.glGenBuffers(1, b, 0);
        quadBuf = b[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuf);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, TRI.length * 4, quad, GLES20.GL_STATIC_DRAW);

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        oesTex = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        surfaceTexture = new SurfaceTexture(oesTex);
        surfaceTexture.setDefaultBufferSize(screenW, screenH);
        surfaceTexture.setOnFrameAvailableListener(st -> frameAvailable = true,
                new Handler(Looper.getMainLooper()));
        surfaceTexture.getTransformMatrix(stMatrix);

        if (readyCb != null) readyCb.onReady(surfaceTexture);
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        full = null; half = null; half2 = null;
        bg = null; bg2 = null; bgInit = false; lastSnapMs = 0; gotFrames = 0;
    }

    @Override public void onDrawFrame(GL10 gl) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, winW, winH);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (oesTex == 0) return;

        if (frameAvailable) {
            try {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(stMatrix);
            } catch (Exception ignored) { }
            frameAvailable = false;
        }

        ensureFbos();

        // 球体几何（抠图与合成共用）
        float base = Math.min(winW, winH * 0.9f);
        float hx = base * halfX * master, hy = base * halfY * master;
        float cx = winW * posX, cy = winH * (1.0f - posY);
        float cr = base * halfY * corner * master;

        if (captureSource == 1) {
            // root 直采：把原始帧上传进 full（行序已翻转），不经 MediaProjection
            uploadRootFrame();
            if (!sawFrame) return;
        } else {
            // Pass 1: 抓屏 OES → 全分辨率实时帧
            useProgram(capProg);
            bindFullscreen(capProg);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, full.fbo);
            GLES20.glViewport(0, 0, full.w, full.h);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(capProg, "iCap"), 0);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(capProg, "uST"), 1, false, stMatrix, 0);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(capProg, "iRes"), full.w, full.h);
            // 抓屏 FBO 与合成采样坐标 Y 方向相反，翻转一次，否则球内背景上下颠倒
            GLES20.glUniform1f(GLES20.glGetUniformLocation(capProg, "uFlipY"), 1f);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
        }

        int bgTex;
        if (selfExcluded) {
            // 抓屏已排除本悬浮窗 → full 就是干净的实时桌面，直接作为折射源（球内实时、无闪、无自采样）
            bgTex = full.tex;
        } else if (bgMode == 1) {
            // 模式二：实时刷新 —— 隔 refreshMs 让球隐几帧去录干净桌面（会闪一下）
            boolean needRefresh = !bgInit
                    || (refreshMs > 0 && System.currentTimeMillis() - lastSnapMs >= refreshMs);
            if (needRefresh) {
                gotFrames++;
                if (gotFrames < 3) return;      // 全透明等干净帧
                useProgram(blurProg);
                bindFullscreen(blurProg);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(blurProg, "iTex"), 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                blurPass(full.tex, bg, 0f, 0f, 0f);
                bgInit = true;
                lastSnapMs = System.currentTimeMillis();
                gotFrames = 0;
            }
            bgTex = bg.tex;
        } else if (!bgInit) {
            // 模式一（默认）：首帧窗口全透明 → full 就是干净桌面，直接作为初始背景
            useProgram(blurProg);
            bindFullscreen(blurProg);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(blurProg, "iTex"), 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            blurPass(full.tex, bg, 0f, 0f, 0f);
            bgInit = true;
            bgTex = bg.tex;
        } else {
            // 模式一：抠图 —— bg2 = 实时帧，但球体范围内用旧背景填 → 背景无球、可实时更新、且不闪烁
            useProgram(maskProg);
            bindFullscreen(maskProg);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bg2.fbo);
            GLES20.glViewport(0, 0, bg2.w, bg2.h);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, full.tex);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(maskProg, "iFrame"), 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bg.tex);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(maskProg, "iBg"), 1);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(maskProg, "iRes"), bg2.w, bg2.h);
            // 球心换算到屏幕像素（y 向下）：窗口左上 + 窗口内 y 翻转
            GLES20.glUniform2f(GLES20.glGetUniformLocation(maskProg, "uCenter"),
                    winX + cx, winY + (winH - cy));
            GLES20.glUniform2f(GLES20.glGetUniformLocation(maskProg, "uHalf"), hx, hy);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(maskProg, "uCorner"), cr);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(maskProg, "uMargin"), 3f);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
            Fbo tmp = bg; bg = bg2; bg2 = tmp;   // ping-pong
            bgTex = bg.tex;
        }

        // Pass 2: 背景模糊（背景每帧在变，需每帧重算）
        buildBlur(bgTex);

        // Pass 3: 折射 + 合成 → 窗口
        useProgram(compProg);
        bindFullscreen(compProg);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, winW, winH);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurSrcTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(compProg, "iBg"), 0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(compProg, "iBlur"), 1);

        GLES20.glUniform2f(GLES20.glGetUniformLocation(compProg, "iRes"), winW, winH);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(compProg, "uScreen"), screenW, screenH);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(compProg, "uWinPos"), winX, winY);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(compProg, "uHalf"), hx, hy);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(compProg, "uCenter"), cx, cy);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uCorner"), cr);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uRefraction"), refraction * master);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uBand"), band * master);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uScale"), master);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uDome"), dome);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uAberration"), aberration);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uTopDarken"), topDarken);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uBlack0"), black0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uBlack1"), black1);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uEdgeBleed"), edgeBleed);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "iTime"), (System.currentTimeMillis() - t0) / 1000f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uWaveAmp"), waveOn ? waveAmp : 0f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uWaveScale"), waveScale);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uZoom"), zoom);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    // root 直采帧 → GL 2D 纹理
    private void uploadRootFrame() {
        byte[] f = readyFrame.getAndSet(null);
        if (f == null) return;
        int need = full.w * full.h * 4;
        if (f.length >= need) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, full.tex);
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, full.w, full.h,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(f));
            sawFrame = true;
        }
        framePool.offer(f);
    }

    // ─────────────────────────── helpers ───────────────────────────
    private void ensureFbos() {
        if (full == null) {
            full = new Fbo(screenW, screenH);
            half = new Fbo(Math.max(1, screenW >> 1), Math.max(1, screenH >> 1));
            half2 = new Fbo(Math.max(1, screenW >> 1), Math.max(1, screenH >> 1));
            bg = new Fbo(screenW, screenH);
            bg2 = new Fbo(screenW, screenH);
        }
    }

    private void buildBlur(int srcTex) {
        useProgram(blurProg);
        bindFullscreen(blurProg);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blurProg, "iTex"), 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        if (blur < 0.5f) {
            // 不模糊：直接用全分辨率背景，和网页版（fboB 全分辨率）一致
            blurSrcTex = srcTex;
        } else {
            // 半分辨率高斯；per0 按半分辨率换算，模糊力度不变
            blurPass(srcTex, half, 0f, 0f, 0f);
            float per0 = blur / 2f;
            int n = Math.max(1, (int) Math.ceil(per0 / 2.5f));
            float per = per0 / (float) Math.sqrt(n);
            for (int i = 0; i < n; i++) {
                blurPass(half.tex, half2, 1f, 0f, per);
                blurPass(half2.tex, half, 0f, 1f, per);
            }
            blurSrcTex = half.tex;
        }
    }

    private void blurPass(int srcTex, Fbo dst, float dx, float dy, float b) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dst.fbo);
        GLES20.glViewport(0, 0, dst.w, dst.h);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(blurProg, "iRes"), dst.w, dst.h);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(blurProg, "uDir"), dx, dy);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(blurProg, "uBlur"), b);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
    }

    private void useProgram(int p) { GLES20.glUseProgram(p); }

    private void bindFullscreen(int prog) {
        int a = GLES20.glGetAttribLocation(prog, "aPos");
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuf);
        GLES20.glEnableVertexAttribArray(a);
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, 0);
    }

    private static int build(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "link: " + GLES20.glGetProgramInfoLog(p));
        }
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "compile: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static class Fbo {
        int tex, fbo, w, h;
        Fbo(int w, int h) {
            this.w = w; this.h = h;
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            tex = t[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            int[] f = new int[1];
            GLES20.glGenFramebuffers(1, f, 0);
            fbo = f[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, tex, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }
    }
}
