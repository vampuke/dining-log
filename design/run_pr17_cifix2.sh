#!/bin/bash
# PR17 CI fix round 2: de-cliff the 491/492 boundary rounds, add assertion messages.
set -uo pipefail
cd /root/dining_log || exit 1
mkdir -p logs

MAX_ATTEMPTS=8

read -r -d '' PROMPT <<'END_PROMPT'
你是修复实现者。完整阅读 design/PR17_CIFIX2_TASK.md 并执行：
1. 复核助手的诊断（492 轮 G2 横排分支未触发或 bounds 语义不符），用源码与 CI 证据证实。
2. 按修法执行：边界轮次去悬崖化（488/496、316/324）、:401 及其它裸 assertTrue 补消息、设计不改生产代码的 maxWidth 记录取法。
3. 其余 Round 1 修复全部不动。一次提交推 feat/pr17-density-and-sliding-sessions，NOTES.md 追加 Round 2 小节。
本机不许跑 gradle/npm。完成后输出改动文件与 diff 概要。
END_PROMPT

for i in $(seq 1 "$MAX_ATTEMPTS"); do
  echo "=== attempt $i $(date +%H:%M:%S) ==="
  BEFORE_SHA=$(git rev-parse origin/feat/pr17-density-and-sliding-sessions 2>/dev/null || git rev-parse HEAD)
  rm -f logs/pr17_cifix2_summary.md
  source "$HOME/.codex/env"
  codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
    -m gpt-6.1-sol \
    --output-last-message logs/pr17_cifix2_summary.md \
    "$PROMPT" > "logs/pr17_cifix2_attempt_$i.log" 2>&1
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
  if grep -q "token rate limit exceeded" "logs/pr17_cifix2_attempt_$i.log"; then
    echo "rate limited, backoff 180s"
    sleep 180
  fi
done
echo "FAILED after $MAX_ATTEMPTS attempts"
exit 1
