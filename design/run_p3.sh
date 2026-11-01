#!/bin/bash
# P3：领域 API 与修改历史。失败自动重试。
set -u
source "$HOME/.codex/env"
export AGENTROUTER_API_KEY
cd /root/dining_log || exit 1
mkdir -p logs

PROMPT="完整阅读 /root/dining_log/design/P3_TASK.md 并严格执行。开工前必须先读 /root/dining_log/design/PLAN.md 的这些节（只读，不要改）：4.1 协议约定、4.4~4.9 CRUD 与搜索、4.10 原子写入幂等与成本、6.1~6.4 字段级修改记录、10.3 P3 必过场景。

背景：P1+P2 已完成（仓库 https://github.com/vampuke/dining-log ，master=655c48e，CI 全绿）。migrations 已定稿（14 表，含 restaurants/dishes/visits/change_sets/revisions/restaurant_search/mutation_guards/idempotency_keys），不许改，SHA 必须保持 191d1a80a7411ba828effc9041fbd144fa4d10abb372f61c793a8f6dc1a5b80b。P2 的 backend/src/auth/ 全部复用：accessIdentity 鉴权、commit、guard、idempotency、错误信封。

本次 = P3 领域 API 与修改历史，范围见 P3_TASK.md。最关键的四个机制（做错就废，先写测试再实现）：
1) CAS + changes() 行数守卫：changes() 必须紧跟业务 UPDATE/INSERT，中间不能插会改计数的 SQL；每守卫不同 operation_id；0 行则整批回滚；子对象必须 INSERT...SELECT...FROM restaurants WHERE deleted_at IS NULL；创建餐厅+首访同一 batch。
2) batch 失败错误归因顺序：先查同 key 幂等结果并核指纹 → 实体存在性/属主 → 409 VERSION_CONFLICT 带 current → 其余 500。不许一律当 409。
3) 无变化保存：仍原子确认版本与父对象，但不改 updated_at/version、不写 revision，返回 changed=false。
4) 服务端算 diff：客户端只发 expected_version + 拟改字段，绝不信任客户端 before；评分用十分位整数（40→43）；多字段一条 revision；白名单外字段不记录。

另外必做：restaurant_search 在变更同一 batch 内重建；keyset cursor 严格验证且绝不拼进 SQL；POST /revisions 只允许 action=update 且 1~7 个不重复字段、禁止 id/owner/version/deleted_at；PATCH revision 的 diff 或原始时间返回 400；搜索按 NFKC+lowercase+instr 字面语义（% 和 _ 当普通字符）。

测试必须覆盖 PLAN 10.3 全部 15 条，外加并发双写一胜一 409 且失败方无残留、batch 第 N 条失败全部回滚、cursor 篡改返回 400。OpenAPI 本期定稿，不留占位。

硬约束（违反会搞挂这台 VPS）：
- 本机仅 956MB。禁止在本机跑 npm install / gradle / docker / vitest。所有构建测试在 GitHub Actions。
- 你只写代码、git commit、git push、用 GitHub API（token 在 ~/.git-credentials）看 CI 和日志。本机可用 sqlite3 验证 SQL。
- 不许降低断言阈值、删测试或 skip 让 CI 变绿。失败读日志修根因。
- 不改 migration；确实缺字段就写 P3_BLOCKERS.md 停下等。
- 不部署 Cloudflare、不建真实 D1、不写真实 secret。
- 走新分支 → PR → 合并，不直推 master。

完成后写 P3_DONE.md：端点清单、10.3 十五条各自对应测试文件+用例名、verified vs unverified、四个核心机制的代码位置、权衡与遗留。不要夸大。"

for i in 1 2 3 4 5 6 7 8; do
  echo "=== attempt $i $(date +%H:%M:%S) ==="
  codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
    -m gpt-6.1-sol --output-last-message logs/p3_summary.md "$PROMPT" \
    > "logs/p3_attempt${i}.log" 2>&1
  rc=$?
  if [ $rc -eq 0 ]; then echo "SUCCESS on attempt $i"; echo "attempt $i exit=0 ok=1" >> logs/p3_retry.log; exit 0; fi
  echo "attempt $i exit=$rc"
  echo "attempt $i exit=$rc ok=0" >> logs/p3_retry.log
  sleep 30
done
echo "ALL FAILED"
exit 1
