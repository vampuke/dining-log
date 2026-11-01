#!/bin/bash
set -u
source "$HOME/.codex/env"
export AGENTROUTER_API_KEY
cd /root/dining_log || exit 1
mkdir -p logs

codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox -m gpt-5.6-sol \
  --output-last-message /root/dining_log/logs/ui2_summary.md \
  "完整阅读 /root/dining_log/design/UI_TASK2.md，按其中 19 条缺陷清单逐条修改 /root/dining_log/design/proto/ 下的三份 HTML，并新建 DIRECTIONS.md 与 DONE.md。不要动 design/PLAN.md。" \
  > /root/dining_log/logs/ui2.log 2>&1
echo "EXIT=$?"
