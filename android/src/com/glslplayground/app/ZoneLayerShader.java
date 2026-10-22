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
        // ── ★1 透镜位移：和球同一条曲线（边缘最强、往里衰减 band）──────────────
        "  float band = max(min(bBest.x, bBest.y) * max(uBandRatio, 0.05), 1.0);\n" +
        "  float sd   = min(d, 0.0);\n" +
        "  float xr   = clamp(1.0 + sd / band, 0.0, 1.0);\n" +
        "  float amp  = circleMap(xr) * uRefract;\n" +
        // 方向：SDF 梯度 + 一点"球面径向"，扁形状也不会出现两侧对称的假折射
        "  vec2 radial = normalize(vec2(lp.x, bBest.x * lp.y / max(bBest.y, 0.001)) + 1e-5);\n" +
        "  vec2 gr = normalize(mix(gBest, radial, clamp(uDome, 0.0, 1.0) * 0.35));\n" +
        "  vec2 base = (uWinPos + p * uPxToScreen) / uScreen;\n" +
        "  vec2 off  = gr * amp / uScreen;\n" +
        // ★2 色散：越靠边越强（球上那圈彩虹边）
        "  float rimness = clamp(amp / max(uRefract, 1.0), 0.0, 1.0);\n" +
        "  float ab = uAb * rimness * clamp(lp.x * lp.y / max(bBest.x * bBest.y, 1.0), -1.0, 1.0);\n" +
        "  vec3 refr;\n" +
        "  refr.r = bg(p + gr * amp * (1.0 + ab)).r;\n" +
        "  refr.g = bg(p + gr * amp).g;\n" +
        "  refr.b = bg(p + gr * amp * (1.0 - ab * 1.4)).b;\n" +
        // 磨砂：面板/圆区是"一片玻璃"，底下必须糊掉（否则桌面上的字和面板上的字叠在一起），
        // 但留一点清晰度，才不会变成一块死板的毛玻璃 —— 折射位移仍然照做，玻璃才有厚度。
        "  refr = mix(refr, bgFrost(p + gr * amp), clamp(uFrost, 0.0, 1.0));\n" +
        // 和球同一句冷调（玻璃对背景的"轻微染色"）
        "  refr = mix(refr, refr * vec3(0.94, 0.96, 1.0) + vec3(0.010, 0.012, 0.020), 0.22);\n" +
        // 贴边处再压一点，玻璃的"厚度"感（球上 innerEdge 那句）
        "  float innerEdge = smoothstep(0.0, -18.0 * uDpr, d);\n" +
        "  refr *= mix(0.72, 1.0, innerEdge);\n" +
        "\n" +
        // ── ★3 黑顶 ────────────────────────────────────────────────────────────
        // ★ 2026-10-23 二修：用户看到"一层锋利的黑色长方形"。原因不是形状，是这条曲线：
        //   smoothstep(cap0=-1, cap1=0.78) 在顶部 15% 里就烧掉了 90% 的暗度 ——
        //   于是面板顶上出现一条**边是直的**（还盖住了圆角）、下沿有明显台阶的黑色带。
        //   现在改成：cap = pow(1 - top, 1.7)，从上往下**连续**衰减；再乘
        //   smoothstep(0, 26dp, -d) 让贴边的那一圈变薄（玻璃边缘本来就薄，不该压黑），
        //   圆角处自然跟着形状收，不再有"长方形"的直边。
        "  float ny = clamp(lp.y / max(bBest.y, 1.0), -1.0, 1.0);\n" +
        "  float topN = clamp((ny + 1.0) * 0.5, 0.0, 1.0);\n" +
        "  float cap = pow(1.0 - topN, uCapPow);\n" +
        "  cap *= smoothstep(0.0, 26.0 * uDpr, -d);\n" +

        "  vec3 glass = mix(refr, vec3(0.010, 0.012, 0.020), cap * uTopDarken);\n" +
        // 面板/圆区通用的一点烟熏：保证白字永远有底（亮桌面上也不会"字浮在空气里"）
        "  glass = mix(glass, glass * 0.18 + vec3(0.006), clamp(uScrim + darkBest * 0.30, 0.0, 1.0));\n" +
        "\n" +
        // ── ★4a 球顶两团柔光（球上那两块反光板，液态玻璃最像球的一笔）─────────
        "  vec2 gq = vec2(lp.x / max(bBest.x, 1.0), lp.y / max(bBest.y, 1.0));\n" +
        "  vec2 s1 = gq - vec2(-0.40, -0.58);\n" +
        "  vec2 s2 = gq - vec2( 0.30, -0.50);\n" +
        "  float spec = exp(-dot(s1, s1) * 30.0) * 0.55 + exp(-dot(s2, s2) * 55.0) * 0.30;\n" +
        "  glass += vec3(0.88, 0.91, 1.0) * spec * uGlow * (1.0 - 0.35 * darkBest);\n" +
        // ── ★4b 底缘亮弧：球底的"厚玻璃边"（折射在这里最强，也是最亮的一圈）────
        "  float botRim = smoothstep(-18.0 * uDpr, -3.0 * uDpr, d) * smoothstep(0.10, 0.80, ny);\n" +
        "  glass += vec3(0.85, 0.90, 1.0) * botRim * (0.10 + 0.55 * uEdgeBleed) * (1.0 + 0.6 * actBest);\n" +
        // ── ★4c 赤道线：黑顶结束的那一条亮线（球上 eqLine）─────────────────────
        "  float eq = exp(-pow((ny - uCap1) / 0.10, 2.0));\n" +
        "  float horiz = 1.0 - abs(lp.x) / max(bBest.x, 1.0) * 0.35;\n" +
        "  glass += vec3(0.95, 0.97, 1.0) * eq * horiz * uEdgeBleed * (1.0 + 1.4 * actBest);\n" +
        "\n" +
        // ── 边缘高光：贴边的窄亮带（左下暖、右上冷，和球同一个 kdir）──────────
        "  float qd  = -d;\n" +
        "  float hw  = (2.0 + 1.6 * actBest) * uDpr;\n" +
        "  float aaq = 1.0;\n" +
        "  float band2 = (1.0 - clamp(qd / hw, 0.0, 1.0))\n" +
        "              * clamp(qd / aaq + 0.5, 0.0, 1.0)\n" +
        "              * clamp((hw - qd) / aaq + 0.5, 0.0, 1.0);\n" +
        "  vec2 kdir = vec2(0.7071, 0.7071);\n" +
        "  float key  = band2 * clamp((dot(kdir, gr) - 0.52) / 0.48, 0.0, 1.0);\n" +
        "  float fill = band2 * clamp((dot(-kdir, gr) - 0.52) / 0.48, 0.0, 1.0);\n" +
        "  key  = key  / (1.0 + (1.0 - key)  * 8.0);\n" +
        "  fill = fill / (1.0 + (1.0 - fill) * 8.0);\n" +
        "  glass += (key + fill) * uHlAmt * (1.0 + 0.9 * actBest);\n" +
        // 选中：内侧整圈微光（"这个格子在回应手指"）
        "  float inner = clamp(1.0 + d / (26.0 * uDpr), 0.0, 1.0);\n" +
        "  glass += actBest * inner * (0.05 + 0.10 * uGlow);\n" +
        // ★5 壳：贴边最外 1.5px 的细亮线（球的 shell）
        "  float shell = smoothstep(2.2 * uDpr, 0.0, qd);\n" +
        "  glass += vec3(0.10, 0.11, 0.13) * shell * 0.6;\n" +
        "\n" +
        "  float sh = (d > 0.0) ? exp(-d / (26.0 * uDpr)) * 0.20 : 0.0;\n" +
        "  float aa = smoothstep(1.5, -1.5, d);\n" +
        "  float alpha = aa + sh * (1.0 - aa);\n" +
        "  gl_FragColor = vec4(glass * aa, alpha);\n" +
        "}\n" +
        "\n";
}
