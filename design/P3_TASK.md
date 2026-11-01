# P3 任务：领域 API 与修改历史

工作目录 `/root/dining_log`。方案 `/root/dining_log/design/PLAN.md` **只读**，是唯一事实来源。
**开工前必须完整读这几节**（不要跳过，全部是硬约束）：

- `4.1 协议约定`（665 行起）：信封、分页 cursor、错误码、`expected_version` 语义、`Idempotency-Key`、DELETE 带 body、64 KiB 上限、snake_case、Zod strict
- `4.4 餐厅 CRUD 与搜索`、`4.5 菜品`、`4.6 到店`、`4.7 Revision`、`4.8 综合搜索`、`4.9 请求示例`
- `4.10 原子写入、幂等与成本`（952 行起）：**这是本期最关键的一节**，batch + `changes()` 行数守卫 + CAS
- `6.1 ~ 6.4 字段级修改记录`（1177 行起）：diff 白名单、规范化、噪音规避
- `10.3 P3 必过场景`（1636 行起）：15 条验收，逐条要有对应用例

`backend/migrations/0001_initial.sql` **已定稿，不要改**。表都在，照着用。P2 的 `src/auth/` 全部复用（`accessIdentity` / `commit` / `guard` / `idempotency` / 错误信封）。

---

## 一、核心机制（做错这几点整个 P3 就废了，优先实现并先写测试）

### 1.1 CAS + 行数守卫（PLAN 4.10）

**这是全期最容易做错的地方。** 正确模式：

```sql
UPDATE restaurants
SET rating_tenths = ?, updated_at = ?, version = version + 1
WHERE id = ? AND user_id = ? AND version = ? AND deleted_at IS NULL;
INSERT INTO mutation_guards (operation_id, affected_rows) VALUES (?, changes());
-- change_set / revision / restaurant_search 重建 / 幂等响应
DELETE FROM mutation_guards WHERE operation_id = ?;
```

硬要求：
- `changes()` 必须**紧跟**对应业务 INSERT/UPDATE，中间**不得插入会改变计数的 SQL**
- 同一 batch 内每个守卫用**不同** `operation_id`（逻辑 key + 实体 ID），不能复用主键
- CAS 更新 0 行 → 守卫 `affected_rows != 1` → 该语句失败 → **整批回滚**
- 创建子对象必须 `INSERT ... SELECT ... FROM restaurants WHERE user_id=? AND deleted_at IS NULL`（父餐厅在预读取后被删也不许写入子对象）
- 修改子对象同样要带父餐厅有效的 `EXISTS`
- 创建餐厅 + 首访是**一个 batch**：两个业务 INSERT、两个独立守卫、两个 revision、一个 change_set。首访校验失败则餐厅也不创建

### 1.2 batch 失败后的错误归因（PLAN 4.10 第 6 条）

batch 抛错后**不能一律当 409**，顺序是：
1. 先重新查同 key 的已提交幂等结果，核对指纹 → 命中就返回原成功结果（解决"第二个同 key 请求先在 CAS 处失败"）
2. 未命中 → 查实体是否存在 / 是否属主 → 404 或 403
3. 版本不符 → 409 `VERSION_CONFLICT`，带上 `current` 快照
4. 其余 SQL 异常 → 500，不要伪装成版本冲突

### 1.3 无变化保存（PLAN 4.10 + 6.3 第 8 条）

内容规范化后与当前值完全相同：
- 仍要**原子确认版本和父对象有效**（带版本条件的 no-op UPDATE + 守卫）
- **不修改** `updated_at` / `version`
- **不写** change_set / revision
- 只存幂等结果
- 返回 `changed=false`、原 version、`change_set_id=null`、`revisions=[]`

### 1.4 幂等（PLAN 4.10）

