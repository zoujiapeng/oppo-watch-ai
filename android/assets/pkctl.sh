#!/system/bin/sh
# 长按电源键 → 桌面悬浮球：装机 / 开关 / 排查（root 下跑）
#
#   su -c 'sh android/assets/pkctl.sh status'
#   su -c 'sh android/assets/pkctl.sh setup <powerkey-daemon.jar> [glsl-playground.apk]'
#   su -c 'sh android/assets/pkctl.sh daemon-start|daemon-stop|daemon-restart|daemon-status'
#   su -c 'sh android/assets/pkctl.sh settings'          # 只改系统设置（关小爱那条长按路）
#   su -c 'sh android/assets/pkctl.sh xiaoai off|on|purge|status'
#   su -c 'sh android/assets/pkctl.sh fire'              # 手动触发一次悬浮球
#   su -c 'sh android/assets/pkctl.sh test'              # 模拟长按电源键（sendevent 注入 uinput）
#   su -c 'sh android/assets/pkctl.sh uninstall'
#
# 结构（为什么这么分）：
#   · 触发：/data/adb/powerkey/powerkey-daemon.jar 以 root 常驻，读 /dev/input/event*（只读，不抢）
#   · 消歧：settings put global power_button_long_press 0 + long_press_power_key none
#           → 框架对长按什么都不做，小爱也不再抢；短按开关屏照旧
#   · 起球：daemon 里 亮屏 → 解锁 → 回桌面 → am start-foreground-service FloatingBallService
#   · 备胎：LSPosed 模块 com.pj.powerkey（只在系统仍走 SystemUI 电源菜单的 ROM 上有用）
set -u

D=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
APP=com.glslplayground.app
MOD=com.pj.powerkey
DB=/data/adb/lspd/config/modules_config.db
UI=com.android.systemui
RECV="$APP/.PowerBallReceiver"
ACT="$APP.action.POWER_BALL"

DDAEMON=/data/adb/powerkey
DJAR="$DDAEMON/powerkey-daemon.jar"
DLOG="$DDAEMON/daemon.log"
DSVC=/data/adb/service.d/powerkey-ball.sh
HOLD_MS="${HOLD_MS:-650}"

PY=
for c in /data/data/com.termux/files/usr/bin/python3 /usr/bin/python3 python3; do
  if [ -x "$c" ] || command -v "$c" >/dev/null 2>&1; then PY=$c; break; fi
done
# root 的 PATH 里没有 Termux，python3 还依赖 Termux 的 libc++
PYLIB=/data/data/com.termux/files/usr/lib

apk_of() { pm path "$1" 2>/dev/null | sed -n 's/^package://p' | head -1; }
pid_of() { pidof "$1" 2>/dev/null | awk '{print $1}'; }
has_pkg() { [ -n "$(apk_of "$1")" ] && echo 1 || echo 0; }

db() {
  if [ ! -f "$DB" ]; then echo "ERR 本机没有 LSPosed（$DB 不存在）"; return 1; fi
  if [ -z "$PY" ]; then echo "ERR 没有 python3"; return 1; fi
  LD_LIBRARY_PATH="$PYLIB" "$PY" "$D/pk_lspd.py" "$@"
}

install_apk() {
  apk="$1"
  [ -f "$apk" ] || { echo "ERR 找不到 APK：$apk"; return 1; }
  i=1
  while [ "$i" -le 5 ]; do
    out=$(pm install -r "$apk" 2>&1 | tail -1)
    case "$out" in
      *Success*) echo "ok install $(basename "$apk")"; return 0 ;;
    esac
    echo "   (第 $i 次没成功：$out，3 秒后重试)"
    sleep 3
    i=$((i+1))
  done
  echo "ERR 装不上：$apk"
  return 1
}

