#!/data/data/com.termux/files/usr/bin/bash
# 把 patches/preset-mobile-use/mobile_plugin.js 的改动同步到 DSH **真正加载**的那份预设副本。
#
# 为什么：DSH 的 mobile 工具加载的是 ~/.dsh/.agent-presets/mobile-use/mobile_plugin.js，
# 不是仓库里的 patches/ 副本；改仓库不会生效（实测：插件日志一直是空的）。
# 做法：拿 git 里的上一版当基底做 diff，再打到预设副本上（两边基线逐字节一致才打得动）。
#
# 用法：bash patches/sync-ball-gate.sh [目标路径]
# 改完必须重启 DSH 才生效（预设插件在进程启动时加载）。
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
TARGET="${1:-$HOME/.dsh/.agent-presets/mobile-use/mobile_plugin.js}"
BASE="$HOME/.cache/mobile-plugin-base.js"
PATCH="$HOME/.cache/ball-gate.patch"

cd "$ROOT"
git show HEAD:patches/preset-mobile-use/mobile_plugin.js > "$BASE"
diff -u "$BASE" patches/preset-mobile-use/mobile_plugin.js > "$PATCH" || true

if ! grep -q '^@@' "$PATCH"; then
  echo "[skip] 仓库副本和基线没差别"
  exit 0
fi

cp -f "$TARGET" "$TARGET.bak.$(date +%s)"
patch -l --no-backup-if-mismatch "$TARGET" < "$PATCH"
node --check "$TARGET"
echo "[ok] 已同步到 $TARGET（重启 DSH 生效）"