- 指纹 = `SHA-256(method + normalized path + canonical normalized JSON)`
- 同 key 同指纹 → 返回同一状态与结果，**不产生第二条历史**
- 同 key 不同指纹 → 409 `IDEMPOTENCY_CONFLICT`
- 成功结果与业务写入**同事务**保存
- 只缓存成功结果，7 天

### 1.5 Diff（PLAN 6.1 / 6.3）

- **服务端计算**：客户端只发 `expected_version` + 拟改字段。**绝不信任客户端提供的 before 值**
- 白名单：
  - restaurant：`name, category, cuisine, address, rating_tenths, review, note, deleted_at`
  - dish：`name, rating_tenths, review, deleted_at`
  - visit：`dining_date, meal, note, deleted_at`
- **不记录** `restaurant_search.content`、`updated_at`、`version`、trace、session
- 评分 diff 用**十分位整数**：4.0→4.3 存 `40 → 43`，绝不是 4.0/4.3 浮点
- 一次保存多字段 → **一条** revision 含多个字段，不是每字段一条
- create：version 0→1，非空字段 `before=null`，**不要制造 null→null**
- delete：`deleted_at` null→时刻；restore：时刻→null
- 规范化后再 diff：CRLF→LF、首尾 trim、可选空串→null；**正文内部换行/空格不压缩**

---

## 二、端点（PLAN 4.4 ~ 4.8，一个不能少）

### 餐厅
`POST/GET /restaurants`、`GET/PATCH/DELETE /restaurants/{id}`、`POST /restaurants/{id}/restore`

### 菜品（全部绑定父餐厅）
`POST/GET /restaurants/{id}/dishes`、`GET/PATCH/DELETE /dishes/{id}`、`POST /dishes/{id}/restore`
- **没有**跨餐厅 `GET /dishes`，**没有**全局菜品一级页
- 接收 `visit_id` / `dish_visit` 等未知字段 → 400（Zod strict 兜住）

### 到店
`POST/GET /restaurants/{id}/visits`、`GET /visits`（全局时间线）、`GET/PATCH/DELETE /visits/{id}`、`POST /visits/{id}/restore`

### Revision
- `POST /revisions`（重新应用历史字段，**不是**补历史）
- `GET /revisions`、`GET /restaurants/{id}/revisions`、`GET /dishes/{id}/revisions`、`GET /visits/{id}/revisions`
- `GET /revisions/{id}`、`PATCH /revisions/{id}`（只改 note）、`DELETE /revisions/{id}`（软隐藏）、`POST /revisions/{id}/restore`
- PATCH `diff_json` / 原始时间 → **400 UNKNOWN_FIELD**
- `POST /revisions` 限制：来源必须 `action=update`；`fields` 1～7 个、不重复、必须存在于来源 diff 且是允许编辑的业务字段；**禁止**应用 `id` / owner / `version` / 系统时刻 / `deleted_at`；服务端重新算"当前→拟应用值"的 diff，**不复制来源 diff**

### 搜索
- `GET /search`：`q` 必填，`groups=restaurants,visits,revisions`，每组 limit 默认 10 最大 20；菜品命中归入所属餐厅并返回 `matched_fields`
- `restaurant_search` 表**在菜品/餐厅变更的同一 batch 内重建**
- 搜索语义：NFKC + lowercase + `instr` 字面匹配，`%` 和 `_` 当普通字符

### 分页（PLAN 4.1）
- 默认 20 最大 50，查询 `limit+1` 判 `has_more`，不默认算 total
- keyset cursor 含排序方式+键值+ID+筛选指纹，解码后严格验证
- **cursor 不能拼进 SQL**，也不能当授权凭据（查询仍绑 `user_id`）

---

## 三、测试：PLAN 10.3 的 15 条必过场景，逐条要有用例

