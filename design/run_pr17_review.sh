#!/bin/bash
# PR17 independent review session (separate from implementer).
set -uo pipefail
cd /root/dining_log || exit 1
mkdir -p logs

MAX_ATTEMPTS=8

read -r -d '' PROMPT <<'END_PROMPT'
你是独立评审者。完整阅读 design/PR17_REVIEW_TASK.md 并执行评审：
1. git diff 002023f..5f676f8 逐文件对照 design/pr17-discussion/PLAN.md。
2. 安全与语义审查（后端滑动窗口边界、cleanupAuth 交互、Android 删除链路残留、e2e 直连 DELETE 语义）。
3. UX 密度验收清单逐条对照。
4. 产出 design/pr17-review/REVIEW.md：BLOCKER/MAJOR/NIT 分级 + 每条文件:行号与具体修法 + 可合并/不可合并结论。
不修改任何生产代码，只写评审文档。评审要严：宁可误报也不放过。
END_PROMPT

for i in $(seq 1 "$MAX_ATTEMPTS"); do
  echo "=== attempt $i $(date +%H:%M:%S) ==="
  rm -f logs/pr17_review_summary.md
  source "$HOME/.codex/env"
  codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
    -m gpt-6.1-sol \
    --output-last-message logs/pr17_review_summary.md \
    "$PROMPT" > "logs/pr17_review_attempt_$i.log" 2>&1
  rc=$?
  echo "exit=$rc"
  if [ $rc -eq 0 ] && [ -s design/pr17-review/REVIEW.md ]; then
    echo "SUCCESS on attempt $i"
    exit 0
  fi
  if grep -q "token rate limit exceeded" "logs/pr17_review_attempt_$i.log"; then
    echo "rate limited, backoff 180s"
    sleep 180
  fi
done
echo "FAILED after $MAX_ATTEMPTS attempts"
exit 1
