<div align="center">

# siri-glsl

**用 WebGL / GLSL 逆向复刻 Apple 新版 Siri 动画与 Liquid Glass 效果**

A GLSL study recreating Apple's new Siri animation and the Liquid Glass effect

[**在线预览 / Live Demo**](https://aaaa-zhen.github.io/siri-glsl/)

[![Live Demo](https://img.shields.io/badge/demo-online-4cd9c0?style=flat-square)](https://aaaa-zhen.github.io/siri-glsl/)
[![WebGL](https://img.shields.io/badge/WebGL-GLSL-5b7cfa?style=flat-square)](https://www.khronos.org/webgl/)
[![No Dependencies](https://img.shields.io/badge/dependencies-none-ff5fa2?style=flat-square)](#)
[![License](https://img.shields.io/badge/license-MIT-lightgrey?style=flat-square)](#license)

</div>

---

每个 demo 都是**单文件 HTML、零依赖**，浏览器直接打开就能跑，源码即教程。
Each demo is a single self-contained HTML file — no build, no dependencies, just open it in a browser.

## Demos

| Demo | 说明 | 交互 |
|---|---|---|
| **[Siri Wave](https://aaaa-zhen.github.io/siri-glsl/siri-wave.html)**<br><sub>[`siri-wave.html`](siri-wave.html)</sub> | 新版 Siri 声波 `siriWaveCore` 与流体圆点 `siriFluidDotsCore`：光谱色散、Lorentzian 发光线、metaball + smin 平滑融合、欠阻尼弹簧动画 | 点击切换形态 |
| **[Liquid Glass](https://aaaa-zhen.github.io/siri-glsl/liquid-glass.html)**<br><sub>[`liquid-glass.html`](liquid-glass.html)</sub> | QuartzCore `glass_background` 重建：透镜折射、色差、backdrop 模糊、黑顶镜面、赤道亮线 | 上传自己的截图 → 玻璃实时折射它；全部参数可调 |
| **[Metaball → Search](https://aaaa-zhen.github.io/siri-glsl/metaball-search.html)**<br><sub>[`metaball-search.html`](metaball-search.html)</sub> | iPadOS 从边缘拖拽唤醒搜索的复刻：梯度感知 smooth-min（液滴从黑边渗出起液颈）、SDF 驱动玻璃折射、CPU 弹簧驱动「圆 → 胶囊」连续形变 | 从顶部黑边**往下拖**，松手凝聚成黑玻璃搜索框 |

> 三个 demo 也可以从首页 [aaaa-zhen.github.io/siri-glsl](https://aaaa-zhen.github.io/siri-glsl/) 统一进入。

## 技术点 / Techniques

**渲染管线**
- 纯 fragment shader 绘制，单个大三角形全屏覆盖（无 mesh、无 draw call 开销）
- 透镜位移场折射 + 两 pass 高斯模糊
- 圆盘多抽样 + mip 联合模糊、距离场驱动的边缘渗光

**形状与融合**
- metaball SDF + smooth-min 流体融合
- 梯度感知 smooth-min：平滑半径随两表面梯度夹角缩放，起液颈时不撑胖
- supercircle (squircle) SDF：圆 / 胶囊 / 圆角矩形是同一原语的连续插值

**光与动效**
- 多光谱采样做色散（chromatic aberration）
- `1/(d+t)` 发光线 + Lorentzian 衰减
- 欠阻尼弹簧 `1 − e^(−lt)·cos(wt)` 做自然回弹

## 本地运行 / Run Locally

```bash
git clone https://github.com/aaaa-zhen/siri-glsl.git
cd siri-glsl
open index.html          # macOS，或直接把 html 拖进浏览器
```

需要上传截图测试折射（Liquid Glass / Metaball）时，建议起个本地服务避免浏览器的本地文件限制：

```bash
python3 -m http.server 8000   # 然后访问 http://localhost:8000
```

**环境要求**：任意支持 WebGL 的现代浏览器（Chrome / Safari / Edge / Firefox）。

## License

MIT
