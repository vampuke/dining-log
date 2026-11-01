# P6 任务书：账号端点补全 + UI/交互修复 + 应用内更新

## 0. 背景与总则

仓库 `/root/dining_log`，master 分支。前五期（P1–P5）已交付并上线：
- 生产 API：`https://dining.alanvampuke.top/api/v1`
- 生产 D1：`dining-log-prod`
- 正式签名 APK 已随 v0.5.0 发布

本期 P6 三个工作流，**必须全部完成**，不可只做其一。

**总则（违反即视为失败）：**
1. 不修改 `backend/migrations/0001_initial.sql`。其 SHA-256 必须保持
   `191d1a80a7411ba828effc9041fbd144fa4d10abb372f61c793a8f6dc1a5b80b`。
   新增结构变更只能通过 **新迁移 `0002_*.sql`**。
2. 不把任何密钥、token、密码、keystore 内容写入仓库、日志或文档。
3. 不得为通过测试而放宽断言。若结论为「未验证」，如实标注并说明原因。
4. 所有 UI 文案为中文（沿用现有风格），代码/注释/commit 信息用英文。
5. 不引入图片资源、头像、照片。视觉仍是 D2 Quiet Grid（精确网格 + 等宽数字）。
6. 任何会产生破坏性数据的操作（删表、重建表）必须先写可复现的验证脚本。

---

## 工作流 A：补全 P2 遗留的 5 个 501 端点

`backend/src/routes/auth.ts` 目前是：

```ts
auth.post('/auth/logout-all', notImplemented);
auth.post('/auth/recovery-codes', notImplemented);
auth.get('/me', notImplemented);
auth.patch('/me', notImplemented);
auth.delete('/me', notImplemented);
```

严格按 `design/PLAN.md` 第 805–813 行与 `contracts/openapi.yaml` 实现：

| 方法 | 路径 | 请求 | 响应 | 鉴权 |
|---|---|---|---|---|
| POST | /auth/logout-all | `{password}` | 204；auth_version +1，撤销全部会话 | Reauth |
| POST | /auth/recovery-codes | `{password}` | 200 `{recovery_codes: string[8]}`；作废全部旧码 | Reauth |
| GET | /me | — | 200 User | Access |
| PATCH | /me | `{expected_version, display_name}` + Idempotency-Key | 200 User；用户名不可改 | Access |
| DELETE | /me | `{expected_version, password, confirm_username}` | 202 `{deleted_at, purge_after}`；立即失效全部会话 | Reauth |

### 硬要求

A1. **User 对象绝不能含** `password_hash` / `password_salt` / `pepper_version` / `password_iterations` / `auth_version` / `deleted_at` 以外的内部字段。测试须断言响应 JSON 的 key 集合精确等于 `{id, username, display_name, version, created_at, updated_at}`。

A2. **Reauth 必须真的重算慢哈希**，且对错误密码走与正常路径相同的耗时（不得提前 return）。沿用 `routes.ts:54` 已有的 dummy-user 手法防止时序侧信道。

A3. **logout-all** 后，旧 access token 立即失效（`auth_version` 递增，JWT 的 `av` 不匹配 → 401）。测试要断言：操作前的旧 token 在新请求中返回 401，且**不是**因为过期。

A4. **recovery-codes 重新生成**：全部旧码作废（`used_at` 或等效标记置位），新码 8 个、互不相同、与旧码无交集。复用 P2 已有的哈希与 CAS 写入方式，不得退化成明文存储。

A5. **PATCH /me** 必须走 `Idempotency-Key`，重复同 key 返回字节相同结果且不递增 version。`expected_version` 不符返回 **409 VERSION_CONFLICT**，错误体带 `current`。用户名不可修改——若请求含 `username` 字段，`strict()` 应直接拒绝（400），不得静默忽略。

