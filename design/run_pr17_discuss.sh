#!/bin/bash
# PR17 phase 1: discussion session. Codex reviews the audit, writes DISCUSSION.md + PLAN.md.
set -uo pipefail
cd /root/dining_log || exit 1
mkdir -p logs

MAX_ATTEMPTS=8

read -r -d '' PROMPT <<'END_PROMPT'
你是设计评审者。完整阅读 design/PR17_AUDIT.md（助手的密度审计结论 + 已拍板事项），然后：
1. 逐条评审 A1-A9、B1/B2、C1/C2、D1-D4、E1-E6、F1/F2、G1-G3：每条给「同意 / 反对（附理由与替代方案）」。特别裁决：G1 做不做、E4 三变体选哪个、F2 做不做、B1/B2 微调数值。
2. 检查遗漏：通读 android/feature/src/main/kotlin 全部 UI 文件（ListScreens.kt、DetailEditScreens.kt、HistoryScreens.kt、SettingsScreen.kt、AuthScreens.kt、UpdateDialog.kt、BiteComponents.kt、QuietGridComponents.kt），找审计没覆盖到的密度问题（竖向堆叠、独占行、大空白、冗余卡片），补充进评审。注意 UpdateDialog.kt 与 QuietGridComponents.kt 助手没有逐条审，你重点看这两个。
3. 已拍板事项（滑动 180 天、删设置页两区块）不参与讨论，直接纳入计划。
4. 把最终结论写成 design/pr17-discussion/DISCUSSION.md（逐条含理由）+ design/pr17-discussion/PLAN.md（最终改动清单：文件、位置、改法、testTag 保持策略、大字体策略、验收方式）。PLAN.md 必须自洽完整——它是下一阶段实现的唯一输入。
5. 全部结论要有代码行号依据，引用实际代码；不许凭空想象布局。
只写这两份文档，不改任何生产代码。
END_PROMPT

for i in $(seq 1 "$MAX_ATTEMPTS"); do
  echo "=== attempt $i $(date +%H:%M:%S) ==="
  rm -f logs/pr17_discuss_summary.md
  source "$HOME/.codex/env"
  codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
    -m gpt-6.1-sol \
    --output-last-message logs/pr17_discuss_summary.md \
    "$PROMPT" > "logs/pr17_discuss_attempt_$i.log" 2>&1
  rc=$?
  echo "exit=$rc"
  if [ $rc -eq 0 ] && [ -s design/pr17-discussion/DISCUSSION.md ] && [ -s design/pr17-discussion/PLAN.md ]; then
    echo "SUCCESS on attempt $i"
    exit 0
  fi
  if grep -q "token rate limit exceeded" "logs/pr17_discuss_attempt_$i.log"; then
    echo "rate limited, backoff 180s"
    sleep 180
  fi
done
echo "FAILED after $MAX_ATTEMPTS attempts"
exit 1
