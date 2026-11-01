#!/bin/bash
set -u
source "$HOME/.codex/env"
export AGENTROUTER_API_KEY
cd /root/dining_log || exit 1
codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox -m gpt-5.6-sol \
  --output-last-message /root/dining_log/logs/ui4_summary.md \
  "完整阅读 /root/dining_log/design/UI_TASK4.md，按其中 3 条缺陷 + 2 处文档同步执行。必须用 headless Chromium 真跑测量并把数字写进 DONE.md。只改 design/proto 下的三份 HTML、DIRECTIONS.md、DONE.md，不要动 design/PLAN.md。" \
  > /root/dining_log/logs/ui4.log 2>&1
echo "EXIT=$?"