A6. **DELETE /me** 按 PLAN 617–619 行：
   - 立即设置 `users.deleted_at`、递增 `auth_version`、撤销全部 session
   - 返回 `purge_after` = deleted_at + 30 天
   - `confirm_username` 不匹配 → 400
   - 不进入业务幂等表
   - 注销后该用户名的注册请求必须仍被拒绝（保留期内占用，PLAN 619 行）

A7. 5 个端点都写进 `contracts/openapi.yaml`，**移除占位**，`TODO` 计数必须为 0。

A8. 新增测试放在 `backend/tests/auth/`，风格与现有 29 个用例一致。至少覆盖：未登录 401、错误密码 401、成功路径、幂等重放、版本冲突、跨账号隔离（用户 A 的操作不影响 B）。

---

## 工作流 B：migration 0002 —— 让地址可空

### B0. 这是本条任务最危险的地方，先读三遍

用户要求：**地址、备注等非重要信息允许不填**。

`restaurants.address` 当前是 `TEXT NOT NULL CHECK (length(trim(address)) BETWEEN 1 AND 500)`。

要让它可空，SQLite 唯一的办法是**重建表**。而 `restaurants` 被 **4 张子表** 用 `ON DELETE CASCADE` 引用：

```
dishes             FOREIGN KEY (user_id, restaurant_id) REFERENCES restaurants(user_id, id) ON DELETE CASCADE
visits             FOREIGN KEY (user_id, restaurant_id) REFERENCES restaurants(user_id, id) ON DELETE CASCADE
restaurant_search  FOREIGN KEY (user_id, restaurant_id) REFERENCES restaurants(user_id, id) ON DELETE CASCADE
revisions          FOREIGN KEY (user_id, restaurant_id) REFERENCES restaurants(user_id, id) ON DELETE CASCADE
```

**我在真实 schema 上实测过**：当 `foreign_keys=ON`（D1 的默认行为）时，
`DROP TABLE restaurants` 会触发隐式 DELETE → **级联清空 dishes / visits / restaurant_search / revisions**。

更阴险的是：**`PRAGMA foreign_key_check` 返回 0 行，完全看不出数据已丢。**

实测记录（14 表 schema，各 1 行数据）：

```
操作前:  restaurants=1 dishes=1 visits=1 restaurant_search=1 revisions=1
朴素 DROP + RENAME 后:
         restaurants=1 dishes=0 visits=0 restaurant_search=0
         fk_issues=0        <-- 完整性检查"干净"，数据却没了
```

**因此：绝对禁止直接 `DROP TABLE restaurants` 后不做恢复。**

### B1. 唯一允许的迁移方式（我已实测通过）

在同一个事务内，先备份所有级联子表到临时表，重建父表，再把子表数据写回：

```sql
PRAGMA foreign_keys=ON;
BEGIN;

CREATE TABLE _b_dishes   AS SELECT * FROM dishes;
CREATE TABLE _b_visits   AS SELECT * FROM visits;
CREATE TABLE _b_search   AS SELECT * FROM restaurant_search;
CREATE TABLE _b_rev      AS SELECT * FROM revisions;

CREATE TABLE restaurants_new (
    id TEXT PRIMARY KEY CHECK (length(id) = 36),
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 120),
    category TEXT NOT NULL CHECK (category IN ('restaurant','cafe','bakery','bar','street_food','other')),
    cuisine TEXT CHECK (cuisine IS NULL OR length(cuisine) BETWEEN 1 AND 60),
    cuisine_normalized TEXT,
    address TEXT CHECK (address IS NULL OR length(trim(address)) BETWEEN 1 AND 500),
    rating_tenths INTEGER CHECK (rating_tenths IS NULL OR (typeof(rating_tenths)='integer' AND rating_tenths BETWEEN 10 AND 50)),
    review TEXT CHECK (review IS NULL OR length(review) <= 4000),
    note TEXT CHECK (note IS NULL OR length(note) <= 4000),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
    deleted_at INTEGER CHECK (deleted_at IS NULL OR deleted_at >= created_at),
    UNIQUE (user_id, id),
    CHECK ((cuisine IS NULL AND cuisine_normalized IS NULL)
        OR (cuisine IS NOT NULL AND cuisine_normalized IS NOT NULL AND length(cuisine_normalized) > 0))
);

INSERT INTO restaurants_new SELECT id,user_id,name,category,cuisine,cuisine_normalized,address,rating_tenths,review,note,version,created_at,updated_at,deleted_at FROM restaurants;
DROP TABLE restaurants;
ALTER TABLE restaurants_new RENAME TO restaurants;

INSERT INTO dishes            SELECT * FROM _b_dishes;  DROP TABLE _b_dishes;
INSERT INTO visits            SELECT * FROM _b_visits;  DROP TABLE _b_visits;
INSERT INTO restaurant_search SELECT * FROM _b_search;  DROP TABLE _b_search;
INSERT INTO revisions         SELECT * FROM _b_rev;     DROP TABLE _b_rev;

COMMIT;
```

