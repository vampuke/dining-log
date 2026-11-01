#!/bin/bash
# P1 任务：建仓库 + D1 migrations + Worker/Android 骨架。带失败自动重试。
set -u
source "$HOME/.codex/env"
export AGENTROUTER_API_KEY
cd /root/dining_log || exit 1
mkdir -p logs

PROMPT="完整阅读 /root/dining_log/design/P1_TASK.md 并严格执行 P1 底座任务。方案唯一来源是 /root/dining_log/design/PLAN.md。本机只有 956MB 内存，禁止在本机跑 npm install / gradle / docker，只写代码并用 sqlite3 验证 SQL。不要部署 Cloudflare，不要写 secret。完成后写 /root/dining_log/P1_DONE.md 记录自检实测输出。"
MARKER=/root/dining_log/P1_DONE.md

for attempt in 1 2 3 4 5 6 7 8; do
  echo "=== attempt $attempt $(date '+%m-%d %H:%M:%S') ===" >> logs/p1_retry.log
  codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox -m gpt-6.1-sol \
    --output-last-message /root/dining_log/logs/p1_summary.md \
    "$PROMPT" > /root/dining_log/logs/p1_attempt${attempt}.log 2>&1
  code=$?
  ok=0
  if [ -s "$MARKER" ] && grep -q "sqlite3" "$MARKER" 2>/dev/null; then ok=1; fi
  echo "attempt $attempt exit=$code ok=$ok" >> logs/p1_retry.log
  if [ "$ok" = "1" ]; then echo "SUCCESS on attempt $attempt"; exit 0; fi
  sleep 30
done
echo "FAILED after 8 attempts"; exit 1