1. 创建餐厅（纯文本地址）→ 5 菜品 → 3 次到店（两次同日）；菜品只归餐厅
2. 初次创建有记录；评分 4.0→4.3 生成一个 revision，diff 里 `rating_tenths` before=40 / after=43；API/UI 显示 4.0/4.3
3. 同时改地址和评价 → **一个**实体 revision 含两个字段
4. 保存相同值、仅空白规范化差异 → 不产生有效变更，version 不增长
5. 同 key 重试不重复建 restaurant/visit/revision；换 payload 复用 key → 409
6. 两设备相同 base version 写同一实体 → 只有一个成功；失败方**没有孤立** change_set / revision
7. 预读取与写入之间删除父餐厅 → 子写入失败，**没有"已删除餐厅中新建菜品"**
8. A 访问 B 的列表/ID/子对象/revision/restore/session 全部失败且不泄露内容
9. 删除餐厅后普通搜索与子对象 CRUD 不可访问；恢复父餐厅**不复活**先前单独删除的菜品
10. 30 天边界：过期 restore → 410；分块清理不影响其他餐厅/账号
11. revision 可读、加说明、隐藏、恢复；diff 和原始时间的 PATCH 被拒
12. POST /revisions 重新应用历史字段生成新 revision，保留当前其他字段；来源/目标非本人失败
13. 搜索命中菜品名/菜品短评/餐厅评价/餐厅笔记，返回 restaurant/dish/review/note 命中类型；中英文大小写、全角半角、多关键词、`%` `_` 都按 NFKC+lowercase+`instr` 字面语义
14. （见 PLAN 10.3，照读）
15. 长评价、换行、null、未评分、合法闰日可往返；非法日期、评分 0、>5、超过一位小数、字符串评分、非整数 tenths、超长内容都有明确错误

另外补：
- **并发**：两个相同 base version 的 PATCH 真并发，断言一个 200 一个 409，且失败方无残留（可参考 P2 `auth.test.ts` 里恢复码竞争用例的写法）
- **批次原子性**：构造让 batch 第 N 条失败的场景，断言前面的写入全部回滚
- **cursor 伪造**：篡改 cursor 应 400，不能 500 或返回别人数据

---

## 四、OpenAPI
`contracts/openapi.yaml` **本期定稿 v1**：所有端点、所有 schema、所有错误码、分页/幂等头都要在契约里。不要留占位。

---

## 五、硬约束

- **本机只有 956MB**：禁止在本机跑 `npm install` / `gradle` / `docker` / `vitest`。你只写代码 + `git commit` + `git push`，**所有构建与测试在 GitHub Actions**。用 GitHub API 读 run 和日志（token 在 `~/.git-credentials`）。
- 本机可用 `sqlite3` 验证 SQL 正确性（轻量，安全）。
- **不许降低断言阈值、不许删测试、不许 skip 让 CI 变绿**。失败读日志修根因。
- **不许改 `backend/migrations/0001_initial.sql`**（SHA 必须保持 `191d1a80a7411ba828effc9041fbd144fa4d10abb372f61c793a8f6dc1a5b80b`）。若确实缺字段，写进 `P3_BLOCKERS.md` 并停下等我决定，不要擅自动表。
- 不部署 Cloudflare、不建真实 D1、不写真实 secret。
- 走**新分支 → PR → 合并**，不直推 master。

---

## 六、完成判据

1. CI 全绿（backend 全部业务测试、android 不回归），PR 已合并
2. `P3_DONE.md`：
   - 端点清单（实际实现的）
   - **10.3 十五条逐条 → 对应测试文件 + 用例名**
   - `verified` vs `unverified`（真实 D1 / 真实 Worker CPU / 真机一律算 unverified）
   - 核心机制的实现位置（CAS 守卫、幂等、diff、搜索重建分别在哪个文件哪一行附近）
   - 遇到的权衡与遗留
3. 后端业务测试用例数与名称列表（方便我复核）
4. 做不到的写进 `P3_BLOCKERS.md`

**不要夸大。** 没做的写没做，模拟器/本地 pool 测试不能冒充真实 Cloudflare。