**注意 `DROP TABLE restaurants` 之后 `ALTER TABLE ... RENAME` 的索引与触发器**：原 `restaurants` 上的 5 个 `CREATE INDEX` 会随 `DROP TABLE` 一起消失。必须在重建后**逐条重新创建**，且定义必须与 0001 完全一致（`idx_restaurants_live_updated` / `live_category` / `live_cuisine` / `live_rating` / `deleted`）。重建后跑 `PRAGMA index_list(restaurants)` 断言 5 个非自动索引存在。

### B2. 迁移的验收脚本（必须提交）

新建 `backend/scripts/verify_migration_0002.sh`（或 .mjs），必须做到：

1. 从 **0001 单独** 建库 → 插入 **多用户、多餐厅、含菜品/到店/搜索索引/修改历史** 的种子数据
2. 记录迁移前每张表的精确行数与校验和
3. 应用 0002
4. 断言：**每张表的行数与校验和不变**（尤其 dishes / visits / restaurant_search / revisions 不能掉一行）
5. 断言 `PRAGMA foreign_key_check` 无输出、`PRAGMA integrity_check` = ok
6. 断言 `PRAGMA index_list(restaurants)` 有 5 个非自动索引
7. 断言 5 个索引的 `sql` 定义与 0001 逐字一致
8. 断言插入 `address = NULL` 成功，插入 `address = ''` 或纯空格仍被 CHECK 拒绝
9. 断言 `revisions` 的 `trg_revisions_core_immutable` 触发器仍存在且仍生效（尝试改 `diff_json` 应 ABORT）

### B3. 后端与 Android 同步放宽

- `backend/src/domain/schemas.ts`：`restaurantFields.address` 从 `text(500, true)` 改为可选（空串规范化为 `null`，与现有 `optionalText` 行为一致）
- `backend/src/domain/search-index.ts` 已对 `null` 安全（`.filter(v => v !== null)`），**不要改坏它**；但需加测试覆盖 `address=null` 时搜索索引仍生成且不含字面量 `"null"`
- Android 编辑器：地址不再是必填，未填时不阻塞保存；UI 上标注「选填」
- **PLAN.md 需同步修订**：第 63 行「餐厅名称、类型和文本地址必填」与第 218 行「名称、地址必填」必须改为反映新规则。这是用户明示的规则变更，不是实现偏差。PLAN 文档本身的修改**允许**（与 0001 迁移的冻结互不影响）。

---

## 工作流 C：UI/交互修复 + 应用内更新

用户原话列出 7 项，逐条落实：

### C1. 页面切换闪烁 → 加切换动效

现状：`ProductRoot.kt` 用裸 `NavHost`，切换无过渡，先白/黑一帧再出内容。

