#!/data/data/com.termux/files/usr/bin/bash
# 把当前 HEAD 推到 GitHub。这台机器上没有存任何 GitHub 凭据（没有 ~/.git-credentials、
# 没有 gh、~/.ssh 是空的），所以推送必须现给一个 token。
#
# 用法：
#   GITHUB_TOKEN=ghp_xxx bash tools/push-github.sh                    # 推到分支 fix/ui-search-zones-20261107
#   GITHUB_TOKEN=ghp_xxx bash tools/push-github.sh main               # 推 main（要求 origin/main 是祖先，否则会被拒）
#   GITHUB_TOKEN=ghp_xxx bash tools/push-github.sh main --force-with-lease   # 慎用：会丢掉别人新推的提交
#
# token 只需要 repo 权限（classic: `repo`；fine-grained: Contents=read/write）。
set -u
TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
BRANCH="${1:-fix/ui-search-zones-20261107}"
shift || true
EXTRA="$*"
REPO="zoujiapeng/oppo-watch-ai"

if [ -z "$TOKEN" ]; then
  echo "用法：GITHUB_TOKEN=ghp_xxx bash tools/push-github.sh [分支名] [额外参数]"
  echo "（本机没有存凭据，必须现给 token；见脚本顶部注释）"
  exit 2
fi

echo "→ 推 $(git rev-parse --short HEAD) 到 $REPO:$BRANCH"
git push "https://x-access-token:${TOKEN}@github.com/${REPO}.git" "HEAD:${BRANCH}" $EXTRA
RC=$?
if [ $RC -ne 0 ]; then
  echo
  echo "❌ 推送失败（rc=$RC）。常见原因："
  echo "   · 目标分支不是快进：先 git fetch origin && git log --oneline HEAD..origin/main 看看上游多了什么"
  echo "   · token 没有 repo 写权限 / 已过期"
  echo "   · 网络：先 git ls-remote https://github.com/${REPO}.git HEAD 能不能通"
fi
exit $RC