# ───────────────────────── 系统设置 ─────────────────────────
set_power_settings() {
  # 小爱那条路：长按电源键 = 唤醒小爱 → none
  settings put system long_press_power_key none
  # AOSP 的「长按电源键」动作：0 = 什么都不做（1 = 电源菜单，5 = 助手）
  settings put global power_button_long_press 0
  # 长按电源键呼出助手（Android 12+ 的开关）也关掉
  settings put secure power_menu_long_press_for_assist 0
  echo "ok long_press_power_key=$(settings get system long_press_power_key)" \
       "power_button_long_press=$(settings get global power_button_long_press)" \
       "power_menu_long_press_for_assist=$(settings get secure power_menu_long_press_for_assist)"
}

# ───────────────────────── 常驻监听 ─────────────────────────
daemon_pid() { pidof app_process 2>/dev/null | tr ' ' '\n' | while read -r p; do
  [ -n "$p" ] && tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | grep -q PowerKeyDaemon && echo "$p" && break
done; }

daemon_install() {
  jar="${1:-}"
  if [ -n "$jar" ]; then
    [ -f "$jar" ] || { echo "ERR 找不到 jar：$jar"; return 1; }
    mkdir -p "$DDAEMON"
    cp -f "$jar" "$DJAR"
  fi
  [ -f "$DJAR" ] || { echo "ERR 还没放 jar：$DJAR"; return 1; }
  chmod 755 "$DDAEMON" "$DJAR"
  mkdir -p /data/adb/service.d
  cat > "$DSVC" <<EOF
#!/system/bin/sh
# 长按电源键 → 桌面悬浮球（由 android/assets/pkctl.sh 生成，删掉这个文件即停用）
JAR=$DJAR
LOG=$DLOG
[ -f "\$JAR" ] || exit 0
i=0
while [ "\$(getprop sys.boot_completed)" != "1" ] && [ \$i -lt 180 ]; do sleep 1; i=\$((i+1)); done
sleep 5
while true; do
  if [ -z "\$(pm path $APP 2>/dev/null)" ]; then sleep 60; continue; fi
  CLASSPATH="\$JAR" /system/bin/app_process /system/bin PowerKeyDaemon $HOLD_MS >>"\$LOG" 2>&1
  sleep 5
done &
EOF
  chmod 755 "$DSVC"
  echo "ok 已装：$DJAR"
  echo "ok 开机自启：$DSVC"
}

daemon_start() {
  [ -f "$DJAR" ] || { echo "ERR 还没装守护进程（pkctl.sh setup <jar>）"; return 1; }
  p=$(daemon_pid)
  if [ -n "$p" ]; then echo "ok 已经在跑 pid=$p"; return 0; fi
  mkdir -p "$DDAEMON"
  # setsid：脱离当前 su 会话，父进程退出也不会被带走
  setsid sh -c "exec env CLASSPATH=$DJAR /system/bin/app_process /system/bin PowerKeyDaemon $HOLD_MS" \
    >>"$DLOG" 2>&1 </dev/null &
  sleep 2
  p=$(daemon_pid)
  if [ -n "$p" ]; then echo "ok 起来了 pid=$p（阈值 ${HOLD_MS}ms）"; else
    echo "ERR 没起来，看 $DLOG 和 logcat -s PowerKeyDaemon"; return 1
  fi
}

daemon_stop() {
  p=$(daemon_pid)
  if [ -n "$p" ]; then kill "$p" 2>/dev/null; echo "ok 停掉 pid=$p"; else echo "ok 本来就没在跑"; fi
}

daemon_status() {
  p=$(daemon_pid)
  echo "daemon_pid=${p:--}"
  echo "daemon_jar=$([ -f "$DJAR" ] && echo 1 || echo 0)  $DJAR"
  echo "daemon_svc=$([ -f "$DSVC" ] && echo 1 || echo 0)  $DSVC"
  [ -f "$DJAR" ] && echo "daemon_jar_md5=$(md5sum "$DJAR" 2>/dev/null | cut -d' ' -f1)"
}

# ───────────────────────── LSPosed 备胎 ─────────────────────────
restart_ui() {
  p=$(pid_of $UI)
  if [ -n "$p" ]; then kill "$p" 2>/dev/null; echo "ok 杀掉 $UI pid=$p（系统会自动重启它，钩子随之注入）"
  else echo "ok $UI 没在跑"; fi
}

