#!/data/data/com.termux/files/usr/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# ball-gate-doctor.sh —— 「AI 误触悬浮球」的一体化体检（换手机/换 ROM 先跑这个）
#
#   bash ball-gate-doctor.sh
#   bash ball-gate-doctor.sh --pkg com.glslplayground.app --port 3181
#
# 它按**让路链路的每一环**逐条断言，最后给一句结论。设计前提：
# 这条链路有 5 个独立环节，任何一环断了症状都一样（点击落到球上/点了没反应），
# 所以不要凭现象猜，跑这个脚本。
#
#   环 1  球在跑（前台服务 + 有 APPLICATION_OVERLAY 窗口）
#   环 2  DSH 进程是**哪个 uid**（root？还是普通 Termux 用户？）—— 决定文件通道能不能用
#   环 3  信号目录/心跳：三处都查（/sdcard、/data/media、球内部私有目录）
#   环 4  用 DSH 的 uid 实测读心跳（这是"本机能用换机不能用"的头号根因）
#   环 5  DSH 侧插件是否装了让路代码（老插件 = 一个信号都不发）
#   环 6  球侧让位开关（float_agent_yield）与信号文件开关
#   环 7  回环 TCP 门控（跨机型主通道：127.0.0.1:3181）
#   环 8  此刻球的窗口是否真的不接触摸（dumpsys input）
#
# 实机结论（2026-10-24，LE2120 / Android 13）：
#   · 本机 DSH 是 root 起的 → 文件通道一直能用，所以"本机好用"；
#   · 把进程降到 Termux uid(10326) 后读/写/stat 该目录全是 Permission denied
#     → 换一台「DSH 以普通用户跑」的手机，插件 `ballRunning()` 返回 false，静默不发信号。
# ═══════════════════════════════════════════════════════════════════════════════
set -uo pipefail

PKG="${BALL_PKG:-com.glslplayground.app}"
PORT="${BALL_GATE_PORT:-3181}"
PROFILE_HINT="${DSH_PROFILE_DIR:-$HOME/.dsh/profiles/web}"
PLUGIN_DIR="${PLUGIN_DIR:-$HOME/dsh-preset-mobile-use}"

while [ $# -gt 0 ]; do
  case "$1" in
    --pkg) PKG="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --plugin) PLUGIN_DIR="$2"; shift 2 ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1"; exit 2 ;;
  esac
done

ok=0; bad=0; warn=0
pass() { echo "  [ok] $*"; ok=$((ok+1)); }
fail() { echo "  [!!] $*"; bad=$((bad+1)); }
note() { echo "  [??] $*"; warn=$((warn+1)); }
hdr()  { echo; echo "── $*"; }

asu() { su -c "$*" 2>/dev/null; }          # root 执行（失败返回空）
have_root() { su -c id 2>/dev/null | grep -q "uid=0"; }

hdr "环 1 · 球在跑吗"
SVC=$(dumpsys activity services "$PKG" 2>/dev/null | grep -c "FloatingBallService")
OV=$(dumpsys window windows 2>/dev/null | grep -c "APPLICATION_OVERLAY")
if [ "${SVC:-0}" -gt 0 ]; then pass "FloatingBallService 在（前台服务）"; else fail "FloatingBallService 没跑 → 设置页「悬浮于桌面」再点一次"; fi
if [ "${OV:-0}" -gt 0 ]; then pass "屏幕上有 ${OV} 个 APPLICATION_OVERLAY 窗口（玻璃层/代理层/文字层…）"; else fail "没有任何悬浮窗 → 悬浮窗权限被 ROM 拦了"; fi
BALL_PID=$(asu "pidof $PKG" | tr -d '\r')
[ -n "${BALL_PID:-}" ] && pass "球进程 pid=$BALL_PID" || fail "拿不到球进程 pid"

hdr "环 2 · DSH 跑在哪个 uid 上（决定文件通道能不能用）"
DSH_LINE=$(ps -A -o user,pid,args 2>/dev/null | grep -E "dsh/lib/bin\.js|/dsh " | grep -v grep | head -1)
if [ -z "${DSH_LINE:-}" ]; then
  fail "没找到 dsh 进程 → 先起 DSH 再跑这个脚本"
  DSH_UID="?"
else
  DSH_USER=$(echo "$DSH_LINE" | awk '{print $1}')
  DSH_PID=$(echo "$DSH_LINE" | awk '{print $2}')
  DSH_UID=$(echo "$DSH_USER" | sed 's/.*_//; s/u0a//')
  echo "       $DSH_LINE"
  if [ "$DSH_USER" = "root" ]; then
    pass "DSH 是 root 跑的（uid=$DSH_UID）→ 文件通道天然可用；换机时如果 DSH 不是 root 起，这一环就断了"
  else
    note "DSH 是 $DSH_USER 跑的（不是 root）→ 它**读不到** /sdcard/Android/data/$PKG（Android 11+ 只对属主开放）"
  fi
