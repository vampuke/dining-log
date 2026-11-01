# 食记

食记是一款私人餐厅记录应用：餐厅档案、餐厅级菜品库、多次到店日期及字段级修改记录，账号之间完全隔离。

方案唯一来源是 [design/PLAN.md](design/PLAN.md)，P1 范围见 [design/P1_TASK.md](design/P1_TASK.md)。当前交付为底座：业务端点返回 501，Android 只有构建入口与 D2 Quiet Grid 主题，没有页面。

```text
Android (Kotlin / Compose)
  app → feature → core ← data (Retrofit / Room / Keystore，后续实现)
                            │ HTTPS JSON
                            ▼
dining.alanvampuke.top/api/v1
  TypeScript / Hono Worker → D1
  routes → services → owner-scoped repositories
```

餐厅评分与菜品评分独立，存储为可空的 `rating_tenths` 整数（10–50）；`Rating.fromTenths(43)` 显示 `4.3`。到店日期为必填 `LocalDate`，菜品不关联到店记录。项目不提供图片、文件上传、地图或社交功能。

## 开发与检查

**重活只在本仓库的 GitHub Actions 跑，不要在低内存机器上跑 gradle。956 MB VPS 禁止执行 npm install、npm ci、Gradle 或 Docker。** 提交或 PR 到 `master` 后，`ci` 独立运行后端校验和 Android 构建；也可在 Actions 页面手动触发。

后端工具链为 Node.js 22、固定版本的 TypeScript / Hono / Wrangler / Vitest，以及 `package-lock.json`。以下安装和测试命令由 GitHub Actions 执行：

```sh
cd backend
npm ci
npm run check
npm test
npm run db:verify
npm run test:capacity
```

具备足够内存的开发环境可使用 `npm run dev` 启动本地 Wrangler（默认 `http://localhost:8787`），从迁移创建独立本地 D1。`.dev.vars.example` 仅列变量名；需要开发密钥时在仓库外受控配置，`.dev.vars` 已忽略。P1 健康检查不需要密钥，其余 42 个契约端点仅为占位。配置中的 D1 ID 仍是方案占位符，真实资源留到 P5。

本机允许的 SQL 自检完全使用 sqlite3 CLI，数据库写在 `/tmp`，逐连接启用外键：

```sh
bash backend/tests/sql/verify.sh
```

命令拒绝覆盖已有数据库。完整命令及实测输出见 [VERIFY.md](backend/migrations/VERIFY.md) 和 [P1_DONE.md](P1_DONE.md)。本地 SQLite 的结果与 Cloudflare Workers pool 中的 D1 测试分别记录。

容量测试使用固定 seed，在 GitHub Actions 的独立 step 测量 4,500 对象 / 13,500 历史以及 6,000 对象 / 18,000 历史；每种查询执行 30 次，p95 上限为 100 ms。原始样本与自动生成的报告在 `capacity-results` artifact；已验收实测值见 [RESULTS.md](backend/tests/capacity/RESULTS.md)。

Android 工具链固定为 JDK 17、SDK 35、AGP 8.9.2、Gradle Wrapper 8.11.1、Kotlin 2.1.21；四个模块为 `app/core/data/feature`。依赖版本集中在 `android/gradle/libs.versions.toml`，debug 与 release 应用包名均为 `top.alanvampuke.dininglog`。

GitHub Actions 使用 SDK 缓存，并执行：

```sh
cd android
./gradlew :app:assembleDebug :core:test :app:testDebugUnitTest :app:lintDebug --no-daemon
```

debug APK 可从 CI 的 `app-debug` artifact 下载，附带实际包名 / SDK、签名检查输出和校验和。VPS 发布路径为 `/var/www/html/dl/dining/shiji-debug.apk`，校验记录为同目录 `SHA256SUMS`；产物来源和验收见 [P1_ACCEPTANCE.md](P1_ACCEPTANCE.md)。`android-ui` 留待 P4 的 API 26/35 合成数据 UI 测试；`release` 留待 P5，当前均为手动占位流程。

## 部署

待 P5 补充。

## 许可

项目代码使用 [MIT](LICENSE)。Gradle Wrapper 保留上游 Apache-2.0 声明；字体保留各自许可证。
