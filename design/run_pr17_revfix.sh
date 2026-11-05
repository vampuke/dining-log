#!/bin/bash
# PR17 review-fix round: close M1 (list baseline) + M2 (apply real font200/dark) + N2/N3.
set -uo pipefail
cd /root/dining_log || exit 1
mkdir -p logs

MAX_ATTEMPTS=10

read -r -d '' PROMPT <<'END_PROMPT'
你是修复实现者。完整阅读 design/PR17_REVIEWFIX_TASK.md 与 design/pr17-review/REVIEW.md（重点 M1/M2/N2/N3 各节的具体修法与关闭条件），执行：
1. M1：列表首屏完整卡数——修正判定（positionInRoot+size、可见区域、floating-tabs 之上、枚举全部可见卡），拿真实基线数字（基线 commit eaa8e42 上跑一次该测试得到卡数写进断言注释，或等价机制），归档 design/pr17-discussion/evidence/M1-list-firstscreen.md 并纳入 git。
2. M2：Apply 弹窗真实窗口字体（font_scale 系统设置/recreate 或测试专用包装），矩阵 font 1/1.3/1.31/2 × light/dark，requested==actual 断言，截图命名带 actualFont。
3. N2：e2e DELETE 的 TOKEN_EXPIRED 转 AuthFailure 让 authenticated 重试；被撤销会话先证明删除前 token 有效。
4. N3：saved 断言改增量。
不改生产布局/守卫/既有断言语义。一次提交推 feat/pr17-density-and-sliding-sessions，NOTES 追加 Review round 一节（含 N4 声明不做）。完成后输出改动文件清单。
END_PROMPT

for i in $(seq 1 "$MAX_ATTEMPTS"); do
  echo "=== attempt $i $(date +%H:%M:%S) ==="
  BEFORE_SHA=$(git rev-parse origin/feat/pr17-density-and-sliding-sessions 2>/dev/null || git rev-parse HEAD)
  rm -f logs/pr17_revfix_summary.md
  source "$HOME/.codex/env"
  codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
    -m gpt-6.1-sol \
    --output-last-message logs/pr17_revfix_summary.md \
    "$PROMPT" > "logs/pr17_revfix_attempt_$i.log" 2>&1
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
  if grep -q "token rate limit exceeded" "logs/pr17_revfix_attempt_$i.log"; then
    echo "rate limited, backoff 180s"
    sleep 180
  fi
done
echo "FAILED after $MAX_ATTEMPTS attempts"
exit 1
