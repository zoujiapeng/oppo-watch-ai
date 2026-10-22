<div align="center">

# glsl-playground

**一组 WebGL / GLSL shader 实验：声波、液态玻璃、流体融合**

A set of WebGL / GLSL shader experiments — sound waves, liquid glass, and fluid metaball morphing

[**在线预览 / Live Demo**](https://aaaa-zhen.github.io/glsl-playground/)

[![Live Demo](https://img.shields.io/badge/demo-online-4cd9c0?style=flat-square)](https://aaaa-zhen.github.io/glsl-playground/)
[![WebGL](https://img.shields.io/badge/WebGL-GLSL-5b7cfa?style=flat-square)](https://www.khronos.org/webgl/)
[![No Dependencies](https://img.shields.io/badge/dependencies-none-ff5fa2?style=flat-square)](#)
[![License](https://img.shields.io/badge/license-MIT-lightgrey?style=flat-square)](#license)

</div>

---

每个 demo 都是**单文件 HTML、零依赖**，浏览器直接打开就能跑，源码即教程。
Each demo is a single self-contained HTML file — no build, no dependencies, just open it in a browser.

## 📱 安卓悬浮水晶球 = 本机 DSH 的前端

这个仓库里还有一个**安卓端**（已合入 `main`）：一颗浮在桌面上的液态玻璃水晶球，
**双指长按打字提问 → 本机 DSH 回答 → 回答逐字打进这颗球里**（球随文字变形；
思考中是流体圆点、回答完文字消散、球缩回原本那颗水晶球）。DSH 侧一行没改，
走的是它已经在给浏览器用的 HTTP / WebSocket 接口。

**双指长按这颗球**就能问它：球当场让路，换 **metaball-search 那一层本身**
（不是球的形变）—— 小黑球从球原来的位置冒出来，再照演示页那套弹簧与材质弹成顶部一条
**玻璃搜索胶囊**（🔍 输入框，光标也是演示页那颗发光白条）。键盘回车直接发送，
点空白/返回键收回去并把球还回来。
见 [`OPERATIONS.md`](android/docs/OPERATIONS.md) 第三·五节。

| 你想干什么 | 看这份 |
|---|---|
| **换一台手机装（给 AI 的操作清单 + 体检命令 + 踩坑表）** | [`android/docs/DEPLOY_OTHER_PHONE.md`](android/docs/DEPLOY_OTHER_PHONE.md) |
| 它怎么接上 DSH 的（协议 / 状态表 / 黑区公式） | [`android/docs/DSH_ASSISTANT.md`](android/docs/DSH_ASSISTANT.md) |
| 接手改代码（现状 / 关键决定 / 未做完） | [`android/docs/HANDOFF.md`](android/docs/HANDOFF.md) |
| DSH 接口完整逆向记录（916 行） | [`android/docs/dsh-external-client-spec.md`](android/docs/dsh-external-client-spec.md) |
| **Agent 操控手机时球不吃手势**（球让路协议 / 验证脚本） | [`android/docs/BALL_TOUCH_GUARD.md`](android/docs/BALL_TOUCH_GUARD.md) |
| **全部操作说明**（手势 / 状态 / 每个开关 / 调试口子 / 日志速查） | [`android/docs/OPERATIONS.md`](android/docs/OPERATIONS.md) |

一句话前提：**本机 root + 标准 Termux + 本机跑着 DSH**（没 root 有降级路径，见部署文档）。

```bash
curl -L -o glsl-playground.apk \
  https://github.com/zoujiapeng/oppo-watch-ai/raw/main/glsl-playground.apk
pm install -r glsl-playground.apk
# 装完进 App →「⚙ 设置」→ 打开「悬浮于桌面」，球才会出现
# 注意：这个开关不持久 —— 重启手机 / 重装 / 被系统杀掉之后都要再点一次
```

### 从源码构建 / Build the APK from source

只要一台装了 **Termux** 的安卓机（不需要 Android Studio、不需要 gradle）：

```bash
pkg install openjdk-17 python aapt2 d8 apksigner   # 工具链（本机都是现成的）
git clone https://github.com/zoujiapeng/oppo-watch-ai.git
cd oppo-watch-ai/android
bash build.sh          # javac → d8 → aapt2 → apksigner，单体 APK 出在仓库根目录
pm install -r ../glsl-playground.apk
```

`build.sh` 只做四件事：`aapt2` 链资源 → `javac --release 8` 编 Java → `d8` 转 dex →
`apksigner` 用仓库里的自签名 keystore 签出来。首次运行会自动下载一个 `android.jar`（~30MB）到
`.build/`，之后离线可编。改完代码想只做语法检查：

```bash
javac --release 8 -classpath .build/android.jar -d .build/javac-check $(find .build/gen src -name '*.java')
```

## Demos

| Demo | 说明 | 交互 |
|---|---|---|
| **[Siri Wave](https://aaaa-zhen.github.io/glsl-playground/siri-wave.html)**<br><sub>[`siri-wave.html`](siri-wave.html)</sub> | 声波与流体圆点两种形态：光谱色散、Lorentzian 发光线、metaball + smin 平滑融合、欠阻尼弹簧动画 | 点击切换形态 |
| **[Liquid Glass](https://aaaa-zhen.github.io/glsl-playground/liquid-glass.html)**<br><sub>[`liquid-glass.html`](liquid-glass.html)</sub> | 液态玻璃材质：透镜折射、色差、backdrop 模糊、黑顶镜面、赤道亮线 | 上传自己的截图 → 玻璃实时折射它；全部参数可调 |
| **[Metaball → Search](https://aaaa-zhen.github.io/glsl-playground/metaball-search.html)**<br><sub>[`metaball-search.html`](metaball-search.html)</sub> | 从屏幕边缘拖出液滴唤起搜索框：梯度感知 smooth-min（液滴渗出时起液颈）、SDF 驱动玻璃折射、CPU 弹簧驱动「圆 → 胶囊」连续形变 | 从顶部黑边**往下拖**，松手凝聚成黑玻璃搜索框 |

> 三个 demo 也可以从首页 [aaaa-zhen.github.io/glsl-playground](https://aaaa-zhen.github.io/glsl-playground/) 统一进入。

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
git clone https://github.com/aaaa-zhen/glsl-playground.git
cd glsl-playground
open index.html          # macOS，或直接把 html 拖进浏览器
```

需要上传截图测试折射（Liquid Glass / Metaball）时，建议起个本地服务避免浏览器的本地文件限制：

```bash
python3 -m http.server 8000   # 然后访问 http://localhost:8000
```

**环境要求**：任意支持 WebGL 的现代浏览器（Chrome / Safari / Edge / Firefox）。

## License

**MIT** —— 代码、shader、文档、APK 全部同许可，可商用、可改、可闭源分发，只需保留版权与许可声明。
完整文本见 [`LICENSE`](LICENSE)。

| 部分 | 许可 |
|---|---|
| 三个 WebGL / GLSL demo（`*.html`） | MIT |
| 安卓悬浮球（`android/`、`glsl-playground.apk`） | MIT |

> 液态玻璃的折射/色散/黑顶参数、SDF 与 `sdf_union` 的公式来自对 iOS QuartzCore
> 的逆向记录（`metaball-search.html` 头部注释），属于公开的逆向资料整理，未包含任何 Apple 二进制。
