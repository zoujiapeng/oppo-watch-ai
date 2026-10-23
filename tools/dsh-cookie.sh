#!/data/data/com.termux/files/usr/bin/bash
# dsh-cookie.sh —— 把「你正在用的那个 DSH」的会话 cookie 抄进 ~/dsh.cookie，
# 让悬浮水晶球直接连它，不必再起第二个 DSH、也不必多占端口。
#
# 两条来源，按顺序试：
#   ① dshpj 的反代进程（dshpj-proxy.mjs）环境里就带着一条 30 天有效的
#      `dsh-auth-…=v1.…` cookie；root 读一次 /proc/<pid>/environ 即可抄出来。
#   ② 只有局域网前端（pj-lan.mjs）在跑时：它的命令行里带 `--target <DSH端口> --token <启动token>`，
#      拿这个 token 去 `GET /?token=` 换一张 cookie（和 App 自己那条路完全一样的握手）。
#
# 用法：
#   bash tools/dsh-cookie.sh          # 生成/刷新 ~/dsh.cookie
#   bash tools/dsh-cookie.sh --check  # 只打印有没有、能不能用，不写盘
set -uo pipefail

H="${HOME:-/data/data/com.termux/files/home}"
OUT="$H/dsh.cookie"

# ── ① dshpj-proxy 的 PJ_PROXY_COOKIE ────────────────────────────────────────
# 一次 root shell 扫完所有进程（每个 PID 单独 su 会慢到几十秒）
found=$(su -c 'for p in /proc/[0-9]*; do
  c=$(tr "\0" " " < "$p/cmdline" 2>/dev/null) || continue
  case "$c" in
    *dshpj-proxy*)
      pid=${p#/proc/}
      ck=$(tr "\0" "\n" < "$p/environ" 2>/dev/null | sed -n "s/^PJ_PROXY_COOKIE=//p" | head -1)
      [ -n "$ck" ] && { printf "%s %s\n" "$pid" "$ck"; break; }
      ;;
  esac
done' 2>/dev/null)

pid=${found%% *}
cookie=${found#* }
port=""

# ── ② pj-lan.mjs 的 --token → 换 cookie ─────────────────────────────────────
if [ -z "${pid:-}" ] || [ "$pid" = "$found" ]; then
  lan=$(su -c 'for p in /proc/[0-9]*; do
    c=$(tr "\0" " " < "$p/cmdline" 2>/dev/null) || continue
    case "$c" in
      *pj-lan.mjs*)
        pid=${p#/proc/}
        printf "%s %s\n" "$pid" "$c"; break
        ;;
    esac
  done' 2>/dev/null)
  lanpid=${lan%% *}
  lancmd=${lan#* }
  if [ -n "${lanpid:-}" ] && [ "$lanpid" != "$lan" ]; then
    port=$(printf '%s\n' "$lancmd" | sed -n 's/.*--target \([0-9]\+\).*/\1/p')
    tok=$(printf '%s\n' "$lancmd" | sed -n 's/.*--token \([A-Za-z0-9_-]\+\).*/\1/p')
    if [ -n "${port:-}" ] && [ -n "${tok:-}" ]; then
      cookie=$(curl -sS -D - -o /dev/null --max-time 5 \
        "http://127.0.0.1:$port/?token=$tok" 2>/dev/null \
        | sed -n 's/^[Ss]et-[Cc]ookie: \([^;]*\).*/\1/p' | tr -d '\r')
      pid=$lanpid
    fi
  fi
fi

if [ -z "${cookie:-}" ]; then
  echo "既没找到 PJ_PROXY_COOKIE（dshpj 反代），也没能从 pj-lan 的 --token 换到 cookie。" >&2
  echo "先确认 DSH 在跑（ps -A | grep 3081 / ~/.dshpj/pj-logs），再重跑这个脚本。" >&2
  exit 1
fi

case "$cookie" in
  dsh-auth-*=v1.*) ;;
  *) echo "cookie 形状不对：${cookie:0:16}…" >&2; exit 1;;
esac

if [ "${1:-}" = "--check" ]; then
  echo "来源 PID=$pid  cookie=${cookie:0:24}…  （未写盘）"
  [ -n "${port:-}" ] && echo "对应 DSH 端口：$port"
  exit 0
fi

printf '%s\n' "$cookie" > "$OUT"
chmod 600 "$OUT"
echo "已写入 $OUT"
echo "  ${cookie:0:24}…（30 天有效；DSH 重启后要重跑一次）"
if [ -n "${port:-}" ]; then
  echo "App 侧：设置页「DSH 地址」= http://127.0.0.1:$port"
else
  echo "App 侧：设置页「DSH 地址」= http://127.0.0.1:3080（dshpj 反代端口）"
fi
