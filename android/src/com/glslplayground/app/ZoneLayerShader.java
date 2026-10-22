package com.glslplayground.app;

/**
 * 状态 3 / 玻璃菜单层的着色器 —— **材质与球、搜索胶囊完全同一套**。
 *
 * 为什么单独一份而不是改 SearchLayerShader：那一份是 metaball-search 演示页的逐行移植
 * （用户明确要求"原样搬"，别动公式）。状态 3 要画的是 **N 个分离的圆角形状**
 * （2×2 四个圆区 + 一格列表面板），SDF 从"单个 blob"换成"取最小距离的那个形状"，
 * 但折射 / 色差 / edge_bleed / 双光高光 / 压暗 这些公式一个字没改 ——
 * 所以四个圆区、工作目录面板、会话列表**看起来就是同一颗球被切开**，
 * 而不是另一套 UI 风格（用户原话：「实际上我要的是全部都是和原本的球一样的液态玻璃」）。
 *
 * 差异只有三处：
 *   ★1 形状：5 个圆角矩形（unrolled，GLES2 不赌动态数组下标），取最近的那个画材质；
 *   ★2 每个形状自带 (act, dark, radius)：act = 手指悬停/选中的高亮，dark = 玻璃深浅，
 *      radius = 圆角（圆区传 half，面板传自己的圆角）；
 *   ★3 激活态多一圈内侧亮边 + 少量提亮，用来表达"松手就会进这个区"。
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
        "uniform float uHeight, uRefract, uHlAmt, uAb, uDpr, uCont, uValid;\n" +
        "uniform vec4  uS0, uS1, uS2, uS3, uS4;   // cx,cy,halfX,halfY\n" +
        "uniform vec4  uM0, uM1, uM2, uM3, uM4;   // act, dark, radius, enabled\n" +
        "\n" +
        "vec3 bgcol(vec2 p, float blurR){\n" +
        "  vec2 uv = (uWinPos + p * uPxToScreen) / uScreen;\n" +
        "  return (blurR < 8.0 ? texture2D(iBg, uv) : texture2D(iBlur, uv)).rgb;\n" +
        "}\n" +
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
        "  float actBest = 0.0, darkBest = 1.0;\n" +
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
        "  vec2  g = gBest;\n" +
        "  vec3  col;\n" +
        "  float sh = (d > 0.0) ? exp(-d / 30.0) * 0.22 : 0.0;\n" +
        "  col = bgcol(p, 0.0) * (1.0 - sh);\n" +
        "\n" +
        "  float wb = uValid;\n" +
        "  float darkAmt = mix(1.0, clamp(darkBest, 0.0, 1.0), wb);\n" +
        "\n" +
        "  vec2 lp = p - cBest;\n" +
        "  vec2 radial = normalize(vec2(lp.x, bBest.x * lp.y / max(bBest.y, 0.001)) + 1e-5);\n" +
        "  vec2 gr = normalize(mix(g, radial, 0.08 * wb));\n" +
        "\n" +
        "  float t   = clamp(-d / uHeight, 0.0, 1.0);\n" +
        "  float mag = 1.0 - sqrt(max(1.0 - (1.0 - t) * (1.0 - t), 0.0));\n" +
        "  vec2  dsp = -uRefract * mag * gr;\n" +
        "\n" +
        "  vec3 sharp;\n" +
        "  sharp.r = bgcol(p + dsp * (1.0 - uAb), 3.0).r;\n" +
        "  sharp.g = bgcol(p + dsp, 3.0).g;\n" +
        "  sharp.b = bgcol(p + dsp * (1.0 + uAb), 3.0).b;\n" +
        "  float br = mix(14.0, 60.0, clamp(-d / (50.0 * uDpr), 0.0, 1.0)) * uDpr;\n" +
        "  vec3 soft = bgcol(p + dsp, br * 0.4) * 0.2;\n" +
        "  for (int i = 0; i < 8; i++) {\n" +
        "    float a = 0.7854 * float(i);\n" +
        "    soft += bgcol(p + dsp + vec2(cos(a), sin(a)) * br * 0.7, br * 0.4) * 0.1;\n" +
        "  }\n" +
        "  float deep = clamp(-d / (40.0 * uDpr), 0.0, 1.0);\n" +
        "  vec3 refr = mix(sharp, soft, deep);\n" +
        "\n" +
        // 压暗：圆区沿用球/胶囊那条"上黑下透"，面板按 dark 抬一点底（保证字能读）
        // 和球一致的"黑顶 + 清透下半"：整块只轻微压暗，顶盖单独压成近黑
        "  float ny = (p.y - cBest.y) / max(bBest.y, 1.0);\n" +
        "  float m  = clamp(0.72 + 0.16 * ny, 0.0, 1.0);\n" +
        "  float crush = mix(1.0, m, clamp(uCont, 0.0, 1.0) * 0.55 * wb);\n" +
        "  refr = refr * crush;\n" +
        "  float cap = smoothstep(-0.10, -0.80, ny);\n" +
        "  refr = mix(refr, vec3(0.012, 0.014, 0.020), cap * (0.35 + 0.5 * uCont));\n" +
        "\n" +
        "  float xb  = clamp(-d / (10.0 * uDpr), 0.0, 1.0);\n" +
        "  float dbl = 24.0 * uDpr * (1.0 - sqrt(xb * (2.0 - xb)));\n" +
        "  vec3 bleed = bgcol(p + gr * dbl, 22.0);\n" +
        "  float wbd  = clamp((d + 26.0 * uDpr) / (20.0 * uDpr), 0.0, 1.0);\n" +
        "  float blum = dot(bleed, vec3(0.2125, 0.7154, 0.0721));\n" +
        "  float bm   = pow(clamp(blum * 1.2, 0.0, 1.0), 2.0) * wbd;\n" +
        "  refr = mix(refr, bleed, bm * bm * 0.85);\n" +
        "\n" +
        "  vec3 glass = mix(refr, refr * 0.10 + vec3(0.016), darkAmt);\n" +
        "\n" +
        "  float qd  = -d;\n" +
        "  float hw  = 2.2 * uDpr + 1.6 * uDpr * actBest;      // ★3 选中区亮边更宽\n" +
        "  float aaq = 1.0;\n" +
        "  float band = (1.0 - clamp(qd / hw, 0.0, 1.0))\n" +
        "             * clamp(qd / aaq + 0.5, 0.0, 1.0)\n" +
        "             * clamp((hw - qd) / aaq + 0.5, 0.0, 1.0);\n" +
        "  vec2 kdir = vec2(0.7071, 0.7071);\n" +
        "  float key  = band * clamp((dot(kdir, gr) - 0.52) / 0.48, 0.0, 1.0);\n" +
        "  float fill = band * clamp((dot(-kdir, gr) - 0.52) / 0.48, 0.0, 1.0);\n" +
        "  key  = key  / (1.0 + (1.0 - key)  * 8.0);\n" +
        "  fill = fill / (1.0 + (1.0 - fill) * 8.0);\n" +
        "  glass += (key + fill) * uHlAmt * mix(1.0, 0.4, darkAmt) * (1.0 + 0.9 * actBest);\n" +
        // ★3 激活区的内侧整圈微光：整块玻璃提亮一点点，远看就是"这个格子在回应手指"
        "  float inner = clamp(1.0 + d / (26.0 * uDpr), 0.0, 1.0);\n" +
        "  glass += actBest * inner * 0.05;\n" +
        "\n" +
        "  float aa = smoothstep(1.5, -1.5, d);\n" +
        "  float alpha = aa + sh * (1.0 - aa);\n" +
        "  gl_FragColor = vec4(glass * aa, alpha);\n" +
        "}\n" +
        "\n";
}
