# P5 签名、部署与发布验收

本文件随 P5 PR 交付，执行中更新真实证据。当前不声明生产部署或 signed APK 已完成。

依据：只读 [PLAN 10.5、11.1–11.5](design/PLAN.md) 和完整 [P5_TASK](design/P5_TASK.md)。基线 `5b3b50f0f5cf1c55ef09822509fa907f68575dd6`（含已准备的生产 D1 ID）；新分支 `p5/signing-production-release-0.5.0`。

## PLAN 10.5 逐条映射

| 交付条目 | 对应交付 | verified / unverified |
| --- | --- | --- |
| public repo 完整实现、MIT、README、架构 ADR、OpenAPI、迁移与运行说明 | public repo vampuke/dining-log；LICENSE、README、design/adr/0001-production-release.md、contracts/openapi.yaml、backend/migrations、docs/OPERATIONS.md | public repo / 既有实现 verified；本次 PR / CI / 合并待真实结果 |
| 唯一生产 API | https://dining.alanvampuke.top/api/v1；workers.dev / preview_urls=false | 配置 verified；DNS / TLS / HTTP 与真实业务 unverified，待部署 |
| 可安装 signed release APK、SHA-256、版本号、更新说明、GitHub Release | versionCode=5 / versionName=0.5.0、固定签名、release.yml、CHANGELOG 0.5.0 | 源码 verified；实际签名 / API 26/35 安装 / Release unverified，待 Actions |
| 正式 Cloudflare 配置、secret 名称、日志 / Cron / Time Travel | wrangler.toml、production environment、docs/OPERATIONS.md、release checkpoint / integrity 脚本 | D1 资源与 GitHub environment 名称 / master policy verified；Worker 与远端操作待验证 |
| README 本地 / 测试 / 签名 / 部署 / 回滚步骤 | README、OPERATIONS.md、固定命令与版本 | 文档已落实；实际步骤按后续证据标记；未运行生产回滚 / D1 restore |
| P0–P5 验收与仍需验证最终状态 | P1–P4_DONE、既有 BLOCKERS、本文件 | 既有 CI 证据保留；P5 不将真机 / 性能 / 恢复未测项包装为通过 |

## verified

- 生产 D1 已存在：dining-log-prod / `90d3c1ef-5ea0-4308-a866-89a96e0411d8`；本次未创建第二库。
- GitHub public repo / default branch master 已通过 API 只读确认。
- `production` environment 已通过 API 核对：仅 `master` / `branch` 可部署，五个 environment secret 名称存在，repository secret 数量 0。验证仅读取名称和策略，不读回值。
- 原始迁移 SHA-256=`191d1a80a7411ba828effc9041fbd144fa4d10abb372f61c793a8f6dc1a5b80b`；PLAN SHA-256=`8346ddfac5231fb10528b330bcaea170b8925b8b6ed441ccff9e9577b18519f0`，均保持原样。
- 本地 release guards（SHA / tag / 候选状态 / 版本递增 / checkpoint / 域名冲突）与 actionlint 通过；未在本机安装 backend 依赖、执行 Gradle、Docker 或 Vitest。

## unverified / 待实际证据

- 本次 Actions CI、PR 审查 / 合并、固定证书验签、signed APK API 26/35 安装、真实 Release。
- Worker 创建 → 四个 secret put → 二次 deploy → D1 迁移 / 14 表 / 完整性 → Custom Domain → health → 独立真实 smoke。
- Worker rollback 与 D1 Time Travel restore 演练、真实 Cron 清理 CPU / 扫描成本、离线加密备份恢复。
- 生产免费 Worker 密码 CPU 冷 / 热每场景 ≥30 样本、6,000 / 18,000 数据集的真实 D1 / 公网 p95、跨地域限流、共享额度与实际告警。
- P4 真机弱网 / 切网、三网、TalkBack、真实进程终止、厂商 Keystore、系统大字体 / 输入法；首次发布没有历史 signed APK 可做真实版本升级。
- 既有额外账号生命周期占位接口仍不计入已实现的 29 领域 +9 auth 范围。

## 真实证据

这里只追加实际执行的 curl、SQL、Cloudflare / GitHub 非敏感元数据、Actions run 链接与产物摘要；不收录密码、token、恢复码、keystore、账号数据或 DB export。

预检确认 Worker 尚不存在、D1 `num_tables=0`、zone active、无目标 Worker Domain 绑定。DNS listing HTTP 403 / code 10000 已停止该查询；公开 DNS 已解析至 Cloudflare edge，不能据此断言不存在显式 DNS 记录。生产绑定将以 Domain changeset 无冲突为前置。
