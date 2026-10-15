#!/data/data/com.termux/files/usr/bin/bash
# 给 agent_mobile_use 的 CLI 包装脚本 /system/bin/vd 打「球让路」补丁（幂等）。
#
# 背景：悬浮球（com.glslplayground.app）常驻屏幕中间，DSH 注入的触摸会被球吃掉。
# 完整方案见 android/docs/BALL_TOUCH_GUARD.md —— DSH 侧在 /api/action 之前写
# /data/local/tmp/dsh-ball-act，球看到信号后把自己从命中测试里摘出去，写完 ACK 才注入。
#
# 这个补丁覆盖的是「手动 vd tap/swipe 调试」这条路径（agent 走 3070 端口，由
# dsh-preset-mobile-use 负责）。补丁内容：tap/swipe/key/case 之前先写一次信号文件。
#
# 为什么改 /data/adb/modules/.../system/bin/vd：Magisk 每次开机把 modules/<id>/system
# 里的文件 bind-mount 成 /system/bin/vd，改这份才是持久的；直接改 /system/bin/vd 重启就没了。
#
# 卸载：删掉脚本里 `# >>> ball-guard (begin/end)` 之间的 8 行，或把 vd.bak-ballguard 还原。

set -euo pipefail

MODVD=/data/adb/modules/agent_mobile_use/system/bin/vd
BEGIN='# >>> ball-guard (begin)'
END='# <<< ball-guard (end)'
# 信号目录 = 球的外部私有目录：球（untrusted_app 域）在 /data/local/tmp 根下能读不能写，
# 写 ACK 会 EACCES（目录是 shell_data_file 标签）；/sdcard/Android/data/<pkg>/files 才写得进去。
# 见 android/docs/BALL_TOUCH_GUARD.md。
GATEDIR=/sdcard/Android/data/com.glslplayground.app/files

if [ ! -f "$MODVD" ]; then
  echo "[vd-patch] 找不到 $MODVD（模块没装？）" >&2
  exit 2
fi

mkdir -p "$GATEDIR" 2>/dev/null || true
echo "[vd-patch] 信号目录 $GATEDIR（存在=$( [ -d "$GATEDIR" ] && echo yes || echo no )）"

if grep -q "ball_signal" "$MODVD"; then
  echo "[vd-patch] vd 已经打过补丁，跳过"
  exit 0
fi

cp -n "$MODVD" "$MODVD.bak-ballguard"
echo "[vd-patch] 备份 -> $MODVD.bak-ballguard"

python3 - "$MODVD" "$BEGIN" "$END" "$GATEDIR" <<'PY'
import sys, io

path, begin, end, gatedir = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
src = io.open(path, encoding='utf-8').read()

helper = f'''{begin}
# 通知悬浮球：agent 马上要注入触摸了（球会把自己从命中测试里摘出去）。
# 见 android/docs/BALL_TOUCH_GUARD.md；没有球在跑时就是写个没人看的文件，无副作用。
# 信号必须写进球能写的目录（{gatedir}）：/data/local/tmp 是 shell_data_file 标签，
# untrusted_app 域只能读不能写，球回 ACK 会 EACCES。
ball_signal() {{
    # 原子写：球每 60ms 读一次，直接 > 重定向会先截断，球可能读到半个 nonce 而 ACK 对不上
    printf '%s\\n' "$(date +%s)-vd-${{1:-touch}}" > {gatedir}/dsh-ball-act.tmp-$$ 2>/dev/null \
        && mv -f {gatedir}/dsh-ball-act.tmp-$$ {gatedir}/dsh-ball-act 2>/dev/null || true
}}
{end}
'''

anchor = 'SERVER_BIN="/data/adb/modules/agent_mobile_use/bin/vd_server"'
if anchor not in src:
    sys.exit("[vd-patch] 锚点没找到：SERVER_BIN 行")
src = src.replace(anchor, helper + '\n' + anchor, 1)

for cmd in ('tap)', 'swipe)', 'key)'):
    needle = f'    {cmd}\n'
    if needle not in src:
        sys.exit(f"[vd-patch] 锚点没找到：{cmd} 分支")
    src = src.replace(needle, f'    {cmd}\n        ball_signal "$1"\n', 1)

io.open(path, 'w', encoding='utf-8').write(src)
print('[vd-patch] 写入完成')
PY

chmod 755 "$MODVD"
# 模块 overlay 只在开机时挂载：本次生效需要手动同步一份到 /system/bin
cp "$MODVD" /system/bin/vd 2>/dev/null && chmod 755 /system/bin/vd || true

echo "[vd-patch] 结果："
grep -n "ball_signal\|ball-guard" "$MODVD"
sh -n "$MODVD" && echo "[vd-patch] 语法 OK"