要求：
- `NavHost` 加 `enterTransition` / `exitTransition` / `popEnterTransition` / `popExitTransition`
- 用短促的 fade + 轻微位移（例如 180–220ms，位移 ≤ 12dp），**不要**夸张的滑动
- 必须尊重系统「移除动画」偏好：读 `Settings.Global.ANIMATOR_DURATION_SCALE == 0` 时直接无动画（避免无障碍/省电模式下的抖动）
- 深色模式下不得出现白色闪帧（这是闪烁的主要观感来源）

### C2. 没有应用图标

现状：`AndroidManifest.xml` **完全没有** `android:icon`，只有一个 `Theme.Material.Light.NoActionBar`。所以装到桌面是默认 Android 机器人。

要求：
- 新增自适应图标 `res/mipmap-anydpi-v26/ic_launcher.xml` + 前景/背景 drawable（**纯 vector XML，不引入任何 png/webp 位图**）
- 同时提供 `android:roundIcon`
- 图标必须是「食记」语义 + D2 Quiet Grid 风格：极简、网格感、单色或双色，不要拟物、不要渐变花哨
- **深浅色都要能看**：背景色与前景对比 ≥ 4.5:1；在深色壁纸和浅色壁纸下都不得糊成一团
- 提供 `monochrome` 层（Android 13+ 主题图标）
- 在 `P6_DONE.md` 给出图标设计说明与对比度实测值
- 把所有现有 `Theme.Material.Light.NoActionBar` 之类的系统主题改为项目自己的主题（当前 App 在深色模式下启动页可能白闪——与 C1 的闪烁问题同源，一并修）

### C3. 底部 tab 图标更换

现状是三个纯文本字符：`"≡"`（餐厅）、`"◷"`（到店）、`"○"`（我的）——直接 `Text(icon)` 渲染。

要求：
- 改成真正的图标：餐厅 / 到店 / 我的，语义要一眼看懂
- 优先用 `androidx.compose.material:material-icons-extended`；如担心体积，用自绘 `ImageVector`（推荐后者，可控且不增依赖）
- 未选中用描边风格，选中用实心（或等价的可辨识差异），不能只靠颜色区分（色盲友好）
- 必须验证 `NavigationBarItem` 的选中态对比度 ≥ 4.5:1（浅色、深色各测）
- 保持 `testTag("tab:restaurants" / "tab:visits" / "tab:settings")` 不变——现有模拟器测试依赖它

### C4. 地址、备注等非重要信息允许不填

见工作流 B3。另外 UI 层面：
- 必填项旁边不加「必填」噪音，只给选填项标「选填」
- 保存时若只有名称 + 类型也允许通过
- 列表/详情页对空地址、空备注要优雅降级：**不要**渲染出 `null`、`""`、`--` 这种丑占位；直接省略该行

### C5. 同一 tab 多次点击屏幕闪烁

根因（我已定位）：
```kotlin
BottomTabs(selected) { tab ->
    vm.selectTab(tab)
    when (tab) {
        "restaurants" -> nav.navigate(RestaurantsRoute) { popUpTo(nav.graph.findStartDestination().id); launchSingleTop = true }
        ...
```
每次点击都 `navigate` + `popUpTo`，即使已经在当前 tab 也会重建 back stack 条目 → 整页重组 → 闪一下。

要求：
- 当前已在目标 tab 时，**不执行任何导航**（判断 `entry.destination` 是否已是目标 route）
- 已在目标 tab 再次点击时，**滚动到顶部**（这是符合直觉的行为），不要整页重建
- 同时保留现有 `launchSingleTop` 语义用于真正需要切换的场景

### C6. 优化布局：有效信息区太小、上方占用太多

现状（`ListScreens.kt`）：LazyColumn 第一项是 `PageHeader`（返回按钮 + 「食记 / QUIET GRID」小字 + 大标题），第二项是搜索框，第三项是一整块按钮区（新增 / 刷新第一页 / 筛选与排序 / 按餐厅筛选 / 打开回收站，每个都是全宽 Button/TextButton 纵向排列），然后可能展开筛选面板，最后才是 `CacheStatus` 和真正的列表。

