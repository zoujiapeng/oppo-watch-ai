# 球体预览（离线调参用）

把 `GlassRenderer.FS_COMP` 的合成流程（黑区 / Siri 波 / 流体圆点 / 赤道线 / 球壳）
在 CPU 上按同一套公式跑一遍，输出 PNG 和增益统计 —— 调「思考中的流体圆点」时
不必每次都刷 APK 上机看。

```bash
cd android/tools/preview
node run.mjs --bg synthetic --modes wave,old,new --out ../../../.preview/cmp.png
python3 sweep.py --sweep radius --values 139,120,100,80 --modes wave,dotsonly --extra "--noglow 1"
```

- `--bg synthetic|dark|capture`：合成桌面 / 深色桌面 / 直接用 `.preview/ball-capture.png`（设置页存的球体截图）当桌面。
- `--modes wave,old,new,dotsonly,blend`：`wave`=聆听中的 Siri 波，`old`=改之前的流体圆点，
  `new`=现在的参数，`dotsonly`=只画圆点本体（不乘 uWaveAmp/waveVis，便于量尺寸），`blend`=两者交叉淡化。
- 可调：`--radius`(环半径px) `--offny`(环心偏移) `--dotr` `--intensity` `--gain` `--t`(时间秒) `--fadein`。
- 统计里的 `addMean/addPeak` 是「有光效 − 无光效」的增量亮度，用来对齐两种形态的观感。

**注意**：`shader.mjs` 是 GLSL 的 CPU 复刻，改 `GlassRenderer.FS_COMP` 时两边要一起改，
常量对齐关系见 `shader.mjs` 里 `DEFAULT_K` 的注释（ringR 0.17 / dotR 0.038 / intensity 0.0035 /
falloffP 1.35 / fadeEnd 0.56 / gain 1.1 ↔ uOrbRadius、uOrbGain、orbScenePx 里的 `k`、`0.038*k`）。
