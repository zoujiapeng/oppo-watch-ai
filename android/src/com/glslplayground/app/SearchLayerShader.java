package com.glslplayground.app;

/**
 * 搜索层着色器 —— **metaball-search.html 里那段 FRAG 的原样移植**。
 *
 * 用户要求原话：「把 Metaball→Search 里的东西原封不动地搬出来」「复用那个页面的动画和效果，
 * 而不是球的变体」。所以这一份不是"照灵感重写"，而是从 `metaball-search.html` 的
 * `const FRAG = `…`` 里**整段抄过来**，只改了四处必须改的地方（都在下面标了 ★）：
 *
 *   ★1 `#version 300 es` → 去掉（本机是 GLES2 上下文），`out vec4 outColor` → `gl_FragColor`；
 *   ★2 `bgcol()` 从"内嵌壁纸纹理 + textureLod 取 mip"换成"本机抓屏的两张纹理"：
 *      清晰那张 = 演示页 lod≈0，模糊那张 = 演示页的大 lod；其余（cover-fit UV、软阴影、
 *      折射位移、8 抽样圆盘、色差、edge_bleed、双光高光、crush 压暗）**一个字没动**；
 *   ★3 `fwidth()` 在 GLES2 要 GL_OES_standard_derivatives（不是所有机器都开）→ 换成固定
 *      1.5 物理像素 AA（球体那条管线本来就是这么做的，观感一致）；
 *   ★4 输出：演示页是整屏 demo，形状外画壁纸；我们是悬浮窗，**窗外必须透明** ——
 *      形状内输出玻璃、形状外只留演示页那层软阴影（预乘 alpha），并加了一行远处像素早退。
 *
 * 要改手感/参数请改 Java 侧那几个 uniform（见 FloatingBallService 的 SQ_* 常量），
 * 不要在这里动公式 —— 这一份的价值就是"跟演示页逐行对得上"。
 */
final class SearchLayerShader {

