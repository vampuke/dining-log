#!/bin/bash
# P5：签名部署发布。失败自动重试，限流退避。
set -u
source "$HOME/.codex/env"
export AGENTROUTER_API_KEY
cd /root/dining_log || exit 1
mkdir -p logs

PROMPT="完整阅读 /root/dining_log/design/P5_TASK.md 并严格执行。开工前先读 /root/dining_log/design/PLAN.md 的 11.1~11.5 节和 10.5 P5 交付清单（只读）。

背景：P1~P4 全部完成，master=1e0fa08，后端 29 领域 + 9 auth 端点，Android 三 tab 产品闭环已合并，CI 全绿。

前置已由 Hermes 完成（不要重做，不要重新生成密钥）：
- 生产 D1 已建：dining-log-prod / 90d3c1ef-5ea0-4308-a866-89a96e0411d8，已写入 backend/wrangler.toml
- 生产密钥在 /root/.dining_secrets/worker_secrets.json（0600），含 JWT_HS256_KEYS / PASSWORD_PEPPERS / REFRESH_RETRY_KEYS / RATE_LIMIT_HMAC_KEY，均验证为 32 字节
- Release keystore 在 /root/release_key/dining-release.jks，参数在 signing.env，b64 在 dining-release.jks.b64，证书 SHA256 指纹 A8:18:06:DB:CD:D4:C5:A2:12:91:5F:8A:6F:76:7B:37:1C:96:D6:6C:C6:1B:13:93:D0:16:B7:7D:B9:CE:70:93
- Cloudflare token 在 /root/.cloudflare_env，账号 49eee8b8033579863adb3b7cf0b1852c，区 alanvampuke.top id e046faddc69173d411003bb43f93ea83
- GitHub 仓库 vampuke/dining-log，token 在 ~/.git-credentials；repo secrets 和 environments 目前都是空的，需要你创建

本次 = P5 全套：
1) Gradle release 签名配置（环境变量读取，缺变量必须失败不许 fallback debug key，debug 加 applicationIdSuffix .debug，versionCode→5 / versionName→0.5.0）
2) .github/workflows/release.yml 完整实现（现在是 12 行占位）：workflow_dispatch 输入 commit_sha+release_tag、校验 SHA 格式和 master 祖先、environment production、concurrency dining-production、后端候选验证、keystore 解码到 RUNNER_TEMP 带 trap 清理、assembleRelease、apksigner 验签并断言证书指纹、versionCode/Name 与 tag 一致、sha256、time-travel info、迁移完整性检查、migrations apply、deploy、health、独立 smoke（注册→登录→建餐厅→改评分→查历史→删除→恢复→登出）、gh release create。失败就停。
3) 部署 Worker：先 wrangler deploy 建立 Worker → wrangler secret put 四个 secret（从 worker_secrets.json 读，值走 stdin）→ 再 deploy 一次 → 远程 D1 应用迁移 → 绑定 Custom Domain dining.alanvampuke.top
4) GitHub production environment + 5 个 secrets（ANDROID_KEYSTORE_B64 / ANDROID_STORE_PASSWORD / ANDROID_KEY_PASSWORD / ANDROID_KEY_ALIAS / CLOUDFLARE_API_TOKEN），用 libsodium sealed box 加密（pynacl 已装）
5) CHANGELOG 0.5.0 段、README 本地/测试/签名/部署/回滚步骤、运维说明（secret 名称清单不含值）、P5_DONE.md 对照 PLAN 10.5

硬约束：
- 本机 956MB：禁止本机跑 npm install / gradle / docker / vitest。Android 构建在 Actions。wrangler 和 curl 可以本机跑。
- 绝对不许把 secret 值写进仓库、日志、汇报。wrangler.toml 只放 database_id。
- 不许改 backend/migrations/0001_initial.sql（SHA 191d1a80a7411ba828effc9041fbd144fa4d10abb372f61c793a8f6dc1a5b80b）和 design/PLAN.md。
- 不许降断言阈值、删测试、skip。
- 走新分支 → PR → 合并，不直推 master。
- 部署是真实生产操作：每步验证（health 200、D1 14 表、迁移记录、secret list），失败停下报告不要盲目重试覆盖。

完成后写 P5_DONE.md，逐条对照 PLAN 10.5，verified vs unverified 分开，附真实证据（curl 输出、SQL 结果）。不要夸大。"

for i in 1 2 3 4 5 6 7 8 9 10; do
  echo "=== attempt $i $(date +%H:%M:%S) ==="
  codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
    -m gpt-6.1-sol --output-last-message logs/p5_summary.md "$PROMPT" \
    > "logs/p5_attempt${i}.log" 2>&1
  rc=$?
  if [ $rc -eq 0 ]; then echo "SUCCESS on attempt $i"; echo "attempt $i exit=0 ok=1" >> logs/p5_retry.log; exit 0; fi
  echo "attempt $i exit=$rc"
  echo "attempt $i exit=$rc ok=0" >> logs/p5_retry.log
  if grep -q "rate limit exceeded" "logs/p5_attempt${i}.log" 2>/dev/null; then
    echo "rate limited, backoff 180s"; sleep 180
  else
    sleep 30
  fi
done
echo "ALL FAILED"
exit 1
