#!/bin/bash
# PR16 test-failure fix dispatcher (e2e regression + height assert evidence).
set -uo pipefail

cd /root/dining_log || exit 1
mkdir -p logs

MAX_ATTEMPTS=8

read -r -d '' PROMPT <<'END_PROMPT'
你是实现者。先完整阅读 design/PR16_TESTFIX_TASK.md，然后严格按任务书执行：
1. 按任务书「修法」一节精确修改 HistoryTimeText.kt（删除 fillMaxWidth()，加 widthIn(min=48.dp)，加对应 import）。
2. 按任务书给 Pr16UiTest.kt 第 125 行断言加上带实际数值的失败消息（只加消息，不改断言）。
3. 在分支 pr14-rebased 上提交（fix: 前缀），并推送到 origin。不要新建分支。
4. 完成后把「实际改动文件清单 + 你对失败 B 的读码推理（文件+行号）」写入 design/pr16-testfix/NOTES.md 并提交。
任务书是唯一指令来源；与任务书冲突时以任务书为准。
END_PROMPT

for i in $(seq 1 "$MAX_ATTEMPTS"); do
  echo "=== attempt $i $(date +%H:%M:%S) ==="
  rm -f logs/pr16_testfix_summary.md
  BEFORE_SHA=$(git rev-parse origin/pr14-rebased 2>/dev/null || echo "")
  source "$HOME/.codex/env"
  codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
    -m gpt-6.1-sol \
    --output-last-message logs/pr16_testfix_summary.md \
    "$PROMPT" > "logs/pr16_testfix_attempt_$i.log" 2>&1
  rc=$?
  echo "exit=$rc"
  if [ $rc -eq 0 ]; then
    git fetch -q origin
    # Compare against the tip BEFORE this attempt, not the local checkout
    # (which sits on master in the dispatcher's own workspace).
    head_sha=$(git rev-parse origin/pr14-rebased 2>/dev/null || echo "")
    echo "branch head now: $head_sha"
    if [ -n "${BEFORE_SHA:-}" ] && [ "$head_sha" != "$BEFORE_SHA" ]; then
      echo "SUCCESS on attempt $i"
      exit 0
    fi
    echo "no new commit on pr14-rebased, retrying"
  else
    if grep -q "token rate limit exceeded" "logs/pr16_testfix_attempt_$i.log"; then
      echo "rate limited, backoff 180s"
      sleep 180
    fi
  fi
done
echo "FAILED after $MAX_ATTEMPTS attempts"
exit 1
