#!/bin/bash
# PR16 fifth-round dispatcher: single-line tolerance fix in Pr16UiTest.
set -uo pipefail

cd /root/dining_log || exit 1
mkdir -p logs

MAX_ATTEMPTS=8

read -r -d '' PROMPT <<'END_PROMPT'
你是实现者。先完整阅读 design/PR16_TESTFIX2_TASK.md，然后严格按任务书执行：
1. 把 Pr16UiTest.kt 中第 125–128 行断言的比较改为 `bounds.bottom - bounds.top >= 48.dp - 0.5.dp`（消息文本保持不变）。
2. 在分支 pr14-rebased 上以 fix: 前缀提交并推送 origin。不要新建分支，不要动其他任何文件。
3. 完成后在 stdout 打印改动后的那 4 行断言代码与 commit 哈希。
任务书是唯一指令来源；与任务书冲突时以任务书为准。
END_PROMPT

for i in $(seq 1 "$MAX_ATTEMPTS"); do
  echo "=== attempt $i $(date +%H:%M:%S) ==="
  rm -f logs/pr16_testfix2_summary.md
  BEFORE_SHA=$(git rev-parse origin/pr14-rebased 2>/dev/null || echo "")
  source "$HOME/.codex/env"
  codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
    -m gpt-6.1-sol \
    --output-last-message logs/pr16_testfix2_summary.md \
    "$PROMPT" > "logs/pr16_testfix2_attempt_$i.log" 2>&1
  rc=$?
  echo "exit=$rc"
  if [ $rc -eq 0 ]; then
    git fetch -q origin
    head_sha=$(git rev-parse origin/pr14-rebased 2>/dev/null || echo "")
    echo "branch head now: $head_sha"
    if [ -n "$BEFORE_SHA" ] && [ "$head_sha" != "$BEFORE_SHA" ]; then
      echo "SUCCESS on attempt $i"
      git diff "$BEFORE_SHA" "$head_sha" --stat | cat
      exit 0
    fi
    echo "no new commit on pr14-rebased, retrying"
  else
    if grep -q "token rate limit exceeded" "logs/pr16_testfix2_attempt_$i.log"; then
      echo "rate limited, backoff 180s"
      sleep 180
    fi
  fi
done
echo "FAILED after $MAX_ATTEMPTS attempts"
exit 1
