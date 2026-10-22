package com.glslplayground.app;

/**
 * 状态 3 / 玻璃菜单层的着色器 —— **就是球那颗玻璃本身**。
 *
 * 2026-10-23 第二次重做：上一版只是"抄了球的几个 uniform"，折射那一段其实是
 * metaball-search 胶囊的圆柱公式（`mag = 1-sqrt(1-(1-t)^2)`，`uHeight=18px`）——
 * 18px 的折射带放在 200px 的圆/面板上，等于只有最外面一圈 18 像素被掰了一下，
 * 里面是**一块平的半透明塑料**（用户原话：「太丑了，我要的是跟球一样的液态玻璃的实时折射」）。
 *
 * 这一版把 {@link GlassRenderer} 里画球那段（FS_COMP）的**光学公式原样搬过来**：
 *   ★1 透镜位移：`circleMap(1 + sd/band) * refract` —— 边缘最强、往里按 band 衰减，
 *      就是球"边缘把桌面掰弯、中心基本是清晰玻璃"的那条曲线；
 *   ★2 色散：位移量本身带上 `lp.x*lp.y/(hx*hy)` 的权重，边缘出现球上那圈彩边；
 *   ★3 黑顶：从球顶往下按 `uCap0 → uCap1` 渐隐（球的黑区同一套 smoothstep），
 *      面板用它垫住白字，圆区只垫上半圈；
 *   ★4 赤道线 / 玻璃壳：黑顶结束那一条亮线 + 贴边的壳光，都是球上那两条；
 *   ★5 每个形状自带 (act, dark, corner)：act = 手指悬停高亮，dark = 玻璃里的烟熏浓度。
 *
 * 坐标约定：`p = (gl_FragCoord.x, uRes.y - gl_FragCoord.y)` = 窗口内**画布像素、y 向下**；
 * 形状数组由服务侧给**屏幕像素**，渲染器先减窗口位置再乘 k（见 GlassRenderer.drawZoneLayer）。
 * 采样屏幕 UV 用 `uPxToScreen` 换回屏幕像素，和球那条 `screenUv()` 完全一致。
 */
final class ZoneLayerShader {

