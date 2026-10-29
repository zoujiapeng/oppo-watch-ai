#!/data/data/com.termux/files/usr/bin/bash
# ball-flash-verify.sh —— 验证「让位模式二：闪开」
#
# 断言五件事（全部是设备上真的跑出来的，不是读代码猜的）：
#   ① 文件钩子 yield-mode 写 flash 之后，球的 STATUS 立刻变成 mode=flash；
#   ② YIELD 的那一刻 flash=1（渲染器已 hidden），logcat 有「玻璃消失（闪开让位）」；
#   ③ 让位期间截屏：**球所在的矩形**里画面明显变了（玻璃真的离场）；
#   ④ RELEASE 之后 flash=0，同一矩形又变回"有球"的样子，logcat 有「玻璃恢复显示」；
#   ⑤ 走完之后模式 / 抓屏标记都还原（不会把调试口子留在用户机器上）。
#
# 为什么脚本里要往信号目录放 show-glass：球默认给自己的 Surface 打了
# SKIP_SCREENSHOT（抓屏/录屏里本来就不含球），root screencap 根本拍不到它 ——
# 不打开这个调试口子，像素断言永远是"空对空"。跑完写 hide-glass 关掉。
#
# 用法：
#   bash android/tools/ball-flash-verify.sh                        # 跑完还原原模式
#   BALL_VERIFY_KEEP=1 bash android/tools/ball-flash-verify.sh     # 跑完保留 flash 模式
#
# 退出码：0 = 全部通过，1 = 有断言失败。
set -uo pipefail

D="${BALL_GATE_DIR:-/sdcard/Android/data/com.glslplayground.app/files}"
PKG="${BALL_PKG:-com.glslplayground.app}"
PORT="${BALL_GATE_PORT:-3181}"
OUT="${BALL_VERIFY_OUT:-${TMPDIR:-$HOME/.cache}/ball-flash-verify}"
mkdir -p "$OUT" || OUT="$HOME"
HERE="$(cd "$(dirname "$0")" && pwd)"

fail=0
ok()  { echo "  [ok] $*"; }
bad() { echo "  [!!] $*"; fail=1; }

pid="$(pidof "$PKG" 2>/dev/null || true)"
if [ -z "${pid:-}" ]; then
  echo "[!!] 球没在跑（pidof $PKG 空）。先启动："
  echo "     su -c 'env -i PATH=/system/bin HOME=/data/local/tmp am start-foreground-service -n $PKG/.FloatingBallService'"
  exit 1
fi
echo "球在跑：pid=$pid  信号目录=$D  端口=$PORT"

# ── TCP 行协议小工具 ─────────────────────────────────────────────────────────
gate() {  # gate <line> -> 一行回复
  python3 - "$PORT" "$1" <<'PY'
import socket, sys
port, line = int(sys.argv[1]), sys.argv[2]
s = socket.socket(); s.settimeout(1.5)
try:
    s.connect(("127.0.0.1", port))
    s.sendall((line + "\n").encode())
    print(s.recv(512).decode().strip())
except Exception as e:
    print("ERR", e)
finally:
    s.close()
PY
}

mode_now()  { gate STATUS | sed -n 's/.* mode=\([a-z]*\).*/\1/p'; }
flash_now() { gate STATUS | sed -n 's/.* flash=\([01]\).*/\1/p'; }

wait_state() {  # wait_state <grep-pattern> <ms>
  local pat="$1" ms="${2:-2000}" t=0
  while [ "$t" -lt "$ms" ]; do
    gate STATUS | grep -q "$pat" && return 0
    sleep 0.1; t=$((t + 100))
  done
  return 1
}

shot() {  # shot <name> -> 路径
  local p="$OUT/$1.png"
  su -c "screencap -p '$p'" 2>/dev/null || screencap -p "$p" 2>/dev/null
  [ -s "$p" ] && echo "$p"
}

# 球此刻占的屏幕矩形（代理窗口比球大 10dp，够用；拿它当"球区"）
ball_rect() {
  su -c 'dumpsys window windows' 2>/dev/null \
    | awk '/dsh-ball-proxy/{p=1} p&&/mAttrs=/{print; exit}' \
    | sed -n 's/.*={(\([0-9]*\),\([0-9]*\))(\([0-9]*\)x\([0-9]*\)).*/\1 \2 \3 \4/p'
}

orig_mode="$(mode_now)"; [ -z "$orig_mode" ] && orig_mode="touch"
echo "跑之前的模式：$orig_mode"

cleanup() {
  if [ "${BALL_VERIFY_KEEP:-0}" != "1" ]; then
    printf '%s\n' "$orig_mode" > "$D/yield-mode" 2>/dev/null || true
  fi
  : > "$D/hide-glass" 2>/dev/null || true     # 关掉抓屏调试口子（生产必须关）
  sleep 0.3
}
trap cleanup EXIT

# ── ① 切到 flash ────────────────────────────────────────────────────────────
printf 'flash\n' > "$D/yield-mode"
if wait_state 'mode=flash' 3000; then
  ok "yield-mode 钩子生效：mode=flash"
else
  bad "模式没切成 flash（STATUS=$(gate STATUS)）"
fi

# ── ② 让球出现在抓屏里 + 定位它 ─────────────────────────────────────────────
: > "$D/show-glass"
sleep 0.8
rect="$(ball_rect)"
if [ -n "$rect" ]; then
  ok "球区（代理窗口）：x/y/w/h = $rect"
else
  bad "拿不到球窗口矩形（dumpsys 里没有 dsh-ball-proxy）"
fi

# ── ③ 截图 before ───────────────────────────────────────────────────────────
before="$(shot 1-before)"; [ -z "$before" ] && { echo "[!!] 截图失败（before）"; exit 1; }

