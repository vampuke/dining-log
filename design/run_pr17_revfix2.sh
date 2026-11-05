#!/bin/bash
# PR17 review-fix round 2: API26 recreate lifecycle race.
set -uo pipefail
cd /root/dining_log || exit 1
mkdir -p logs

MAX_ATTEMPTS=8

read -r -d '' PROMPT <<'END_PROMPT'
你是修复实现者。完整阅读 design/PR17_REVIEWFIX2_TASK.md 并执行：
1. 复核诊断（apply 测试 finally 的 recreate 在 API26 上未等 RESUMED，下一测试 setContent 撞 DESTROYED）。
2. 按修法改 setSystemFontAndRecreate（recreate 后等 RESUMED，带超时与 catch）+ render 开头防御性自愈。
3. 不改 M1/M2 断言、不改生产代码。一次提交推 feat/pr17-density-and-sliding-sessions，NOTES 追加 Review round 2 小节。
本机不许跑 gradle/npm。完成后输出 diff 概要。
END_PROMPT

for i in $(seq 1 "$MAX_ATTEMPTS"); do
  echo "=== attempt $i $(date +%H:%M:%S) ==="
  BEFORE_SHA=$(git rev-parse origin/feat/pr17-density-and-sliding-sessions 2>/dev/null || git rev-parse HEAD)
  rm -f logs/pr17_revfix2_summary.md
  source "$HOME/.codex/env"
  codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
    -m gpt-6.1-sol \
    --output-last-message logs/pr17_revfix2_summary.md \
    "$PROMPT" > "logs/pr17_revfix2_attempt_$i.log" 2>&1
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
  if grep -q "token rate limit exceeded" "logs/pr17_revfix2_attempt_$i.log"; then
    echo "rate limited, backoff 180s"
    sleep 180
  fi
done
echo "FAILED after $MAX_ATTEMPTS attempts"
exit 1
