#!/bin/bash
# PR17 dispatcher: sliding 180d sessions + drop session manager UI + densify detail page.
set -uo pipefail

cd /root/dining_log || exit 1
mkdir -p logs

MAX_ATTEMPTS=8

read -r -d '' PROMPT <<'END_PROMPT'
你是实现者。先完整阅读 design/PR17_TASK.md，然后严格按任务书执行三个改动：
1. 后端会话改滑动窗口 180 天（session.ts + rotation.ts，新常量 SESSION_TTL_SECONDS，新 2 个 auth 用例）。
2. 设置页删除「刷新账号与会话」按钮与「登录会话」区块及其全部连带代码（保留退出登录），e2e 中撤销其他会话的段落改为直接调 API。
3. 详情页密度优化（六处布局收紧，testTag 全部保留）。
全部完成后：新建分支 feat/sliding-sessions-and-density（基于最新 origin/master），一次提交（fix: 或 feat: 前缀均可，用 feat:），推 origin，并用 GitHub API 开 PR 到 master。
任务书是唯一指令来源；与任务书冲突时以任务书为准。完成后把三个改动各自的文件清单与验收证据写进 design/pr17/NOTES.md 并一并提交。
END_PROMPT

for i in $(seq 1 "$MAX_ATTEMPTS"); do
  echo "=== attempt $i $(date +%H:%M:%S) ==="
  rm -f logs/pr17_summary.md
  BEFORE_SHA=$(git rev-parse origin/master 2>/dev/null || echo "")
  source "$HOME/.codex/env"
  codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
    -m gpt-6.1-sol \
    --output-last-message logs/pr17_summary.md \
    "$PROMPT" > "logs/pr17_attempt_$i.log" 2>&1
  rc=$?
  echo "exit=$rc"
  if [ $rc -eq 0 ]; then
    git fetch -q origin
    head_sha=$(git rev-parse origin/feat/sliding-sessions-and-density 2>/dev/null || echo "")
    echo "branch head now: ${head_sha:-none}"
    if [ -n "$head_sha" ] && [ "$head_sha" != "none" ]; then
      echo "SUCCESS on attempt $i"
      exit 0
    fi
    echo "no branch, retrying"
  else
    if grep -q "token rate limit exceeded" "logs/pr17_attempt_$i.log"; then
      echo "rate limited, backoff 180s"
      sleep 180
    fi
  fi
done
echo "FAILED after $MAX_ATTEMPTS attempts"
exit 1