    static final String FRAG =
        "precision highp float;\n" +
        "uniform vec2  uRes;\n" +
        "uniform vec2  uScreen;\n" +
        "uniform vec2  uWinPos;\n" +
        "uniform sampler2D iBg;\n" +
        "uniform sampler2D iBlur;\n" +
        "uniform float uPxToScreen;\n" +
        "uniform float uK;\n" +
        // 材质：折射强度(px) / 折射带（占形状短半轴的比例） / 球面感 / 色散 / 高光 / 烟熏
        "uniform float uRefract, uBandRatio, uDome, uAb, uHlAmt, uCont, uValid, uDpr;\n" +
        // 黑顶：ny 从 -1(顶) 到 +1(底)，cap0 处最黑、cap1 处完全透明
        "uniform float uTopDarken, uCap0, uCap1, uEdgeBleed, uScrim, uGlow, uFrost;\n" +
        "uniform float uEdgeShade, uShadow;   // 贴边压暗(圆区用) / 外阴影\n" +
        "uniform float uEdgeShade2;           // 上缘内阴影强度（面板 1.0）\n" +
        "uniform float uDesat;                // 背景去饱和（磨砂玻璃把颜色洗淡）\n" +
        "uniform float uCapPow;   // 黑顶衰减指数（越大越只在最顶上黑）\n" +
        "uniform vec4  uS0, uS1, uS2, uS3, uS4;   // cx,cy,halfX,halfY\n" +
        "uniform vec4  uM0, uM1, uM2, uM3, uM4;   // act, dark, cornerRadius, enabled\n" +
        "\n" +
        "vec2 bgUv(vec2 q){ return (uWinPos + q * uPxToScreen) / uScreen; }\n" +
        "vec3 bg(vec2 q){ return texture2D(iBg, bgUv(q)).rgb; }\n" +
        "vec3 bgFrost(vec2 q){ return texture2D(iBlur, bgUv(q)).rgb; }\n" +
        "\n" +
        "float circleMap(float x){ return 1.0 - sqrt(max(1.0 - x * x, 0.0)); }\n" +
        "\n" +
        "vec4 sdRoundBox(vec2 p, vec2 c, vec2 b, float r){\n" +
        "  vec2 lp = p - c;\n" +
        "  vec2 q  = abs(lp) - b + r;\n" +
        "  vec2 mq = max(q, vec2(0.0));\n" +
        "  float dOut = length(mq);\n" +
        "  float d = dOut + min(max(q.x, q.y), 0.0) - r;\n" +
        "  vec2 grad = (dOut > 1e-4) ? (mq / dOut) * sign(lp)\n" +
        "            : ((q.x > q.y) ? vec2(sign(lp.x), 0.0) : vec2(0.0, sign(lp.y)));\n" +
        "  return vec4(d, grad, 1.0);\n" +
        "}\n" +
        "\n" +
        "void consider(vec2 p, vec4 s, vec4 m,\n" +
        "              inout float dBest, inout vec2 gBest, inout float actBest,\n" +
        "              inout float darkBest, inout vec2 cBest, inout vec2 bBest){\n" +
        "  if (m.w < 0.5) return;\n" +
        "  vec4 r = sdRoundBox(p, s.xy, s.zw, m.z);\n" +
        "  if (r.x < dBest) {\n" +
        "    dBest = r.x; gBest = r.yz; actBest = m.x; darkBest = m.y;\n" +
        "    cBest = s.xy; bBest = s.zw;\n" +
        "  }\n" +
        "}\n" +
        "\n" +
        "void main(){\n" +
        "  vec2 p = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y);\n" +
        "\n" +
        "  float dBest = 1e9;\n" +
        "  vec2  gBest = vec2(0.0);\n" +
        "  float actBest = 0.0, darkBest = 0.0;\n" +
        "  vec2  cBest = vec2(0.0), bBest = vec2(1.0);\n" +
        "  consider(p, uS0, uM0, dBest, gBest, actBest, darkBest, cBest, bBest);\n" +
        "  consider(p, uS1, uM1, dBest, gBest, actBest, darkBest, cBest, bBest);\n" +
        "  consider(p, uS2, uM2, dBest, gBest, actBest, darkBest, cBest, bBest);\n" +
        "  consider(p, uS3, uM3, dBest, gBest, actBest, darkBest, cBest, bBest);\n" +
        "  consider(p, uS4, uM4, dBest, gBest, actBest, darkBest, cBest, bBest);\n" +
        "\n" +
        "  if (dBest > 96.0 * uDpr) { gl_FragColor = vec4(0.0); return; }\n" +
        "\n" +
        "  float d = dBest;\n" +
        "  vec2  lp = p - cBest;\n" +
        "\n" +
        // ── ★1 透镜：**弯月面剖面**（边缘处位移为 0 → 不会出现"贴边一圈"的暗/亮环）──
        //   amp(u) = refract · sin(π·u^0.6)^1.15，u = 深度/band：
        //     u=0（贴边）   → 0     ← 和玻璃外面的画面对齐，边界不跳变
        //     u≈0.35        → 峰值  ← 玻璃"最厚"的那一圈，背景被明显放大/拖弯
        //     u=1（更里面） → 0     ← 中心是平的、清晰的
        //   这就是真实玻璃片边缘的样子：一圈厚边 + 中间平的。
        "  float band = max(min(bBest.x, bBest.y) * max(uBandRatio, 0.05), 1.0);\n" +
        "  float sd   = min(d, 0.0);\n" +
        "  float u    = clamp(-sd / band, 0.0, 1.0);\n" +
        // sin²：两端导数为 0 → 边缘和"平的中心"之间没有折痕（用 sin 会有可见的棱线）
        "  float s1p  = sin(3.14159265 * pow(u, 0.70));\n" +
        "  float amp  = uRefract * s1p * s1p;\n" +
        // 方向：SDF 梯度 + 一点"球面径向"
        "  vec2 radial = normalize(vec2(lp.x, bBest.x * lp.y / max(bBest.y, 0.001)) + 1e-5);\n" +
        "  vec2 gr = normalize(mix(gBest, radial, clamp(uDome, 0.0, 1.0) * 0.75));\n" +
        "  vec2 base = (uWinPos + p * uPxToScreen) / uScreen;\n" +
        // 折射：色散强度跟着"坡度"走（越靠近最厚那圈，彩边越明显）
        "  float abAmt = uAb * clamp(abs(s1p * cos(3.14159265 * pow(u, 0.70))) * 2.2, 0.0, 1.0);\n" +
        "  vec3 refr;\n" +
        "  refr.r = bg(p + gr * amp * (1.0 + abAmt)).r;\n" +
        "  refr.g = bg(p + gr * amp).g;\n" +
        "  refr.b = bg(p + gr * amp * (1.0 - abAmt * 1.25)).b;\n" +
        // 磨砂：面板是"一片玻璃"，底下要糊；但位移仍然照做 → 有厚度、不是死板毛玻璃
        "  refr = mix(refr, bgFrost(p + gr * amp), clamp(uFrost, 0.0, 1.0));\n" +
        // 玻璃的冷调 + **去饱和**：真实磨砂玻璃会把背景的颜色洗淡、提一点亮，
        // 不去饱和的话，桌面偏绿的壁纸会直接把面板染成脏绿色（看着像塑料不像玻璃）
        "  float lumr = dot(refr, vec3(0.2126, 0.7152, 0.0722));\n" +
        "  refr = mix(refr, vec3(lumr), clamp(uDesat, 0.0, 1.0));\n" +
        "  refr = mix(refr, refr * vec3(0.96, 0.98, 1.0) + vec3(0.035, 0.038, 0.048), 0.34);\n" +
        "  refr *= mix(1.0, mix(0.72, 1.0, smoothstep(0.0, -60.0 * uDpr, d)), uEdgeShade);\n" +
        "\n" +
        // ── ★2 黑顶（面板为 0）：保留给圆区，面板不压任何整块暗度 ──────────────
        "  float ny = clamp(lp.y / max(bBest.y, 1.0), -1.0, 1.0);\n" +
        "  float topN = clamp((ny + 1.0) * 0.5, 0.0, 1.0);\n" +
        "  float cap = pow(1.0 - topN, uCapPow);\n" +
        "  cap *= smoothstep(0.0, 26.0 * uDpr, -d);\n" +
        "  vec3 glass = mix(refr, vec3(0.010, 0.012, 0.020), cap * uTopDarken);\n" +
        // 均匀烟熏（不是渐变）：给白字一个稳定的底
        "  glass = mix(glass, glass * 0.20 + vec3(0.006), clamp(uScrim + darkBest * 0.26, 0.0, 1.0));\n" +
        "\n" +
        // ── ★3 真实玻璃的四道光（全部**贴着边**、跟着形状走，不是横向色块）────
        "  vec2 kdir = vec2(0.62, 0.78);          // 光从左上来（屏幕 y 向下）\n" +
        "  float qd  = -d;                        // 离边多远（内为正）\n" +
        "  float nearEdge = smoothstep(0.0, 9.0 * uDpr, qd);   // 贴边 9dp 内\n" +
        // 3a 亮边：最外 1.6dp 的一条细亮线，朝光那侧最亮
        "  float rim   = smoothstep(1.6 * uDpr, 0.0, qd);\n" +
        "  float lit   = clamp(0.35 + 0.65 * dot(-gr, kdir), 0.0, 1.0);\n" +
        "  glass += vec3(0.96, 0.98, 1.0) * rim * lit * (0.55 + 0.55 * uHlAmt);\n" +
        // 3b 内侧高光雾：贴边一圈柔光，让边看起来是"圆过去的"而不是刀切
        "  float halo  = (1.0 - nearEdge) * (0.55 + 0.45 * lit);\n" +
        "  glass += vec3(0.80, 0.86, 0.95) * halo * 0.10 * uHlAmt;\n" +
        // 3c 上缘内阴影：只在"朝上"的边（gr.y < 0）出现，厚度感就靠它
        "  float upness = clamp(-gr.y, 0.0, 1.0);\n" +
        "  glass *= 1.0 - nearEdge * upness * 0.30 * uEdgeShade2;\n" +
        // 3d 下缘反光：玻璃底部那一条"接光"的亮弧（默认很淡，面板给 1.0）
        "  float downness = clamp(gr.y, 0.0, 1.0);\n" +
        "  glass += vec3(0.88, 0.92, 1.0) * nearEdge * downness * 0.16 * uEdgeBleed;\n" +
        // 选中：内侧整圈微光
        "  float inner = clamp(1.0 + d / (26.0 * uDpr), 0.0, 1.0);\n" +
        "  glass += actBest * inner * (0.05 + 0.10 * uGlow);\n" +
        // 球顶两团柔光（只给圆区用，uGlow=0 时没有）
        "  vec2 gq = vec2(lp.x / max(bBest.x, 1.0), lp.y / max(bBest.y, 1.0));\n" +
        "  vec2 s1 = gq - vec2(-0.40, -0.58);\n" +
        "  vec2 s2 = gq - vec2( 0.30, -0.50);\n" +
        "  float spec = exp(-dot(s1, s1) * 30.0) * 0.55 + exp(-dot(s2, s2) * 55.0) * 0.30;\n" +
        "  glass += vec3(0.88, 0.91, 1.0) * spec * uGlow * (1.0 - 0.35 * darkBest);\n" +
        // 赤道线（只在有黑顶时）
        "  float eq = exp(-pow((ny - uCap1) / 0.10, 2.0));\n" +
        "  float horiz = 1.0 - abs(lp.x) / max(bBest.x, 1.0) * 0.35;\n" +
        "  glass += vec3(0.95, 0.97, 1.0) * eq * horiz * uEdgeBleed * 0.8\n" +
        "         * clamp(uTopDarken * 2.0, 0.0, 1.0);\n" +
        "\n" +
        "  float sh = (d > 0.0) ? exp(-d / (46.0 * uDpr)) * 0.20 * uShadow : 0.0;\n" +
        "  float aa = smoothstep(1.5, -1.5, d);\n" +
        "  float alpha = aa + sh * (1.0 - aa);\n" +
        "  gl_FragColor = vec4(glass * aa, alpha);\n" +
        "}\n" +
        "\n";
}
