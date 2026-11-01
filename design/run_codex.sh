#!/bin/bash
export AGENTROUTER_API_KEY="$(grep AGENTROUTER_API_KEY "$HOME/.codex/env" | cut -d'"' -f2)"
cd /root/dining_log
exec codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox -m gpt-6.1-sol \
  --output-last-message /root/dining_log/design/codex_summary.md \
  "请阅读 /root/dining_log/design/TASK.md 并严格按其中要求产出方案，写入 /root/dining_log/design/PLAN.md"
