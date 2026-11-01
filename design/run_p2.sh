#!/bin/bash
# P2：独立账号闭环。失败自动重试。
set -u
source "$HOME/.codex/env"
export AGENTROUTER_API_KEY
cd /root/dining_log || exit 1
mkdir -p logs

PROMPT="完整阅读 /root/dining_log/design/P2_TASK.md 并严格执行。同时先读 /root/dining_log/design/PLAN.md 的第 5 节认证部分、第 10 节 P2 一行、10.2 P2 必过场景（该文件只读，不要改）。

背景：P1 已完成并验收（仓库 https://github.com/vampuke/dining-log ，master 最新 commit d3e0da6，CI 全绿；migrations 14 表已在 backend/migrations/0001_initial.sql 定稿，PBKDF2 10,000 次实现已在 backend/src/auth/password.ts，JWT 在 backend/src/auth/jwt.ts，都直接复用不要重写）。

本次任务 = P2 独立账号闭环：
- 后端 9 个 auth 端点（register/login/refresh/logout/recover/password/session/sessions/DELETE sessions/:id）
- 严格按 PLAN 5 节实现：用户名 NFKC+lowercase 唯一、PBKDF2 10000+pepper、未知用户名与错密码统一 401 且跑假 PBKDF2 防时序、限流+连续失败锁定、refresh 一次一换 + 同 request_id 幂等重放 + 跨 request_id 旧 token 重用撤销整个 session、改密码/恢复后旧 access 与 refresh 立刻失效、恢复码一次性 CAS 竞争、幂等键
- backend/tests/auth/ 覆盖 PLAN 10.2 的 1-6 条
- Android：Keystore 加密存 refresh（禁明文 fallback、密文损坏回登录、切账号清空无残留）、刷新单飞去重、Compose 登录/注册/恢复三页面（D2 Quiet Grid token、浅色深色都要对、大字体不破版、429 文案）
- 7-8 条需真机的写进 P2_BLOCKERS.md，不许声称已验证

硬约束（违反会搞挂这台 VPS）：
- 本机仅 956MB。禁止在本机跑 npm install / gradle / docker / vitest。所有构建和测试必须在 GitHub Actions 执行。
- 你只写代码、git commit、git push、用 GitHub API（token 在 ~/.git-credentials）看 CI 结果和日志。
- 不许降低断言阈值或删测试来让 CI 变绿，失败读日志修根因。
- 不部署 Cloudflare、不建真实 D1、不写真实 secret。
- 不改 backend/migrations/0001_initial.sql（真要改必须先写 P2_BLOCKERS.md 说明）。
- 走新分支 → PR → 合并，不直推 master。

完成后写 P2_DONE.md：实现了哪些端点、10.2 八条的对应测试文件+用例名、已验证 vs 未验证/需真机、权衡与遗留。不要夸大。"

for i in 1 2 3 4 5 6 7 8; do
  echo "=== attempt $i $(date +%H:%M:%S) ==="
  codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
    -m gpt-6.1-sol --output-last-message logs/p2_summary.md "$PROMPT" \
    > "logs/p2_attempt${i}.log" 2>&1
  rc=$?
  if [ $rc -eq 0 ]; then echo "SUCCESS on attempt $i"; echo "attempt $i exit=0 ok=1" >> logs/p2_retry.log; exit 0; fi
  echo "attempt $i exit=$rc"
  echo "attempt $i exit=$rc ok=0" >> logs/p2_retry.log
  sleep 30
done
echo "ALL FAILED"
exit 1
