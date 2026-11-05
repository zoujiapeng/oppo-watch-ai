#!/data/data/com.termux/files/usr/bin/bash
# 重启之后跑这个：确认 root 回来 → 装最新 APK → 打印设置页里的下一步。
#
# 用途：本次会话里我直接写 Magisk 的授权库（magisk --sqlite UPDATE policies …），
# 把这个 Magisk 31.0 的 Rust 守护进程写崩了，su 变成 "Cannot connect to daemon"。
# 那个是**用户态守护进程**，重启就自己起来；Magisk 本体（打过补丁的 boot）没动过。
# 本脚本不碰 magisk.db，只检查 + 装包。
set -u

REPO=/data/data/com.termux/files/home/OPPO-watch-ai
APK="$REPO/glsl-playground.apk"

echo "== 1/3 检查 root"
out=$(su -c id 2>&1)
case "$out" in
  *uid=0*)
    echo "   OK  $out" ;;
  *"Cannot connect to daemon"*)
    echo "   ✗ magiskd 还是没起来：Magisk → 点「修复运行环境」（会再自动重启一次）"
    echo "     还不行的重刷 Magisk（boot 补丁在 /data/adb，通常不需要）"
    exit 1 ;;
  *"Permission denied"*|*"rejected"*|*"permission"*)
    echo "   ✗ root 被拒：屏幕上应该弹了 Magisk 授权框 → 点「允许」→ 再跑一次本脚本"
    exit 1 ;;
  *)
    echo "   ✗ 未知状态：$out"
    exit 1 ;;
esac

echo "== 2/3 装最新 APK（含「按住说话」不依赖 root 的那条路）"
# 刚开机时 PackageManager 常常还没就绪，第一次 pm install 会报
# "Failure calling service package: Failed transaction" —— 重试几次。
ok=0
for i in 1 2 3 4 5; do
  out=$(su -c "pm install -r '$APK'" 2>&1)
  echo "$out" | sed 's/^/   /'
  case "$out" in
    *Success*) ok=1; break ;;
  esac
  echo "   （第 $i 次没成功，等 3 秒重试）"
  sleep 3
done
if [ "$ok" != "1" ]; then
  echo "   ✗ 装包失败：把上面 pm 的输出发我"
  exit 1
fi
echo "   OK"

echo "== 3/3 LSPosed / 模块状态"
if [ -f /data/adb/lspd/config/modules_config.db ]; then
  echo "   LSPosed：已安装（重启后 DB 已就绪）"
  # 一次性把「键盘隐身」那一档需要的手工点按也做掉：
  #   启用模块（作用域=当前默认输入法）→ 重启输入法进程 → 把本 App 设为隐身目标
  echo "   → 启用 VoiceMap 模块（作用域=当前默认输入法）"
  su -c "sh '$REPO/android/assets/vmctl.sh' enable"
  echo "   → 重启输入法进程（让模块注入）"
  su -c "sh '$REPO/android/assets/vmctl.sh' restart"
  echo "   → 把本 App 设为隐身目标"
  su -c "sh '$REPO/android/assets/vmctl.sh' cmd register com.glslplayground.app"
  echo "   --- 自检 ---"
  su -c "sh '$REPO/android/assets/vmctl.sh' status"
else
  echo "   LSPosed：未安装 —— 设置页里点「下载并安装 LSPosed」，重启后回来点「启用模块」"
  echo "   （不装也能用：按住说话会走本 App 自己的语音识别）"
fi

cat <<'NEXT'

---- 接下来（手机上的设置页）----
无 root 也能用：打开 App → 设置 → 「语音按键 · VoiceMap」→ 引擎那行若是
「本 App 语音识别（不需要 root）」→ 直接按住说话就有字。

想要键盘隐身那一档：
  Magisk 弹框点允许 → 面板「下载并安装 LSPosed」→ 重启
  → 「安装 / 更新 VoiceMap」→ 选输入法 → 「启用模块」→ 「重启输入法进程」
  → 「本 App 设为隐身目标」→ 引擎会自动切回「输入法语音（VoiceMap）」
NEXT
