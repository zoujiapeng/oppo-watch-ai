#!/data/data/com.termux/files/usr/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# install-ball-guard-plugin.sh —— 在**任意一台**手机上，把「球让路」那半边装进 DSH
#
# 为什么需要这一步：防止 AI 误触悬浮球靠的是**两半**——
#   球侧（APK）：收到信号就把自己所有窗口退出命中测试；
#   DSH 侧（插件 dsh-preset-mobile-use 的 lib/index.js）：**注入触摸之前先发信号**。
# 只装 APK 不装插件 = 球永远等不到信号 = 点击照旧落在球上。本机一直"好用"是因为
# 这台机器的插件被手工打过补丁；换台手机，插件要么没装、要么还是原版。
#
#   bash patches/install-ball-guard-plugin.sh              # 装/升级到本仓库的版本
#   bash patches/install-ball-guard-plugin.sh --check      # 只看状态，不改任何文件
#   bash patches/install-ball-guard-plugin.sh --from /path/to/index.js
#
# 装完必须**重启 DSH**（插件是 ESM，启动时 import 进内存）。
# ═══════════════════════════════════════════════════════════════════════════════
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(dirname "$HERE")"
SRC="${SRC:-$HERE/preset-mobile-use/index.js.patched}"
BUNDLE="${BUNDLE:-$HOME/dsh-preset-mobile-use}"
PROFILE="${DSH_PROFILE_DIR:-$HOME/.dsh/profiles/web}"   # 注意别用 DSH_PROFILE：那是 DSH 自己的环境变量（值就是 "web"）
BUNDLE_NAME="dsh-preset-mobile-use"
MODE="${1:-install}"

say() { printf '%s\n' "$*"; }
ok()  { printf '  [ok] %s\n' "$*"; }
bad() { printf '  [!!] %s\n' "$*"; }
inf() { printf '  [--] %s\n' "$*"; }

say "== 0) 位置 =="
inf "插件目录：$BUNDLE"
inf "补丁源文件：$SRC"
inf "DSH profile：$PROFILE"
inf "模式：$MODE"
[ -d "$PROFILE" ] || { bad "profile 目录不存在（DSH 没在这台机器上起过？）"; }
[ -f "$SRC" ] || bad "找不到补丁源文件：$SRC（用 --from 指定，或在仓库里跑）"

# ── 1) 插件 bundle 本身在不在 ────────────────────────────────────────────────
say
say "== 1) dsh-preset-mobile-use 装了吗 =="
TARGET=""
for c in "$BUNDLE/lib/index.js" \
         "$PROFILE/node_modules/$BUNDLE_NAME/lib/index.js"; do
  [ -f "$c" ] && TARGET="$c" && break
done
if [ -z "$TARGET" ]; then
  bad "没装。它不在 npm 上，只能从仓库装："
  say "      git clone https://github.com/AcidGr/dsh-preset-mobile-use.git \"$BUNDLE\""
  say "      dsh plugin add --profile web \"link:$BUNDLE\""
  say "      然后重跑本脚本"
else
  ok "插件入口：$TARGET"
  if grep -q "waitForBallStandDown" "$TARGET"; then
    if grep -q "BALL_GATE_PORTS" "$TARGET"; then
      ok "已经是 2026-10-24 版（TCP + 多目录 + su 中继 + 不再静默）"
    else
      inf "是旧版（只有文件通道）→ 需要升级"
    fi
  else
    bad "是**原版** preset：里面没有让路逻辑，AI 的点击会直接落到球上"
  fi
fi

if [ "$MODE" = "--check" ] || [ "$MODE" = "check" ]; then
  say
  say "== 2) 注册情况 =="
  if [ -f "$PROFILE/package.json" ]; then
    python3 - "$PROFILE/package.json" "$BUNDLE_NAME" <<'PY'
