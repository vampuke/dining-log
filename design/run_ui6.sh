#!/bin/bash
set -u
source "$HOME/.codex/env"
export AGENTROUTER_API_KEY
cd /root/dining_log || exit 1
mkdir -p logs
for attempt in 1 2 3 4 5 6; do
  echo "=== attempt $attempt $(date '+%H:%M:%S') ===" >> logs/ui6_retry.log
  codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox -m gpt-5.6-sol \
    --output-last-message /root/dining_log/logs/ui6_summary.md \
    "完整阅读 /root/dining_log/design/UI_TASK6.md 并严格执行，只改 design/proto/d2-quiet-grid.html，改完用 headless Chromium 实测并把数字写进 design/proto/DONE.md 第六轮小节。" \
    > /root/dining_log/logs/ui6_attempt${attempt}.log 2>&1
  code=$?
  ok=0
  [ -s /root/dining_log/logs/ui6_summary.md ] && grep -q "第六轮" /root/dining_log/design/proto/DONE.md 2>/dev/null && ok=1
  echo "attempt $attempt exit=$code ok=$ok" >> logs/ui6_retry.log
  if [ "$ok" = "1" ]; then echo "SUCCESS on attempt $attempt"; exit 0; fi
  sleep 20
done
echo "FAILED after 6 attempts"; exit 1
