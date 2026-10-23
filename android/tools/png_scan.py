#!/usr/bin/env python3
"""png_scan.py —— 纯 python 解 PNG（无 PIL），在一行像素带里找"暗像素"的连续段。

用途：验证「agent 用注射手势在便签画布上画线，线穿过悬浮球所在的矩形时有没有断口」。
如果球把 ACTION_DOWN 吃掉，画布要么整条线不出现，要么中间断一截。

用法：
  python3 android/tools/png_scan.py <file.png> <y0> <y1> [--dark=110] [--xmin=0] [--xmax=1079]
输出：
  每一行的连续暗像素段（x 起止 + 长度），以及整条带里"最长连续段"（按列取 or：任一行为暗即算）。
"""
import sys
import zlib
import struct


def load_png(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "不是 PNG"
    pos, idat, w, h, bd, ct = 8, b"", 0, 0, 8, 6
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            w, h, bd, ct = struct.unpack(">IIBB", body[:10])
        elif typ == b"IDAT":
            idat += body
        elif typ == b"IEND":
            break
        pos += 12 + ln
    ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ct]
    assert bd == 8, f"只支持 8bit，实际 {bd}"
    raw = zlib.decompress(idat)
    stride = w * ch
    rows, prev, k = [], bytearray(stride), 0
    for _ in range(h):
        ft = raw[k]
        k += 1
        line = bytearray(raw[k:k + stride])
        k += stride
        if ft == 1:
            for i in range(ch, stride):
                line[i] = (line[i] + line[i - ch]) & 0xFF
        elif ft == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ft == 3:
            for i in range(stride):
                a = line[i - ch] if i >= ch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif ft == 4:
            for i in range(stride):
                a = line[i - ch] if i >= ch else 0
                b = prev[i]
                c = prev[i - ch] if i >= ch else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        rows.append(bytes(line))
        prev = line
    return w, h, ch, rows


def runs(flags):
    out, start = [], None
    for i, f in enumerate(flags):
        if f and start is None:
            start = i
        elif not f and start is not None:
            out.append((start, i - 1))
            start = None
    if start is not None:
        out.append((start, len(flags) - 1))
    return out


def main(argv):
    path, y0, y1 = argv[1], int(argv[2]), int(argv[3])
    dark = 110
    xmin, xmax = 0, 10 ** 9
    for a in argv[4:]:
        if a.startswith("--dark="):
            dark = int(a.split("=")[1])
        elif a.startswith("--xmin="):
            xmin = int(a.split("=")[1])
        elif a.startswith("--xmax="):
            xmax = int(a.split("=")[1])
    w, h, ch, rows = load_png(path)
    print(f"# {path}  {w}x{h} ch={ch}  扫描行 {y0}..{y1}  暗阈值 <{dark}")
    band = [False] * w
    per_row = []
    for y in range(max(0, y0), min(h - 1, y1) + 1):
        r = rows[y]
        flags = []
        for x in range(w):
            o = x * ch
            lum = (r[o] * 299 + r[o + 1] * 587 + r[o + 2] * 114) // 1000
            f = lum < dark and xmin <= x <= xmax
            flags.append(f)
            if f:
                band[x] = True
        segs = [(a, b) for a, b in runs(flags) if b - a >= 2]
        per_row.append((y, segs))
    for y, segs in per_row:
        if segs:
            txt = " ".join(f"[{a}..{b}]({b - a + 1})" for a, b in segs)
            print(f"y={y}: {txt}")
    merged = [(a, b) for a, b in runs([f and xmin <= x <= xmax for x, f in enumerate(band)]) if b - a >= 2]
    print("按列合并（任一行为暗即算）:", " ".join(f"[{a}..{b}]({b - a + 1})" for a, b in merged) or "（没有暗像素）")
    if merged:
        longest = max(merged, key=lambda s: s[1] - s[0])
        print(f"最长连续段：x {longest[0]}..{longest[1]}（{longest[1] - longest[0] + 1}px）")


if __name__ == "__main__":
    main(sys.argv)
