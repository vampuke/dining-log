# ADR 0001：固定签名、单域名与串行生产发布

状态：P5 实现；依据为只读 PLAN 11.1–11.5、10.5 与 P5_TASK。适用仓库 `vampuke/dining-log`，主分支 `master`。

## 决策

1. 正式 Android 包固定为 `top.alanvampuke.dininglog`，debug 加 `.debug`。沿用已有 RSA 3072 release key，环境变量提供 keystore / password / alias；缺任一项立即失败。release 保留 R8 与 ProGuard，应用版本来自 BuildConfig。
2. `production` environment 只允许 master，签名与 Cloudflare token 只在 release job 的必要步骤注入。常规 CI 只有只读 GitHub 权限及公开的本地合成测试材料，不能读取生产 key。
3. 手动选定完整 SHA 与稳定 tag，验证 HEAD、master 祖先、该 SHA 的完整 master CI 与 API 26/35 检查、版本及未使用的 tag。所有 actions 固定提交 SHA，Wrangler 来自 lock。
4. 同一候选先通过后端检查，再签名 / 验签 / 比较版本 / SHA-256 / API 26 与 35 安装。记录 Worker deployment / version 与 D1 bookmark，检查冻结 migration / schema 兼容性，再迁移、完整性、deploy、health、独立 smoke；成功后创建 Release。
5. Worker `dining-log-api` 仅使用 `dining.alanvampuke.top` Custom Domain；workers.dev 和 preview URLs 关闭。D1 `dining-log-prod` 为唯一生产数据库，不增加其他 API 主机或存储服务。
6. `dining-production` concurrency 不取消正在发布的任务。任何迁移、部署、smoke 或已有 Release / tag 冲突均失败停止，不覆盖产物、不自动倒退 D1。破坏性 schema 变更必须拆分为兼容发布；当前 0001 只允许空库初始化或已应用的原样 schema。

## 运维与验证边界

首次部署先建立无域名的 Worker，再 stdin 写入现有四个 secrets、重新部署、迁移与完整性检查，最后接管 Custom Domain。生产资源标识与证书摘要可以公开；密钥、keystore、凭据、auth 正文、DB export 与测试账号信息不进入仓库或 artifact。

release-evidence 仅保存签名 / 版本 / checksum、非敏感部署与 D1 元数据、health、smoke 的固定步骤标签及模拟器启动结果。keystore 位于 RUNNER_TEMP，权限受 umask 077 保护，退出 / 中断清理。

Worker 可以回滚到已记录且兼容的 version；D1 Time Travel 是单独的数据恢复操作，必须明确数据损失窗口。APK 沿用 key 并递增 versionCode，不覆盖已发布版本。实际部署、发布、回滚和真机验证的状态分开记入 [P5_DONE.md](../../P5_DONE.md)，命令见 [OPERATIONS.md](../../docs/OPERATIONS.md)。
