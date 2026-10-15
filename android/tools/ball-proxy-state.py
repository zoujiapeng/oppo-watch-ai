#!/usr/bin/env python3
"""打印悬浮球「代理触摸窗口」当前是否可触摸。

判定方式：**按窗口名找**。代理层在 addView 之前用 `setTitle("dsh-ball-proxy")` 起了名字，
`dumpsys window windows` 里就是 `Window{<token> u0 dsh-ball-proxy}`。
（早期版本靠"可见窗口里面积最大的那个"猜，实测会被文字触摸层/玻璃层带偏，别走回头路。）

输出：`touchable 713x499 at (185,1052) tok=a2d693b` / `notTouchable ...` / `notfound`
"""
import re
import subprocess
import sys

TITLE = "dsh-ball-proxy"


def main() -> int:
    out = subprocess.run(
        ["dumpsys", "window", "windows"], capture_output=True, text=True
    ).stdout
    for b in re.split(r"\n\s*Window #", out):
        m = re.search(r"Window\{(\w+) u0 " + re.escape(TITLE) + r"\}", b)
        if not m:
            continue
        attrs = re.search(r"mAttrs=\{\((-?\d+),(-?\d+)\)\((\d+)x(\d+)\)", b)
        fl = re.search(r"\bfl=([^\n]*)", b)
        not_touchable = "NOT_TOUCHABLE" in (fl.group(1) if fl else "")
        x, y, w, h = map(int, attrs.groups()) if attrs else (0, 0, 0, 0)
        state = "notTouchable" if not_touchable else "touchable"
        print(f"{state} {w}x{h} at ({x},{y}) tok={m.group(1)}")
        return 0
    print("notfound")
    return 1


if __name__ == "__main__":
    sys.exit(main())