import json,sys
p,name=sys.argv[1],sys.argv[2]
d=json.load(open(p,encoding="utf-8"))
dep=d.get("dependencies",{}).get(name)
bundles=(d.get("dsh",{}).get("profile",{}).get("bundles") or [])
print(f"  [--] dependencies: {dep or '(缺)'}")
print(f"  [--] bundles: {'包含' if name in bundles else '(缺)'} {name}")
PY
  else
    bad "profile 里没有 package.json"
  fi
  exit 0
fi

[ -f "$SRC" ] || exit 2
[ -n "$TARGET" ] || exit 2

# ── 2) 打补丁（幂等：已经一样就不动）────────────────────────────────────────
say
say "== 2) 打补丁 =="
if cmp -s "$SRC" "$TARGET"; then
  ok "内容已一致，不用改"
else
  BAK="$TARGET.bak-$(date +%Y%m%d-%H%M%S)"
  cp -a "$TARGET" "$BAK" && ok "备份：$BAK"
  if cp "$SRC" "$TARGET"; then ok "已写入 $TARGET"; else bad "写入失败（权限？）"; exit 3; fi
  # 语法自检：DSH 起来时被语法错误拖死是最贵的故障
  if node --input-type=module --eval "await import('file://$TARGET')" >/dev/null 2>&1; then
    ok "ESM 载入自检通过（能 import）"
  else
    inf "载入自检没通过（缺 @deepseek-ai/dsh-tools 之类的宿主依赖时属正常，看下面这条）"
    cp "$SRC" "$TARGET.mjs-check" 2>/dev/null
    if node --check "$TARGET.mjs-check" >/dev/null 2>&1; then ok "至少语法通过（node --check）"; else bad "语法错误 → 回滚"; mv -f "$BAK" "$TARGET"; rm -f "$TARGET.mjs-check"; exit 4; fi
    rm -f "$TARGET.mjs-check"
  fi
fi

# ── 3) 注册进 profile（依赖 + bundle 列表）──────────────────────────────────
say
say "== 3) 注册进 DSH profile =="
if [ -f "$PROFILE/package.json" ]; then
  CHANGED=$(python3 - "$PROFILE/package.json" "$BUNDLE_NAME" "$BUNDLE" <<'PY'
import json,sys
p,name,dirpath=sys.argv[1],sys.argv[2],sys.argv[3]
d=json.load(open(p,encoding="utf-8"))
changed=False
dep=d.setdefault("dependencies",{})
if name not in dep:
    dep[name]=f"link:{dirpath}"; changed=True
dsh=d.setdefault("dsh",{}); prof=dsh.setdefault("profile",{})
bundles=prof.setdefault("bundles",[])
if name not in bundles:
    bundles.append(name); changed=True
if changed:
    json.dump(d,open(p,"w",encoding="utf-8"),ensure_ascii=False,indent=2)
print("yes" if changed else "no")
PY
)
  if [ "$CHANGED" = "yes" ]; then
    ok "profile/package.json 已更新（依赖 link + bundles 里加了 $BUNDLE_NAME）"
    ( cd "$PROFILE" && pnpm install --silent >/dev/null 2>&1 ) && ok "pnpm install 完成" || bad "pnpm install 失败 → 手动在 $PROFILE 里跑 pnpm install"
  else
    ok "profile 里本来就注册好了"
  fi
else
  bad "没有 $PROFILE/package.json → 跳过注册（手动加依赖 + bundles）"
fi

say
say "== 完成 =="
say "1) **重启 DSH**（插件是 ESM，启动时才读进内存）："
say "     pkill -f 'dsh.*--profile web' ; 然后按你平时的方式起 dsh web"
say "2) 球要在跑（设置页 →「悬浮于桌面」）"
say "3) 体检： bash android/tools/ball-gate-doctor.sh"
say "4) 贯通： node android/tools/ball-guard-plugin-e2e.mjs click"
say
say "回滚：把 $TARGET.bak-* 覆盖回去，再重启 DSH。"
