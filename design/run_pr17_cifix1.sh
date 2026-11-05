#!/bin/bash
# PR17 CI fix round 1: dialog density, font200 title squeeze, 48dp touch target.
set -uo pipefail
cd /root/dining_log || exit 1
mkdir -p logs

MAX_ATTEMPTS=8

read -r -d '' PROMPT <<'END_PROMPT'
你是修复实现者。完整阅读 design/PR17_CIFIX1_TASK.md，按其执行：
1. 先拉取 API26 job 日志核对失败集合（用 ~/.git-credentials 的 token，check-runs API 找 26 的 job id，下载 logs 到 /tmp/pr17_i26.log）。
2. 逐条复核任务书里助手的 5 项诊断（用源码与 Compose 行为验证，不许照抄）。
3. 按修复纪律执行修复：Compose Dialog 的 fontScale 断言处理、font200 detail title 挤压的真布局修复、filter-unrated 48dp、stale TextLayoutResult 的 idle 处理。
4. 一次提交推到 feat/pr17-density-and-sliding-sessions（不新开分支），NOTES.md 追加 Round 1 修复一节。
本机不许跑 gradle/npm。完成后输出改动文件清单。
END_PROMPT

for i in $(seq 1 "$MAX_ATTEMPTS"); do
  echo "=== attempt $i $(date +%H:%M:%S) ==="
  BEFORE_SHA=$(git rev-parse origin/feat/pr17-density-and-sliding-sessions 2>/dev/null || git rev-parse HEAD)
  rm -f logs/pr17_cifix1_summary.md
  source "$HOME/.codex/env"
  codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
    -m gpt-6.1-sol \
    --output-last-message logs/pr17_cifix1_summary.md \
    "$PROMPT" > "logs/pr17_cifix1_attempt_$i.log" 2>&1
  rc=$?
  echo "exit=$rc"
  if [ $rc -eq 0 ]; then
    git fetch -q origin
    AFTER_SHA=$(git rev-parse origin/feat/pr17-density-and-sliding-sessions 2>/dev/null || echo "")
    echo "before=$BEFORE_SHA after=$AFTER_SHA"
    if [ -n "$AFTER_SHA" ] && [ "$AFTER_SHA" != "$BEFORE_SHA" ]; then
      echo "SUCCESS on attempt $i (new commit $AFTER_SHA)"
      exit 0
    fi
    echo "no new commit, retrying"
  fi
  if grep -q "token rate limit exceeded" "logs/pr17_cifix1_attempt_$i.log"; then
    echo "rate limited, backoff 180s"
    sleep 180
  fi
done
echo "FAILED after $MAX_ATTEMPTS attempts"
exit 1