# ───────────────────────── 小爱同学 ─────────────────────────
xiaoai() {
  what="${1:-status}"
  pkgs="com.miui.voiceassist com.miui.voiceassistoverlay com.miui.voicetrigger com.miui.touchassistant"
  case "$what" in
    off)
      for p in $pkgs; do
        [ "$(has_pkg $p)" = "1" ] && pm disable-user --user 0 "$p" >/dev/null 2>&1 && echo "ok disable $p"
      done
      echo "ok 小爱同学已停用（pkctl.sh xiaoai on 可恢复）"
      ;;
    on)
      for p in $pkgs; do
        [ "$(has_pkg $p)" = "1" ] && pm enable --user 0 "$p" >/dev/null 2>&1 && echo "ok enable $p"
      done
      ;;
    purge)
      for p in com.miui.voiceassist com.miui.voiceassistoverlay; do
        [ "$(has_pkg $p)" = "1" ] && pm uninstall --user 0 "$p" >/dev/null 2>&1 && echo "ok uninstall --user 0 $p"
      done
      echo "ok 小爱同学（用户 0）已卸载；恢复：pm install-existing <pkg>"
      ;;
    status)
      for p in $pkgs; do
        [ "$(has_pkg $p)" = "1" ] || continue
        st=$(dumpsys package "$p" 2>/dev/null | grep -m1 -E "^ +enabled=" | tr -d ' ')
        echo "xiaoai $p ${st:--}"
      done
      ;;
    *) echo "ERR xiaoai 只支持 off/on/purge/status" ;;
  esac
}

# ───────────────────────── 模拟长按（测触发用）─────────────────────────
uinput_power_dev() {
  # /proc/bus/input/devices：name 含 uinput 且 KEY 位图带 KEY_POWER(116) 的那个 eventN
  awk '
    /^N: Name=/     { name=$0; ev="" }
    /^H: Handlers=/ { k=split($0, a, "="); if (k>1) { m=split(a[2], b, " "); for (i=1;i<=m;i++) if (b[i] ~ /^event[0-9]+$/) ev=b[i] } }
    /^B: KEY=/      { if (name ~ /uinput/ && ev != "") print "/dev/input/" ev }
  ' /proc/bus/input/devices 2>/dev/null | sort -u | head -1
}

sim_longpress() {
  dev=$(uinput_power_dev)
  [ -n "$dev" ] || { echo "ERR 没找到 uinput 的 KEY_POWER 节点（这台机器没法用 sendevent 模拟）"; return 1; }
  echo "注入 $dev：KEY_POWER down → 700ms → up"
  sendevent "$dev" 1 116 1; sendevent "$dev" 0 0 0
  sleep 0.7
  sendevent "$dev" 1 116 0; sendevent "$dev" 0 0 0
}


# ───────────────────────── 玻璃参数还原 ─────────────────────────
# 现象：球的折射/亮度跑偏（背景被放大得离谱、整体发亮），设置页却没动过。
# 原因：demo 页（liquid-glass.html）早年一打开就把滑杆值 push 给球 —— 球的
#       float_params 被悄悄改成 zoom=1.63 / blur=0.3 之类，跟 demo 默认不一样了。
# 这条命令把 float_params 写回 demo 默认值（zoom 1.00 / blur 1.8 / refraction 200 …）。
glass_reset() {
  # 用法：glass-reset [blur]   默认 blur=0.3（用户喜欢的：demo 默认 + 只把模糊调到 0.3）
  BLUR="${2:-0.3}"
  if [ -z "$PY" ]; then echo "ERR 没有 python3"; return 1; fi
  am force-stop "$APP" >/dev/null 2>&1
  sleep 1
  BLUR="$BLUR" LD_LIBRARY_PATH="$PYLIB" "$PY" - <<'PYEOF'
import re, html, json, os
p="/data/data/com.glslplayground.app/shared_prefs/watch.xml"
d={"master":1,"posX":0.5,"posY":0.5,"halfX":0.37,"halfY":0.25,"corner":1,
   "refraction":200,"band":170,"zoom":1,"dome":0.4,"aberration":10,
   "blur":float(os.environ.get("BLUR","0.3")),
   "topDarken":0.93,"black0":-1,"black1":-0.01,"edgeBleed":0.11,
   "waveAmp":1.9,"waveScale":0.85,"waveOn":True,"waveMode":1}
s=open(p,encoding="utf-8").read()
esc=html.escape(json.dumps(d, ensure_ascii=False, separators=(",",":")), quote=True)
s,n=re.subn(r'<string name="float_params">.*?</string>',
            '<string name="float_params">%s</string>' % esc, s, flags=re.S)
open(p,"w",encoding="utf-8").write(s)
print("ok 写回 demo 默认参数（替换 %d 处）" % n)
PYEOF
  am start -n "$APP/.MainActivity" --ez auto_float true >/dev/null 2>&1
  echo "ok 球已按 demo 默认参数重启"
}

