#!/bin/bash
set -u
source "$HOME/.codex/env"
export AGENTROUTER_API_KEY
cd /root/dining_log || exit 1
codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox -m gpt-5.6-sol \
  --output-last-message /root/dining_log/logs/ui3_summary.md \
  "完整阅读 /root/dining_log/design/UI_TASK3.md，按 8 组要求修改 /root/dining_log/design/proto/ 下三份 HTML，并同步更新 DIRECTIONS.md 与 DONE.md。不要动 design/PLAN.md 与其他 md。" \
  > /root/dining_log/logs/ui3.log 2>&1
echo "EXIT=$?"