    static final String FRAG =
        "precision highp float;\n" +
        "uniform vec2  uRes;\n" +
        "uniform vec2  uScreen;   // 屏幕尺寸\n" +
        "uniform vec2  uWinPos;   // 窗口左上角（屏幕像素，y 向下）\n" +
        "uniform sampler2D iBg;   // 清晰抓屏（已排除本窗口自身）\n" +
        "uniform sampler2D iBlur; // 模糊抓屏\n" +
        "uniform float uPxToScreen; // 绘制缓冲像素 → 屏幕像素（降分辨率渲染时 > 1）\n" +
        "uniform vec4  uBlob;   // cx, cy, halfX, halfY (px, y 向下)\n" +
        "uniform float uEdge;   // 顶部黑边高度\n" +
        "uniform float uK;      // 融合半径\n" +
        "uniform float uHeight, uRefract, uHlAmt, uAb, uDpr, uCont;\n" +
        "uniform float uDark;   // 液滴材质 1=贴边黑玻璃 0=落地浅磨砂\n" +
        "uniform float uValid;\n" +
        "\n" +
        "// ---------- 背景：直接用本机抓屏（清晰 iBg / 模糊 iBlur 两张，按演示页的 blur 半径阈值切换）----------\n" +
        "// 演示页是内嵌壁纸纹理 + textureLod 取 mip；安卓这边球本来就在抓屏，两张纹理正好对应\n" +
        "// \"清晰\"与\"模糊\"，语义与演示页的 lod 一致（blurR 小=清晰、大=模糊）。\n" +
        "vec3 bgcol(vec2 p, float blurR){\n" +
        "  vec2 uv = (uWinPos + p * uPxToScreen) / uScreen;\n" +
        "  return (blurR < 8.0 ? texture2D(iBg, uv) : texture2D(iBlur, uv)).rgb;\n" +
        "}\n" +
        "\n" +
        "// ---------- rounded-box SDF + 解析梯度 ----------\n" +
        "// supercircle_sdf 的圆角分支(cornerFlags=圆角);圆/胶囊/圆角矩形同一原语\n" +
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
        "// ---------- 苹果 sdf_union:梯度感知 smooth min ----------\n" +
        "// QuartzCore ShaderUtils_::sdf_union 逐行翻译\n" +
        "vec4 sdfUnion(vec4 a, vec4 b, float k){\n" +
        "  if (b.w == 0.0) b = vec4(10000.0, 0.0, 0.0, 0.0);     // half 0x70E2\n" +
        "  float kEff = k * clamp(0.5 - 0.5 * dot(a.yz, b.yz), 0.0, 1.0) + 1e-4;\n" +
        "  float h = clamp(0.5 + 0.5 * (b.x - a.x) / kEff, 0.0, 1.0);\n" +
        "  float d = mix(b.x, a.x, h) - kEff * h * (1.0 - h);    // IQ 多项式 smin\n" +
        "  vec2  g = mix(b.yz, a.yz, h);                         // 梯度同步混合\n" +
        "  return vec4(d, normalize(g + vec2(1e-5)), 1.0);\n" +
        "}\n" +
        "\n" +
        "void main(){\n" +
        "  vec2 p = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y);  // y 向下,对齐 UI 坐标\n" +
        "\n" +
        "  vec4 bar  = vec4(p.y - uEdge, 0.0, 1.0, 1.0);            // 顶部黑边 = 半平面\n" +
        "  vec4 blob = sdRoundBox(p, uBlob.xy, uBlob.zw, min(uBlob.z, uBlob.w));\n" +
        "  blob.w = uValid;\n" +
        "  vec4 s = sdfUnion(bar, blob, uK);\n" +
        "  float d = s.x;  vec2 g = s.yz;\n" +
        "\n" +
        "    // 悬浮层加的一行：离形状太远的像素直接丢掉（软阴影到这里只剩 1%，看不见）\n" +
        "  if (d > 96.0 * uDpr) { gl_FragColor = vec4(0.0); return; }\n" +
        "\n" +
        "  float sh = (d > 0.0) ? exp(-d / 30.0) * 0.22 : 0.0;\n" +
        "  vec3 col = bgcol(p, 0.0) * (1.0 - sh);\n" +
        "\n" +
        "  // ---------- 玻璃 = 折射 + 高光,无 face color ----------\n" +
        "  // 公式同 QuartzCore sdf_glass_displacement/highlight,参数取 z1han siri27 标定\n" +
        "  float wb = uValid * clamp(0.5 + 0.5 * (bar.x - blob.x) / 24.0, 0.0, 1.0);\n" +
        "  float darkAmt = mix(1.0, uDark, wb);\n" +
        "\n" +
        "  // 梯度向\"中心径向\"微混 8%,透镜更圆润(只对液滴,bar 区域 wb≈0)\n" +
        "  vec2 lp = p - uBlob.xy;\n" +
        "  vec2 radial = normalize(vec2(lp.x, uBlob.z * lp.y / max(uBlob.w, 0.001)) + 1e-5);\n" +
        "  vec2 gr = normalize(mix(g, radial, 0.08 * wb));\n" +
        "\n" +
        "  // 圆弧剖面 (curvature=1),折射量为负 → 边缘把外侧内容\"拉进来\"(放大镜感)\n" +
        "  float t   = clamp(-d / uHeight, 0.0, 1.0);\n" +
        "  float mag = 1.0 - sqrt(max(1.0 - (1.0 - t) * (1.0 - t), 0.0));\n" +
        "  vec2  dsp = -uRefract * mag * gr;\n" +
        "  // 边缘环:近清晰采样 + 色差\n" +
        "  vec3 sharp;\n" +
        "  sharp.r = bgcol(p + dsp * (1.0 - uAb), 3.0).r;\n" +
        "  sharp.g = bgcol(p + dsp, 3.0).g;\n" +
        "  sharp.b = bgcol(p + dsp * (1.0 + uAb), 3.0).b;\n" +
        "  // 内部:8 抽样圆盘 + 中等 mip 联合模糊——纯 mip 三线性有块感,圆盘把它抹匀\n" +
        "  float br = mix(14.0, 60.0, clamp(-d / (50.0 * uDpr), 0.0, 1.0)) * uDpr;\n" +
        "  vec3 soft = bgcol(p + dsp, br * 0.4) * 0.2;\n" +
        "  for (int i = 0; i < 8; i++) {\n" +
        "    float a = 0.7854 * float(i);\n" +
        "    soft += bgcol(p + dsp + vec2(cos(a), sin(a)) * br * 0.7, br * 0.4) * 0.1;\n" +
        "  }\n" +
        "  float deep = clamp(-d / (40.0 * uDpr), 0.0, 1.0);   // 越深入越用模糊\n" +
        "  vec3 refr = mix(sharp, soft, deep);\n" +
        "\n" +
        "  // face 压暗 = 乘法系数的垂直渐变(真机截图逐像素实测标定):\n" +
        "  // 顶 ×0.03(近纯黑) 中 ×0.19 底 ×0.34,线性 m ≈ 0.20 + 0.40·ny;\n" +
        "  // 用乘法不残留底图对比(不发花),用渐变还原\"上黑下透\"\n" +
        "  float ny = (p.y - uBlob.y) / max(uBlob.w, 1.0);   // -1=顶 +1=底\n" +
        "  float m  = clamp(0.20 + 0.40 * ny, 0.0, 1.0);\n" +
        "  float crush = mix(1.0, m, uCont * wb);\n" +
        "  refr = refr * crush + vec3(0.008, 0.010, 0.014) * uCont * wb;\n" +
        "\n" +
        "  // edge_bleed(IR 解码):亮背景从边缘渗入玻璃内侧——圆剖面位移 + mip 模糊\n" +
        "  // + 贴边距离带 + 亮度四次方门控(背景越亮渗越多,暗处几乎不渗)\n" +
        "  float xb  = clamp(-d / (10.0 * uDpr), 0.0, 1.0);\n" +
        "  float dbl = 24.0 * uDpr * (1.0 - sqrt(xb * (2.0 - xb)));\n" +
        "  vec3 bleed = bgcol(p + gr * dbl, 22.0);\n" +
        "  float wbd  = clamp((d + 26.0 * uDpr) / (20.0 * uDpr), 0.0, 1.0);\n" +
        "  float blum = dot(bleed, vec3(0.2125, 0.7154, 0.0721));\n" +
        "  float bm   = pow(clamp(blum * 1.2, 0.0, 1.0), 2.0) * wbd;\n" +
        "  refr = mix(refr, bleed, bm * bm * 0.85);\n" +
        "\n" +
        "  vec3 glass = mix(refr, refr * 0.10 + vec3(0.016), darkAmt); // 落地=纯玻璃,贴边=黑玻璃\n" +
        "\n" +
        "  // 高光:细带 2.2px,key 45° + fill 225° 对角双光,锐掩码 cut 0.52,压缩 norm 8\n" +
        "  float qd  = -d;\n" +
        "  float hw  = 2.2 * uDpr;\n" +
        "  float aaq = 1.0;   // 演示页 fwidth(qd)；GLES2 无导数 → 固定 1px\n" +
        "  float band = (1.0 - clamp(qd / hw, 0.0, 1.0))\n" +
        "             * clamp(qd / aaq + 0.5, 0.0, 1.0)\n" +
        "             * clamp((hw - qd) / aaq + 0.5, 0.0, 1.0);\n" +
        "  vec2 kdir = vec2(0.7071, 0.7071);\n" +
        "  float key  = band * clamp((dot(kdir, gr) - 0.52) / 0.48, 0.0, 1.0);\n" +
        "  float fill = band * clamp((dot(-kdir, gr) - 0.52) / 0.48, 0.0, 1.0);\n" +
        "  key  = key  / (1.0 + (1.0 - key)  * 8.0);\n" +
        "  fill = fill / (1.0 + (1.0 - fill) * 8.0);\n" +
        "  glass += (key + fill) * uHlAmt * mix(1.0, 0.4, darkAmt);\n" +
        "\n" +
        "  // ── 悬浮层输出（与演示页唯一的区别）──────────────────────────────────\n" +
        "  // 演示页是整屏 demo：形状外画的是壁纸。我们是**悬浮窗**，窗外必须是透明的，\n" +
        "  // 所以：形状内 = 玻璃（不透明），形状外 = 只留演示页那层软阴影（黑色、按 sh 给 alpha）。\n" +
        "  // 输出预乘 alpha，和球体那条管线一致。\n" +
        "  float aa = smoothstep(1.5, -1.5, d);\n" +
        "  float alpha = aa + sh * (1.0 - aa);\n" +
        "  gl_FragColor = vec4(glass * aa, alpha);\n" +
        "}\n" +
        "\n";
}
