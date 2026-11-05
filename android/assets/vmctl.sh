#!/system/bin/sh
# VoiceMap 控制面 —— glslplayground 设置页 → su → 这个脚本
#
# 由 App 在运行期从 assets 拷到私有目录（files/vmctl.sh）再以 root 执行，
# 所以这里不用管路径、也不用装任何东西。所有输出都是 key=value 一行一条，
# Java 侧直接拼成 JSON 给设置页读。
#
# 子命令：
#   status                 环境自检（是否装了/LSPosed 在不在/模块启用没有/作用域/进程）
#   listime                本机输入法列表（每行 ime=<pkg>）
#   enable [imePkg]        在 LSPosed 里启用模块 + 设作用域（默认取当前默认输入法）
#   disable                在 LSPosed 里停用模块
#   restart                杀掉输入法进程（下次输入时自动重启并注入模块）
#   cmd <cmd> [args]       把命令广播给输入法进程里的模块
#                          calibrate / dump / clearcal / stealthon / stealthoff
#                          register <pkg>（把某个 App 设成"隐身 + 可触发"的目标）
#   trigger <down|up>      按住说话：往输入法键盘注入 ACTION_DOWN/UP
#   install <apk> <modapk> 装触发 App + 模块（-r -g）
#   uninstall              卸载两个包
#   lspd-install <zip>     用 magisk 装 LSPosed 模块（重启后生效）
#   log                    取最近一段 VoiceMap / LSPosed 日志
set -u

D=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
DB=/data/adb/lspd/config/modules_config.db
APP=com.pj.voicemap
MOD=com.pj.voicemap.ime
TRIG=com.pj.voicemap.TRIGGER

PY=
for c in /data/data/com.termux/files/usr/bin/python3 /usr/bin/python3 python3; do
  if command -v "$c" >/dev/null 2>&1; then PY=$c; break; fi
done

ime_cur() {
  v=$(settings get secure default_input_method 2>/dev/null)
  case "$v" in
    ""|null) ;;
    *) printf '%s\n' "${v%%/*}" ;;
  esac
}
pid_of() { pidof "$1" 2>/dev/null | awk '{print $1}'; }
has_pkg() { [ -n "$(pm path "$1" 2>/dev/null)" ] && echo 1 || echo 0; }

# 模块活在输入法进程里，命令必须指定 -p 才不会被系统拦成隐式广播
bc() {
  ime=$(ime_cur)
  if [ -z "$ime" ]; then echo "ERR no ime"; return 1; fi
  am broadcast -a "$TRIG" -p "$ime" "$@" 2>&1 | tail -1
}

db() {
  if [ ! -f "$DB" ]; then
    echo "ERR 本机没有 LSPosed：先点「下载并安装 LSPosed」并重启"
    return 1
  fi
  if [ -z "$PY" ]; then echo "ERR 没有 python3（LSPosed 数据库要它来写）"; return 1; fi
  "$PY" "$D/vm_lspd.py" "$@"
}

case "${1:-status}" in
  status)
    echo "root=1"
    echo "app=$(has_pkg $APP)"
    echo "mod=$(has_pkg $MOD)"
    echo "lspd=$([ -f "$DB" ] && echo 1 || echo 0)"
    echo "py=$([ -n "$PY" ] && echo 1 || echo 0)"
    ime=$(ime_cur)
    echo "ime=${ime:--}"
    echo "imepid=$(pid_of "${ime:-none}")"
    echo "magisk=$(command -v magisk >/dev/null 2>&1 && echo 1 || echo 0)"
    if [ -f "$DB" ] && [ -n "$PY" ]; then
      db query 2>/dev/null
    fi
    echo "imes=$(ime list -a -s 2>/dev/null | cut -d/ -f1 | tr '\n' ',' | sed 's/,$//')"
    ;;
  listime)
    ime list -a -s 2>/dev/null | while IFS= read -r l; do
      case "$l" in */*) echo "ime=${l%%/*}" ;; esac
    done
    ;;
  enable)
    ime="${2:-$(ime_cur)}"
    if [ -z "$ime" ]; then echo "ERR 没有可用输入法"; exit 1; fi
    db enable "$ime"
    ;;
  disable)
    db disable
    ;;
  restart)
    ime=$(ime_cur)
    p=$(pid_of "${ime:-none}")
    if [ -n "$p" ]; then
      kill "$p" 2>/dev/null
      echo "ok killed $ime pid=$p"
    else
      echo "ok 输入法进程没在跑，不需要重启"
    fi
    ;;
  cmd)
    sub="${2:-}"
    shift 2 2>/dev/null || true
    case "$sub" in
      register)
        bc --es cmd register --es pkg "${1:-com.glslplayground.app}"
        ;;
      calibrate|dump|clearcal|stealthon|stealthoff)
        bc --es cmd "$sub"
        ;;
      *)
        echo "ERR 不认识的命令：$sub"
        ;;
    esac
    ;;
  trigger)
    dir="${2:-down}"
    case "$dir" in
      down|up) bc --es cmd "$dir" ;;
      *) echo "ERR 只支持 down/up" ;;
    esac
    ;;
  install)
    a="${2:-}"; m="${3:-}"
    if [ -z "$a" ] || [ -z "$m" ]; then echo "ERR 缺少 apk 路径"; exit 1; fi
    echo "== app"; pm install -r -g --user 0 "$a" 2>&1 | tail -1
    echo "== mod"; pm install -r -g --user 0 "$m" 2>&1 | tail -1
    echo "ok app=$(has_pkg $APP) mod=$(has_pkg $MOD)"
    ;;
  uninstall)
    pm uninstall --user 0 "$APP" 2>&1 | tail -1
    pm uninstall --user 0 "$MOD" 2>&1 | tail -1
    echo "ok app=$(has_pkg $APP) mod=$(has_pkg $MOD)"
    ;;
  lspd-install)
    z="${2:-}"
    if [ -z "$z" ] || [ ! -f "$z" ]; then echo "ERR zip 不存在：$z"; exit 1; fi
    if ! command -v magisk >/dev/null 2>&1; then echo "ERR 没有 magisk"; exit 1; fi
    magisk --install-module "$z" 2>&1 | tail -5
    echo "ok 已装入 magisk，重启后 LSPosed 生效"
    ;;
  log)
    logcat -d -s LSPosed-Bridge:I VoiceMap:I 2>/dev/null | grep -a -E "VoiceMap|LSPosed" | tail -30
    ;;
  *)
    echo "ERR 未知子命令：$1"
    ;;
esac
