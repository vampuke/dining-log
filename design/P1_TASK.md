# P1 开工任务：建仓库底座 + D1 migrations + Worker 骨架

你是资深 Android + Cloudflare Workers 工程师。这是一个**已定稿方案的实际实现**，方案在 `/root/dining_log/design/PLAN.md`（约 2050 行，中文，已通过评审并经用户拍板）。

## 你的工作原则

1. **PLAN.md 是唯一事实来源**。所有表结构、字段名、CHECK 约束、API 路径、错误码、DDL 必须与该文档逐字一致。先完整通读，不要凭印象写。
2. 视觉定稿在 `/root/dining_log/design/proto/d2-quiet-grid.html` 和 `DIRECTIONS.md`。这一轮**不写 UI**，但要把设计 token 落到 Compose 主题文件里。
3. 本机（ora VPS）**只有 956MB 内存**，绝对不能在本机跑 gradle / npm install / docker build。所有重活必须设计成在 GitHub Actions 跑。本机只允许：写代码、跑 `sqlite3` 验证 SQL、跑 node 单文件语法检查。
4. 不要真的部署到 Cloudflare，不要把任何 secret 写进仓库。

## 本轮范围（P1 底座）

### 1. 建 GitHub public 仓库

- 仓库：`vampuke/dining-log`（public）
- 用 GitHub API（token 在 `~/.git-credentials`，已知有 repo + workflow 权限）
- 不要 push 任何 secret
- 本地目录就是 `/root/dining_log`，把它初始化为该仓库的工作副本并推到 master

### 2. 目录结构（严格按 PLAN.md 11.1）

```
dining-log/
  android/
    app/
    core/
    data/
    feature/{auth,restaurants,dishes,visits,revisions,settings}
    gradle/libs.versions.toml
    gradle/wrapper/
    build.gradle.kts, settings.gradle.kts, gradle.properties
  backend/
    src/{routes,services,repositories,auth}
    migrations/0001_initial.sql
    tests/
    wrangler.toml
    package.json
    tsconfig.json
    .dev.vars.example
  contracts/openapi.yaml
  design/            # 已有内容，保留
  .github/workflows/{ci.yml,android-ui.yml,release.yml}
  README.md
  CHANGELOG.md
  LICENSE            # MIT
  .gitignore
```

### 3. D1 migrations（最重要，本轮核心交付）

从 PLAN.md 第 3 节的完整 DDL 逐字生成 `backend/migrations/0001_initial.sql`。必须包含：

- `users`（含 `password_iterations INTEGER NOT NULL DEFAULT 10000 CHECK (password_iterations >= 10000)`）
- `sessions`、`refresh_tokens`、`recovery_codes`、`auth_rate_limits`
- `restaurants`（含 `rating_tenths INTEGER CHECK (... BETWEEN 10 AND 50)`）
- `dishes`（同样 `rating_tenths`）
- `visits`
- `change_sets`、`revisions`（字段级 diff，JSON）
- `restaurant_search`（聚合搜索索引，第 3 节新增的表）
- 所有索引、组合外键、CHECK 约束、不可变 trigger

**验证要求**：在 `/tmp` 用 `sqlite3` 把迁移跑一遍，确认：
- 建表成功无报错
- 组合外键、CHECK 生效（故意插非法评分 0 / 51 / 非整十分位应被拒绝）
- trigger 不可变生效
把这些验证命令和输出写进 `backend/migrations/VERIFY.md`。

### 4. Worker 骨架（TypeScript + Hono）

只要骨架，不实现业务逻辑：
- `src/index.ts`：Hono app，挂 `/api/v1` 前缀，健康检查 `GET /api/v1/health`
- `src/routes/`：空路由文件占位（auth / restaurants / dishes / visits / revisions），每个导出 Hono 子应用并返回 501
- `src/auth/`：`password.ts` 用 **@noble/hashes PBKDF2-HMAC-SHA256 10,000 次 + 独立 pepper 的 HMAC-SHA256**（照 PLAN.md 第 5 节实现，含单测）；`jwt.ts` 用 jose 签发验证 HS256
- `src/repositories/`：空的 D1 查询封装占位
- `wrangler.toml`：照 PLAN.md 8.4 的配置，免费档（不设 `[limits] cpu_ms`）
- `.dev.vars.example`：只列变量名无值

**单测**：`backend/tests/password.test.ts` — 用标准测试向量验证 PBKDF2 10,000 次 + pepper 组合，至少 3 个用例。

### 5. Android 骨架

- 4 模块 Gradle（app / core / data / feature），Kotlin 2.x、Compose、Hilt、Room、Retrofit、kotlinx.serialization
- `gradle/libs.versions.toml` 版本目录，固定版本
- minSdk 26 / targetSdk 35 / compileSdk 35
- applicationId `top.alanvampuke.dininglog`
- `core`：纯 Kotlin 领域模型 `Restaurant`、`Dish`、`Visit`、`Revision`，`Rating` 用十分位整数封装（`Rating.fromTenths(43)` → 显示 `4.3`），必填 `LocalDate`
- `feature`：Compose 主题文件，把 D2 Quiet Grid 的 light/dark 全部 token 落成 `ColorScheme` + `Typography` + `Shapes`（token 值从 `DIRECTIONS.md` 和 d2 HTML 的 `:root` 取）
- 不写任何页面 UI（本轮不做界面）

### 6. CI（不跑 gradle on this machine）

`.github/workflows/ci.yml`：
- job `backend`：`npm ci` → `npm run check`（tsc --noEmit + eslint）→ `npm test`（vitest，Cloudflare workers pool）
- job `android`：`./gradlew :app:assembleDebug`（在 ubuntu-latest 上跑，带 JDK 17 + Android SDK 缓存）

`.github/workflows/android-ui.yml`、`release.yml`：先建占位骨架 + 注释说明将来用途，不要写会失败的步骤。

### 7. contracts/openapi.yaml

按 PLAN.md 第 6 节的 endpoint 清单写 OpenAPI 3.1 初稿。**只写路径和 schema 骨架**，不要求每个字段都完善，但 auth / restaurants / dishes / visits / revisions 的路径必须齐全且与 PLAN 一致。

### 8. README.md

- 项目一句话说明（食记，私人餐厅记录）
- 架构图（文字/ASCII 即可）
- 本地开发步骤（backend 需要什么、android 怎么构建）
- 明确写：**重活只在本仓库的 GitHub Actions 跑，不要在低内存机器上跑 gradle**
- 部署说明留空待 P5

## 绝对不要做的事

- 不要部署到 Cloudflare
- 不要把 token / secret / key 写进任何文件
- 不要在本机跑 `npm install` / `gradle` / `docker`
- 不要修改 `design/PLAN.md`、`design/proto/*`（这是定稿，只读）
- 不要写业务 API 逻辑（这是 P2/P3 的事）

## 交付前自检（必须真跑，把结果写进 `P1_DONE.md`）

1. `sqlite3 /tmp/t.db < backend/migrations/0001_initial.sql` 成功
2. 非法数据被拒绝（评分 0、51、非整十分位、非法日期）
3. trigger 不可变生效
4. `node --check` 通过所有 .ts 转译后文件？——不要求，改用 `npx tsc --noEmit` 若本机有 tsc；没有就跳过并说明
5. `git status` 干净，无 secret 文件
6. 仓库 URL 可访问（`curl -s -o /dev/null -w "%{http_code}" https://api.github.com/repos/vampuke/dining-log` 返回 200）
