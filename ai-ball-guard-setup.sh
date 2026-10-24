#!/data/data/com.termux/files/usr/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# ai-ball-guard-setup.sh —— 一条命令把这台手机的「AI 不误触悬浮球」修好（幂等）
#
# 给 AI 用：跑 `bash ai-ball-guard-setup.sh`；最后一行 `RESULT: PASS|FAIL`，
# 退出码 0 = 全通。FAIL 时按打印的 NEXT 做，别自己发明步骤。
#
#   bash ai-ball-guard-setup.sh                 # 装/修，但不重启 DSH（会打印重启办法）
#   bash ai-ball-guard-setup.sh --restart-dsh   # 装/修 + 3 秒后后台重启 DSH（本会话会断一下）
#   bash ai-ball-guard-setup.sh --check         # 只看状态，不改任何东西
#
# 它做四件事（每件都幂等）：
#   ① 球：装本仓库的 APK → 起前台服务 → 等到 127.0.0.1:3181 回 ALIVE
#   ② 插件：装/升级 dsh-preset-mobile-use → 打让路补丁 → 注册进 DSH profile
#   ③ DSH：按需重启（插件是 ESM，只有重启才进内存）
#   ④ 验证：doctor 八环 + 插件真机贯通
#
# 设计前提（别改）：
#   · 只装/升级，**从不卸载、从不改球设置**（shared_prefs 原样保留）；
#   · 球的所有者数据、DSH 的会话都不碰；
#   · 没有 root 也能跑完 ②，但 ① 的"起服务"和 ④ 的部分检查会退化 → 会明确说出来。
# ═══════════════════════════════════════════════════════════════════════════════
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$HERE"
LOGDIR="${TMPDIR:-$HOME/.cache}/bg-setup"
mkdir -p "$LOGDIR"
PKG="${BALL_PKG:-com.glslplayground.app}"
APK="$REPO/glsl-playground.apk"
PORT="${BALL_GATE_PORT:-3181}"
PLUGIN_DIR="${PLUGIN_DIR:-$HOME/dsh-preset-mobile-use}"
PROFILE="${DSH_PROFILE_DIR:-$HOME/.dsh/profiles/web}"
PLUGIN_REPO="${PLUGIN_REPO:-https://github.com/AcidGr/dsh-preset-mobile-use.git}"
MODE="install"

for a in "$@"; do
  case "$a" in
    --restart-dsh) MODE="install-restart" ;;
    --check|check) MODE="check" ;;
    -h|--help) sed -n '2,28p' "$0"; exit 0 ;;
    *) echo "unknown arg: $a"; exit 2 ;;
  esac
done

STEP_FAIL=0
ok()   { printf '  [ok] %s\n' "$*"; }
bad()  { printf '  [!!] %s\n' "$*"; STEP_FAIL=$((STEP_FAIL+1)); }
inf()  { printf '  [--] %s\n' "$*"; }
next() { printf '       NEXT: %s\n' "$*"; }

have() { command -v "$1" >/dev/null 2>&1; }
asu()  { su -c "$*" 2>/dev/null; }
is_root() { [ "$(id -u 2>/dev/null || echo 0)" = "0" ] || asu id | grep -q "uid=0"; }

tcp_alive() { # $1=port → 0 if ball answered ALIVE
  local p="${1:-$PORT}" rep=""
  rep=$(printf 'PING\n' | nc -w 1 127.0.0.1 "$p" 2>/dev/null | head -1 | tr -d '\r')
  [ -z "$rep" ] && [ -x /system/bin/nc ] && rep=$(printf 'PING\n' | /system/bin/nc -w 1 127.0.0.1 "$p" 2>/dev/null | head -1 | tr -d '\r')
  printf '%s' "$rep" | grep -q '^ALIVE'
}

svc_running() { [ "$(dumpsys activity services "$PKG" 2>/dev/null | grep -c FloatingBallService)" -gt 0 ]; }

echo "════════ 0) 环境 ════════"
inf "仓库：$REPO"
inf "APK ：$APK"
inf "插件：$PLUGIN_DIR"
inf "profile：$PROFILE"
inf "模式：$MODE"
case "$HOME" in
  /data/data/com.termux/files/home) ok "HOME 是标准 Termux 路径" ;;
  *) bad "HOME=$HOME 不是标准路径（App 读 token 会失败，球的助手功能不完整）" ;;
esac
for c in git node nc pnpm python3; do have "$c" && ok "有 $c" || bad "缺 $c（pkg install ${c/nc/netcat-openbsd}）"; done
if is_root; then ok "root 可用（su 正常）"; else inf "没有 root：能装插件，但起服务/读内部目录会退化"; fi
if [ -f "$APK" ]; then ok "仓库里有 APK（$(du -h "$APK" | cut -f1)）"; else bad "缺 $APK（这个仓库没同步全？先 git pull）"; fi

if [ "$MODE" = "check" ]; then
  echo
  echo "════════ 检查模式：只报告，不改 ════════"
  svc_running && ok "球服务在跑" || bad "球服务没跑"
  tcp_alive && ok "TCP 门控在 $PORT 应答" || bad "TCP 门控没应答（球是老 APK 或没跑）"
  grep -q "BALL_GATE_PORTS" "$PLUGIN_DIR/lib/index.js" 2>/dev/null && ok "插件是新版" || bad "插件缺失或不是新版"
  echo; echo "RESULT: $([ "$STEP_FAIL" -eq 0 ] && echo PASS || echo FAIL)"
  exit $([ "$STEP_FAIL" -eq 0 ] && echo 0 || echo 1)
fi

