package com.glslplayground.app;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
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

    private final int screenW, screenH;
    private int winW, winH;
    public volatile int capW = 0, capH = 0;   // 实际抓屏分辨率（低延迟模式可小于屏幕；采样用归一化 UV，与分辨率无关）
    public volatile int refSize = 0;          // 参考尺寸（原悬浮窗边长）：几何缩放/位置基准，与窗口实际高度解耦

    // ── Siri 形变模式：由服务端驱动球体几何（弹出/随文字变宽变高/圆角变小）──
    public volatile boolean siriGeom = false;
    public volatile float svMaster = 1f, svHalfX = 0.37f, svHalfY = 0.25f, svCorner = 1f;
    /**
     * 常态球（非 Siri 形态）的相对缩放：球宽固定 = 屏宽 × 1/3。
     * 由服务侧 {@code ballIdleScale()} 算好塞进来（和触摸代理窗口用的是同一个系数）。
     * 默认 1 = 原版大小；不设的话冷启动头几百毫秒会画出一颗原版大球。
     */
    public volatile float idleScale = 1f;
    public volatile float svPosX = 0.5f, svPosY = 0.5f;
    public volatile float svBlackOffset = 0f;   // 黑区整体下移量（Siri 行数越多越负）
    /**
     * 搜索胶囊（双指长按唤起）：0 = 普通球；1 = 整块形状「上黑下透」的深色玻璃。
     *
     * 形状本身不在这里，它还是走 svHalfX/svHalfY/svCorner —— 因为「圆 / 胶囊 / 圆角矩形」
     * 在 sdRoundRect 里本来就是**同一个原语**（metaball-search.html 的注释也是这么写的），
     * 所以「小黑球 → 黑团 → 搜索胶囊」只是这三个数在弹，不需要第二套几何。
     * 这里只管材质：顶上纯黑（用户要的"先是一团黑"）→ 往下慢慢透出折射。
     */


    // ── 搜索层（metaball-search.html 原样移植的那一层）────────────────────────
    // 用户要求：「长按就隐藏球，然后出现小黑球，和 Metaball→Search 里从顶部拿到的黑球一模一样，
    // 复用那个页面的动画和效果，而不是球的变体」。所以这一层**不是**球的形变：
    // 它跑的是演示页自己那段 FS（SearchLayerShader），球那一趟渲染整趟让路。
    public volatile boolean searchMode = false;
    public volatile float sBlobCx = 0f, sBlobCy = 0f, sBlobHx = 1f, sBlobHy = 1f;  // 屏幕像素（y 向下）
    public volatile float sK = 64f, sHeight = 18f, sRefract = 14f;   // 顶部黑边已按用户要求删掉（见 SearchLayerShader ★5）
    public volatile float sHlAmt = 1.5f, sAb = 0.12f, sDark = 1f, sCont = 0f, sValid = 0f;
    public volatile float sDensity = 2.75f;   // 物理像素 / CSS px（演示页的 uDpr）
    private int searchProg = 0;

    // ── 状态 3 / 玻璃菜单层（ZoneLayerShader）────────────────────────────────
    // 和搜索层同一条渲染套路：整屏窗口、抓屏当折射源、材质公式一模一样，
    // 只是形状从"单个 blob"变成"最多 5 个圆角形状里取最近的那个"。
    // 服务侧每帧组装好数组再整体赋值（copy-on-write），避免 GL 线程读到半截数据。
    public volatile boolean zoneMode = false;
    /** 5 × vec4(cx, cy, halfX, halfY)，屏幕像素、y 向下。 */
    public volatile float[] zShape = new float[24];   // 6 个形状 × (x,y,hx,hy)
    /** 5 × vec4(act, dark, cornerRadius, enabled)。 */
    public volatile float[] zMeta = new float[24];
    // 材质（2026-10-23 改成球那套）：折射强度 / 折射带宽（占短半轴比例）/ 球面感 /
    // 色散 / 烟熏 / 黑顶（cap0 最黑处、cap1 完全透处，ny 单位）/ 赤道线亮度
    public volatile float zCont = 0.55f;        // 保留字段（旧参数，已不参与绘制）
    public volatile float zRefract = 64f;       // 屏幕像素
    public volatile float zBandRatio = 1.0f;    // band = min(halfX,halfY) × 该值
    public volatile float zDome = 0.55f;
    public volatile float zAb = 0.16f;
    public volatile float zTopDarken = 0.92f;
    public volatile float zCap0 = -1.0f;
    public volatile float zCap1 = 0.35f;
    public volatile float zEdgeBleed = 0.12f;
    public volatile float zScrim = 0.10f;
    public volatile float zGlow = 0.5f;
    /** 磨砂占比：0 = 纯透镜（和球一样清晰）、1 = 全糊（毛玻璃）。面板给 0.8，圆区 0.55。 */
    public volatile float zFrost = 0.8f;
    /** 黑顶衰减指数：越大越"只在最顶上黑"（面板 1.6 / 圆区 1.4）。 */
    public volatile float zCapPow = 1.6f;
    /** 贴边压暗强度：0 = 完全不要那圈"薄黑边"（面板默认 0），圆区 0.65。 */
    public volatile float zEdgeShade = 0f;
    /** 上缘内阴影（玻璃"厚度"的主要来源，只压朝上的那一条边，不是整块渐变）。 */
    public volatile float zEdgeShade2 = 1f;
    /** 背景去饱和：面板 0.45（磨砂玻璃的"洗色"），圆区 0.15。 */
    public volatile float zDesat = 0.45f;
    /** 水珠的缩小倍率（越大越"缩小镜"）。4 颗水珠都读这一个值。 */
    public volatile float zDropZoom = 1.70f;
    /** 外阴影强度：面板 0.25（很淡），圆区 0.5。 */
    public volatile float zShadow = 0.25f;
    public volatile float zHeight = 18f;        // 保留字段（旧参数，已不参与绘制）
    public volatile float zDensity = 2.75f;
    /** 玻璃面板的磨砂力度（只作用于 zoneMode；球仍然用 {@link #blur}）。 */
    public volatile float zoneBlur = 9f;   // 面板是"一片玻璃"：要真的糊（球的 0.3 只是不糊）
    private int zoneProg = 0;

    private OnSurfaceReady readyCb;

    // ── 球体参数（默认值与 liquid-glass.html 滑块一致）──
    public float master = 1.0f, posX = 0.5f, posY = 0.5f;   // 球体整体尺寸（设置页「球体大小」滑块，1.0 = 原始大小）
    public float halfX = 0.37f, halfY = 0.25f, corner = 1.0f;
    public float refraction = 200f, band = 170f, zoom = 1.0f, dome = 0.40f, aberration = 10f;
    // 「玻璃体的失焦」= 唯一一个纯口味滑杆：数值越大越磨砂，越小越像一枚清晰透镜。
    // 0.3 = 用户 2026-10-16 看着实物定的（1.8 → 1.2 → 0.5 → 0.3 一路往下）。
    // ⚠️ 0.3 意味着**完全不做模糊**：buildBlur() 里 `blur < 0.5` 直接跳过高斯、
    // 用全分辨率原图当折射源 —— 球内就是"清晰 + 被折射放大/拖弯 + 边缘色散"的画面，
    // 玻璃感全靠折射位移 / 色散 / 黑顶 / 边缘亮线。（这个值正是这条管线最早的老默认。）
    // 想糊一点就往 0.8 / 1.2 / 1.8 加。
    // 设备上的 float_params.blur 会覆盖这里的默认值（演示页滑杆推的就是它，
    // 那个页面默认还是 1.8：打开演示页时别顺手推参数，会把球改回磨砂）。
    public float blur = 0.3f;
    public float topDarken = 0.93f, black0 = -1.0f, black1 = -0.01f, edgeBleed = 0.11f;
    /**
     * 「波进玻璃」模式：1 = 把 Siri 波先画进背景纹理，再走折射/色散/模糊 ——
     * 观感就是"玻璃后面的一道光"，跟背景完全同一套光学（用户 2026-10-16 要的方形态效果）；
     * 0 = 老样子：波直接叠在玻璃上（圆球态/思考时的观感，别动）。
     */
    public volatile float waveInGlass = 0f;
    /**
     * Siri 波头在玻璃底边往上的**可见高度**（像素）。玻璃多高、波幅多大，都只露这一截。
     *
     * 旧的 `waveDropPx`（「沉底偏移」）已经没有意义：波心藏多深不再由外部设，
     * 着色器按「波峰包络」自己算（见 FS_COMP 里 uWaveInGlass 那一支），
     * 这里只留「露多少」一个常量，由服务侧按 dp 写进来。
     */
    public volatile float waveHeadPx = 78f;
    public float waveAmp = 1.90f, waveScale = 0.85f;
    public boolean waveOn = true;
    public int waveMode = 1;   // 0=关(由 waveOn 决定) 1=siriWaveCore 2=siriFluidDotsCore
    /**
     * 波形整体幅度闸门（0..1），由服务侧的状态包络驱动。
     * 一个 uniform 同时决定波形与流体圆点：直接切 waveMode 会「瞬间换脸」——实测观感就是
     * 「处理中 ↔ Siri 波」之间硬跳。所以换形态时把 gate 快速压到 0 → 换模式 → 再抬回 1，
     * 中间那一瞬没有任何波/点在画，切换就看不出来了。
     */
    public volatile float waveGate = 1f;
    /** 思考中胶囊加载模式：把 GL 里的 Siri 波 / 圆点 / 流体全部让位给 WebView 叠层 */
    public volatile boolean suppressWave = false;
    /** 球在窗口里的几何（窗口像素，y 向下），给叠层对齐用 */
    public volatile float ballCxW, ballCyW, ballHxW, ballHyW;

    // ── 思考中（流体圆点）的成形参数：环半径按屏幕像素给，圆点在任何球里都是等大的圆 ──
    public volatile float orbRadius = 90f;    // 圆点环半径（屏幕像素）
    public volatile float orbOffset = -0.03f; // 环心 ny 偏移（负 = 更靠球底，避开文字）
    /** orbOffset 的"用户设定值"（有字时会被临时改成"底带位置"，恢复时用它）。 */
    public float orbOffsetDefault = -0.03f;
    public volatile float orbGain = 1.1f;     // 亮度增益（跟 Siri 波对齐）

    // ── 思考中第二种光效：连续发光流体（Fluid Glow）──────────────────────────
    // 用户要求：「和 dots 一样的光核，但是一团连续的、在水里收缩扩散的光」，
    // 而且**颜色可调（纯白 / 单色 / 混色）**、**亮度不要均匀（有的地方刺眼、有的地方柔和）**、
    // 背景跟 dots 一样只有纯黑、不要浮尘/水雾。
    // 这里只放开关和参数；形状/光核在 FS_COMP 的 fluidScenePx() 里（和 orbScenePx 并排）。
    /** true = 思考中画流体发光；false = 还是那圈流体圆点。由服务按设置推过来。 */
    public volatile boolean fluidOrb = false;
    /** 纬线数量：越多越密（球小的时候 8~12 最清楚）。 */
    public volatile float fluidRibs = 12f;
    /** 这团光的半径相对 uOrbRadius 的倍数。 */
    public volatile float fluidSize = 1.0f;
    /** 刺眼程度：0 = 整团都比较柔和，1 = 少数地方烧到纯白。 */
    public volatile float fluidHot = 1.0f;
    /** 0 = 纯白，1 = 单色（fluidColor），2 = 混色（按丝线走光谱）。 */
    public volatile int fluidColorMode = 0;
    public volatile float fluidColorR = 0.55f, fluidColorG = 0.92f, fluidColorB = 1.0f;
    /** 总亮度倍数（和圆点的 uOrbGain 分开，流体自己一套）。 */
    public volatile float fluidGain = 1.2f;
    /** 断线密度：0 = 连续的一整根；>0 = 沿丝线切成一段一段（单位≈每弧度的段数）。 */
    public volatile float fluidDash = 0f;
    public volatile float orbRim = 1.3f;      // 边缘辉光 = 圆点自身亮度 × 这个系数（跟波对齐，别把球边点亮）
    /** 波↔圆点的交叉淡化进度：0=纯 Siri 波，1=纯流体圆点（见 FloatingBallService.setAssistantState）。 */
    public volatile float orbMix = 0f;

    /** 审批/提问面板模式：1 时黑区底部带强调色渐变与光晕（0 = 普通球，纯黑区）。 */
    public volatile float panelMode = 0f;
    /** 保留字段：面板现在是纯黑（不再用颜色），留在这里避免别处改动，值不参与绘制。 */
    public volatile float panelAccentR = 1f, panelAccentG = 0.30f, panelAccentB = 0.24f;

    public volatile float winX, winY;
    /**
     * 窗口的**真实像素尺寸**（params.width/height）。为什么需要它：
     * 方形态面积最大到 2.2M 像素，全分辨率画下来每帧 45~55ms（实测）→ 肉眼就是"卡顿/一闪一闪"。
     * 于是大方框改用 `SurfaceView.setFixedSize()` 把**绘制缓冲**降分辨率（SurfaceFlinger 会把它
     * 放大回窗口大小）。折射源本来就是半屏分辨率（540×1206），所以玻璃看着几乎没差别，
     * 但片元数能砍掉一大半。这里存窗口尺寸，用来算"缓冲像素 ÷ 窗口像素"的缩放系数。
     */
    public volatile int winPixelW = 0, winPixelH = 0;

    /**
     * 取证快门：**暂停吃新帧**，让玻璃停在上一帧的样子。
     *
     * 为什么需要：玻璃层平时带着 SKIP_SCREENSHOT（抓屏排除），普通截图/录屏里它是隐身的。
     * 要"用音量键那种普通截图把它也拍进去"，只能临时撤掉那个标记 —— 可一旦撤掉，
     * **我们自己的抓屏**（CapLive / screencap）也会把球拍进去，球就会折射自己，
     * 屏幕上立刻变成另一副样子（实测：整体发暗、折射细节全糊）。这样拍下来的"证据"
     * 是假象，不是用户看到的东西。
     *
     * 所以快门期间冻结采集：这一小段（≈250ms）玻璃的输入不变 → 渲染结果不变 →
     * 系统截图拍到的就是**眼睛此刻看到的**那一帧。
     */
    public volatile boolean freezeCapture = false;

    // ── GL 资源 ──
    private int capProg, blurProg, compProg, maskProg;
    // 思考中胶囊加载：点精灵直接画进「背景内容」纹理，玻璃的折射/黑区/边缘一起处理
    private int loaderProg, loaderBuf;
    private Fbo loaderFbo;
    private int loaderBlackTex = 0;
    public int loaderMode = 0;
    public float loaderSpeed = 1f;
    public float loaderScale = 1f;
    public float loaderDot = 1f;
    /** 动画时钟倍率（每个动画自己的「速度」滑杆，由服务按状态槽推过来）。 */
    public volatile float animSpeed = 1f;
    /** 胶囊加载点阵的竖直偏移（窗口像素，正值向下）：球里有字时沉到球底动效带。 */
    public volatile float loaderDyPx = 0f;
    /**
     * 有字时给胶囊加载留的「底部动效带」高度（窗口像素，0 = 整颗球）。
     *
     * ★ 2026-11-08 用户：「回复时动画是在聊天页面的方形玻璃里的」——
     *   以前胶囊加载永远按**整颗球**布点，方形态一出现，点阵就铺满整块玻璃、压在字上，
     *   看起来既不像设置里选的动画、字也没法读。现在服务把文字区底部那条带子
     *   （siriAnimBand）报给渲染器，点阵只在那条带子里生成、按带子的中心摆。
     */
    public volatile float loaderBandPx = 0f;
    // ── 搜索胶囊 / 六区这类「界面自绘」的层 ────────────────────────────────
    // 这两层各自用自己的玻璃着色器画整块界面，球那一趟合成根本不会跑，
    // 所以胶囊加载动画改走**屏幕空间叠加**：同一套点阵，直接点在窗口画布上。
    public volatile boolean overlayLoader = false;
    public volatile float ovlCx = 0f, ovlCy = 0f, ovlHx = 0f, ovlHy = 0f;   // 窗口像素
    private final CapsuleLoaderEngine loaderEngine = new CapsuleLoaderEngine();
    private final float[] loaderVerts = new float[512 * 4];
    private java.nio.FloatBuffer loaderVb;
    private long loaderLastNs = 0L;
    private static final String LOADER_VS =
        "attribute vec2 aPos; attribute float aSize; attribute float aAlpha;\n" +
        "uniform vec2 uRes; varying float vA;\n" +
        "void main(){ vA=aAlpha; gl_PointSize=aSize;\n" +
        "  gl_Position=vec4(aPos.x/uRes.x*2.0-1.0, 1.0-aPos.y/uRes.y*2.0, 0.0, 1.0); }";
    private static final String LOADER_FS =
        "precision mediump float; varying float vA;\n" +
        "void main(){ vec2 p=gl_PointCoord*2.0-1.0; float d=length(p); if(d>1.0) discard;\n" +
        "  float core=smoothstep(0.24,0.0,d); float halo=pow(max(1.0-d,0.0),2.4);\n" +
        "  float a=(core*0.95+halo*0.55)*vA; gl_FragColor=vec4(a,a,a,a); }";
    private int quadBuf;
    private int oesTex = 0;
    /** 每次 surface 重建后打一行"第一帧画了多大"，用来验冷启动那颗球是不是直接就是常态尺寸。 */
    private boolean loggedGeom = false;
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

    /** 最后一帧到达时间：抓帧进程死了会停更，服务端据此重启采集（背景冻结的根因）。 */
    public volatile long lastFrameAtMs = 0L;

    /** root 采集线程投递一帧（已转成 RGBA、行序翻转好）。 */
    public void submitFrame(byte[] b, int w, int h) {
        lastFrameAtMs = System.currentTimeMillis();
        byte[] old = readyFrame.getAndSet(b);
        if (old != null) framePool.offer(old);
    }

    /** 切换背景模式并重置背景初始化（GL 线程调用）。 */
    public void setBgMode(int mode) { bgMode = mode; bgInit = false; gotFrames = 0; }

    /**
     * 把「背景」整块重灌一遍（GL 线程调用：服务侧用 glView.queueEvent 投递）。
     *
     * 为什么需要：抠图是 ping-pong 的 —— 球体那一块**永远保留 bg 里的旧内容**（那是"球后面
     * 的桌面"，用来避免自采样），只有球外区域每帧刷新。所以只要有一帧 bg 是在
     * 「抓屏里还含着球自己」的时候灌进去的，球里就会永久卡着那张含球的脏图：
     * 屏幕上看着就是**折射消失 / 变成一块死玻璃**，等多久都不会自愈。
     * 抓屏排除（SKIP_SCREENSHOT）重新打上、或者抓帧源重启之后，必须调这个把它冲掉。
     */
    public void resetBackground() {
        bgInit = false;
        gotFrames = 0;
        lastSnapMs = 0;
    }

    private final long t0 = System.currentTimeMillis();
    // ── 动画时钟 ──────────────────────────────────────────────────────────
    // 以前直接传「开机到现在多少秒」，所以任何「速度」都只能靠改代码。
    // 现在按 animSpeed 累积：调速度的那一刻相位是连续的（不会突然跳一下）。
    private long animClockNs = 0L;
    private float animClockSec = 0f;
    private float animClock() {
        long now = System.nanoTime();
        if (animClockNs == 0L) animClockNs = now;
        float dt = (now - animClockNs) / 1e9f;
        animClockNs = now;
        dt = Math.max(0f, Math.min(0.1f, dt));
        animClockSec += dt * Math.max(.05f, Math.min(4f, animSpeed));
        return animClockSec;
    }

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
        "uniform float uTopDarken, uBlack0, uBlack1, uEdgeBleed, uBlackOffset;\n" +
        "uniform float uWaveInGlass;  // 1 = 玻璃里有字：波钉在玻璃底边，只露波头\n" +
        "uniform float uPxToScreen;    // 绘制缓冲像素 → 屏幕像素（方形态降分辨率渲染时 > 1）\n" +
        "uniform float uWaveHead;     // 波头可见高度（缓冲像素）：从玻璃底边往上只画这一截\n" +
        "uniform float iTime, uWaveAmp, uWaveScale, uZoom, uScale, uOrbMix;\n" +
        // 审批/提问面板：0=普通球（黑区还是纯黑），1=面板（黑区底部带强调色渐变 + 光晕）
        "uniform float uPanelMode;\n" +
        "uniform vec3 uPanelAccent;\n" +

        "uniform float uOrbRadius, uOrbOffset, uOrbGain, uOrbRim;\n" +
        "uniform float uFluid;          // 1 = 思考中画流体发光（否则画流体圆点）\n" +
        "uniform float uFluidRibs, uFluidSize, uFluidHot, uFluidColorMode, uFluidGain, uFluidDash;\n" +
        "uniform sampler2D iLoader; uniform float uLoaderOn;\n" +
        "uniform vec3  uFluidColor;      // 单色模式的颜色（0..1）\n" +
        "uniform float uDropMode;   // 1 = 水珠模式（四区那四个圆）\n" +
        "uniform float uDropZoom;   // 水珠的缩小倍率（>1 = 缩小镜：把更大的范围压进圆里）\n" +
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
        "// ── Fluid Dots: siriFluidDotsCore (metaball + smin) ──\n" +
        "float hash11(float n){ return fract(sin(n*127.1 + 311.7)*43758.5453); }\n" +
        "float settleWL(float tau, float w, float l){ if(tau<=0.0) return 0.0; return 1.0 - exp(-l*tau)*cos(w*tau); }\n" +
        "float settleOrb(float tau){ return settleWL(tau, 4.6, 3.2); }\n" +
        "float settleCrit(float tau, float l){ if(tau<=0.0) return 0.0; return 1.0 - exp(-l*tau)*(1.0 + l*tau); }\n" +
        "float smin(float a, float b, float k){ float h=max(k-abs(a-b),0.0)/k; return min(a,b) - h*h*k*0.25; }\n" +
        "vec3 hue2rgb(float h){ h=fract(h);\n" +
        "  float r=clamp(abs(h*6.0-3.0)-1.0,0.0,1.0);\n" +
        "  float g=clamp(2.0-abs(h*6.0-2.0),0.0,1.0);\n" +
        "  float b=clamp(2.0-abs(h*6.0-4.0),0.0,1.0);\n" +
        "  return vec3(r,g,b); }\n" +
        "float orbDotR(float fi, float seed, float t, float rBase){ return rBase + 0.010*sin(t*1.3+seed*6.2831853) + 0.005*sin(t*2.4+fi*1.3); }\n" +
        "float orbDotSD(vec2 p, vec2 pos, float r, float t, float fi, float shapeDamp){\n" +
        "  vec2 dd = p - pos;\n" +
        "  float sq = 0.075*(0.5+0.5*sin(t*0.9+fi*2.0))*shapeDamp;\n" +
        "  float ca=cos(t*0.35+fi), sa=sin(t*0.35+fi);\n" +
        "  dd = mat2(ca,-sa,sa,ca)*dd; dd *= vec2(1.0+sq,1.0-sq);\n" +
        "  return length(dd) - r; }\n" +
        // rBase（圆点半径）按球体实际像素尺寸换算：这样圆点在任何球里都是同样大小的圆，
        // 与「环半径 = uOrbRadius 像素」配套（见 orbScenePx）。
        "vec3 orbScene(vec2 p, float t, float rBase){\n" +
        "  float k=floor(t/6.0); float te=fract(t/6.0)*6.0;\n" +
        "  float tg=mod(t,12.0);\n" +
        "  float g=settleCrit((tg-9.2)*1.8,7.5)-settleWL(tg-10.0,6.5,4.0);\n" +
        "  float gC=clamp(g,0.0,1.0);\n" +
        "  float tb=tg-10.0;\n" +
        "  float charge=smoothstep(-0.30,0.0,min(tb,0.0))*gC;\n" +
        "  float flash=tb>0.0?exp(-tb*7.0):0.0;\n" +
        "  float gBright=mix(1.0,0.85,gC)*(1.0+0.35*charge+1.2*flash);\n" +
        "  vec3 spc=vec3(0.0,0.5,1.0)*0.005;\n" +
        "  vec3 total3=vec3(1e5); vec3 cAcc=vec3(0.0); float wAcc=1e-6;\n" +
        "  for(int i=0;i<6;i++){\n" +
        "    float fi=float(i); float seed=hash11(fi);\n" +
        "    float ang=fi/6.0*6.2831853 + t*0.35;\n" +
        "    vec2 dir=vec2(cos(ang),sin(ang));\n" +
        "    float R=0.17+0.010*sin(t*1.0)+0.007*sin(t*1.3+seed*6.2831853);\n" +
        "    float pairId=mod(fi,3.0); float moverLow=mod(k+pairId,2.0);\n" +
        "    float isMover=(fi<2.5)?step(moverLow,0.5):step(0.5,moverLow);\n" +
        "    float goStart=pairId*0.33; float retStart=0.99+pairId*0.33;\n" +
        "    float m=(settleOrb(te-goStart)-settleOrb(te-retStart))*isMover;\n" +
        "    float rec=(settleOrb(te-goStart-0.11)-settleOrb(te-retStart-0.11))*(1.0-isMover);\n" +
        "    float rSelf=orbDotR(fi,seed,t,rBase); rSelf=mix(rSelf,rBase,gC); rSelf*=1.0-0.18*charge;\n" +
        "    float fj=mod(fi+3.0,6.0); float rPart=orbDotR(fj,hash11(fj),t,rBase);\n" +
        "    float deep=-(R+0.035)-0.12*rPart;\n" +
        "    float radial=mix(R,deep,m)+0.035*rec; radial=mix(radial,0.008,g);\n" +
        "    vec2 pos=radial*dir;\n" +
        "    float sdR=orbDotSD(p-spc.r*dir,pos,rSelf,t,fi,1.0-gC);\n" +
        "    float sdG=orbDotSD(p-spc.g*dir,pos,rSelf,t,fi,1.0-gC);\n" +
        "    float sdB=orbDotSD(p-spc.b*dir,pos,rSelf,t,fi,1.0-gC);\n" +
        "    total3=vec3(smin(total3.r,sdR,0.08),smin(total3.g,sdG,0.08),smin(total3.b,sdB,0.08));\n" +
        "    float hue=fract(fi/6.0+t*0.06)*0.667;\n" +
        "    vec3 dotCol=mix(vec3(1.0),hue2rgb(hue),0.01);\n" +
        "    float w=exp(-sdG*0.5); cAcc+=w*dotCol; wAcc+=w;\n" +
        "  }\n" +
        "  vec3 sd3=max(total3,vec3(0.0))+1e-4;\n" +
        "  vec3 core3=clamp(0.0035/pow(sd3,vec3(1.35)),0.0,1.0);\n" +
        "  vec3 edge3=1.0-smoothstep(vec3(0.02),vec3(0.56),sd3);\n" +
        "  return core3*edge3*gBright*(cAcc/wAcc); }\n" +
        // 思考中的流体圆点：坐标 = 球内像素 / uOrbRadius * uRingR（uRingR=0.17），
        // 所以环半径恒等于 uOrbRadius 像素；x/y 同倍率 → 圆点在扁球里也不会被拉长。
        // 旧写法把 uWaveScale 当像素倍率（x/y 同值），竖轴等于又乘了 hy/hx 倍 →
        // 2:1 的胶囊球里圆点被拉成 2.8 倍高的椭圆、又大又亮，整颗球像换了个东西。
        "vec3 orbScenePx(vec2 localPx, float hx, float hy, float t){\n" +
        "  float k = 0.17/max(uOrbRadius, 1e-3);\n" +
        "  vec2 q = localPx - vec2(0.0, -uOrbOffset*hy);\n" +
        "  return orbScene(q*k, t, 0.038*k); }\n" +
        // ── Fluid Glow（连续发光流体）────────────────────────────────────────
        // 和上面 orbScene 用的是**同一个光核**：
        //     g = clamp(0.0032 / pow(sd, 1.35), 0, 1) * (1 - smoothstep(0.02, 0.56, sd))
        // 只是 sd 从"圆点距离场"换成"球壳上的丝线距离场"——一圈点变成一团连续流动的光。
        // 坐标：和 orbScenePx 同一套（环半径 = uOrbRadius 屏幕像素），
        //       所以球体大小/形状一变，这团光跟着一起缩，不会跑出去。
        "float fh11(vec2 p){ vec3 p3=fract(vec3(p.xyx)*0.1031); p3+=dot(p3,p3.yzx+33.33); return fract((p3.x+p3.y)*p3.z); }\n" +
        "float fn2(vec2 p){ vec2 i=floor(p), f=fract(p);\n" +
        "  f=f*f*f*(f*(f*6.0-15.0)+10.0);\n" +
        "  return mix(mix(fh11(i),fh11(i+vec2(1,0)),f.x), mix(fh11(i+vec2(0,1)),fh11(i+vec2(1,1)),f.x), f.y); }\n" +
        "float ffbm3(vec2 p){ float s=0.0,a=0.5; for(int i=0;i<3;i++){ s+=a*fn2(p);\n" +
        "  p = mat2(0.80,0.60,-0.60,0.80) * p * 2.03 + 19.7; a*=0.5; } return s; }\n" +
        "float ffbm2(vec2 p){ float s=0.0,a=0.55; for(int i=0;i<2;i++){ s+=a*fn2(p);\n" +
        "  p = mat2(0.80,0.60,-0.60,0.80) * p * 2.11 + 7.3; a*=0.45; } return s; }\n" +
        "vec3 hue2rgbF(float h){ h=fract(h);\n" +
        "  return vec3(clamp(abs(h*6.0-3.0)-1.0,0.0,1.0), clamp(2.0-abs(h*6.0-2.0),0.0,1.0), clamp(2.0-abs(h*6.0-4.0),0.0,1.0)); }\n" +
        "vec3 rotFY(vec3 v,float a){ float c=cos(a),s=sin(a); return vec3(c*v.x+s*v.z, v.y, -s*v.x+c*v.z); }\n" +
        "vec3 rotFX(vec3 v,float a){ float c=cos(a),s=sin(a); return vec3(v.x, c*v.y-s*v.z, s*v.y+c*v.z); }\n" +
        "vec3 rotFZ(vec3 v,float a){ float c=cos(a),s=sin(a); return vec3(c*v.x-s*v.y, s*v.x+c*v.y, v.z); }\n" +
        // ── Fluid Glow（**照搬演示页 fluid-glow.html 的那一套**）─────────────────
        // 用户：页面上的效果很自然。所以这里不再自创参数，直接把页面的几何/光核搬过来：
        //   球壳纬线 + 0.38 的低频扭曲 + 0.30 的屏幕剪切 + 球面透视；
        //   线 id 一律从画出来的 f 取（否则亮度会在丝线中间跳变）；
        //   光核 = dots 同族：clamp(0.0032/sd^1.35) × (1 - smoothstep(0.02, 0.56, sd))。
        // 唯一改动：半径按球体短半轴算（页面上是相对画布短边），并把背面壳压到 30%。
        "vec3 fluidShell(vec2 q, float side, float t, float dens){\n" +
        "  float r2 = dot(q,q); float z = sqrt(max(0.0, 1.0-r2));\n" +
        "  vec3 v = vec3(q, z*side);\n" +
        "  vec3 ldir = normalize(vec3(-0.55,-0.42,0.72));\n" +
        "  float lit = 0.14 + 0.86*max(dot(v, ldir), 0.0);\n" +
        "  float rimL = pow(1.0-z, 2.5)*0.55;\n" +
        "  v = rotFY(v, t*0.30 + 0.85);\n" +
        "  v = rotFZ(v, -0.80 + 0.06*sin(t*0.11));\n" +
        "  v = rotFX(v, 0.10 + 0.05*sin(t*0.17));\n" +
        "  float lat = asin(clamp(v.y,-1.0,1.0)); float lon = atan(v.x, v.z);\n" +
        "  float wn  = ffbm3(vec2(cos(lon),sin(lon))*1.15 + vec2(lat*0.85, t*0.16));\n" +
        "  float wn2 = ffbm2(vec2(cos(lon),sin(lon))*2.40 - vec2(lat*0.70, t*0.11));\n" +
        "  float foreshorten = 0.42 + 0.58*z;\n" +
        "  float spacing = 3.14159265/max(dens, 3.0);\n" +
        "  float shear = 0.30*q.x*(1.0 + 0.15*sin(t*0.27));\n" +
        "  float f = (lat + (wn-0.5)*0.38 + shear)/spacing;\n" +       // ← 页面原值
        "  float band = floor(f + 0.5);\n" +                            // ← id 跟画出来的线走
        "  float hb = fh11(vec2(band, side*7.0));\n" +
        "  float latEff = f*spacing;\n" +
        "  float sd = abs(fract(f+0.5)-0.5)*spacing*foreshorten;\n" +
        "  float thick = 0.55 + 0.65*pow(0.5+0.5*sin(latEff*2.2 + t*0.9 + hb*6.2831853), 3.0);\n" +
        "  float sdN = sd/max(thick, 0.32);\n" +
        "  float g = clamp(0.0032/pow(max(sdN,1e-4), 1.35), 0.0, 1.0) * (1.0 - smoothstep(0.02, 0.56, sdN));\n" +
        "  float segN = ffbm2(vec2(band*0.73, hb*4.0 + t*0.13));\n" +
        "  float hotN = ffbm2(vec2(latEff*1.7 + band*0.9, lon*0.6 + t*0.12));\n" +
        "  float hot  = smoothstep(0.55, 0.92, hotN) * clamp(uFluidHot, 0.0, 2.0);\n" +
        "  float wSeg = mix(0.35, 1.45, segN);\n" +
        "  vec3 base;\n" +
        "  if(uFluidColorMode < 0.5)      base = vec3(0.96,0.98,1.00);\n" +
        "  else if(uFluidColorMode < 1.5) base = uFluidColor;\n" +
        "  else { float hue = fract(0.478 + 0.09*sin(band*1.7 + t*0.21) + 0.05*(wn2-0.5)); base = hue2rgbF(hue); }\n" +
        "  vec3 c = mix(base, vec3(1.0), clamp(hot*0.85, 0.0, 1.0));\n" +
        "  float depth = (side > 0.0) ? 1.0 : 0.30;\n" +
        "  return c * (g*wSeg + hot*g*1.5) * (lit + rimL) * depth;\n" +
        "}\n" +
        "vec3 fluidScenePx(vec2 localPx, float hx, float hy, float t){\n" +
        "  float R = max(min(hx, hy) * 0.62 * max(uFluidSize, 0.05), 3.0);\n" +
        "  vec2 q = (localPx - vec2(0.0, -uOrbOffset*hy*0.5)) / R;\n" +
        "  float pulse = 0.085*sin(t*0.55) + 0.040*sin(t*1.27 + 2.1);\n" +
        "  q /= (1.0 + pulse);\n" +
        "  vec2 flow = vec2(ffbm2(q*2.1 + vec2(0.0, t*0.33)) - 0.5,\n" +
        "                   ffbm2(q*2.1 + vec2(5.2, t*0.30) + 3.1) - 0.5);\n" +
        "  vec2 wq = q + flow*0.11;\n" +
        "  float ang = atan(wq.y, wq.x);\n" +
        "  float bulge = 0.060*sin(2.0*ang + t*0.31) + 0.030*sin(3.0*ang - t*0.23)\n" +
        "              + 0.015*sin(5.0*ang + t*0.17 + 1.7);\n" +
        "  wq /= (1.0 + bulge);\n" +
        "  float r = length(wq);\n" +
        "  vec3 col = vec3(0.0);\n" +
        "  if(r < 1.06){\n" +
        "    float edge = smoothstep(1.06, 0.86, r);\n" +
        "    col += fluidShell(wq,  1.0, t, uFluidRibs) * edge;\n" +
        "    col += fluidShell(wq, -1.0, t, uFluidRibs) * edge;\n" +
        "  }\n" +
        "  vec3 bc = (uFluidColorMode < 0.5) ? vec3(0.96,0.98,1.0)\n" +
        "          : ((uFluidColorMode < 1.5) ? uFluidColor : hue2rgbF(0.50));\n" +
        "  col += bc * 0.09 * exp(-r*r*2.4);\n" +                            // 体内柔光（页面同款）
        "  return col * max(uFluidGain, 0.0);\n" +
        "}\n" +
        "vec2 screenUv(vec2 fc){\n" +
        // fc 是**绘制缓冲**里的像素；乘 uPxToScreen 换成屏幕像素，才能和 uWinPos/uScreen 对齐
        "  vec2 top = vec2(fc.x, iRes.y - fc.y) * uPxToScreen;\n" +
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
        "  vec2 cScreenTop = uWinPos + vec2(uCenter.x, iRes.y - uCenter.y) * uPxToScreen;\n" +
        "  vec2 cuv = cScreenTop / uScreen;\n" +
        "  suv = cuv + (suv - cuv) / uZoom;\n" +
        "  float dispI = uAberration * ((c.x * c.y) / (uHalf.x * uHalf.y)) / 30.0;\n" +
        "  vec2 disp = gradUV * d * dispI / uScreen;\n" +
        "  vec3 col;\n" +
        "  col.r = texture2D(iBlur, suv + disp).r;\n" +
        "  col.g = texture2D(iBlur, suv).g;\n" +
        "  col.b = texture2D(iBlur, suv - disp).b;\n" +
        "  col = mix(col, col*vec3(0.94,0.96,1.0)+vec3(0.01,0.012,0.02), 0.22);\n" +
        // ── ★ 水珠（uDropMode=1）：真球面光学 ─────────────────────────────────
        // 不做任何"画上去的高光/蒙版"。把形状当成一个半球，逐像素算：
        //   法线 N = (q, sqrt(1-q²))；折射位移 ∝ 斜率(1-z)（边缘弯得厉害、中心几乎不动）；
        //   反射 R = 2(N·V)N − V，用**实时抓屏**当环境贴图采样；
        //   菲涅尔 F = 0.03 + 0.97(1−z)⁵ —— 边缘几乎全反射、正面几乎全透。
        // 所以反光和折射都跟着背后的桌面实时变，而不是一层写死的高光。
        "  if (uDropMode > 0.5) {\n" +
        "    vec2 q = c / uHalf;\n" +
        "    float r2 = clamp(dot(q, q), 0.0, 0.999);\n" +
        "    float z  = sqrt(1.0 - r2);\n" +
        "    vec2 uv0 = screenUv(fc);\n" +
        // 缩小镜：采样点相对片元**往外**推 → 圆里装的画面范围更大、东西看起来更小；
        // 推到边缘时倍率回到 1.0，所以圆周上和外面的画面对得上（不留接缝）。
        // 缩小镜的映射：采样半径 = r·(k − (k−1)r²)。
        //   r=0 → 0、r=1 → 1（圆周上和外面的画面对齐）；
        //   k≈1.9 时中心把 1.9 倍的视野压进圆里（东西变小），靠近边缘那一圈会轻微"翻过来"，
        //   正是水珠边缘那种倒过来的像。之前用 smoothstep 版会把整个场景糊成放射条纹。
        "    float r2q = clamp(dot(q, q), 0.0, 1.0);\n" +
        "    float kz  = max(uDropZoom, 1.0);\n" +
        "    vec2 qs   = q * (kz - (kz - 1.0) * r2q);\n" +
        "    vec2 duv  = (qs - q) * uHalf * uPxToScreen / uScreen;\n" +
        "    float ab = uAberration * 0.004 * (1.0 - z);\n" +
        "    vec3 refr;\n" +
        "    refr.r = texture2D(iBg, uv0 + duv * (1.0 + ab)).r;\n" +
        "    refr.g = texture2D(iBg, uv0 + duv).g;\n" +
        "    refr.b = texture2D(iBg, uv0 + duv * (1.0 - ab)).b;\n" +
        "    vec2 rxy = 2.0 * z * q;\n" +                  // R.xy（V 朝屏幕里看）
        "    vec2 reflOff = rxy * (uHalf / uScreen) * 0.18;\n" +
        "    vec3 refl = texture2D(iBlur, uv0 + reflOff).rgb;\n" +
        "    float F = 0.03 + 0.97 * pow(1.0 - z, 5.0);\n" +
        "    col = mix(refr, refl, clamp(F, 0.0, 1.0));\n" +

        "  }\n" +
        "  float ny = -c.y / uHalf.y;\n" +
        "  float b0 = uBlack0 - uBlackOffset;\n" +
        "  float b1 = uBlack1 - uBlackOffset;\n" +
        // 流体模式：黑区下沿往下压到 ny=0.55（球的下半也基本黑），
        // 这样丝线整团都在黑底上 —— 桌面的行/线不会被玻璃折射进来糊在丝线上（那才是"割裂"的来源之一）
        "  b1 = mix(b1, 0.55, clamp(uFluid, 0.0, 1.0));\n" +
        "  float blackMask = smoothstep(b0, b1, ny);\n" +
        // 流体发光模式：整颗球垫成纯黑（用户：背景不要东西，跟 dots 一样纯黑）。
        // 只改这一层的遮罩，玻璃的折射/边缘亮线照旧在外面叠。
        // 流体模式也用同一条黑区渐变（用户要 Siri 波那种"上黑下透"），不整颗填黑

        "  blackMask = blackMask*blackMask*(3.0-2.0*blackMask);\n" +
        // 普通球：黑区是深色玻璃。面板：底色**保持黑**，但做成「上暗下亮」——
        // 顶上几乎纯黑，越往下越亮，最底下那一条亮到有点刺眼（像底部藏了个光源）。
        // 面板底色：顶上**纯黑**（给文字垫底），往下**加速**变透，底部基本只剩玻璃。
        // 玻璃的折射/色散/黑顶/赤道线在下面那半段照旧可见（苹果那张卡片就是这个层次：
        // 壳是玻璃，只有字/按钮那几块有底）。
        // 输出是**预乘 alpha**，减透明度要同时缩 col 与 alpha。
        "  vec3 blackCol = vec3(0.012,0.015,0.025);\n" +
        "  float panelFade = 1.0;\n" +
        "  float tBlackK = 1.0;\n" +
        "  if(uPanelMode > 0.5){\n" +
        "    float rise = clamp((1.0 - ny) / 1.5, 0.0, 1.0);\n" +      // 0=球顶 1=黑区底
        "    blackCol = vec3(0.0);\n" +                               // 纯黑，不带任何颜色
        "    panelFade = 1.0;\n" +
        // ★ 2026-10-23 二修（用户：「依然有一层锋利的黑色长方形」）：
        //   旧写法 mix(1.0, 0.12, pow(rise,2.6)) 在**整块上半段**都贴着 1.0（rise<0.45 时
        //   pow≈0.1，混出来还是 0.91 黑），于是黑块是一整片、下沿是直的、还盖住了圆角。
        //   现在从顶到底**连续**掉：0.92 → 0.02（0.6 次幂，掉得快 → 下半截就是普通玻璃，
        //   和球/胶囊同一套观感；用户 2026-11-06：「审批界面的玻璃跟别的玻璃不一样、像没实时渲染」），再乘 smoothstep(0,18dp,-sd)
        //   让贴边那一圈变薄 —— 圆角自然跟着玻璃形状收，不再有"方框"。
        "    float top01 = clamp((1.0 + ny) * 0.5, 0.0, 1.0);\n" +   // 0=球顶 1=球底
        "    tBlackK = mix(0.92, 0.02, pow(top01, 0.60));\n" +
        "    tBlackK *= smoothstep(0.0, 18.0 * uScale, -sd);\n" +
        "  }\n" +
        "  float blackMix = blackMask*uTopDarken*tBlackK;\n" +
        "  col = mix(col, blackCol, blackMix);\n" +
        // 思考中胶囊加载：像 dots 一样画在玻璃内容层（黑区之上），但位置/形状严格贴球的 SDF，绝不会出胶囊
        "  if(uLoaderOn > 0.001){\n" +
        // ★ 2026-11-08 用户：「动画被玻璃折射了 —— Liquid Glass 页面里的画面是不变形的」。
        //   所以动画**不进透镜位移场**：用球内的原始坐标取样/计算，圆点保持正圆、
        //   位置不打弯；玻璃的折射只作用在背后的桌面上。
        //   黑区仍按原公式小幅遮蔽（点阵要亮一点才看得清）。
        "    vec2 luv = vec2(c.x/uHalf.x, -c.y/uHalf.y) * 0.5 + 0.5;\n" +
        "    float lfade = smoothstep(0.0, -12.0*uScale, sd);\n" +
        "    float lvis = mix(1.0 - blackMask*uTopDarken*0.55, 1.0, clamp(uFluid,0.0,1.0));\n" +
        "    col += texture2D(iLoader, clamp(luv, 0.0, 1.0)).rgb * (uLoaderOn * lfade * lvis);\n" +
        "  }\n" +
        "  vec3 wglow = vec3(0.0);\n" +
        // 波照旧画，**只挪竖直锚点**：
        //   圆球态 = 黑区下沿 b1（原来的观感，一点没动），坐标按球高归一化；
        //   玻璃里有字 = 钉在玻璃底边：波心藏在底边下方，藏多深由「波的峰高」反推，
        //   露出来的永远只是贴底那一截波头（uWaveHead 像素，再往上渐隐）。
        //   竖直尺度也用半宽（85% 屏宽的一半，固定）→ 玻璃长高/变矮，波头一像素都不变。
        // 用户原话：「Siri 波固定在回复 AI 文字的那块玻璃的底部，无论玻璃变高还是变矮，
        //           固定只显示 Siri 波头」。
        "  if(uWaveAmp > 0.001){\n" +
        // 动画不打弯：直接用球内坐标（不叠加透镜位移、也不吃 zoom）。
        "    vec2 cw = c;\n" +
        "    vec2 wp;\n" +
        "    if(uWaveInGlass > 0.5){\n" +
        "      float s = max(uHalf.x * max(uWaveScale, 0.1), 1.0);\n" +
        "      float dropPx = 0.32 * s - 0.55 * uWaveHead;   // 波心藏在玻璃底边下方\n" +
        "      wp = vec2(cw.x / s, (-cw.y + uHalf.y + dropPx) / s);\n" +
        "    } else {\n" +
        "      wp = vec2(cw.x/uHalf.x, -cw.y/uHalf.y - b1) / max(uWaveScale, 0.1);\n" +
        "    }\n" +
        // ── 玻璃底边那一道波头：只有贴底 uWaveHead 像素内要画，其余照旧算就是白烧 GPU ──
        // 只算底边这条带（headMask=0 的像素直接跳过）。光的配方和球里那条波完全一样
        // （核心 + 蓝白辉光 + 黑区遮蔽），所以"沉到玻璃底"看起来就是同一条 Siri 波，
        // 只是锚点固定、只露波头。
        "    if(uWaveInGlass > 0.5){\n" +
        "      float hAbove = uHalf.y - cw.y;\n" +          // 距玻璃底边多少像素（向上为正）
        "      float headMask = 1.0 - smoothstep(0.0, max(uWaveHead, 1.0), hAbove);\n" +
        "      if(abs(wp.y) < 1.6 && headMask > 0.004){\n" +
        // 黑区遮蔽照旧：玻璃里那块底是黑的，波不压一下会过曝（用户原话：「像 LED 一样白」）
        "        float gVis = mix(0.45, 1.0 - uTopDarken*0.85, blackMask);\n" +
        "        vec3 gWave = waveCol(wp, iTime, 1.0);\n" +
        "        vec3 gGlow = waveCol(wp, iTime, 7.0) * vec3(0.80,0.95,1.25);\n" +
        "        float gBelow = smoothstep(0.7, -0.5, wp.y);\n" +
        "        gWave += gGlow*(0.25 + 0.6*gBelow);\n" +
        "        col += gWave * (uWaveAmp * gVis * headMask);\n" +
        "      }\n" +
        "    } else {\n" +
        // 黑区遮蔽：圆球态照旧（波画在黑区里，不压一下会过曝）
        "    float waveVis = mix(1.0 - blackMask*uTopDarken*0.85, 1.0, clamp(uFluid, 0.0, 1.0));\n" +
        "    // 两种形态用同一个框架，uOrbMix 做交叉淡化：思考→回答时不再「啪」地换一套光效\n" +
        "    vec3 waveEff = waveCol(wp, iTime, 1.0);\n" +
        "    wglow = waveCol(wp, iTime, 7.0) * vec3(0.80,0.95,1.25);\n" +
        "    float below = smoothstep(0.7, -0.5, wp.y);\n" +
        "    waveEff += wglow*(0.25 + 0.6*below);\n" +
        "    vec3 orbRaw = mix(orbScenePx(cw, uHalf.x, uHalf.y, iTime),\n" +
        "                      fluidScenePx(cw, uHalf.x, uHalf.y, iTime), clamp(uFluid,0.0,1.0));\n" +
        // 体量调到很大时，丝线会顶到球形的平边、被"切一刀"——在球内缩 26px 就开始淡出，
        // 于是再大也是柔和地消失在黑里，不会出现直边。
        "    float shapeFade = smoothstep(0.0, -26.0*uScale, sd);\n" +
        "    orbRaw *= mix(1.0, shapeFade, clamp(uFluid, 0.0, 1.0));\n" +
        "    vec3 orbEff = pow(max(orbRaw, 0.0), vec3(1.0/1.2)) * uOrbGain;\n" +
        // 边缘辉光必须跟着光效自己的亮度：Siri 波用波的颜色（很暗），圆点若用固定满值
        // (0.80,0.95,1.25)，球壳高光/赤道线/底缘会整体亮 5 倍 —— 「思考中球边缘太亮」的根源。
        "    float orbLum = max(orbRaw.r, max(orbRaw.g, orbRaw.b)) * 1.2;\n" +
        "    vec3 orbGlow = vec3(0.80,0.95,1.25) * (orbLum * uOrbRim);\n" +
        "    wglow = mix(wglow, orbGlow, uOrbMix);\n" +
        "    col += mix(waveEff, orbEff, uOrbMix) * uWaveAmp * waveVis;\n" +
        "    }\n" +
        "  }\n" +
        "  float innerEdge = smoothstep(0.,-18.*uScale,sd);\n" +
        "  col *= mix(mix(0.70, 1.0, innerEdge), 1.0, uDropMode);\n" +
        "  float mid=(b0+b1)*0.5;\n" +
        "  float eqLine = smoothstep(b0,mid,ny)*smoothstep(b1,mid,ny);\n" +
        "  float horiz = 1.0 - abs(c.x)/uHalf.x*0.3;\n" +
        "  float fluidK = 1.0 - clamp(uFluid, 0.0, 1.0);\n" +                 // 流体模式：球面不要任何"模板线"
        "  col += vec3(0.95,0.97,1.0)*eqLine*horiz*uEdgeBleed*(1.0 - uDropMode)*fluidK;\n" +
        "  float bottomRim = smoothstep(-14.*uScale,-2.*uScale,sd) * smoothstep(0.0,-0.6,-ny);\n" +
        "  col += vec3(0.85,0.88,0.95) * bottomRim * uEdgeBleed * 0.5 * (1.0 - uDropMode) * fluidK;\n" +
        "  col += wglow * uWaveAmp * (eqLine*horiz + bottomRim) * 0.9;\n" +
        "  float shell = smoothstep(3.0*uScale, 0.0, abs(sd + 2.5*uScale));\n" +
        "  float shellLit = 0.30 + 0.70*smoothstep(0.3, -0.9, ny);\n" +
        "  col += (vec3(0.10,0.11,0.13) + wglow*uWaveAmp*1.2) * shell * shellLit * (1.0 - 0.5*uDropMode) * fluidK;\n" +
        "  float aa = smoothstep(1.5, -1.5, sd);\n" +
        "  aa *= mix(1.0, panelFade, blackMask*uTopDarken*tBlackK);\n" +   // 面板底半透明，玻璃照旧

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

    /**
     * 搜索层合成：把 {@link SearchLayerShader} 那套 uniform 摆好，整屏画一遍。
     *
     * 坐标约定必须和演示页一致：shader 里 `p = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y)`
     * 是"y 向下"的画布像素；我们的画布是**绘制缓冲**，所以给进去的每个长度量都要
     * 乘 `k`（缓冲像素/窗口像素）—— 不这么做的话，降分辨率渲染时胶囊会整体缩一圈
     * （球的方形态当年就是栽在这个双重/缺失缩放上，见 HANDOFF「降分辨率的坑」）。
     */
    private void drawSearchLayer(int bgTex) {
        if (searchProg == 0) return;
        float k = (winPixelW > 0 && winW > 0) ? (float) winW / (float) winPixelW : 1f;
        float invK = 1f / Math.max(k, 1e-3f);
        float dpr = Math.max(0.5f, sDensity) * k;      // 演示页的 uDpr（CSS px → 画布 px）
        useProgram(searchProg);
        bindFullscreen(searchProg);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, winW, winH);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurSrcTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(searchProg, "iBg"), 0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(searchProg, "iBlur"), 1);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(searchProg, "uRes"), winW, winH);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(searchProg, "uScreen"), screenW, screenH);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(searchProg, "uWinPos"), winX, winY);
        GLES20.glUniform4f(GLES20.glGetUniformLocation(searchProg, "uBlob"),
                sBlobCx * k, sBlobCy * k, Math.max(sBlobHx, 0.5f) * k, Math.max(sBlobHy, 0.5f) * k);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(searchProg, "uK"), sK * k);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(searchProg, "uHeight"), sHeight * k);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(searchProg, "uRefract"), sRefract * k);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(searchProg, "uHlAmt"), sHlAmt);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(searchProg, "uAb"), sAb);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(searchProg, "uDpr"), dpr);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(searchProg, "uDark"),
                Math.max(0f, Math.min(1f, sDark)));
        GLES20.glUniform1f(GLES20.glGetUniformLocation(searchProg, "uCont"),
                Math.max(0f, Math.min(1f, sCont)));
        GLES20.glUniform1f(GLES20.glGetUniformLocation(searchProg, "uValid"), sValid);
        // 屏幕 UV 方向：抓屏纹理是"屏幕像素、y 向下"，而 bgcol 收到的是画布像素 → 乘 invK 回到窗口像素
        GLES20.glUniform1f(GLES20.glGetUniformLocation(searchProg, "uPxToScreen"), invK);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
    }

    /**
     * 状态 3 / 玻璃菜单层：和 {@link #drawSearchLayer} 同一条管线（抓屏→背景→模糊→合成），
     * 只是把 5 个形状与它们的高亮/深浅/圆角交给 {@link ZoneLayerShader}。
     *
     * 坐标同样是"屏幕像素、y 向下"，画布是绘制缓冲 → 所有长度量乘 k。
     */
    private void drawZoneLayer(int bgTex) {
        if (zoneProg == 0) return;
        float k = (winPixelW > 0 && winW > 0) ? (float) winW / (float) winPixelW : 1f;
        float invK = 1f / Math.max(k, 1e-3f);
        float dpr = Math.max(0.5f, zDensity) * k;
        useProgram(zoneProg);
        bindFullscreen(zoneProg);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, winW, winH);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurSrcTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(zoneProg, "iBg"), 0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(zoneProg, "iBlur"), 1);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(zoneProg, "uRes"), winW, winH);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(zoneProg, "uScreen"), screenW, screenH);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(zoneProg, "uWinPos"), winX, winY);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uK"), 10f * k);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uHeight"), zHeight * k);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uRefract"), zRefract * k);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uBandRatio"), zBandRatio);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uDome"), zDome);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uAb"), zAb);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uTopDarken"), zTopDarken);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uCap0"), zCap0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uCap1"), zCap1);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uCapPow"), zCapPow);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uEdgeShade"), zEdgeShade);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uEdgeShade2"), zEdgeShade2);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uDesat"), zDesat);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uShadow"), zShadow);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uEdgeBleed"), zEdgeBleed);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uScrim"), zScrim);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uGlow"), zGlow);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uFrost"), zFrost);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uHlAmt"), sHlAmt);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uDpr"), dpr);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uCont"),
                Math.max(0f, Math.min(1f, zCont)));
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uValid"), 1f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(zoneProg, "uPxToScreen"), invK);

        float[] s = zShape;
        float[] m = zMeta;
        for (int i = 0; i < 5; i++) {
            // ⚠ 形状坐标是**屏幕**像素，而 shader 里的 p 是**窗口内**画布像素：
            //   先减窗口在屏幕上的位置，再乘 k。搜索层不用减是因为它整屏、且窗口顶边对齐屏幕顶边。
            GLES20.glUniform4f(GLES20.glGetUniformLocation(zoneProg, "uS" + i),
                    (s[i * 4] - winX) * k, (s[i * 4 + 1] - winY) * k,
                    Math.max(s[i * 4 + 2], 0.5f) * k, Math.max(s[i * 4 + 3], 0.5f) * k);
            GLES20.glUniform4f(GLES20.glGetUniformLocation(zoneProg, "uM" + i),
                    Math.max(0f, Math.min(1f, m[i * 4])),      // act
                    Math.max(0f, Math.min(1f, m[i * 4 + 1])),  // dark
                    Math.max(0f, Math.min(1f, m[i * 4 + 2])) * Math.min(
                            Math.max(s[i * 4 + 2], 0.5f), Math.max(s[i * 4 + 3], 0.5f)) * k,
                    m[i * 4 + 3] >= 0.5f ? 1f : 0f);           // enabled
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
    }

    /**
     * 区域层 = **同一颗球**画 N 遍。
     *
     * 为什么这么做：用户明确要求「直接复用聊天界面那个方形」「把初始状态的胶囊弄成圆形、同时弄四个」。
     * 于是这里不再有第二套材质 —— 用的就是 {@link #FS_COMP}：同样的抓屏、同样的折射/色散/
     * 黑区/赤道线/壳光，只是每个形状换一组 uCenter/uHalf/uCorner/uPanelMode，
     * 开 GL_BLEND（预乘 alpha：ONE / ONE_MINUS_SRC_ALPHA）把 5 个形状依次叠上去。
     */
    private long lastKLog = 0;
    private void drawZoneLayerBall(int bgTex) {
        if (compProg == 0) return;
        float k = (winPixelW > 0 && winW > 0) ? (float) winW / (float) winPixelW : 1f;
        if (Math.abs(k - 1f) > 0.02f && System.currentTimeMillis() - lastKLog > 2000) {
            lastKLog = System.currentTimeMillis();
            android.util.Log.w("FloatingBall", "zones: 画布缩放 k=" + k + "（win " + winW + "x" + winH
                    + " / surface " + winPixelW + "x" + winPixelH + "）—— 圆会和图标错位");
        }
        float invK = 1f / Math.max(k, 1e-3f);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
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

        // ── 全局（和球那一趟完全一致；波/圆点全关）──
        GLES20.glUniform2f(GLES20.glGetUniformLocation(compProg, "iRes"), winW, winH);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(compProg, "uScreen"), screenW, screenH);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(compProg, "uWinPos"), winX, winY);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uPxToScreen"), invK);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uZoom"), 1f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "iTime"),
                (System.currentTimeMillis() - t0) / 1000f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uWaveAmp"), 0f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uOrbMix"), 0f);
        // 圆点环半径用设置里的值（原来写死 90f，设置页怎么调都不生效 → dots 永远那么大）
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uOrbRadius"),
                Math.max(8f, orbRadius) * k);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uOrbOffset"), 0f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uOrbGain"), orbGain);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uOrbRim"), 0f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uBlackOffset"), 0f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uWaveInGlass"), 0f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uWaveHead"), 0f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uWaveScale"), 1f);
        GLES20.glUniform3f(GLES20.glGetUniformLocation(compProg, "uPanelAccent"), 1f, 1f, 1f);

        float[] s = zShape;
        float[] m = zMeta;
        for (int i = 0; i < 6; i++) {                              // 2026-10-23：四区 → 六区
            if (m[i * 4 + 3] < 0.5f) continue;                     // enabled
            float hx = Math.max(1f, s[i * 4 + 2] * k);
            float hy = Math.max(1f, s[i * 4 + 3] * k);
            float cx = (s[i * 4] - winX) * k;
            // ⚠ 2026-11-07：FS_COMP 里的 uCenter 是 **gl_FragCoord 空间（y 向上）**，
            //   shader 里 `c = fc - uCenter; c.y = -c.y;`。而这里的 s.y 是**屏幕像素、y 向下**。
            //   以前直接把 y 向下的值当 y 向上传 → 所有形状都绕画布中线上下镜像：
            //   球的圆心本来就在中线（winH*0.5）上，所以一直看不出来；
            //   六区和卡片一旦离开中线，玻璃就整体跑到另一半 —— 正是用户报的
            //   「图标离圆」「文字跑到玻璃外面」「标题行点不到」。
            //   这里翻一次 y：uCenter.y = 画布高 − 窗口内 y 向下值。
            float cy = (winH - (s[i * 4 + 1] - winY)) * k;
            float cr = Math.max(0f, Math.min(1f, m[i * 4 + 2])) * Math.min(hx, hy);
            float shortHalf = Math.min(hx, hy);
            boolean panel = m[i * 4 + 1] >= 0.5f;                  // dark>=0.5 视作"面板"

            GLES20.glUniform2f(GLES20.glGetUniformLocation(compProg, "uCenter"), cx, cy);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(compProg, "uHalf"), hx, hy);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uCorner"), cr);
            // 折射：和球同一条公式，长度按形状短半轴换算（球是 refract 200 / band 170）
            // 水珠比球"更弯"：折射强度 ≈ 0.85R、带子 1.6R（整颗都在折射带里）→ 边缘把桌面掰得很明显
            GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uRefraction"),
                    panel ? zRefract * k : shortHalf * 0.11f);   // 水珠：边缘位移 ≈ 0.19R
            GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uBand"),
                    Math.max(1f, panel ? shortHalf * zBandRatio : shortHalf * 1.60f));
            GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uAberration"),
                    panel ? zAb : 2.0f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uScale"), k);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uDome"), zDome);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uAberration"), zAb);
            // 面板沿用聊天卡那条黑区（0.93 / -1 → -0.01）；
            // 四区是"水珠"：黑区只压最上面一小片，且淡很多（用户：太黑了）
            GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uTopDarken"),
                    panel ? 0.93f : 0.20f);         // 水珠：只在最上面垫一点点（给白图标一点底）
            GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uBlack0"),
                    panel ? -1.0f : -0.98f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uBlack1"),
                    panel ? -0.01f : 0.25f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uEdgeBleed"),
                    panel ? 0.11f : 0.22f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uPanelMode"), panel ? 1f : 0f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uDropMode"), panel ? 0f : 1f);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uDropZoom"), panel ? 1f : zDropZoom);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
        }
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(0f, 0f, 0f, 0f);

        // 这个回调意味着**换了一套 EGL 上下文**（进程没死但 surface 重建过）：上一套上下文里的
        // program / buffer / texture 名字全部作废。这里不能 glDelete（那些名字在新上下文里
        // 可能已经是别人的），只能直接忘掉；FBO 尤其关键 —— 留着旧引用的话，抓屏/抠图会全部
        // 画到"在新上下文里根本没附件"的 FBO 上，球里就是一片黑（折射消失的另一条路）。
        capProg = blurProg = compProg = searchProg = zoneProg = maskProg = loaderProg = 0;
        quadBuf = 0; oesTex = 0;
        full = null; half = null; half2 = null; bg = null; bg2 = null;
        bgInit = false; gotFrames = 0; lastSnapMs = 0; frameAvailable = false;

        capProg = build(VS, FS_CAP);
        blurProg = build(VS, FS_BLUR);
        compProg = build(VS, FS_COMP);
        searchProg = build(VS, SearchLayerShader.FRAG);   // metaball-search 那一层（原样移植）
        zoneProg = build(VS, ZoneLayerShader.FRAG);       // 状态 3：同一套材质、多个形状
        maskProg = build(VS, FS_MASK);
        loaderProg = build(LOADER_VS, LOADER_FS);

        int[] b = new int[1];
        GLES20.glGenBuffers(1, b, 0);
        quadBuf = b[0];
        int[] lb = new int[1];
        GLES20.glGenBuffers(1, lb, 0);
        loaderBuf = lb[0];
        loaderVb = java.nio.ByteBuffer.allocateDirect(loaderVerts.length * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
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
        winW = w; winH = h;                    // 窗口尺寸随实际情况更新（Siri 模式窗口会贴合球）
        GLES20.glViewport(0, 0, w, h);
        // ⚠ 这套回调**每次 surface 重建**都会走一遍（音量加隐藏 → 电源键恢复就是这条路），
        //   所以旧 FBO 必须真的 glDelete 掉、而不是只把引用置 null：以前那样每切一次尺寸
        //   就漏 5 张全/半分辨率贴图（540×1206 那档 ≈ 9MB），藏几次显存就见底了。
        releaseFbos();
        loggedGeom = false;
        bgInit = false; lastSnapMs = 0; gotFrames = 0;
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
        // k = 绘制缓冲像素 / 窗口像素（方形态降分辨率渲染时 <1）。合成 pass 里凡是以
        // "屏幕像素"给的量（球半径/圆角/折射位移/带宽/边缘宽度/圆点环半径/波的下沉量）
        // 都要乘 k，才能和缓冲像素坐标系里的 c/sd 对齐；屏幕 UV 那边则乘 1/k。
        float k = (winPixelW > 0 && winW > 0) ? (float) winW / (float) winPixelW : 1f;
        float invK = 1f / Math.max(k, 1e-3f);
        float ref = refSize > 0 ? refSize : winW;
        float base = ref * 0.9f;
        // 球半径按**屏幕像素**给（base/hx/hy/cr），缓冲降分辨率时再乘 k 换算到缓冲像素；
        // 而 (cx, cy) 是"窗口内的位置"，直接用缓冲尺寸算即可 —— 千万别再乘一次 k（会双重缩放，球跑偏）。
        float hx = base * halfX * master, hy = base * halfY * master;
        float cx = winW * posX, cy = winH * 0.5f + ref * k * (0.5f - posY);
        float cr = base * halfY * corner * master;
        if (siriGeom) {
            // Siri 模式：几何完全由服务端动画驱动
            hx = base * svHalfX * svMaster;
            hy = base * svHalfY * svMaster;
            cx = winW * svPosX;
            cy = winH * (1.0f - svPosY);
            cr = base * svHalfY * svCorner * svMaster;
        } else if (idleScale > 0.001f && idleScale < 0.999f) {
            // ★ 常态球（非 Siri 形态）：同样要按「屏宽 1/3」缩 —— 系数由服务侧 ballIdleScale() 给。
            //
            // 为什么必须在这儿也乘一次：Siri 形态走的是 sv*（服务侧动画值，里面已经是 1/3 的
            // minHalfX），而**普通球那条路**以前一直用原版 halfX/halfY（0.37/0.25）。
            // 于是 App 冷启动的头几百毫秒（窗口已经加上、Siri 节拍还没接管）画出来的是一颗
            // 661×447 的**大球**，等 enterSearchMode/助手把 Siri 形态打开才"啪"地缩到 360×243
            // —— 用户看到的就是「刚启动第一次弹出的球还是大的」。
            // 现在第一帧就已经是常态尺寸，和稳定态完全一致（shapeK 会跟着变成 0.544，
            // 镜缘/带宽/圆点环半径也一起缩，见下面 shapeK 那段）。
            hx *= idleScale;
            hy *= idleScale;
            cr *= idleScale;
        }

        if (!loggedGeom) {
            loggedGeom = true;
            Log.i(TAG, "first frame geom: 球 " + Math.round(2 * hx) + "x" + Math.round(2 * hy)
                    + "（hx=" + Math.round(hx) + " hy=" + Math.round(hy) + " siriGeom=" + siriGeom
                    + " idleScale=" + (Math.round(idleScale * 1000) / 1000f) + " win=" + winW + "x" + winH + "）");
        }

        // ★ 2026-11-06：形状相对**原版球**的尺寸比（球缩到屏宽 1/3 时 ≈ 0.544；方形态/卡片 → 1）。
        //
        // 玻璃里凡是"用像素长度描述、但描述的是形状比例"的量，都必须乘它 ——
        // 原版（liquid-glass.html / 原版球）里这些量是跟着形状一起变的（P.band/P.master 那一套）：
        //   · 折射镜缘 uBand / uRefraction / uScale（球缩了不缩 → 中间那道折痕）
        //   · 思考中的流体圆点 uOrbRadius（环半径 = uOrbRadius 像素、点半径 = 0.038×它）
        //     —— 不缩的话小球里圆点会顶满整颗球，跟原版"一小圈点在球心附近"完全两样。
        // 方形态/聊天卡片比原版球大 → 夹到 1，卡片一个像素都不变。
        float shapeK = 1f;
        {
            float refHx = Math.max(1e-3f, base * halfX * Math.max(1e-3f, master));
            float refHy = Math.max(1e-3f, base * halfY * Math.max(1e-3f, master));
            shapeK = Math.max(0.05f, Math.min(1f, Math.min(hx / refHx, hy / refHy)));
        }

        // 防"球被窗口裁成方形"：
        // hx = base*halfX*master，而窗口大小是按 winSize 定的、不跟 master 变。
        // master 拉到 1.7 之类的值时球比窗口还宽 —— 两侧被裁平（看着像方形），
        // 折射/色差采样又跑到窗口外，玻璃整个花掉。
        //
        // ⚠️ 只夹「手动球体大小」这一路（非 siriGeom）。聊天卡片那种大方形玻璃是服务端
        // 动画驱动的（siriGeom=true），窗口本身会按卡片尺寸重设；在这里跟着夹会把宽度
        // 一起等比缩小 —— 表现就是"大玻璃变瘦、文字跑到玻璃外面"（实测踩过）。
        if (!siriGeom) {
            float maxHx = Math.max(1f, winW * 0.5f - 2f);
            float maxHy = Math.max(1f, winH * 0.5f - 2f);
            if (hx > maxHx || hy > maxHy) {
                float f = Math.min(maxHx / Math.max(hx, 1e-3f), maxHy / Math.max(hy, 1e-3f));
                hx *= f;
                hy *= f;
                cr *= f;
            }
        }

        {
            float kk = Math.max(k, 1e-3f);
            float wpx = winPixelW > 0 ? winPixelW : winW;
            float wpy = winPixelH > 0 ? winPixelH : winH;
            ballCxW = cx / kk;
            ballCyW = wpy - cy / kk;   // 抠图/合成用的是 y 向上，叠层视图是 y 向下
            ballHxW = hx / kk;
            ballHyW = hy / kk;
        }

        if (captureSource == 1) {
            // root 直采：把原始帧上传进 full（行序已翻转），不经 MediaProjection
            uploadRootFrame();
            if (!sawFrame) return;
        } else if (!freezeCapture) {
            // Pass 1: 抓屏 OES → 全分辨率实时帧
            // （freezeCapture = 取证快门：这一小段不更新 full，玻璃停在上一帧，见字段注释）
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
            // 抠图 pass 的坐标系是**抓屏像素 = 屏幕像素**，而 (cx, cy) 是缓冲像素 → 先 /k 回到窗口像素
            GLES20.glUniform2f(GLES20.glGetUniformLocation(maskProg, "uCenter"),
                    winX + cx * invK, winY + (winPixelH > 0 ? winPixelH - cy * invK : winH - cy * invK));
            GLES20.glUniform2f(GLES20.glGetUniformLocation(maskProg, "uHalf"), hx, hy);   // 屏幕像素
            GLES20.glUniform1f(GLES20.glGetUniformLocation(maskProg, "uCorner"), cr);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(maskProg, "uMargin"), 3f);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
            Fbo tmp = bg; bg = bg2; bg2 = tmp;   // ping-pong
            bgTex = bg.tex;
        }

        // 波的幅度（含闸门）
        float waveAmpEff = (waveOn && !suppressWave) ? waveAmp * waveGate : 0f;

        // 思考中胶囊加载：先画进背景内容（玻璃再折射它），位置/形状都由球的几何决定
        drawCapsuleLoader();

        // Pass 2: 背景模糊（背景每帧在变，需每帧重算）
        buildBlur(bgTex);

        // ── 状态 3 / 玻璃菜单层（同一套材质，多个形状）────────────────────────
        // ── 搜索层：演示页那一套（球整趟让路）──────────────────────────────
        // 抓屏/抠图/模糊三趟照旧复用（搜索层的折射就是要"球窗之外的真实桌面"），
        // 只是合成换成 metaball-search 的 FS。
        // ★ 2026-11-07：**搜索层先判**。以前 zoneMode 在前，两种状态同时为真时
        //   画的是六区、胶囊玻璃直接消失（用户报的"搜索框自己变成图 2"）。
        //   服务侧已经加了互斥（enterSearchMode 会先退状态 3），这里再兜一层。
        if (searchMode) {
            drawSearchLayer(bgTex);
            drawLoaderOverlay();
            return;
        }
        if (zoneMode) {
            // ★ 2026-10-23：直接用**球 / 聊天卡片那颗 shader（FS_COMP）**画菜单与四区，
            //   每个形状单独一趟、预乘 alpha 混合叠上去（用户：「直接复用聊天界面那个方形」）。
            drawZoneLayerBall(bgTex);
            drawLoaderOverlay();
            return;
        }

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
        GLES20.glUniform2f(GLES20.glGetUniformLocation(compProg, "uHalf"), hx * k, hy * k);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(compProg, "uCenter"), cx, cy);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uCorner"), cr * k);
        // ★ 2026-11-06：折射是**几何**量，必须跟着形状大小走（liquid-glass 原版的逻辑就是
        //   uBand/uRefraction = P.band/P.refraction × P.master —— master 一变，形状和镜缘一起变）。
        //   球缩到屏宽 1/3 后 master 没变，镜缘却还是原来那 170/200 像素：
        //   band(170) 比球半高(121)还大 → 整颗球都落在镜缘斜坡里，位移梯度在正中翻向，
        //   屏幕上就是中间一道折痕（用户："缩小的中间都看到折痕了怪怪的"）。
        //   这里按「当前形状 ÷ 原版球」再补一个系数，镜缘重新变成"边缘一圈、内部零位移平台"。
        //   方形态/卡片比原版球大 → 系数夹到 1，聊天卡片一点没变。
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uRefraction"),
                refraction * master * shapeK * k);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uBand"),
                band * master * shapeK * k);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uScale"), master * shapeK * k);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uDome"), dome);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uAberration"), aberration);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uTopDarken"), topDarken);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uBlack0"), black0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uBlack1"), black1);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uBlackOffset"), svBlackOffset);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uEdgeBleed"), edgeBleed);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uWaveInGlass"), waveInGlass);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uPxToScreen"), invK);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uWaveHead"), waveHeadPx * k);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "iTime"), animClock());
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uWaveAmp"), waveAmpEff);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uWaveScale"), waveScale);
        // 思考中的流体圆点：uOrbRadius = 环半径(屏幕像素)，uOrbMix = 波↔圆点的交叉淡化进度
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uOrbMix"), orbMix);
        // 圆点环半径按形状等比缩（原版：环半径恒为球的一半高左右，球缩了它也跟着缩）
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uOrbRadius"),
                Math.max(4f, orbRadius * shapeK) * k);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uOrbOffset"), orbOffset);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uOrbGain"), orbGain);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uOrbRim"), orbRim);
        // 思考中第二种光效（流体发光）的开关与参数：颜色三选一、刺眼程度、丝线密度…
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uFluid"), waveMode == 3 ? 1f : 0f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uFluidRibs"), Math.max(4f, fluidRibs));
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uFluidSize"), Math.max(0.3f, fluidSize));
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uFluidHot"), Math.max(0f, fluidHot));
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uFluidColorMode"), fluidColorMode);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uFluidGain"), Math.max(0f, fluidGain));
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uFluidDash"), Math.max(0f, fluidDash));
        GLES20.glUniform3f(GLES20.glGetUniformLocation(compProg, "uFluidColor"),
                Math.max(0f, Math.min(1f, fluidColorR)),
                Math.max(0f, Math.min(1f, fluidColorG)),
                Math.max(0f, Math.min(1f, fluidColorB)));
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, loaderFbo != null ? loaderFbo.tex : loaderBlackTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(compProg, "iLoader"), 3);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uLoaderOn"),
                loaderFbo != null ? waveGate : 0f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uPanelMode"), panelMode);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uDropMode"), 0f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uDropZoom"), 1f);
        GLES20.glUniform3f(GLES20.glGetUniformLocation(compProg, "uPanelAccent"),
                panelAccentR, panelAccentG, panelAccentB);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(compProg, "uZoom"), zoom);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /**
     * 推进点阵引擎一次并把点写进 loaderVb。
     *
     * @param bw,bh 球的局部像素尺寸（引擎坐标系）
     * @param sx,sy 局部像素 → 目标画布的缩放
     * @param ox,oy 目标画布偏移
     * @return 点数
     */
    private int stepLoaderPoints(float bw, float bh, float sx, float sy, float ox, float oy) {
        if (loaderMode <= 0 || loaderProg == 0 || loaderBuf == 0 || loaderVb == null) return 0;
        if (!waveOn || waveGate <= .01f) return 0;
        long now = System.nanoTime();
        float dt = loaderLastNs == 0 ? 0f : (now - loaderLastNs) / 1e9f;
        loaderLastNs = now;
        // 有字 → 点阵只在「底部动效带」里布点（带高 = 服务给的 siriAnimBand）
        float bandFrac = 0f;
        if (loaderBandPx > 4f && bh > 8f) {
            bandFrac = Math.max(.10f, Math.min(.70f, loaderBandPx / bh));
        }
        float engineH = bandFrac > 0f ? bh * bandFrac : bh;
        loaderEngine.setMode(loaderMode);
        loaderEngine.setSpeed(loaderSpeed * Math.max(.1f, Math.min(4f, animSpeed)));
        loaderEngine.setSize(bw, engineH);
        loaderEngine.step(dt);
        int n = Math.min(loaderEngine.count(), 512);
        if (n <= 0) return 0;

        float[] p = loaderEngine.data();
        FloatBuffer fb = loaderVb;
        fb.clear();
        float sc = Math.max(.2f, Math.min(8f, loaderScale));
        float dot = Math.max(.2f, Math.min(6f, loaderDot));
        float ccx = bw / 2f, ccy = engineH / 2f;
        // 带子里布点时，整团点阵的中心要落到「球底那条带子」的中心
        float engineCy = bandFrac > 0f ? (bh - engineH * 0.5f) : bh * 0.5f;
        float k = (sx + sy) * 0.5f;
        for (int i = 0; i < n; i++) {
            int q = i * 4;
            fb.put((ccx + (p[q] - ccx) * sc) * sx + ox);
            fb.put((engineCy + (p[q + 1] - ccy) * sc + loaderDyPx) * sy + oy);
            fb.put(Math.max(2f, p[q + 2] * sc * dot * 10.4f * k));
            fb.put(p[q + 3] * waveGate);
        }
        fb.position(0);
        fb.limit(n * 4);
        return n;
    }

    /** 把 loaderVb 里的点画到当前帧缓冲（调用方负责绑定/清屏/混合）。 */
    private void drawLoaderPoints(int n, float resW, float resH) {
        GLES20.glUseProgram(loaderProg);
        int aPos = GLES20.glGetAttribLocation(loaderProg, "aPos");
        int aSize = GLES20.glGetAttribLocation(loaderProg, "aSize");
        int aAlpha = GLES20.glGetAttribLocation(loaderProg, "aAlpha");
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, loaderBuf);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, n * 16, loaderVb, GLES20.GL_DYNAMIC_DRAW);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, 0);
        GLES20.glEnableVertexAttribArray(aSize);
        GLES20.glVertexAttribPointer(aSize, 1, GLES20.GL_FLOAT, false, 16, 8);
        GLES20.glEnableVertexAttribArray(aAlpha);
        GLES20.glVertexAttribPointer(aAlpha, 1, GLES20.GL_FLOAT, false, 16, 12);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(loaderProg, "uRes"), resW, resH);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, n);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aSize);
        GLES20.glDisableVertexAttribArray(aAlpha);
    }

    /** 球的胶囊加载：点精灵先画进一张球的局部贴图，合成 pass 再把它加在黑区之上、SDF 之内。 */
    private void drawCapsuleLoader() {
        if (overlayLoader) return;                    // 搜索/六区走屏幕空间那条
        float bw = 2f * ballHxW, bh = 2f * ballHyW;
        if (bw < 8f || bh < 8f) return;
        int tw = 512;
        int th = Math.max(64, Math.round(tw * bh / bw));
        if (loaderFbo == null || loaderFbo.w != tw || loaderFbo.h != th) {
            if (loaderFbo != null) loaderFbo.release();
            loaderFbo = new Fbo(tw, th);
        }
        int n = stepLoaderPoints(bw, bh, tw / bw, th / bh, 0f, 0f);
        if (n <= 0) return;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, loaderFbo.fbo);
        GLES20.glViewport(0, 0, tw, th);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        drawLoaderPoints(n, tw, th);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /**
     * 搜索胶囊 / 六区的胶囊加载：这两层整块界面由自己的着色器画，球那趟合成不会跑，
     * 所以在整屏画完之后把同一套点阵直接叠到窗口画布上（位置由服务按界面几何给）。
     */
    private void drawLoaderOverlay() {
        if (!overlayLoader) return;
        float bw = Math.max(8f, ovlHx * 2f), bh = Math.max(8f, ovlHy * 2f);
        int n = stepLoaderPoints(bw, bh, 1f, 1f, ovlCx - ovlHx, ovlCy - ovlHy);
        if (n <= 0) return;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, winW, winH);
        drawLoaderPoints(n, winW, winH);
    }

    // root 直采帧 → GL 2D 纹理
    private void uploadRootFrame() {
        byte[] f = readyFrame.getAndSet(null);
        if (f == null) return;
        if (freezeCapture) { framePool.offer(f); return; }   // 取证快门：丢掉这一帧，纹理保持上一帧
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
    /** GL 线程内释放全部离屏缓冲（context 还在的时候调用；换上下文时只能置 null）。 */
    private void releaseFbos() {
        if (full != null) { full.release(); full = null; }
        if (half != null) { half.release(); half = null; }
        if (half2 != null) { half2.release(); half2 = null; }
        if (bg != null) { bg.release(); bg = null; }
        if (bg2 != null) { bg2.release(); bg2 = null; }
        if (loaderFbo != null) { loaderFbo.release(); loaderFbo = null; }
    }

    private void ensureFbos() {
        if (full == null) {
            int cw = capW > 0 ? capW : screenW;
            int ch = capH > 0 ? capH : screenH;
            full = new Fbo(cw, ch);
            half = new Fbo(Math.max(1, cw >> 1), Math.max(1, ch >> 1));
            half2 = new Fbo(Math.max(1, cw >> 1), Math.max(1, ch >> 1));
            bg = new Fbo(cw, ch);
            bg2 = new Fbo(cw, ch);
        }
    }

    /**
     * 把 Siri 波画进背景纹理（屏幕空间）→ 返回"背景 + 波"的新纹理。
     *
     * 为什么要多这一趟：老的画法是波直接加在合成结果上（`col += wave`），它永远是**贴在玻璃表面**
     * 的一层亮条 —— 不参与透镜位移、也没有色散。用户要的是"像背景一样被折射变糊"，
     * 那就只能先把波放进背景里，再让后面那条既有的折射/色散/模糊链去处理它。
     *
     * 锚点用 uWaveNy（ny 单位，+1 球顶 / -1 球底），所以波永远贴着球底，跟文字多少无关。
     */
    private void buildBlur(int srcTex) {
        useProgram(blurProg);
        bindFullscreen(blurProg);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blurProg, "iTex"), 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        // 玻璃面板（菜单 / 四区 / 卡片）走自己的磨砂力度：球是 0.3（几乎不糊，靠透镜）
        // 但一块 260dp 的面板要是不糊，底下的桌面文字会和面板文字叠在一起（用户：太丑）。
        // ★ 只影响 zoneMode 这一趟，退出后球还是用户自己那个 float_params.blur。
        float blur = zoneMode ? zoneBlur : this.blur;

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

        void release() {
            if (fbo != 0) {
                int[] f = { fbo };
                GLES20.glDeleteFramebuffers(1, f, 0);
                fbo = 0;
            }
            if (tex != 0) {
                int[] t = { tex };
                GLES20.glDeleteTextures(1, t, 0);
                tex = 0;
            }
            w = 0; h = 0;
        }
    }
}
