#!/bin/bash
# 并行启动两个 Codex 任务：UI 三方向原型（UI_TASK.md） + PLAN.md 三项修订（PATCH_TASK.md）
set -u
source "$HOME/.codex/env"   # 提供 AGENTROUTER_API_KEY
export AGENTROUTER_API_KEY
cd /root/dining_log || exit 1

mkdir -p design/proto logs

codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox -m gpt-6.1-sol \
  --output-last-message /root/dining_log/logs/ui_agent_summary.md \
  "请完整阅读 /root/dining_log/design/UI_TASK.md，严格按其中要求产出三套高保真 UI 原型与 DIRECTIONS.md。文件写到 /root/dining_log/design/proto/。" \
  > /root/dining_log/logs/ui_agent.log 2>&1 &
echo "UI_PID=$!"

codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox -m gpt-6.1-sol \
  --output-last-message /root/dining_log/logs/patch_agent_summary.md \
  "请完整阅读 /root/dining_log/design/PATCH_TASK.md，严格按其中要求对 /root/dining_log/design/PLAN.md 做定点修订，并写 /root/dining_log/design/PLAN_REVISION.md。" \
  > /root/dining_log/logs/patch_agent.log 2>&1 &
echo "PATCH_PID=$!"

wait
echo "ALL_DONE"