fi

hdr "环 3 · 心跳文件（三处路径）"
DIRS_SDCARD="/sdcard/Android/data/$PKG/files"
DIRS_MEDIA="/data/media/0/Android/data/$PKG/files"
DIRS_INTERNAL="/data/user/0/$PKG/files"
for d in "$DIRS_SDCARD" "$DIRS_MEDIA" "$DIRS_INTERNAL"; do
  if [ -f "$d/dsh-ball-alive" ]; then
    AGE=$(( $(date +%s) - $(stat -c %Y "$d/dsh-ball-alive" 2>/dev/null || echo 0) ))
    BODY=$(head -c 200 "$d/dsh-ball-alive" 2>/dev/null || asu "head -c 200 '$d/dsh-ball-alive'")
    pass "心跳存在：$d（${AGE}s 前，$BODY）"
  else
    note "没有心跳：$d（球是老 APK 时这很正常）"
  fi
done

hdr "环 4 · 用 DSH 的 uid 实测能不能读（头号根因）"
if [ -n "${DSH_UID:-}" ] && [ "$DSH_UID" != "?" ] && [ "$DSH_UID" != "0" ]; then
  OUT=$(su "$DSH_UID" -c "cat '$DIRS_SDCARD/dsh-ball-alive'" 2>&1 | head -1)
  case "$OUT" in
    *"Permission denied"*)
      fail "uid=$DSH_UID 读 $DIRS_SDCARD 被拒（EACCES）→ **插件的文件通道在这台机器上必然静默失效**"
      echo "         ⇒ 必须用回环 TCP 通道（球 ≥2026-10-24 的 APK）或让 DSH 以 root 启动"
      ;;
    "") fail "uid=$DSH_UID 读不到心跳（文件不存在？球没写？）" ;;
    *)  pass "uid=$DSH_UID 能读心跳：$OUT" ;;
  esac
  OUTW=$(su "$DSH_UID" -c "touch '$DIRS_SDCARD/.doctor-probe' && echo WRITE_OK" 2>&1 | head -1)
  case "$OUTW" in
    *WRITE_OK*) pass "uid=$DSH_UID 能写信号目录"; asu "rm -f '$DIRS_SDCARD/.doctor-probe'" ;;
    *) fail "uid=$DSH_UID 写不进信号目录（$OUTW）→ 文件通道单向死" ;;
  esac
else
  note "跳过（DSH 是 root 或没找到进程，root 不适用这条限制）"
fi

hdr "环 5 · DSH 侧插件装没装让路代码"
PLUGIN_JS=""
for c in "$PLUGIN_DIR/lib/index.js" \
         "$PROFILE_HINT/node_modules/dsh-preset-mobile-use/lib/index.js" \
         "$HOME/.dsh/profiles/web/node_modules/dsh-preset-mobile-use/lib/index.js"; do
  [ -f "$c" ] && PLUGIN_JS="$c" && break
done
if [ -z "$PLUGIN_JS" ]; then
  fail "没装 dsh-preset-mobile-use（或路径不同）→ 没有 mobile 工具，也没有任何让路信号"
else
  echo "       插件：$PLUGIN_JS"
  if grep -q "waitForBallStandDown" "$PLUGIN_JS"; then
    if grep -q "gateTcp\|BALL_GATE_PORTS" "$PLUGIN_JS"; then
      pass "插件含让路逻辑 + 回环 TCP 通道（2026-10-24 版）"
    else
      note "插件含让路逻辑，但只有文件通道（老版）→ DSH 非 root 时静默失效，需换成新版"
    fi
  else
    fail "插件里**没有** waitForBallStandDown → 这是原版 preset：它根本不知道球的存在，点击直接落到球上"
    echo "         ⇒ bash ~/oppo-watch-ai/patches/install-ball-guard-plugin.sh"
  fi
  grep -q "waitForBallStandDown" "$PLUGIN_JS" 2>/dev/null && \
    echo "       提示：改完插件要**重启 DSH** 才生效（ESM 在启动时 import 进内存）"
fi

hdr "环 6 · 球侧让位开关"
PREF=$(asu "cat /data/data/$PKG/shared_prefs/watch.xml" 2>/dev/null)
case "$PREF" in
  *float_agent_yield*"false"*) fail "设置里「DSH 操控时全部 UI 让位」是**关**的 → 球会回 !skip，永不让路" ;;
  *float_agent_yield*"true"*)  pass "让位开关=开" ;;
  "" ) note "读不到 shared_prefs（球没起过 / 没 root）" ;;
  * ) pass "让位开关=默认开（prefs 里没有这一项）" ;;