case "${1:-status}" in
  status)
    echo "root=1"
    echo "app=$(has_pkg $APP)  app_apk=$(apk_of $APP)"
    echo "mod=$(has_pkg $MOD)"
    echo "lspd=$([ -f "$DB" ] && echo 1 || echo 0)"
    echo "long_press_power_key=$(settings get system long_press_power_key 2>/dev/null)"
    echo "power_button_long_press=$(settings get global power_button_long_press 2>/dev/null)"
    echo "power_menu_long_press_for_assist=$(settings get secure power_menu_long_press_for_assist 2>/dev/null)"
    echo "systemui_pid=$(pid_of $UI)"
    echo "ball_pid=$(pid_of $APP)"
    daemon_status
    if [ -f "$DB" ] && [ -n "$PY" ]; then db query; fi
    ;;
  setup)
    jar="${2:-}"
    [ -n "$jar" ] || { echo "用法：pkctl.sh setup <powerkey-daemon.jar> [glsl-playground.apk] [powerkey-hook.apk]"; exit 1; }
    [ -n "${3:-}" ] && { install_apk "$3" || exit 1; }
    set_power_settings
    daemon_install "$jar" || exit 1
    daemon_stop >/dev/null 2>&1
    daemon_start || exit 1
    if [ -n "${4:-}" ]; then
      install_apk "$4" && db enable "$UI" && restart_ui
    fi
    echo "---- 自检 ----"
    sleep 2
    logcat -d -s PowerKeyDaemon 2>/dev/null | tail -5
    echo "下一步：长按电源键试试；或者跑 pkctl.sh test"
    ;;
  settings) set_power_settings ;;
  glass-reset) glass_reset ;;
  daemon-start) daemon_start ;;
  daemon-stop) daemon_stop ;;
  daemon-restart) daemon_stop; daemon_start ;;
  daemon-status) daemon_status ;;
  install-daemon) daemon_install "${2:-}" ;;
  enable)
    set_power_settings
    db enable "$UI" || exit 1
    restart_ui
    ;;
  disable)
    db disable || exit 1
    restart_ui
    echo "ok LSPosed 模块已停用（常驻监听不受影响）"
    ;;
  restart-ui) restart_ui ;;
  xiaoai) xiaoai "${2:-status}" ;;
  fire) am broadcast -a "$ACT" -n "$RECV" --user 0 2>&1 | tail -1 ;;
  test) sim_longpress ;;
  uninstall)
    daemon_stop
    rm -f "$DSVC"
    echo "ok 删掉开机自启 $DSVC（jar 留在 $DJAR，删目录即彻底清掉）"
    if [ -f "$DB" ] && [ -n "$PY" ]; then db disable; fi
    echo "ok 完成；系统设置没有回滚（settings put 三条保持现状）"
    ;;
  *)
    echo "子命令：status | setup <jar> [app.apk] [mod.apk] | settings | glass-reset | daemon-start|daemon-stop|daemon-restart|daemon-status|install-daemon <jar> | enable|disable|restart-ui | xiaoai off|on|purge|status | fire | test | uninstall"
    exit 1
    ;;
esac