# ── ④ YIELD：玻璃应该当场消失 ───────────────────────────────────────────────
nonce="flash-verify-$(date +%s%N)"
reply="$(gate "YIELD $nonce 5000")"
if [ "$reply" = "OK $nonce" ]; then
  ok "YIELD 回 OK（${nonce}）"
else
  bad "YIELD 回复不对：$reply"
fi
if [ "$(flash_now)" = "1" ]; then
  ok "让位中：STATUS flash=1（渲染器已 hidden）"
else
  bad "让位中 flash 不是 1（STATUS=$(gate STATUS)）"
fi
during="$(shot 2-during)"; [ -z "$during" ] && { echo "[!!] 截图失败（during）"; exit 1; }

# ── ⑤ RELEASE：玻璃应该回来 ─────────────────────────────────────────────────
reply="$(gate "RELEASE $nonce")"
case "$reply" in
  RELEASED*) ok "RELEASE 回 $reply" ;;
  *)         bad "RELEASE 回复不对：$reply" ;;
esac
if wait_state 'flash=0' 2000; then
  ok "恢复后：STATUS flash=0（玻璃重新入场）"
else
  bad "恢复后 flash 仍是 1（STATUS=$(gate STATUS)）"
fi
sleep 0.4
after="$(shot 3-after)"; [ -z "$after" ] && { echo "[!!] 截图失败（after）"; exit 1; }
: > "$D/hide-glass"     # 立刻关掉抓屏调试口子，后面只做像素比对

# ── ⑥ 日志：闪开/恢复两条都在 ───────────────────────────────────────────────
logs="$(su -c 'logcat -d -s FloatingBall' 2>/dev/null || logcat -d -s FloatingBall 2>/dev/null || true)"
echo "$logs" | grep -q '玻璃消失（闪开让位）' && ok "logcat：玻璃消失（闪开让位）" || bad "logcat 里没有「玻璃消失（闪开让位）」"
echo "$logs" | grep -q '玻璃恢复显示'          && ok "logcat：玻璃恢复显示"          || bad "logcat 里没有「玻璃恢复显示」"

# ── ⑦ 像素：球区里 during 没球、after 有球 ──────────────────────────────────
python3 - "$before" "$during" "$after" "$rect" "$HERE" <<'PY'
import sys
sys.path.insert(0, sys.argv[5])
from png_scan import load_png

b, d, a = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    x, y, w, h = [int(v) for v in sys.argv[4].split()]
except Exception:
    print("  [!!] 没有球区矩形，跳过像素断言"); sys.exit(1)

def load(p):
    w_, h_, ch_, rows = load_png(p)
    return w_, h_, ch_, rows

bw, bh, bch, brow = load(b)
dw, dh, dch, drow = load(d)
aw, ah, ach, arow = load(a)
step, TH = 3, 24
x0, y0 = max(0, x), max(0, y)
x1 = min(min(bw, dw, aw) - 1, x + w - 1)
y1 = min(min(bh, dh, ah) - 1, y + h - 1)

def diff(r1, ch1, r2, ch2):
    n = 0
    for yy in range(y0, y1 + 1, step):
        p1, p2 = r1[yy], r2[yy]
        for xx in range(x0, x1 + 1, step):
            o1, o2 = xx * ch1, xx * ch2
            if (abs(p1[o1] - p2[o2]) > TH
                    or abs(p1[o1 + 1] - p2[o2 + 1]) > TH
                    or abs(p1[o1 + 2] - p2[o2 + 2]) > TH):
                n += 1
    return n

total = ((x1 - x0) // step + 1) * ((y1 - y0) // step + 1)
gone = diff(brow, bch, drow, dch)
back = diff(drow, dch, arow, ach)
print(f"  球区 {x0},{y0} {x1-x0+1}x{y1-y0+1}  采样 {total}")
print(f"  让位前 ↔ 让位中不同 {gone}（{100.0*gone/total:.1f}%）")
print(f"  让位中 ↔ 恢复后不同 {back}（{100.0*back/total:.1f}%）")
good = True
if gone < total * 0.15:
    print("  [!!] 让位期间球区几乎没变 —— 玻璃没有真的消失")
    good = False
else:
    print(f"  [ok] 让位期间球区变了 {100.0*gone/total:.0f}% —— 玻璃确实离场")
if back < total * 0.15:
    print("  [!!] 恢复后球区还是让位中的样子 —— 玻璃没回来")
    good = False
else:
    print(f"  [ok] 恢复后球区又变回 {100.0*back/total:.0f}% —— 玻璃回来了")
sys.exit(0 if good else 1)
PY
[ $? -eq 0 ] || fail=1

# ── ⑧ 还原检查 ──────────────────────────────────────────────────────────────
# 钩子是球的主线程每 60ms 读一次，球正忙（画帧/动画）时偶尔会晚到几百毫秒，
# 所以这里等 3s 而不是死等一个 sleep —— 否则会误报"没还原"。
if [ "${BALL_VERIFY_KEEP:-0}" != "1" ]; then
  printf '%s\n' "$orig_mode" > "$D/yield-mode" 2>/dev/null || true
  if wait_state "mode=$orig_mode" 3000; then
    ok "模式已还原：$orig_mode"
  else
    bad "模式没还原（现在是 $(mode_now)，应为 $orig_mode）"
  fi
fi

echo
if [ "$fail" -eq 0 ]; then
  echo "★ 全部通过"
  echo "  截图：$OUT/{1-before,2-during,3-after}.png"
else
  echo "☆ 有断言失败 —— 看上面的 [!!] 行"
fi
exit "$fail"
