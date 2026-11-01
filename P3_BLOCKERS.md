# P3 未验证事项与既有范围缺口

没有需要修改 migration 的字段阻断；十四表的冻结迁移 SHA-256 未变。P3_TASK 的 29 个领域端点均已实现。以下缺口不能由 CI 模拟器冒充通过：

| 项目 | 状态 / 原因 / 后续 |
| --- | --- |
| PLAN 10.3.14 真实 D1 SQL p95、免费 Worker CPU、稳定公网 CRUD p95≤1.5s | **unverified**。本次明确禁止部署 Cloudflare、建立真实 D1、写真实 secret，故只能在 Actions 本地 Workers/D1 pool 保留 6,000/18,000 样本和 100 ms 断言。需要后续另行授权真实平台与网络验收 |
| PLAN 10.3.2 Android 领域评分展示；P4 真机业务端到端 | **unverified / P4**。本期验证 API 4/4.3 与 revision 十分位 40/43；不把 auth 模拟器检查视为领域产品 UI 已完成 |
| 生产 Cron CPU/扫描成本、清理中断和 Time Travel | **unverified**。CI 验证 30 天边界与有界分块不误删其他餐厅/账号；真实平台运维演练仍待后续阶段 |
| P2 遗留账号生命周期接口 | `POST /auth/logout-all`、`POST /auth/recovery-codes`、`GET/PATCH/DELETE /me` 在 `backend/src/routes/auth.ts` 中仍返回 501，不在本次 P3_TASK 的领域端点范围内。OpenAPI v1 中有它们的完整计划请求/响应类型，并明确实现边界；不能宣称全部 44 条契约都已实现。后续账号安全/设置闭环需实现这些接口并测试注销账号的完整清理 |

全部常规 CI 失败按日志修复根因；未删测试、降低性能阈值或新增 skip。分支/PR/合并证据见 P3_DONE.md。真实平台缺口不影响已实现领域 API 的 CI 验证，但 **PLAN 10.3 的全部外部验收不能宣称完成**。
