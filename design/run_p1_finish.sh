#!/bin/bash
# P1 收尾：容量测试 + APK 下载。失败自动重试。
set -u
source "$HOME/.codex/env"
export AGENTROUTER_API_KEY
cd /root/dining_log || exit 1
mkdir -p logs

PROMPT="完整阅读 /root/dining_log/design/P1_FINISH_TASK.md 并严格执行。

背景：P1 主体验收已通过（CI run 41270748412 全绿、migrations 14 表实测通过、Worker+Android 骨架完成）。本次只补两项遗留：
1. 4,500 对象容量测试（可重现、实测数字、写进 RESULTS.md、接入 CI）
2. 把 CI 产的 debug APK 下载发布到 /var/www/html/dl/dining/

关键约束（违反会搞挂这台 VPS）：
- 本机仅 956MB 内存。禁止在本机跑 npm install / gradle / docker / vitest。所有构建与测试执行必须在 GitHub Actions。
- 你只写代码、git commit、git push、读 CI 结果。用 GitHub API（token 在 ~/.git-credentials）查 run 和拉 artifact。
- 该仓库是 https://github.com/vampuke/dining-log ，默认分支 master。
- 不要降低断言阈值来让 CI 变绿。失败就读日志修根因。
- 不要部署 Cloudflare、不要写任何 secret 进仓库。
- 最终必须让 CI 跑绿，并把真实数字写进文件，不要估算。

完成后在仓库根目录写 P1_FINISH_DONE.md，列出：改了哪些文件、CI run id 与结论、RESULTS.md 里的核心数字、APK 的路径+大小+sha256、以及任何未完成项。"

for i in 1 2 3 4 5 6; do
  echo "=== attempt $i $(date +%H:%M:%S) ==="
  codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
    -m gpt-6.1-sol --output-last-message logs/p1f_summary.md "$PROMPT" \
    > "logs/p1f_attempt${i}.log" 2>&1
  rc=$?
  if [ $rc -eq 0 ]; then echo "SUCCESS on attempt $i"; echo "attempt $i exit=0 ok=1" >> logs/p1f_retry.log; exit 0; fi
  echo "attempt $i exit=$rc"
  echo "attempt $i exit=$rc ok=0" >> logs/p1f_retry.log
  sleep 20
done
echo "ALL FAILED"
exit 1