echo
echo "════════ 1) 球（APK + 前台服务 + TCP 门控）════════"
if tcp_alive; then
  ok "已经是新版球且 TCP 门控在 $PORT 应答（跳过安装）"
else
  if [ -f "$APK" ]; then
    inf "安装/升级 APK（不会动 shared_prefs、不卸载）…"
    if pm install -r "$APK" 2>&1 | tail -1 | grep -qi success; then ok "APK 安装成功"
    else bad "APK 安装失败"; next "看 pm install -r \"$APK\" 的完整报错（签名冲突就先让用户确认再 pm uninstall）"; fi
  else
    bad "没有 APK 可装"
  fi
  sleep 1
  if ! svc_running; then
    inf "起悬浮球前台服务…"
    asu "/system/bin/am start-foreground-service -n $PKG/.FloatingBallService" >/dev/null 2>&1
    sleep 2
  fi
  for i in 1 2 3 4 5 6 7 8 9 10; do tcp_alive && break; sleep 1; done
  if tcp_alive; then ok "TCP 门控起来了（127.0.0.1:$PORT）"
  else
    bad "TCP 门控没应答"
    next "球服务可能没起：打开 App → 设置 →「悬浮于桌面」；或 su -c '/system/bin/am start-foreground-service -n $PKG/.FloatingBallService'"
    next "还不行就看 logcat -s FloatingBall（应有 'gate tcp listening 127.0.0.1:3181'）"
  fi
fi

echo
echo "════════ 2) DSH 侧插件（让路那半边）════════"
if [ ! -d "$PLUGIN_DIR" ]; then
  inf "插件 bundle 不存在，clone 一份…"
  if git clone --depth 1 "$PLUGIN_REPO" "$PLUGIN_DIR" >/dev/null 2>&1; then ok "已 clone 到 $PLUGIN_DIR"
  else bad "clone 失败（网络？）"; next "git clone $PLUGIN_REPO $PLUGIN_DIR  然后重跑本脚本"; fi
fi
if [ -d "$PLUGIN_DIR" ]; then
  if bash "$REPO/patches/install-ball-guard-plugin.sh" >$LOGDIR/plugin-install.log 2>&1; then
    ok "插件补丁 + 注册完成"
  else
    bad "插件补丁/注册失败"; next "看 $LOGDIR/plugin-install.log（常见：profile 里没 package.json → 先起一次 DSH）"
  fi
  grep -q "BALL_GATE_PORTS" "$PLUGIN_DIR/lib/index.js" 2>/dev/null && ok "校验：插件里已有 TCP 通道" || bad "校验：插件里没有 TCP 通道"
  tail -4 $LOGDIR/plugin-install.log | sed 's/^/       /'
fi

echo
echo "════════ 3) 重启 DSH（插件是 ESM，只有重启才进内存）════════"
DSH_LINE=$(ps -A -o pid,args 2>/dev/null | grep -E "dsh/lib/bin\.js" | grep -v grep | head -1)
DSH_PID=$(printf '%s' "$DSH_LINE" | awk '{print $1}')
if [ -z "${DSH_PID:-}" ]; then
  bad "没找到正在跑的 dsh 进程"; next "先起 DSH（dsh web --port 3080 或你平时的命令）再重跑 --check"
elif [ "$MODE" != "install-restart" ]; then
  inf "不自动重启（加 --restart-dsh 才会）。手动方式："
  echo "       kill $DSH_PID   # 然后按平时的方式重新起 dsh（token 会重新写进 ~/dsh.log）"
  echo "       或者： bash $0 --restart-dsh"
else
  read -r -a CMDLINE <<< "$(ps -o args= -p "$DSH_PID")"
  QCMD=$(printf '%q ' "${CMDLINE[@]}")
  SCRIPT="$HOME/.dsh-restart-ball-guard.sh"
  cat > "$SCRIPT" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
sleep 3
kill $DSH_PID 2>/dev/null
sleep 2
cd "$HOME"
exec setsid nohup $QCMD >> "$HOME/dsh.log" 2>&1 &
EOF
  chmod +x "$SCRIPT"
  if setsid nohup bash "$SCRIPT" >/dev/null 2>&1 & then
    ok "已安排 3 秒后重启 DSH（本会话会断一下，重连后继续）"
    inf "重启后回来看： bash $REPO/android/tools/ball-gate-doctor.sh"
  else
    bad "安排重启失败"; next "手动 kill $DSH_PID 后重新起 dsh"
  fi
fi

echo
echo "════════ 4) 验证 ════════"
echo "── doctor"
bash "$REPO/android/tools/ball-gate-doctor.sh" 2>&1 | sed 's/^/   /' | tail -40
DOCTOR_RC=${PIPESTATUS[0]:-1}
echo "── 插件真机贯通"
if node "$REPO/android/tools/ball-guard-plugin-e2e.mjs" click >$LOGDIR/e2e.log 2>&1; then
  ok "e2e 全通"; grep -E "^\[ball\] 返回|★" $LOGDIR/e2e.log | sed 's/^/   /'
else
  bad "e2e 未全通"; tail -12 $LOGDIR/e2e.log | sed 's/^/   /'
fi

echo
echo "════════ 结论 ════════"
if [ "$STEP_FAIL" -eq 0 ]; then
  echo "RESULT: PASS"
  echo "球侧 + 插件侧都就位。重启 DSH 之后，agent 的每次点击都会先请球让路；"
  echo "证据在 $HOME/dsh-ball-guard.log（via=tcp:3181 就是走了新通道）。"
  exit 0
else
  echo "RESULT: FAIL（$STEP_FAIL 处）"
  echo "按上面每条 NEXT 处理；处理完重跑： bash $0"
  exit 1
fi
