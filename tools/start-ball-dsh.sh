#!/data/data/com.termux/files/usr/bin/bash
# start-ball-dsh.sh —— 给「悬浮水晶球」起一个专用的本机 DSH 后端（默认 3090），
# 并把启动横幅（含 launch token）写进 ~/dsh.log —— App 只认这个文件里的 token。
#
# 为什么不用主 DSH（3080）：主 DSH 的 token 只打印在它自己的终端里，没有落盘到 dsh.log，
# App 读不到就换不到 cookie。这个脚本用独立的 DSH_HOME 起第二个实例，互不干扰。
#
# 用法：
#   bash tools/start-ball-dsh.sh            # 起（已在跑就只补一条会话）
#   BALL_DSH_PORT=3091 bash tools/start-ball-dsh.sh
#   bash tools/start-ball-dsh.sh stop       # 停
set -uo pipefail

PORT="${BALL_DSH_PORT:-3090}"
H="${HOME:-/data/data/com.termux/files/home}"
STATE="${BALL_DSH_HOME:-$H/.dsh-watch-test}"
LOG="$H/dsh.log"
BIN="/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
BASE="http://127.0.0.1:$PORT"

running() { curl -s -o /dev/null --max-time 2 "$BASE/"; }

cookie_of() {
  curl -sS -D - -o /dev/null "$BASE/?token=$1" 2>/dev/null \
    | sed -n 's/^[Ss]et-[Cc]ookie: \([^;]*\).*/\1/p'
}

ensure_session() {
  local token cookie n
  token=$(grep -ao 'token=[A-Za-z0-9_-]*' "$LOG" | tail -1 | cut -d= -f2)
  [ -n "${token:-}" ] || { echo "!! $LOG 里没有 token"; return 1; }
  cookie=$(cookie_of "$token")
  [ -n "${cookie:-}" ] || { echo "!! token 换不到 cookie"; return 1; }
  n=$(curl -sS -H "Cookie: $cookie" -H 'content-type: application/json' \
        -d '{"type":"client-request","rpcId":"count","method":"session/list","payload":{"args":{"_request":{}}}}' \
        "$BASE/api/session/list" \
      | python3 -c 'import sys,json; print(len(json.load(sys.stdin)["result"]["value"]["items"]))' 2>/dev/null)
  if [ "${n:-0}" = "0" ]; then
    # App 建会话时用的是它自己的 files 目录（/data/user/0/...），Termux 这边 DSH 建不了；
    # 这里先替它建一条，App 的 session/list 就不会为空、也就不会去 create。
    curl -sS -H "Cookie: $cookie" -H 'content-type: application/json' \
      -d "{\"type\":\"client-request\",\"rpcId\":\"mk\",\"method\":\"session/create\",\"payload\":{\"args\":{\"request\":{\"cwd\":\"$H\"}}}}" \
      "$BASE/api/session/create" >/dev/null
    echo "   已预建一条会话（cwd=$H）"
  else
    echo "   已有 $n 条会话"
  fi
}

case "${1:-start}" in
  stop)
    pkill -f -- "--profile web --port $PORT" 2>/dev/null
    echo "stopped :$PORT"
    exit 0
    ;;
esac

if running; then
  echo "== DSH 已在 :$PORT 上跑"
  ensure_session
  exit 0
fi

mkdir -p "$STATE"
[ -f "$STATE/.credentials.yaml" ] || cp "$H/.dsh/.credentials.yaml" "$STATE/" 2>/dev/null
[ -d "$STATE/profiles" ] || cp -r "$H/.dsh/profiles" "$STATE/" 2>/dev/null
[ -d "$STATE/.agent-presets" ] || cp -r "$H/.dsh/.agent-presets" "$STATE/" 2>/dev/null

echo "== 启动 DSH :$PORT（DSH_HOME=$STATE，横幅 >> $LOG）"
# 记下当前日志长度：只认这次新写进去的横幅，避免读到上一次启动的旧 token
OFFSET=$(wc -c < "$LOG" 2>/dev/null || echo 0)
# setsid：脚本退出后子进程仍留在自己的会话里（Termux 会把脚本的进程组一起收掉）
DSH_HOME="$STATE" setsid nohup node --expose-internals "$BIN" \
  --profile web --port "$PORT" --no-open >> "$LOG" 2>&1 < /dev/null &

for i in $(seq 1 60); do
  sleep 1
  if tail -c +$((OFFSET + 1)) "$LOG" 2>/dev/null | grep -aq "dsh web: .*:$PORT"; then break; fi
done
tail -c +$((OFFSET + 1)) "$LOG" | grep -ao "dsh web: .*:$PORT/?token=[A-Za-z0-9_-]*" | tail -1 || {
  echo "!! 起不来，看 $LOG"; exit 1; }
for i in $(seq 1 30); do running && break; sleep 1; done

sleep 1
ensure_session
echo "== 完成：App 设置页「DSH 地址」填 $BASE，再点「问一句（测试链路）」"
