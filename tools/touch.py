#!/usr/bin/env python3
"""把真实触摸帧直接写进 /dev/input/event5（fts 触摸屏，device id 4）。

为什么要这一份：`input tap` / `dispatchGesture` 注入的触摸 device id 不在
realTouchDevices 里 → 球把它当"机器人触摸"直接让开（onSyntheticDown），
永远走不到长按 / 双指长按那几条用户手势。写 evdev 才会被当成真手指。

用法：
  touch.py tap X Y [hold_ms]
  touch.py two X Y DX [hold_ms]        # 两指（第二指在 X+DX）
  touch.py holddrag X1 Y1 X2 Y2 [hold_ms] [steps]
  touch.py swipe X1 Y1 X2 Y2 [ms] [steps]
"""
import struct, sys, time

DEV = "/dev/input/event5"
EV_SYN, EV_KEY, EV_ABS = 0, 1, 3
ABS_MT_SLOT, ABS_MT_TOUCH_MAJOR, ABS_MT_POSITION_X, ABS_MT_POSITION_Y, ABS_MT_TRACKING_ID = 47, 48, 53, 54, 57
BTN_TOUCH = 330
SYN_REPORT = 0


class Touch:
    def __init__(self, path=DEV):
        self.f = open(path, "wb", buffering=0)

    def _ev(self, t, c, v):
        self.f.write(struct.pack("llHHi", 0, 0, t, c, v))

    def syn(self):
        self._ev(EV_SYN, SYN_REPORT, 0)

    def down(self, x, y, slot=0, tid=1):
        self._ev(EV_ABS, ABS_MT_SLOT, slot)
        self._ev(EV_ABS, ABS_MT_TRACKING_ID, tid)
        self._ev(EV_ABS, ABS_MT_POSITION_X, int(x))
        self._ev(EV_ABS, ABS_MT_POSITION_Y, int(y))
        self._ev(EV_ABS, ABS_MT_TOUCH_MAJOR, 12)
        self._ev(EV_KEY, BTN_TOUCH, 1)
        self.syn()

    def move(self, x, y, slot=0):
        self._ev(EV_ABS, ABS_MT_SLOT, slot)
        self._ev(EV_ABS, ABS_MT_POSITION_X, int(x))
        self._ev(EV_ABS, ABS_MT_POSITION_Y, int(y))
        self.syn()

    def up(self, slot=0):
        self._ev(EV_ABS, ABS_MT_SLOT, slot)
        self._ev(EV_ABS, ABS_MT_TRACKING_ID, -1)
        self.syn()

    def allup(self, slots=2):
        for s in range(slots):
            self._ev(EV_ABS, ABS_MT_SLOT, s)
            self._ev(EV_ABS, ABS_MT_TRACKING_ID, -1)
        self._ev(EV_KEY, BTN_TOUCH, 0)
        self.syn()


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    cmd = argv[1]
    t = Touch()
    if cmd == "tap":
        x, y = int(argv[2]), int(argv[3])
        hold = int(argv[4]) if len(argv) > 4 else 60
        t.down(x, y)
        time.sleep(hold / 1000.0)
        t.up()
        t.allup(1)
    elif cmd == "two":
        x, y, dx = int(argv[2]), int(argv[3]), int(argv[4])
        hold = int(argv[5]) if len(argv) > 5 else 900
        t.down(x, y, 0, 11)
        t.down(x + dx, y, 1, 12)
        time.sleep(hold / 1000.0)
        t.allup(2)
    elif cmd in ("holddrag", "swipe"):
        x1, y1, x2, y2 = int(argv[2]), int(argv[3]), int(argv[4]), int(argv[5])
        hold = int(argv[6]) if len(argv) > 6 else (600 if cmd == "holddrag" else 0)
        steps = int(argv[7]) if len(argv) > 7 else 12
        t.down(x1, y1)
        if hold:
            time.sleep(hold / 1000.0)
        for i in range(1, steps + 1):
            t.move(x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps)
            time.sleep(0.016)
        time.sleep(0.08)
        t.up()
        t.allup(1)
    else:
        print("unknown cmd", cmd)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
