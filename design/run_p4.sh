#!/bin/bash
# P4：Android 产品闭环。失败自动重试（含限流退避）。
set -u
source "$HOME/.codex/env"
export AGENTROUTER_API_KEY
cd /root/dining_log || exit 1
mkdir -p logs

PROMPT="完整阅读 /root/dining_log/design/P4_TASK.md 并严格执行。开工前必须先读 /root/dining_log/design/PLAN.md 的 7.1~7.5 节和 10.4 P4 端到端路径（只读，不要改）。

背景：P1+P2+P3 已完成，master=17d8b4f，后端 29 个领域端点 + 9 个 auth 端点全部实现且 CI 全绿。Android 已有 4 模块骨架和 P2 认证闭环（Keystore 存 refresh、单飞刷新、登录/注册/恢复三页），视觉 token 在 android/feature/.../theme/ 目录，是 D2 Quiet Grid 明暗两套。参考原型 design/proto/d2-quiet-grid.html。

本次 = P4 Android 产品闭环：底部三 tab（餐厅/到店/我的）、餐厅列表+详情+编辑（菜品在餐厅编辑内增删改，菜品不是独立 tab）、全局到店时间线、修改历史（摘要/详情/加说明/隐藏/恢复/重新应用字段）、聚合搜索（搜餐厅+菜品+评价+备注并返回命中类型）、我的（session 管理、改密码、退出）。加 Room 只读缓存 + 草稿（500ms debounce、Idempotency-Key 固定、result_unknown 原 key 重试、7 天窗口）和 409 三栏冲突面板（base_snapshot / server_current / local_draft）。

硬要求（用户手机常开深色，这些不是加分项）：
- 浅色和深色必须都正确，模拟器测试要断言实际色值
- 200% 大字体不破版、保存按钮可达
- 键盘弹出时保存按钮不被遮挡
- 无图片/头像/占位图/上传入口，只有文字数字图标色块
- 评分一律 ★ 4.3 形式，编辑滑杆 0.1 步进

硬约束（违反会搞挂这台 VPS）：
- 本机仅 956MB。禁止在本机跑 npm install / gradle / docker / vitest / 模拟器。所有构建测试在 GitHub Actions。
- 你只写代码、git commit、git push、用 GitHub API（token 在 ~/.git-credentials）看 CI 和日志。
- 不许降低断言阈值、删测试或 skip 让 CI 变绿。失败读日志修根因。
- 不许改 backend/migrations/0001_initial.sql（SHA 必须 191d1a80a7411ba828effc9041fbd144fa4d10abb372f61c793a8f6dc1a5b80b）和 design/PLAN.md。
- 不动后端业务逻辑（P3 已定稿）；确实发现后端 bug 才可以改，并在 P4_DONE.md 单列。
- 不部署 Cloudflare、不写真实 secret。
- 走新分支 → PR → 合并，不直推 master。
- 需真机的项（弱网、三网、TalkBack、进程终止、不同厂商 Keystore）写进 P4_BLOCKERS.md 的需真机清单，不许声称已验证。

完成后写 P4_DONE.md：页面清单与实际完成度、10.4 端到端路径逐步对应测试、浅色/深色/大字体/键盘各自验证方式与结果、离线草稿冲突三机制实现位置、verified vs unverified、后端是否被改。并把新 APK 发布到 /var/www/html/dl/dining/（命名 shiji-<短sha>.apk + 软链 shiji-debug.apk + SHA256SUMS）。不要夸大。"

for i in 1 2 3 4 5 6 7 8 9 10; do
  echo "=== attempt $i $(date +%H:%M:%S) ==="
  codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
    -m gpt-6.1-sol --output-last-message logs/p4_summary.md "$PROMPT" \
    > "logs/p4_attempt${i}.log" 2>&1
  rc=$?
  if [ $rc -eq 0 ]; then echo "SUCCESS on attempt $i"; echo "attempt $i exit=0 ok=1" >> logs/p4_retry.log; exit 0; fi
  echo "attempt $i exit=$rc"
  echo "attempt $i exit=$rc ok=0" >> logs/p4_retry.log
  # 限流退避：检测 rate limit 就等更久
  if grep -q "rate limit exceeded" "logs/p4_attempt${i}.log" 2>/dev/null; then
    echo "detected rate limit, backing off 180s"
    sleep 180
  else
    sleep 30
  fi
done
echo "ALL FAILED"
exit 1
