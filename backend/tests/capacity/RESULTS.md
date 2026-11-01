# 容量测试实测结果

来源：[GitHub Actions run 41285189852](https://github.com/vampuke/dining-log/actions/runs/41285189852)，attempt 1，commit `8c4db3f146115edbc9a17cb1b263e695bb358f31`。
采集时间：2026-11-01T07:33:49.411Z。测试状态：两组容量 / 性能断言通过。整个 workflow 结论另以 GitHub API 和 P1_ACCEPTANCE.md 为准。

环境：GitHub Linux runner（image 20261026.354.1），Node v22.23.4，Vitest 3.2.4，Workers pool 0.8.49，Miniflare 4.20250617.5，workerd 1.20250617.0；compatibility_date=2025-06-17。
**这是 GitHub Actions 内的本地 Workers / D1 模拟器实测，不是真实 Cloudflare D1 延迟、计费、Worker CPU 或端到端网络实测。** 本机 VPS 未运行构建或测试。

## 重现和计量方法

以下命令仅在 GitHub Actions 或获准的充足内存环境执行；956 MB VPS 禁止执行。

```sh
cd backend
npm ci
npm run check
mkdir -p tests/capacity/output
npm run test:capacity 2>&1 | tee tests/capacity/output/capacity.log
node tests/capacity/report.mjs tests/capacity/output/capacity.log
```

- 固定 mulberry32 seed `0x51a71c`，固定业务时刻 `2026-10-31T12:00:00.000Z`；UUID、评分、文本、日期及版本历史都确定。npm 脚本顺序启动两个独立 Vitest / Workers-pool 进程，各选择一个场景，从 14 张应用表全空的独立 D1 初始化；使用现有 migrations。大数据库禁用池内逐测试快照，避免 stacked-storage 回滚时断连，普通测试配置不受影响。
- 文本混合英文短文、中文短文与接近字段上限（98–99.9%）的中文长文；中文 UTF-8 约 3 bytes/字，存储统计使用 BLOB 长度而非字符数。评分全为 10–50 整数。
- 每种查询连续串行执行 30 次，不丢弃首次样本、不重试挑选结果。nearest-rank p50 是第 15 个、p95 是第 29 个排序后样本。wall 使用 `Date.now()`（1 ms 分辨率）测 await D1 的调用耗时；当前 Workers 的 `performance.now()` 返回零，不能用作墙钟。SQL 为 D1 `meta.duration`（组合 batch 相加）；两者 p95 均断言 ≤100 ms，并断言全量生成和完整写入的 wall >0。
- 原始 30 次样本、SQL / 参数、EXPLAIN QUERY PLAN 和所有统计归档于同目录 `measurements.json`，CI artifact `capacity-results` 另含原始日志及自动生成报告；仓库 RESULTS.md 来自该报告。
- 表行数 / 存储与查询在额外写入 benchmark 前采集；写入后的对象增量和完整性另行断言，不混入基线容量。
- 测试脚本 SHA-256：`07234d4b270f4b0d92915d7bc926794bc1b1ced6ab33527812570d3955238982`；migration SHA-256：`191d1a80a7411ba828effc9041fbd144fa4d10abb372f61c793a8f6dc1a5b80b`。

任务的 P1 样本为 500 × (1 餐厅 + 5 菜品 + 3 到店) = 4,500 对象。只读 PLAN.md 8.6 / 10.3.14 使用 8 菜品，即 6,000 对象；本次两组都实测，未修改方案或性能目标。

## p1-4500：4,500 对象 / 13,500 历史

SQLite 版本：未读取（Error: D1_ERROR: not authorized to use function: sqlite_version at offset 7: SQLITE_ERROR）；1 user、500 restaurants、2500 dishes、1500 visits。每家餐厅 11–11 条 revision；菜品 / 到店各 2 条。每条基线 revision 有独立 change_set，保守计入分组存储。
生成数据 SHA-256：`664bf1e7a42ce7a6c32850a4ba38ef94aafd533eeae2c1b0bef186e972f1bbfc`。初始化 32001 条 SQL、1001 个 batch（每 batch ≤40 条 SQL），实测 wall 17603.000 ms、rows_read=29000、rows_written=194003。

| 文本类型 | restaurants | dishes | visits |
| --- | ---: | ---: | ---: |
| english_short | 167 | 833 | 500 |
| chinese_short | 167 | 834 | 500 |
| near_limit | 166 | 833 | 500 |

### 存储与行数

`SELECT SUM(pgsize) FROM dbstat`：不可用（Error: D1_ERROR: no such table: dbstat: SQLITE_ERROR）。采用逐表 TEXT/BLOB 的 `SUM(length(CAST(column AS BLOB)))`。。
逐表 UTF-8 文本有效载荷合计：177,696,967 bytes / 169.465 MiB（不含数值、行头、索引与页空隙，不能直接当完整数据库存储）。
D1 `meta.size_after` 完整已分配数据库体积：205,619,200 bytes / 196.094 MiB（含索引 / 元数据 / 空闲页，非远端账单）。

受 D1 保护、不直接读取的内部表：_cf_METADATA；其页仍计入完整体积。全部 14 张应用表逐表统计如下。

| 表 | 行数 | TEXT/BLOB UTF-8 bytes |
| --- | ---: | ---: | ---: |
| auth_rate_limits | 0 | 0 |
| change_sets | 13500 | 1458000 |
| d1_migrations | 1 | 16 |
| dishes | 2500 | 5576257 |
| idempotency_keys | 0 | 0 |
| mutation_guards | 0 | 0 |
| recovery_codes | 0 | 0 |
| refresh_rotation_results | 0 | 0 |
| refresh_tokens | 0 | 0 |
| restaurant_search | 500 | 19625922 |
| restaurants | 500 | 4387108 |
| revisions | 13500 | 140458518 |
| sessions | 0 | 0 |
| users | 1 | 182 |
| visits | 1500 | 6190964 |

### 查询：每种 30 次

| 查询 | wall p50 ms | wall p95 ms | SQL p50 ms | SQL p95 ms | rows_read min–max | 返回行数 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 列表分页 | 1.000 | 2.000 | 0.000 | 1.000 | 20–20 | 20 |
| 评分排序 | 1.000 | 2.000 | 0.000 | 1.000 | 20–20 | 20 |
| 聚合搜索：20 个命中 | 19.000 | 27.000 | 18.000 | 19.000 | 998–998 | 20 |
| 聚合搜索：无命中，完整账号扫描 | 18.000 | 24.000 | 17.000 | 18.000 | 1000–1000 | 0 |
| 单店详情（长文） | 1.000 | 2.000 | 0.000 | 1.000 | 1–1 | 1 |
| 单店菜品 | 1.000 | 1.000 | 0.000 | 1.000 | 6–6 | 5 |
| 单店到店 | 1.000 | 1.000 | 0.000 | 1.000 | 4–4 | 3 |
| 详情 + 菜品 + 到店 batch | 2.000 | 2.000 | 0.000 | 1.000 | 11–11 | 1 + 5 + 3 |
| 单实体 10 条历史 | 8.000 | 8.000 | 2.000 | 3.000 | 10–10 | 10 |

所有读取的 rows_written=0；断言验证返回的对象集合，分页 / 评分采用稳定 id 次序，历史另外验证连续降序版本。

<details>
<summary>实际 SQL 与查询计划</summary>

**列表分页**

```sql
SELECT id,name,category,cuisine,address,rating_tenths,updated_at FROM restaurants WHERE user_id=? AND deleted_at IS NULL ORDER BY updated_at DESC,id DESC LIMIT 20;
```

```text
SEARCH restaurants USING INDEX idx_restaurants_live_updated (user_id=?)
```

**评分排序**

```sql
SELECT id,name,category,cuisine,address,rating_tenths,updated_at FROM restaurants WHERE user_id=? AND deleted_at IS NULL ORDER BY rating_tenths DESC,id DESC LIMIT 20;
```

```text
SEARCH restaurants USING INDEX idx_restaurants_live_rating (user_id=?)
```

**聚合搜索：20 个命中**

```sql
SELECT id,name,category,cuisine,address,rating_tenths,updated_at FROM restaurants WHERE user_id=? AND deleted_at IS NULL AND instr((SELECT content FROM restaurant_search WHERE restaurant_id=restaurants.id),?)>0 LIMIT 20;
```

```text
SEARCH restaurants USING INDEX idx_restaurants_live_rating (user_id=?)
CORRELATED SCALAR SUBQUERY 1
SEARCH restaurant_search USING INDEX sqlite_autoindex_restaurant_search_1 (restaurant_id=?)
```

**聚合搜索：无命中，完整账号扫描**

```sql
SELECT id,name,category,cuisine,address,rating_tenths,updated_at FROM restaurants WHERE user_id=? AND deleted_at IS NULL AND instr((SELECT content FROM restaurant_search WHERE restaurant_id=restaurants.id),?)>0 LIMIT 20;
```

```text
SEARCH restaurants USING INDEX idx_restaurants_live_rating (user_id=?)
CORRELATED SCALAR SUBQUERY 1
SEARCH restaurant_search USING INDEX sqlite_autoindex_restaurant_search_1 (restaurant_id=?)
```

**单店详情（长文）**

```sql
SELECT * FROM restaurants WHERE user_id=? AND id=? AND deleted_at IS NULL;
```

```text
SEARCH restaurants USING INDEX sqlite_autoindex_restaurants_2 (user_id=? AND id=?)
```

**单店菜品**

```sql
SELECT * FROM dishes WHERE user_id=? AND restaurant_id=? AND deleted_at IS NULL ORDER BY updated_at DESC,id DESC;
```

```text
SEARCH dishes USING INDEX idx_dishes_restaurant_live (user_id=? AND restaurant_id=?)
```

**单店到店**

```sql
SELECT * FROM visits WHERE user_id=? AND restaurant_id=? AND deleted_at IS NULL ORDER BY dining_date DESC,id DESC;
```

```text
SEARCH visits USING INDEX idx_visits_restaurant_date (user_id=? AND restaurant_id=?)
```

**详情 + 菜品 + 到店 batch**

```sql
SELECT * FROM restaurants WHERE user_id=? AND id=? AND deleted_at IS NULL;
SELECT * FROM dishes WHERE user_id=? AND restaurant_id=? AND deleted_at IS NULL ORDER BY updated_at DESC,id DESC;
SELECT * FROM visits WHERE user_id=? AND restaurant_id=? AND deleted_at IS NULL ORDER BY dining_date DESC,id DESC;
```

```text
SEARCH restaurants USING INDEX sqlite_autoindex_restaurants_2 (user_id=? AND id=?)
SEARCH dishes USING INDEX idx_dishes_restaurant_live (user_id=? AND restaurant_id=?)
SEARCH visits USING INDEX idx_visits_restaurant_date (user_id=? AND restaurant_id=?)
```

**单实体 10 条历史**

```sql
SELECT * FROM revisions WHERE user_id=? AND entity_type=? AND entity_id=? AND deleted_at IS NULL ORDER BY created_at DESC,id DESC LIMIT 10;
```

```text
SEARCH revisions USING INDEX idx_revisions_entity_time (user_id=? AND entity_type=? AND entity_id=?)
```

</details>

### P3 实际 API 读取（含 accessIdentity 和响应序列化）

| 路径 | wall p50 ms | wall p95 ms |
| --- | ---: | ---: |
| /restaurants?limit=20 | 4.000 | 8.000 |
| /restaurants?sort=rating_desc&limit=20 | 3.000 | 4.000 |
| /search?q=%E6%8B%9B%E7%89%8C%E6%B5%8B%E8%AF%95 | 15.000 | 34.000 |
| /search?q=capacity_missing_51a71c | 22.000 | 24.000 |
| /restaurants/36b96093-b118-4f24-9243-c225d2902229 | 5.000 | 20.000 |
| /restaurants/36b96093-b118-4f24-9243-c225d2902229/dishes | 4.000 | 9.000 |
| /restaurants/36b96093-b118-4f24-9243-c225d2902229/visits | 4.000 | 5.000 |
| /restaurants/36b96093-b118-4f24-9243-c225d2902229/revisions?limit=10 | 5.000 | 25.000 |

每条路径保留 30 次原始样本，不跳过冷样本；p95 仍断言 ≤100 ms。仅为 CI 本地 Workers/D1 pool，未经过公网，不代表免费 Worker CPU。

### 一次完整写入 batch

创建 1 餐厅 + 5 菜品 + 3 到店，9 条 create revision 加 1 次真实餐厅更新 / update revision，共 10 revision；同 batch 还写 1 change_set 与 1 restaurant_search。
实测：22 条 SQL，22 次逻辑行变更，rows_read=25，**rows_written=143**，**wall=9.000 ms**，SQL duration 合计=1.000 ms。
这是直接 SQL 的容量 batch，未计入鉴权、幂等与 CAS 守卫；P3 实际 API 读取在下表单独测量，生产写入/CPU/网络性能仍未验证。
写入后增量：restaurants=1，dishes=5，visits=3，revisions=10，change_sets=1，restaurant_search=1；其他业务表增量=0。foreign_key_check 无异常，quick_check=ok。

### PLAN.md 8.5 免费档边界对照

| 限额 | 本次实测 / 由实测折算 | 判断 |
| --- | --- | --- |
| 5,000,000 rows read/day | 最贵常用查询 1000 rows_read/次；仅重复该操作最多 5000 次/日 | 单账号日常查询有余量；仍需计入其他账号和其他业务 |
| 100,000 rows written/day | 完整写入 batch 143 rows_written/次；仅重复该 batch 最多 699 次/日；本次初始化 194003 rows_written | 初始化至少 2 个额度日；分批不改变每日总额 |
| 5 GB 总存储（5,000,000,000 bytes/account） | 205,619,200 bytes / 196.094 MiB，占 4.112% | 本次单账号样本以内；账户内其他库共享额度 |
| 单库 500 MB（500,000,000 bytes） | 205,619,200 bytes / 196.094 MiB，占 41.124% | 本次完整样本可容纳；历史持续增长需监控 |

结论：在本次混合长短文的单账号数据量下，模拟器完整存储低于单库上限，常用读写的额度折算有余量；全量初始化最低需 2 个额度日。远端 CPU、账单、共享用量和长期历史增长未验证，不能据此保证整个生产账号永久免费。

## plan-6000：6,000 对象 / 18,000 历史

SQLite 版本：未读取（Error: D1_ERROR: not authorized to use function: sqlite_version at offset 7: SQLITE_ERROR）；1 user、500 restaurants、4000 dishes、1500 visits。每家餐厅 14–14 条 revision；菜品 / 到店各 2 条。每条基线 revision 有独立 change_set，保守计入分组存储。
生成数据 SHA-256：`5021392ad1d8cec6c9b8a7400cf50b2fe6b0c2e05b2148dfc66e596d52685244`。初始化 42501 条 SQL、1501 个 batch（每 batch ≤40 条 SQL），实测 wall 20254.000 ms、rows_read=38000、rows_written=258503。

| 文本类型 | restaurants | dishes | visits |
| --- | ---: | ---: | ---: |
| english_short | 167 | 1333 | 500 |
| chinese_short | 167 | 1334 | 500 |
| near_limit | 166 | 1333 | 500 |

### 存储与行数

`SELECT SUM(pgsize) FROM dbstat`：不可用（Error: D1_ERROR: no such table: dbstat: SQLITE_ERROR）。采用逐表 TEXT/BLOB 的 `SUM(length(CAST(column AS BLOB)))`。。
逐表 UTF-8 文本有效载荷合计：231,507,007 bytes / 220.782 MiB（不含数值、行头、索引与页空隙，不能直接当完整数据库存储）。
D1 `meta.size_after` 完整已分配数据库体积：269,004,800 bytes / 256.543 MiB（含索引 / 元数据 / 空闲页，非远端账单）。

受 D1 保护、不直接读取的内部表：_cf_METADATA；其页仍计入完整体积。全部 14 张应用表逐表统计如下。

| 表 | 行数 | TEXT/BLOB UTF-8 bytes |
| --- | ---: | ---: | ---: |
| auth_rate_limits | 0 | 0 |
| change_sets | 18000 | 1944000 |
| d1_migrations | 1 | 16 |
| dishes | 4000 | 8926780 |
| idempotency_keys | 0 | 0 |
| mutation_guards | 0 | 0 |
| recovery_codes | 0 | 0 |
| refresh_rotation_results | 0 | 0 |
| refresh_tokens | 0 | 0 |
| restaurant_search | 500 | 26128735 |
| restaurants | 500 | 4386992 |
| revisions | 18000 | 183927400 |
| sessions | 0 | 0 |
| users | 1 | 182 |
| visits | 1500 | 6192902 |

### 查询：每种 30 次

| 查询 | wall p50 ms | wall p95 ms | SQL p50 ms | SQL p95 ms | rows_read min–max | 返回行数 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 列表分页 | 1.000 | 5.000 | 0.000 | 1.000 | 20–20 | 20 |
| 评分排序 | 1.000 | 2.000 | 0.000 | 1.000 | 20–20 | 20 |
| 聚合搜索：20 个命中 | 24.000 | 38.000 | 23.000 | 24.000 | 974–974 | 20 |
| 聚合搜索：无命中，完整账号扫描 | 24.000 | 25.000 | 23.000 | 23.000 | 1000–1000 | 0 |
| 单店详情（长文） | 1.000 | 2.000 | 0.000 | 1.000 | 1–1 | 1 |
| 单店菜品 | 1.000 | 2.000 | 0.000 | 1.000 | 9–9 | 8 |
| 单店到店 | 1.000 | 2.000 | 0.000 | 1.000 | 4–4 | 3 |
| 详情 + 菜品 + 到店 batch | 2.000 | 2.000 | 0.000 | 1.000 | 14–14 | 1 + 8 + 3 |
| 单实体 10 条历史 | 8.000 | 11.000 | 3.000 | 3.000 | 10–10 | 10 |

所有读取的 rows_written=0；断言验证返回的对象集合，分页 / 评分采用稳定 id 次序，历史另外验证连续降序版本。

<details>
<summary>实际 SQL 与查询计划</summary>

**列表分页**

```sql
SELECT id,name,category,cuisine,address,rating_tenths,updated_at FROM restaurants WHERE user_id=? AND deleted_at IS NULL ORDER BY updated_at DESC,id DESC LIMIT 20;
```

```text
SEARCH restaurants USING INDEX idx_restaurants_live_updated (user_id=?)
```

**评分排序**

```sql
SELECT id,name,category,cuisine,address,rating_tenths,updated_at FROM restaurants WHERE user_id=? AND deleted_at IS NULL ORDER BY rating_tenths DESC,id DESC LIMIT 20;
```

```text
SEARCH restaurants USING INDEX idx_restaurants_live_rating (user_id=?)
```

**聚合搜索：20 个命中**

```sql
SELECT id,name,category,cuisine,address,rating_tenths,updated_at FROM restaurants WHERE user_id=? AND deleted_at IS NULL AND instr((SELECT content FROM restaurant_search WHERE restaurant_id=restaurants.id),?)>0 LIMIT 20;
```

```text
SEARCH restaurants USING INDEX idx_restaurants_live_rating (user_id=?)
CORRELATED SCALAR SUBQUERY 1
SEARCH restaurant_search USING INDEX sqlite_autoindex_restaurant_search_1 (restaurant_id=?)
```

**聚合搜索：无命中，完整账号扫描**

```sql
SELECT id,name,category,cuisine,address,rating_tenths,updated_at FROM restaurants WHERE user_id=? AND deleted_at IS NULL AND instr((SELECT content FROM restaurant_search WHERE restaurant_id=restaurants.id),?)>0 LIMIT 20;
```

```text
SEARCH restaurants USING INDEX idx_restaurants_live_rating (user_id=?)
CORRELATED SCALAR SUBQUERY 1
SEARCH restaurant_search USING INDEX sqlite_autoindex_restaurant_search_1 (restaurant_id=?)
```

**单店详情（长文）**

```sql
SELECT * FROM restaurants WHERE user_id=? AND id=? AND deleted_at IS NULL;
```

```text
SEARCH restaurants USING INDEX sqlite_autoindex_restaurants_2 (user_id=? AND id=?)
```

**单店菜品**

```sql
SELECT * FROM dishes WHERE user_id=? AND restaurant_id=? AND deleted_at IS NULL ORDER BY updated_at DESC,id DESC;
```

```text
SEARCH dishes USING INDEX idx_dishes_restaurant_live (user_id=? AND restaurant_id=?)
```

**单店到店**

```sql
SELECT * FROM visits WHERE user_id=? AND restaurant_id=? AND deleted_at IS NULL ORDER BY dining_date DESC,id DESC;
```

```text
SEARCH visits USING INDEX idx_visits_restaurant_date (user_id=? AND restaurant_id=?)
```

**详情 + 菜品 + 到店 batch**

```sql
SELECT * FROM restaurants WHERE user_id=? AND id=? AND deleted_at IS NULL;
SELECT * FROM dishes WHERE user_id=? AND restaurant_id=? AND deleted_at IS NULL ORDER BY updated_at DESC,id DESC;
SELECT * FROM visits WHERE user_id=? AND restaurant_id=? AND deleted_at IS NULL ORDER BY dining_date DESC,id DESC;
```

```text
SEARCH restaurants USING INDEX sqlite_autoindex_restaurants_2 (user_id=? AND id=?)
SEARCH dishes USING INDEX idx_dishes_restaurant_live (user_id=? AND restaurant_id=?)
SEARCH visits USING INDEX idx_visits_restaurant_date (user_id=? AND restaurant_id=?)
```

**单实体 10 条历史**

```sql
SELECT * FROM revisions WHERE user_id=? AND entity_type=? AND entity_id=? AND deleted_at IS NULL ORDER BY created_at DESC,id DESC LIMIT 10;
```

```text
SEARCH revisions USING INDEX idx_revisions_entity_time (user_id=? AND entity_type=? AND entity_id=?)
```

</details>

### P3 实际 API 读取（含 accessIdentity 和响应序列化）

| 路径 | wall p50 ms | wall p95 ms |
| --- | ---: | ---: |
| /restaurants?limit=20 | 4.000 | 21.000 |
| /restaurants?sort=rating_desc&limit=20 | 4.000 | 4.000 |
| /search?q=%E6%8B%9B%E7%89%8C%E6%B5%8B%E8%AF%95 | 18.000 | 38.000 |
| /search?q=capacity_missing_51a71c | 27.000 | 47.000 |
| /restaurants/7900e620-0083-4b1b-90b1-18f0665e228b | 4.000 | 10.000 |
| /restaurants/7900e620-0083-4b1b-90b1-18f0665e228b/dishes | 4.000 | 6.000 |
| /restaurants/7900e620-0083-4b1b-90b1-18f0665e228b/visits | 4.000 | 10.000 |
| /restaurants/7900e620-0083-4b1b-90b1-18f0665e228b/revisions?limit=10 | 5.000 | 5.000 |

每条路径保留 30 次原始样本，不跳过冷样本；p95 仍断言 ≤100 ms。仅为 CI 本地 Workers/D1 pool，未经过公网，不代表免费 Worker CPU。

### 一次完整写入 batch

创建 1 餐厅 + 5 菜品 + 3 到店，9 条 create revision 加 1 次真实餐厅更新 / update revision，共 10 revision；同 batch 还写 1 change_set 与 1 restaurant_search。
实测：22 条 SQL，22 次逻辑行变更，rows_read=25，**rows_written=143**，**wall=10.000 ms**，SQL duration 合计=3.000 ms。
这是直接 SQL 的容量 batch，未计入鉴权、幂等与 CAS 守卫；P3 实际 API 读取在下表单独测量，生产写入/CPU/网络性能仍未验证。
写入后增量：restaurants=1，dishes=5，visits=3，revisions=10，change_sets=1，restaurant_search=1；其他业务表增量=0。foreign_key_check 无异常，quick_check=ok。

### PLAN.md 8.5 免费档边界对照

| 限额 | 本次实测 / 由实测折算 | 判断 |
| --- | --- | --- |
| 5,000,000 rows read/day | 最贵常用查询 1000 rows_read/次；仅重复该操作最多 5000 次/日 | 单账号日常查询有余量；仍需计入其他账号和其他业务 |
| 100,000 rows written/day | 完整写入 batch 143 rows_written/次；仅重复该 batch 最多 699 次/日；本次初始化 258503 rows_written | 初始化至少 3 个额度日；分批不改变每日总额 |
| 5 GB 总存储（5,000,000,000 bytes/account） | 269,004,800 bytes / 256.543 MiB，占 5.380% | 本次单账号样本以内；账户内其他库共享额度 |
| 单库 500 MB（500,000,000 bytes） | 269,004,800 bytes / 256.543 MiB，占 53.801% | 本次完整样本可容纳；历史持续增长需监控 |

结论：在本次混合长短文的单账号数据量下，模拟器完整存储低于单库上限，常用读写的额度折算有余量；全量初始化最低需 3 个额度日。远端 CPU、账单、共享用量和长期历史增长未验证，不能据此保证整个生产账号永久免费。

## 待决

- 两组常用 SQL 的实测 p95 均 ≤100 ms，无性能超标；没有修改 migration、删测试或降低目标。
- 聚合搜索的无命中场景必须扫描整个账号聚合文本；当前账号仅 500 家店。扩大账号餐厅数或持续增加长文时，需重新实测，不能由本报告外推无限容量。
- 真实 Cloudflare D1、Workers 免费档 10 ms CPU、部署后网络与端到端 CRUD 均未验证，属于后续阶段；本轮没有创建或部署云资源。
- 本报告仅对指定初始样本测量，未实测每个实体均 10 条历史的增长变体；该项仍见 PLAN.md CAPACITY-01 的后续验证，不包含在本次两项收尾完成声明中。
