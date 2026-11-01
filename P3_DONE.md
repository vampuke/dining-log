# P3 领域 API 与修改历史

## 范围与证据

PR：[vampuke/dining-log #3](https://github.com/vampuke/dining-log/pull/3)，从 `655c48e` 的独立 `p3-domain-api` 分支提交，未直推 master。核心测试先于实现：`14b0221` → `31c6d3f`；后续竞态、搜索和契约问题也先补用例再修复。

代码验收提交 `687242b0778f873ffba000621a6c1d7fcdb2c07c` 的 [GitHub Actions run 41285189852](https://github.com/vampuke/dining-log/actions/runs/41285189852) 已全部通过，2026-11-01 UTC 核对 API 状态与日志：

- [backend](https://github.com/vampuke/dining-log/actions/runs/41285189852/job/124784955463)：TypeScript / ESLint；44 个 v1 endpoint 契约与引用；8 文件 **143/143** 常规后端用例；sqlite3 CLI 冻结 DDL / 外键 / 约束验证；两组容量实测。
- [android](https://github.com/vampuke/dining-log/actions/runs/41285189852/job/124784955350)：`:app:assembleDebug :core:test :app:testDebugUnitTest :app:lintDebug` 全部成功；APK 的包名、版本、签名、校验和检查通过。
- [API 26](https://github.com/vampuke/dining-log/actions/runs/41285189852/job/124784955493) / [API 35](https://github.com/vampuke/dining-log/actions/runs/41285189852/job/124784955476)：各 **7/7** auth / Keystore / Compose instrumentation 测试通过；属于模拟器证据。

文档与容量样本随后提交；最终文档提交的合并门禁和合并状态以 [PR #3 的 Checks](https://github.com/vampuke/dining-log/pull/3/checks) / PR 合并记录为证据入口。上述最终代码验收提交与随后文档提交的领域实现和用例完全相同。

补充测试 `1f6b47b`、`c2bba79` 在 Actions 先复现评分容差、守卫清理顺序、API 时刻的失败，再由 `751d3db` 修复通过；批次末尾失败用例同时证明搜索和成功幂等快照共同回滚。`c61648f` / run `41284478724` 先复现规范化顺序，`bb948f4` 修复；`c0d50bc` / run `41284903501` 先复现幂等缓存未命中后预检查竞态，`687242b` 修复。两条新增竞态用例在最终 run 通过，涵盖实体修改、删除、revision metadata 与父删除后的子创建重放。原有测试与 100 ms 性能阈值保留。

`design/PLAN.md` 只读；migration 未改，SHA-256 保持 `191d1a80a7411ba828effc9041fbd144fa4d10abb372f61c793a8f6dc1a5b80b`。所有 npm/TypeScript/ESLint/Vitest/Android 构建与测试均在 GitHub Actions。未部署 Cloudflare、未创建真实 D1、未写真实 secret。

## 实际实现的 29 个领域端点

均以 `/api/v1` 为前缀，必须通过 P2 accessIdentity：

| 对象 | Method / path |
| --- | --- |
| 餐厅（6） | `POST /restaurants`；`GET /restaurants`；`GET /restaurants/{restaurant_id}`；`PATCH /restaurants/{restaurant_id}`；`DELETE /restaurants/{restaurant_id}`；`POST /restaurants/{restaurant_id}/restore` |
| 菜品（6） | `POST /restaurants/{restaurant_id}/dishes`；`GET /restaurants/{restaurant_id}/dishes`；`GET /dishes/{dish_id}`；`PATCH /dishes/{dish_id}`；`DELETE /dishes/{dish_id}`；`POST /dishes/{dish_id}/restore` |
| 到店（7） | `POST /restaurants/{restaurant_id}/visits`；`GET /restaurants/{restaurant_id}/visits`；`GET /visits`；`GET /visits/{visit_id}`；`PATCH /visits/{visit_id}`；`DELETE /visits/{visit_id}`；`POST /visits/{visit_id}/restore` |
| 历史（9） | `POST /revisions`；`GET /revisions`；`GET /restaurants/{restaurant_id}/revisions`；`GET /dishes/{dish_id}/revisions`；`GET /visits/{visit_id}/revisions`；`GET /revisions/{revision_id}`；`PATCH /revisions/{revision_id}`；`DELETE /revisions/{revision_id}`；`POST /revisions/{revision_id}/restore` |
| 搜索（1） | `GET /search`，独立选择 restaurants / visits / revisions 组；菜品命中归入餐厅 |

没有 `GET /dishes`。DELETE 有 JSON body；资源响应显式返回 nullable 字段；summary 不替代完整详情。`contracts/openapi.yaml` 定稿版本 `1.0.0`，44 个 method/path 包含上述 29 个领域端点、既有 auth/me 契约和 health；CI 校验精确路径集合、引用、类型、错误和头，取消了通用空 schema。

P2 遗留的 `POST /auth/logout-all`、`POST /auth/recovery-codes`、`GET/PATCH/DELETE /me` 仍是 501，未计入本期 29 个已实现领域端点。契约中明确标注它们的计划行为与本期实现边界；没有宣称整个账号生命周期已完成。见 [P3_BLOCKERS.md](P3_BLOCKERS.md)。

## PLAN 10.3 十五条 → 测试映射

简称：core=`backend/tests/domain/core.test.ts`，acceptance=`backend/tests/domain/acceptance.test.ts`，queries=`backend/tests/domain/queries.test.ts`。每条以下给出完整用例名。

| # | 对应测试文件 + 用例名 | 验证边界 |
| --- | --- | --- |
| 1 | acceptance：`S1 plain address five restaurant dishes three visits including same day` | 纯文本地址、5 菜品、3 到店、同日两条；stats/时间线；拒绝 visit_id/dish_visit 与全局菜品列表 |
| 2 | core：`S2 create history and 40 to 43 integer rating diff` | create 非空字段 before=null；API 4/4.3，存储 diff 40/43。Android 领域评分 UI 属 P4，未验证 |
| 3 | core：`S3 multiple changed fields make one server computed revision` | 地址与评价一条 revision、两个字段；客户端 before 返回 UNKNOWN_FIELD |
| 4 | core：`S4 normalized no-op preserves time version and history atomically`；`no-op child save still guards the parent at batch time`；`no-op restaurant save loses a version race and leaves no successful cache or history`；`normalization precedes text length validation within the 64 KiB envelope` | 不递增 version/时间，不写 set/revision；原子确认版本和父对象，返回 changed=false |
| 5 | core：`S5 same key retries create restaurant first visit and history once`；`same-key CAS loser replays the committed winner before classifying conflict`；`same-key different fingerprint batch loser is idempotency conflict before version conflict`；`same-key commit after cache miss replays before entity and metadata preflight conflicts`；`same-key child creation replays committed success when its parent disappears after the cache miss` | 成功状态、data、meta 完全重放；同 key 改 payload 409；并发失败先查幂等 |
| 6 | core：`S6 real concurrent same-base PATCH has one winner and no loser residue` | Promise.all 实际请求并发，一 200 一 409，current 属本人；失败方 set/revision/key/guard 无残留 |
| 7 | core：`S7 deletion between parent read and child insert rolls back every child write`；`no-op child save still guards the parent at batch time` | test-only 拦截真实 D1 batch，在 read/write 间提交父删除；所有子写入回滚 |
| 8 | acceptance：`S8 all account lists IDs children history restore and sessions isolate owners`；`S12 reapply recomputes current diff and preserves unselected fields and ownership`；core：`foreign IDs fail without revealing current or leaving a partial restaurant first visit`；既有 `backend/tests/auth/auth.test.ts`：`session listing/revocation is owner scoped and logout accepts expired access` | 列表无 B 内容、定向 ID/子对象/历史/restore/session 404，无 current 泄露；组合 FK 同时拒绝跨账号 child 插入 |
| 9 | acceptance：`S9 parent delete excludes search and every child CRUD restore preserves child tombstone` | 所有子 CRUD 受父有效条件保护；父恢复不复活单独删除的菜品；delete/restore diff 与搜索同步。API diff 时刻与 tombstone 的 ISO UTC 值完全一致，存储 diff 仍为毫秒整数 |
| 10 | acceptance：`S10 restore exact thirty day boundary and bounded purge preserve other accounts`；`restore succeeds just inside thirty days without a session clock jump` | 固定仍有效的当前 auth 时钟，正好 30 天为 410，边界内 1 ms 可恢复；25 子对象分块清理，其他账号和餐厅保留，FK 完整 |
| 11 | acceptance：`S11 revision read note hide restore forbid diff and original timestamps` | note/metadata CAS；隐藏、include、恢复；diff/diff_json/原始时间等 PATCH 400；不写“历史的历史” |
| 12 | acceptance：`S12 reapply recomputes current diff and preserves unselected fields and ownership`；`reapply rejects create delete restore sources duplicates empty excessive and forbidden fields`；`visit update no-op delete and restore keep one revision per save and scoped history`；`dish reapplication validates integer historical tenths and preserves current text` | 当前值→选中历史值的重新 diff；保留未选字段；来源/目标可访问性，1–7 不重复字段，拒绝非 update 源及系统字段；无变化 200 |
| 13 | queries：`S13 normalized Chinese English fullwidth multiple keywords and literal percent underscore hits`；`search index rebuilds with sibling concurrent changes and delete restore in the same batch`；`visit and optional revision search groups exclude deleted targets and hidden history` | 中文、大小写、全半角、AND 多关键词、%/_ 字面匹配；四种 matched_fields；兄弟并发更新不丢搜索片段 |
| 14 | `backend/tests/capacity/capacity_test.ts`：`plan-6000: exact corpus, measured SQL, atomic complete write, p95 <= 100ms`（it.each 展开名）；同文件 `p1-4500: exact corpus, measured SQL, atomic complete write, p95 <= 100ms` | 精确 6,000 / 18,000（另保留 4,500 / 13,500）；每种 30 次，九种 SQL + 八条真实 API 读取路径，100 ms 阈值保持。真实 D1/免费 Worker CPU/稳定网络 CRUD p95≤1.5s **unverified** |
| 15 | acceptance：`S15 long text internal newlines null unrated and leap day round trip explicit errors`；`ratings require exact tenths for restaurant and dish writes` | 4,000 字符长文、内部换行空格/null/闰日往返；非法日期/评分/字符串/非整数 tenths/超长/未知字段/缺 version/64 KiB 明确错误。餐厅及菜品的 create/PATCH 拒绝 `4.30000000001` 等偏离十分位的值，拒绝后不写历史或幂等结果 |

补充批次失败验收：core `batch Nth statement failure rolls back business guards history search and idempotency`、`late batch failure rolls back rebuilt search and successful idempotency response`、`first visit late constraint failure also rolls back the restaurant`。末尾注入失败会回滚已经执行的搜索重建与成功结果缓存，同 key 随后的正常请求可以成功；`success response is cached before transactional guard cleanup` 用 test-only SQL trigger 检验规定的成功结果→守卫清理顺序。补充 cursor 伪造验收：queries `keyset rating null and duplicate dates pages have no gaps and strict cursors reject tampering`，覆盖恶意 ID、键值、签名、未知字段、错误排序、过长、变化筛选、跨账号以及 null 评分翻页。

## 核心机制代码位置

| 机制 | 位置与实现 |
| --- | --- |
| CAS + changes 守卫 | `backend/src/domain/mutations.ts:11` 子 INSERT…SELECT；`:41` 创建循环业务 INSERT 紧接守卫，key+kind+ID；`:71` CAS UPDATE 含 version/deleted_at/父 EXISTS，`:73` 紧接守卫。P2 `backend/src/auth/config.ts:33` guard 利用冻结 migration 的 CHECK 将 0 行变成 batch SQL 失败；创建餐厅+首访一个 batch、两个独立 guard/revision、一个 set；`backend/src/auth/routes.ts:35` 同批先写成功幂等结果再清理守卫 |
| 错误归因 / 幂等 | P2 `backend/src/auth/routes.ts:35` commit，catch 的第一步 `cached`；`backend/src/auth/idempotency.ts:41` BusinessIdempotency，SHA-256/canonical JSON/规范化路径、同事务成功信封、7 天；`backend/src/repositories/domain.ts:14` owned→version→其余异常；创建失败 `mutations.ts:49` 外账号 ID 404、本人 ID_CONFLICT、其余 500。`backend/src/domain/http.ts:30` 包含预检查与失败重查，覆盖缓存 miss→另请求提交→实体/父/source 已变化；未将任意 SQL 异常伪装成 409 |
| 无变化保存 | `backend/src/domain/mutations.ts:67` diff 无变化不递增；`:70` 仅 SET version=原值，但 CAS/父条件及紧随 guard 保留；不创建 change_set/revision，仅缓存成功。revision 元数据同类逻辑在 `backend/src/domain/revision-metadata.ts:7` |
| 服务端 diff | `backend/src/domain/model.ts:6` 固定白名单，`:7` diff、`:17` rating→tenths；`backend/src/domain/schemas.ts:5` NFC/CRLF/trim，`:11` 精确十分位校验；`mutations.ts:91` 重应用校验来源/字段/整数评分，重新读当前目标；一保存一实体一 revision。`backend/src/repositories/history.ts:14` 仅在出站把 deleted_at diff 转为 ISO UTC，评分 40/43 保留整数 |
| 搜索同批重建 | `backend/src/domain/search-index.ts:11` namespaced JSON fragment 更新最新数据库行，再重建 text，`:14` 父删除移除索引；`mutations.ts:45` / `:77` 在业务/history 同批追加。`backend/src/domain/queries.ts:30` 所有关键词绑定 instr；`:89` keyset 参数化；`backend/src/domain/pagination.ts:10` strict 解码、签名、类型/筛选/owner 校验 |

P2 auth 只做向后兼容的接口导出、IdempotencyStore 通用接口、业务错误/guard 扩展，以及 commit 的可选事务末尾清理语句参数；既有调用默认不增加清理语句。accessIdentity、密码、JWT、刷新与恢复流程复用，所有既有 auth 测试保留。

## Verified / unverified 与权衡

- **Verified**（以上 run 与日志）：Workers 本地 D1 pool 的 40 个领域用例、29 个既有 auth 用例；常规后端总计 143/143，8/8 文件通过；SQL 约束/完整性、OpenAPI 44 条契约；Android debug/core/app/lint 和 API 26/35 各 7 个 auth instrumentation 用例。
- **Verified 容量样本**：两组各有 9 种 SQL ×30 次、8 条实际 API 读取 ×30 次。4,500 / 13,500 组：SQL wall p95 最大 27 ms、SQL meta.duration p95 最大 19 ms、API wall p95 最大 34 ms；6,000 / 18,000 组：分别最大 38 / 24 / 47 ms，均保持 ≤100 ms。完整写入 batch wall 分别 9 / 10 ms，仅为直接 SQL 样本；两组 FK violations=0、quick_check=ok。
- **Unverified**：真实 Cloudflare D1 原子性/rows 计费/延迟；免费 Worker CPU≤10 ms/1102；公网 CRUD/列表 p95≤1.5s、三网链路；真实设备、Android 领域 UI/Room 全流程、生产 Cron 时间预算、Time Travel 演练。P3 并发用例的 DB batch 在 CI 的真实 Workers SQLite 执行，不是远端 D1，也不是纯 JS 假数据库。
- `restaurant_search.content` 在现有 TEXT 字段中存 JSON（每实体 normalized fragment + 聚合 text），不改 migration。并发兄弟写通过 json_set 在已提交最新行上更新，防止预读全量文本覆盖；kind:UUID 避免跨表同 UUID 覆盖。额外存储与 JSON 解析成本由容量样本测量，不能外推免费 CPU；每家店菜品极多时全量恢复仍需预算验证。
- 软删除保留子标记；恢复父仅重建索引。清理按各阶段最多 20 主键，先缓存/历史，再子实体和父，另清空 set；保证单次主键量有界，SQL 扫描与 Worker CPU 没有宣称已远端实测。
- auth 表/幂等既有 P2 时刻是秒，领域实体/业务幂等采用毫秒；转换在清理条件中显式处理，不改已有迁移或 P2凭据时间语义。
- cursor 使用已有 HMAC key 签名并绑定当前账号与筛选；变更该 key 将使旧 cursor 返回 400，需要从头分页。分页没有跨请求快照保证，rating null 排末尾，同键按 ID DESC。
- revision 隐藏不等于删除存储，元数据恢复没有独立 30 天窗口，原始 diff 跟随目标直到永久清理；source_revision_id 在迁移中无 FK，源永久清理后仍保留来源 UUID，不编造其内容。
- 旧容量结果未当作本期结果；`backend/tests/capacity/RESULTS.md` 与 `measurements.json` 从 run `41285189852` 的 `capacity-results` artifact（ID `13074836657`）原样复制。metadata 中 `headSha=8c4db3f146115edbc9a17cb1b263e695bb358f31` 是 GitHub 的 `refs/pull/3/merge` 临时验证提交，API 返回的 PR source head 是 `687242b`；二者用途不同。artifact 保存原始日志。直接 SQL 的完整 batch 写入测量不冒充经过鉴权/幂等/CAS 的生产 CRUD 性能。

## 后端领域用例数与完整名称列表

P3 新增常规领域用例 **40**（core 20、acceptance 13、queries 7）。既有 auth **29** 个全部保留，名单见 [P2_DONE.md](P2_DONE.md)；常规后端 **143/143**、8/8 文件通过。两项独立容量用例各在新 Workers-pool 进程执行，原有 `-t` 场景选择保留：每个进程运行一个场景并报告另一个未选中，两个场景各自都实际通过；没有新增 skip 或降低 100 ms 断言。另有独立 OpenAPI 精确覆盖检查，不计入 Vitest 数量。

### backend/tests/domain/core.test.ts（20）


1. `S2 create history and 40 to 43 integer rating diff`
2. `S3 multiple changed fields make one server computed revision`
3. `S4 normalized no-op preserves time version and history atomically`
4. `normalization precedes text length validation within the 64 KiB envelope`
5. `S5 same key retries create restaurant first visit and history once`
6. `S6 real concurrent same-base PATCH has one winner and no loser residue`
7. `same-key CAS loser replays the committed winner before classifying conflict`
8. `S7 deletion between parent read and child insert rolls back every child write`
9. `no-op child save still guards the parent at batch time`
10. `batch Nth statement failure rolls back business guards history search and idempotency`
11. `late batch failure rolls back rebuilt search and successful idempotency response`
12. `success response is cached before transactional guard cleanup`
13. `first visit late constraint failure also rolls back the restaurant`
14. `same-key commit after cache miss replays before entity and metadata preflight conflicts`
15. `same-key child creation replays committed success when its parent disappears after the cache miss`
16. `no-op restaurant save loses a version race and leaves no successful cache or history`
17. `same-key different fingerprint batch loser is idempotency conflict before version conflict`
18. `batch deletion of target is NOT_FOUND before any version conflict`
19. `restaurant and first visit sharing a UUID have independent changes guards and revisions`
20. `foreign IDs fail without revealing current or leaving a partial restaurant first visit`

### backend/tests/domain/acceptance.test.ts（13）

1. `S1 plain address five restaurant dishes three visits including same day`
2. `S8 all account lists IDs children history restore and sessions isolate owners`
3. `S9 parent delete excludes search and every child CRUD restore preserves child tombstone`
4. `S10 restore exact thirty day boundary and bounded purge preserve other accounts`
5. `restore succeeds just inside thirty days without a session clock jump`
6. `S11 revision read note hide restore forbid diff and original timestamps`
7. `S12 reapply recomputes current diff and preserves unselected fields and ownership`
8. `reapply rejects create delete restore sources duplicates empty excessive and forbidden fields`
9. `S15 long text internal newlines null unrated and leap day round trip explicit errors`
10. `ratings require exact tenths for restaurant and dish writes`
11. `business cache lasts seven days and P2 auth janitor cannot erase it early`
12. `visit update no-op delete and restore keep one revision per save and scoped history`
13. `dish reapplication validates integer historical tenths and preserves current text`

### backend/tests/domain/queries.test.ts（7）

1. `S13 normalized Chinese English fullwidth multiple keywords and literal percent underscore hits`
2. `search index rebuilds with sibling concurrent changes and delete restore in the same batch`
3. `visit and optional revision search groups exclude deleted targets and hidden history`
4. `keyset rating null and duplicate dates pages have no gaps and strict cursors reject tampering`
5. `restaurant rating category visited dates and revision field time filters are enforced`
6. `restaurant and dish may share a UUID without overwriting search fragments`
7. `visited flag and date interval are independent predicates and visit q matches only note or restaurant name`
