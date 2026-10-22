#!/data/data/com.termux/files/usr/bin/bash
# dsh-cookie.sh —— 把「你正在用的那个 DSH」的会话 cookie 抄进 ~/dsh.cookie，
# 让悬浮水晶球直接连它（dshpj 起在 3080 的那个），不必再起第二个 DSH、也不必多占端口。
#
# 原理：dshpj 的反代进程（dshpj-proxy.mjs）环境里就带着一条 30 天有效的
# `dsh-auth-…=v1.…` cookie；root 读一次 /proc/<pid>/environ 即可抄出来。
#
# 用法：
#   bash tools/dsh-cookie.sh          # 生成/刷新 ~/dsh.cookie
#   bash tools/dsh-cookie.sh --check  # 只打印有没有、能不能用，不写盘
set -uo pipefail

H="${HOME:-/data/data/com.termux/files/home}"
OUT="$H/dsh.cookie"

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

if [ -z "${pid:-}" ] || [ "$pid" = "$found" ]; then
  echo "没找到带 PJ_PROXY_COOKIE 的 dshpj 反代进程 —— dshpj 没在跑？先起 dshpj 再跑这个脚本" >&2
  exit 1
fi

case "$cookie" in
  dsh-auth-*=v1.*) ;;
  *) echo "cookie 形状不对：${cookie:0:16}…" >&2; exit 1;;
esac

if [ "${1:-}" = "--check" ]; then
  echo "反代 PID=$pid  cookie=${cookie:0:24}…  （未写盘）"
  exit 0
fi

printf '%s\n' "$cookie" > "$OUT"
chmod 600 "$OUT"
echo "已写入 $OUT"
echo "  ${cookie:0:24}…（30 天有效；DSH 重启后要重跑一次）"
echo "App 侧：设置页「DSH 地址」= http://127.0.0.1:3080"