esac
for d in "$DIRS_SDCARD" "$DIRS_INTERNAL" /data/local/tmp; do
  if [ -f "$d/dsh-ball-guard-off" ]; then fail "存在关机文件：$d/dsh-ball-guard-off（插件/球都会忽略信号）"; fi
done

hdr "环 7 · 回环 TCP 门控（跨机型主通道）"
tcp_try() { # $1=port → 打印回复或空
  local p="$1" rep=""
  command -v nc >/dev/null 2>&1 && rep=$(printf 'PING\n' | nc -w 1 127.0.0.1 "$p" 2>/dev/null | head -1)
  if [ -z "$rep" ] && [ -x /system/bin/nc ]; then
    rep=$(printf 'PING\n' | /system/bin/nc -w 1 127.0.0.1 "$p" 2>/dev/null | head -1)
  fi
  if [ -z "$rep" ]; then
    rep=$(exec 3<>"/dev/tcp/127.0.0.1/$p" 2>/dev/null && printf 'PING\n' >&3 && timeout 1 head -1 <&3; exec 3<&- 2>/dev/null)
  fi
  printf '%s' "$rep" | tr -d '\r'
}
TCP_REPLY=$(tcp_try "$PORT")
if [ -n "$TCP_REPLY" ]; then
  pass "127.0.0.1:$PORT → $TCP_REPLY"
else
  for p in 3182 3183; do
    R=$(tcp_try "$p"); [ -n "$R" ] && TCP_REPLY="$R" && PORT="$p" && break
  done
  if [ -n "$TCP_REPLY" ]; then
    pass "127.0.0.1:$PORT → $TCP_REPLY（默认端口被占，球顺延到 $PORT）"
  else
    fail "127.0.0.1:3181/3182/3183 都没应答 → 球是 2026-10-24 之前的 APK（没有 TCP 门控），只能走文件通道"
  fi
fi

hdr "环 8 · 此刻球的窗口接触摸吗"
if command -v dumpsys >/dev/null 2>&1; then
  RAW=$(dumpsys input 2>/dev/null | grep -E "name='[^']*(com\\.glslplayground|dsh-ball-proxy)" | head -12)
  TOUCH=0; NOTOUCH=0
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    NAME=$(printf '%s' "$line" | sed -E "s/.*name='([^']*)'.*/\\1/")
    CFG=$(printf '%s' "$line" | sed -E "s/.*inputConfig=([^,]*),.*/\\1/")
    REG=$(printf '%s' "$line" | sed -E 's/.*touchableRegion=(.*), ownerPid.*/\1/')
    if printf '%s' "$CFG" | grep -q NOT_TOUCHABLE || [ "$REG" = "<empty>" ]; then
      NOTOUCH=$((NOTOUCH+1)); MARK="点不到"
    else
      TOUCH=$((TOUCH+1)); MARK="**可触摸**"
    fi
    printf '        %-44s | %-46s | %-22s | %s\n' "${NAME:0:44}" "${CFG:0:46}" "${REG:0:22}" "$MARK"
  done <<< "$RAW"
  echo "        汇总：可触摸 $TOUCH 个 / 点不到 $NOTOUCH 个"
  if [ "$NOTOUCH" -gt 0 ] && [ "$TOUCH" -eq 0 ] && [ "$NOTOUCH" -ge 2 ]; then
    note "**此刻正处在让路窗口里**（全部点不到）—— 如果有 agent 动作刚发过，这是正确状态"
  elif [ "$TOUCH" -ge 1 ]; then
    pass "平时状态正常：球至少有一个窗口可触摸（用户能点/拖）"
  else
    note "读不到球窗口（球没起？）"
  fi
else
  note "没有 dumpsys（Termux 里正常）→ 用 su -c dumpsys input 手工看"
fi

echo
echo "════ 结论 ════"
if [ "$bad" -eq 0 ]; then
  echo "全部环节通过（ok=$ok warn=$warn）：agent 的点击不会落到球上。"
else
  echo "坏掉 $bad 环（ok=$ok warn=$warn）。最常见的两种："
  echo "  A. 环 5 = 插件没装让路代码 → 装：bash patches/install-ball-guard-plugin.sh，然后重启 DSH"
  echo "  B. 环 4 = DSH 不是 root，读不了球的 Android/data → 装 2026-10-24 之后的球 APK（走 TCP），"
  echo "     或者把 DSH 改成 root 启动（su -c 'dsh web ...'）"
fi