**问题**：首屏大半屏被「标题 + 搜索 + 五个纵向按钮」吃掉，用户看不到几条记录。

要求：
- 标题区压缩：`PageHeader` 在大标题上方那行「食记 / QUIET GRID」占用太多，收敛它（或只在根 tab 保留一行紧凑标识）
- 搜索框保留，但去掉其下方的说明性小字（`可搜店名 · 类型 · 菜系 · 地址 · 菜品 · 短评 · 评价 · 备注`）——那是把功能说明写成常驻文本，浪费首屏
- **把纵向堆叠的 5 个按钮收敛**：「刷新」「筛选」「回收站」等次要操作移到溢出菜单或行内小图标，只保留主操作「新增餐厅」一个显眼按钮
- 「刷新第一页」这种 debug 味道的操作不应占据首屏黄金位置
- 目标：**首屏至少能看到 3 条真实记录**（在 360×640dp 的常见小屏上实测）
- 必须实测：写脚本或用现有 `androidTest` 断言首屏可见的 `record-list` item 数 ≥ 3
- 深色模式同样要检查（用户手机常开深色）

### C7. 应用内更新

现状：无任何更新检查。发布走 GitHub Releases（`v0.5.0`，附件 `app-release.apk`）。

要求：
- 新增 `GET /api/v1/app/version`（Worker 端点）返回：
  ```json
  { "latest": { "version_code": 6, "version_name": "0.6.0", "url": "...", "sha256": "...", "notes": "...", "released_at": 1730000000 } }
  ```
  数据来源**不要**用数据库，直接写在 Worker 配置/常量里即可（减少依赖），但要有测试。
  **不要硬编码成只有开发知道的值**——从 `wrangler.toml` 的 `[vars]` 读取，便于发版时更新。
- Android 端：
  - 启动后在后台静默检查（不阻塞首屏，失败静默）
  - 有新版时在「我的」页显示一个不打扰的入口（例如「有新版本 0.6.0」一行），**不要**弹窗打断
  - 点击后：优先用系统浏览器打开下载页还是应用内下载？**本期简化为打开下载 URL**（`Intent.ACTION_VIEW`），不做应用内安装（那需要 REQUEST_INSTALL_PACKAGES 权限，涉及安全评审，超出本期范围）
  - 必须校验 `sha256` 后再提示用户安装（如果做下载）；本期只做**跳转 + 版本展示**，不做静默下载安装
  - 版本比较用 `BuildConfig.VERSION_CODE`，**不要**用字符串比较（"0.10.0" < "0.9.0" 的坑）
  - 更新检查要能被关闭（「我的」页提供开关，持久化到本地设置）
- 隐私：请求不带任何用户标识，不得附带 Authorization header
- 该端点必须**无需登录**即可访问

---

## 交付物

1. 5 个账号端点实现 + 测试 + OpenAPI 更新
2. `backend/migrations/0002_address_optional.sql` + 验证脚本
3. Android：动效、图标、tab 图标、可选字段、布局收敛、应用内更新
4. `P6_DONE.md`：逐条对照本任务书，标注 **verified / unverified**，unverified 必须给原因
5. 所有改动通过 CI（backend + android + API 26/35 模拟器）

## 完成标准

- `backend/migrations/0001_initial.sql` 的 SHA-256 未变
- migration 0002 的验证脚本**实际跑通**，且能证明子表数据零丢失
- `npm test` 全绿，新增测试数 > 0
- OpenAPI 中 5 个端点的 `notImplemented` / TODO 全部消失
- Android 在 API 26 与 35 上都能装上、跑通现有全部 instrumentation 测试
- 首屏可见记录数 ≥ 3 的断言存在且通过
- 深浅色对比度实测值为 0 失败
- `P6_DONE.md` 中每条都有真实证据（命令输出、文件路径、行号），不接受「应该可以」

## 提交方式

开新分支 `p6-account-endpoints-ui-update`，提交后开 PR，不要直推 master。
