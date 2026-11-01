# P1 收尾任务：补完两项遗留验收

P1 主体验收已通过（CI 全绿、migrations 实测通过、Worker/Android 骨架完成）。现在补完 P1 验收标准里还缺的两项。
**不要重做已通过的部分，只补这两项。**

工作目录 `/root/dining_log`，方案 `/root/dining_log/design/PLAN.md`（只读）。

## 任务 1：4,500 对象容量测试，必须可重现

PLAN.md 第 8.6 节要求：每个账号 500 家餐厅 × 5 个菜品 × 3 次到店 = 2,500 + 2,500 = 4,500 个业务对象，外加 13,500 条历史。

要做的事：

### 1.1 写一个可重现的容量测试脚本

- 路径：`backend/tests/capacity/capacity_test.ts`（Vitest + Cloudflare Workers pool，沿用现有测试风格）
- 用**确定性伪随机**（固定 seed，如 mulberry32）生成数据，不要每次跑出不同结果
- 生成：1 个 user → 500 restaurants → 每店 5 dishes → 每店 3 visits；每家餐厅至少 10 条 revision（共 13,500 条以上）
- 文本长度分三档混合：英文短、中文短（约 3 bytes/字）、接近字段上限的长文
- `rating_tenths` 用 10–50 的合法值

### 1.2 必须实测并记录的数字

用 `env.DB` 执行下列查询，把结果写进 `backend/tests/capacity/RESULTS.md`：

1. **存储**：`SELECT SUM(pgsize) FROM dbstat` 或退而求其次统计各表 `length(...)` 总和；记录每张表的行数
2. **查询性能**：每种查询跑 30 次取 p50 / p95
   - 列表分页：`SELECT ... FROM restaurants WHERE user_id=? AND deleted_at IS NULL ORDER BY updated_at DESC, id DESC LIMIT 20`
   - 按评分排序：`... ORDER BY rating_tenths DESC, id DESC LIMIT 20`
   - 聚合搜索：`... WHERE user_id=? AND instr((SELECT content FROM restaurant_search WHERE restaurant_id=restaurants.id), ?) > 0 LIMIT 20`
   - 详情 + 子对象：单店 dishes / visits 查询
   - revision 列表：单实体 10 条
3. **写入**：一次完整「创建餐厅 + 5 菜品 + 3 到店 + 10 revision」的 batch，记录 rows_written 与耗时
4. **D1 免费档边界对照**：把实测值与 PLAN.md 8.5 的免费额度（5M rows read/day、100K rows written/day、5GB storage、单库 500MB）逐项对照，给出「这个账号量级下是否够用」的结论

### 1.3 性能目标

PLAN.md 10.3 第 14 条要求：6,000 业务对象 / 18,000 历史下常用 SQL p95 ≤ 100ms。
如果实测超标，**不要改目标值**，而是：
- 记录超标的具体查询与实际数字
- 分析原因（缺索引 / 全表扫描 / 查询写法）
- 给出修复建议，但如果修复需要改 migration，**先不做**，写进 RESULTS.md 的「待决」章节

### 1.4 在 CI 里跑

在 `.github/workflows/ci.yml` 的 backend job 里加一个 step 跑容量测试（或单独 job），
注意它是重测试，可以标记为 `continue-on-error: false` 但要设合理超时（如 15 分钟）。

## 任务 2：Android debug APK 可下载

CI 已经成功 upload-artifact。要做的事：

### 2.1 从 GitHub Actions 下载最新 debug APK

- 用 GitHub API 找到最近一次成功 run 的 `app-debug` artifact
- 下载并解压到 `/var/www/html/dl/dining/`
- 命名规范：`shiji-<短sha>.apk`，并建软链或复制一份 `shiji-debug.apk` 指向最新
- 计算 `sha256sum` 写进 `/var/www/html/dl/dining/SHA256SUMS`

### 2.2 验证 APK 是真的

- `unzip -l` 确认含 `classes.dex`、`AndroidManifest.xml`、`res/`
- 用 `aapt2 dump badging` 或 `unzip -p ... AndroidManifest.xml | head` 之类确认包名是 `top.alanvampuke.dininglog`
- 记录 APK 大小与 md5

### 2.3 写 `P1_ACCEPTANCE.md`

放在仓库根目录，逐条对照 PLAN.md 10.1 的 P0 六条门槛和 P1 验收标准，标注每一条：
- **已验证** + 证据（命令 + 实际输出/数字）
- **未验证** + 原因（例如「真实 Worker CPU 需部署后才能测，属 P2 前置」）

**不要夸大**。没做的就写没做。

## 约束（重要）

- 本机只有 956MB 内存：**禁止在本机跑 npm install / gradle / docker / vitest**。
  容量测试的**执行**必须在 GitHub Actions 里完成；你只负责写脚本、推代码、看 CI 结果、把真实数字填进 RESULTS.md。
- 如果 CI 失败，读日志、修、再推，直到绿。不要为了让 CI 通过而降低断言阈值或删测试。
- 不要部署 Cloudflare，不要写 secret。

## 完成判据

1. CI 绿（含容量测试 step）
2. `backend/tests/capacity/RESULTS.md` 里有**真实跑出来的数字**（不是估算）
3. `/var/www/html/dl/dining/shiji-debug.apk` 存在且 sha256 记录在案
4. `P1_ACCEPTANCE.md` 逐条对照，区分已验证/未验证
