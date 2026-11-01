#!/bin/bash
# 第五轮：D2 原型 3 项修改 + PLAN.md 数据模型同步。带失败自动重试。
set -u
source "$HOME/.codex/env"
export AGENTROUTER_API_KEY
cd /root/dining_log || exit 1
mkdir -p logs

PROMPT="完整阅读 /root/dining_log/design/UI_TASK5.md 并严格执行：改 design/proto/d2-quiet-grid.html（评分0.1精度+图标、取消菜品独立tab、搜索覆盖菜品/评价/备注），同时定点修订 design/PLAN.md（评分十分位整数存储、菜品归餐厅、搜索索引表、视觉换 D2）。改完用 headless Chromium 实测并把数字写进 design/proto/DONE.md 第五轮小节。不要动 d1/d3 两份 HTML，不要动其他 md。"

for attempt in 1 2 3 4 5 6; do
  echo "=== attempt $attempt $(date '+%H:%M:%S') ===" >> logs/ui5_retry.log
  codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox -m gpt-5.6-sol \
    --output-last-message /root/dining_log/logs/ui5_summary.md \
    "$PROMPT" > /root/dining_log/logs/ui5_attempt${attempt}.log 2>&1
  code=$?
  # 成功判据：有 summary 内容且 DONE.md 出现「第五轮」
  ok=0
  [ -s /root/dining_log/logs/ui5_summary.md ] && grep -q "第五轮" /root/dining_log/design/proto/DONE.md 2>/dev/null && ok=1
  echo "attempt $attempt exit=$code ok=$ok" >> logs/ui5_retry.log
  if [ "$ok" = "1" ]; then echo "SUCCESS on attempt $attempt"; exit 0; fi
  sleep 20
done
echo "FAILED after 6 attempts"
exit 1
