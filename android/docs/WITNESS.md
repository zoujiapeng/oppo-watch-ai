# 取证文件在哪 / 怎么读（给 AI 的速查）

用户报 UI 异常时会**双击音量减**拍一张（或让 AI 跑 `bash tools/witness.sh`）。
一次「快门」产出三个同名文件，`<ID>` = `glass_YYYYMMDD_HHmmss_SSS[_N]`：

| # | 路径 | 内容 |
|---|---|---|
| 1 | `/sdcard/Pictures/Screenshots/<ID>.png` | 全屏 PNG（1080×2340），**玻璃层真的在里面** |
| 2 | `/sdcard/Android/data/com.glslplayground.app/files/<ID>.txt` | 那一刻的状态快照（键值对） |
| 3 | `/sdcard/Android/data/com.glslplayground.app/files/<ID>.log` | logcat 里 `FloatingBall/PowerBall` 最近 400 行 |

> 为什么普通截图拍不到：玻璃层带 `SKIP_SCREENSHOT`，MIUI 的音量减+电源截图 / 录屏里那一层是隐身的。
> 快门时 App 临时撤掉该标记 **并冻结采集**（`GlassRenderer.freezeCapture`，防球折射自己 = 伪证），
> 拍完立刻恢复。所以 #1 就是"眼睛此刻看到的"。

## 触发方式（三选一）

```bash
bash tools/witness.sh                # 立刻拍；-d 5 延迟 5 秒；-n 6 -i 2 连拍 6 张
su -c 'touch /sdcard/Android/data/com.glslplayground.app/files/witness'   # 文件钩子
# 或用户在手机上：0.8s 内按两次音量减（没在按住说话时）
```

拍完 `tools/witness.sh` 会把三件套收进 `shots/witness/<时间>/`。手机没 root 时 #1 拍不出来（`witness: 取证截图失败`）。

## #2 状态快照怎么读

```
flags=searchActive:… zonesActive:… menuShown:… convoOpen:… approval:… uiHidden:…
glassWin=<x,y,w,h>     玻璃窗口的真实屏幕矩形（getLocationOnScreen）
glassParams=<x,y,w,h>  params（y 已减掉状态栏 inset）
renderer=… winPixel:WxH selfExcluded:… freeze:…
search=phase:<1期…> capsH:<dp>      1=小黑球 2=胶囊已落定 3=收回
springs=cx,cy,bx,by,k,dark,cont    CSS/dp 单位；dark=1 是"深黑液滴"、0 是落地玻璃
blob=<x,y,w,h>         弹簧算出的胶囊矩形（**屏幕坐标**）
barReq=<x,y,w,h>       期望摆位（已减 inset）
barReal=<x,y,w,h>      输入行窗口真实位置（getLocationOnScreen）
zones=center:R:ring:level:hover
menu=<x,y,w,h>
```

判据（出问题必居其一）：

| 现象 | 看这里 |
|---|---|
| 胶囊/圆区**被压扁、整体偏移**，图标对不准 | `pixel ≠ view`（`k = 缓冲÷窗口` 失真）；logcat 里 `hb 不一致` / `画布缩放 k=` |
| 胶囊的玻璃**整个不见**，只剩一行字或一颗黑块 | `flags` 里 `searchActive` 和 `zonesActive` 同时为 true（状态 2/3 打架） |
| 文字跑到玻璃外面 | `blob` 与 `barReal` 的 x/y/w/h 差 > 2px |
| 玻璃发死、发暗、折射不对 | logcat `skip-screenshot` / `背景重灌` 缺失；`selfExcluded:false` |
| 菜单/卡片位置不对 | `menu=` 与 `glassWin` 对不上；`restoreHoldWindow` 那条路径 |

## logcat 关键字

```bash
su -c "logcat -d | grep -E 'FloatingBall' | grep -E 'witness|hb|画布缩放|search:|zones:|hw' | tail -200"
```

- `witness: 快门 <ID>` → 快门开始；`快门结束（抓屏排除已恢复）` → 结束
- `hb: search=… zones=… win=… view=… pixel=…` → **每秒一行**（只在状态 2/3/菜单/审批时打）；三者不一致紧跟 `hb 不一致` + `hb 自愈`
- `画布缩放 k=…（win / surface）` → 出现且**持续** = 缓冲没同步
- `search: 状态 3 还开着 → 先退出状态 3 再开胶囊` → 互斥保护生效
