#!/bin/bash
# PR17 phase 2: implementation per design/pr17-discussion/PLAN.md.
set -uo pipefail
cd /root/dining_log || exit 1
mkdir -p logs

MAX_ATTEMPTS=10

read -r -d '' PROMPT <<'END_PROMPT'
你是实现者。唯一指令来源是 design/pr17-discussion/PLAN.md（195 行，逐节执行）：
- 第 1 节：全局组件能力扩展（PageHeader trailing slot、RatingMeter 抽取、RatingEditor framed/label 参数、加减清除同排、Sticker maxLines、HistoryTimeText 大字体多行）。
- 第 2 节：详情页与编辑页全部布局改动（A1-A9/A5b、D1/D2/N3）。
- 第 3 节：列表页微调与筛选面板（B1/B2/N6/G2/G3）。
- 第 4 节：历史详情页（E1-E6/N5/N7）。
- 第 5 节：设置页 C1/C2 与更新弹窗 N1/N2。
- 第 6 节：删除设置页会话管理全链路（UI+VM+数据层+测试迁移，按清单逐条执行，含 OkHttp 直连 DELETE 与失效语义断言）。
- 第 7 节：后端会话滑动 180 天（SESSION_TTL_SECONDS、guarded batch 写回、两个新 auth 测试，含假时钟方案）。
- 第 8 节：p7_baseline.json 只退役 revoke tag；p8_protected_hashes.json 刷新 session.ts/rotation.ts/auth.test.ts 三文件哈希。
- 第 9 节：验收——视觉守卫 exit 0、npm run check/test、gradle 编译、两个新增 Pr17UiTest.kt 覆盖矩阵、截图证据存 design/pr17-discussion/evidence/。
- 第 10 节：新分支 feat/pr17-density-and-sliding-sessions，一次提交，推 origin，开 PR「density pass + sliding 180-day sessions」，总结写 design/pr17-discussion/NOTES.md。
按第 10 节顺序执行。本机无编译环境，不许运行 gradle/npm（后端 npm run check/test 也不许在本机跑）——这些由 CI 验证，你只负责把代码、测试、基线、哈希改对。截图证据由 CI 产物提供，你只在 NOTES 里注明哪些证据待 CI 采集。完成后列出改动文件清单与 NOTES 路径。
END_PROMPT

for i in $(seq 1 "$MAX_ATTEMPTS"); do
  echo "=== attempt $i $(date +%H:%M:%S) ==="
  rm -f logs/pr17_impl_summary.md
  BEFORE_SHA=$(git rev-parse HEAD)
  source "$HOME/.codex/env"
  codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
    -m gpt-6.1-sol \
    --output-last-message logs/pr17_impl_summary.md \
    "$PROMPT" > "logs/pr17_impl_attempt_$i.log" 2>&1
  rc=$?
  echo "exit=$rc"
  if [ $rc -eq 0 ] && [ -f design/pr17-discussion/NOTES.md ] && git show-ref --verify --quiet refs/heads/feat/pr17-density-and-sliding-sessions; then
    git fetch -q origin
    local_sha=$(git rev-parse feat/pr17-density-and-sliding-sessions 2>/dev/null)
    remote_sha=$(git rev-parse origin/feat/pr17-density-and-sliding-sessions 2>/dev/null || echo "")
    if [ -n "$remote_sha" ] && [ "$local_sha" = "$remote_sha" ]; then
      echo "SUCCESS on attempt $i"
      exit 0
    fi
  fi
  if grep -q "token rate limit exceeded" "logs/pr17_impl_attempt_$i.log"; then
    echo "rate limited, backoff 180s"
    sleep 180
  fi
done
echo "FAILED after $MAX_ATTEMPTS attempts"
exit 1
