# 食记

食记是一款私人餐厅记录应用：餐厅档案、餐厅级菜品库、多次到店日期及字段级修改记录，账号之间完全隔离。

方案唯一来源是 [design/PLAN.md](design/PLAN.md)。P3 已实现 29 个领域端点，沿用 P2 的 9 个认证端点，验收见 [P3_DONE.md](P3_DONE.md)。P4 加入 Android 餐厅 / 到店 / 我的三 tab、餐厅与店内菜品管理、修改历史、聚合搜索、账号安全、Room 缓存与草稿；自动化证据见 [P4_DONE.md](P4_DONE.md)，真机与发布环境缺口见 [P4_BLOCKERS.md](P4_BLOCKERS.md)。

```text
Android (Kotlin / Compose)
  app → feature → core ← data (Retrofit / Room / Keystore)
                            │ HTTPS JSON
                            ▼
dining.alanvampuke.top/api/v1
  TypeScript / Hono Worker → D1
  routes → services → owner-scoped repositories
```

餐厅评分与菜品评分独立，存储为可空的 `rating_tenths` 整数（10–50）；页面显示 `★ 4.3`，滑杆按 0.1 步进。到店日期为必填 `LocalDate`，同日多次分别保留；菜品只归餐厅。项目不提供图片、头像、文件上传、地图或社交功能。

页面先显示账号隔离的 Room 缓存，再刷新网络。输入约 500 ms 后保留为本机草稿，点击保存才提交；响应未知时锁定原请求并使用原 Idempotency-Key 重试，超过 7 天需比较服务器当前值后确认新保存。联网恢复只刷新第一页和打开的详情，不自动上传草稿。409 冲突显示开始编辑时、服务器当前和本机草稿三栏，同字段必须选择。

## 开发与检查

**构建和测试只在本仓库的 GitHub Actions 跑。956 MB VPS 禁止执行 npm install、npm ci、Gradle、Docker、Vitest 或模拟器。** 新分支通过 PR 到 `master`，`ci` 运行后端检查、Android debug / release 编译、lint、JVM 测试与 API 26/35 模拟器测试；也可在 Actions 页面手动触发。

后端工具链为 Node.js 22、固定版本的 TypeScript / Hono / Wrangler / Vitest，以及 `package-lock.json`。以下安装和测试命令由 GitHub Actions 执行：

```sh
cd backend
npm ci
npm run check
npm test
npm run db:verify
npm run test:capacity
```

具备足够内存的独立开发环境可使用 `npm run dev` 启动本地 Wrangler（默认 `http://localhost:8787`），从迁移创建独立本地 D1。`.dev.vars.example` 仅列变量名；开发密钥在仓库外受控配置，`.dev.vars` 已忽略。健康检查不需要密钥；认证测试仅使用公开合成 fixtures。账号导出与账号生命周期占位端点的范围见 [P3_BLOCKERS.md](P3_BLOCKERS.md)。配置中的 D1 ID 仍是方案占位符，真实资源留到 P5。

GitHub Actions 的 SQL 检查使用 sqlite3 CLI，数据库写在 runner 的 `/tmp`，逐连接启用外键：

```sh
bash backend/tests/sql/verify.sh
```

命令拒绝覆盖已有数据库。完整命令及实测输出见 [VERIFY.md](backend/migrations/VERIFY.md) 和 [P1_DONE.md](P1_DONE.md)。本地 SQLite 的结果与 Cloudflare Workers pool 中的 D1 测试分别记录。

容量测试使用固定 seed，在 GitHub Actions 的独立 step 测量 4,500 对象 / 13,500 历史以及 6,000 对象 / 18,000 历史；每种查询执行 30 次，p95 上限为 100 ms。原始样本与自动生成的报告在 `capacity-results` artifact；已验收实测值见 [RESULTS.md](backend/tests/capacity/RESULTS.md)。

Android 工具链固定为 JDK 17、SDK 35、AGP 8.9.2、Gradle Wrapper 8.11.1、Kotlin 2.1.21；四个模块为 `app/core/data/feature`。依赖版本集中在 `android/gradle/libs.versions.toml`，debug 与 release 应用包名均为 `top.alanvampuke.dininglog`。

GitHub Actions 使用 SDK 缓存，并执行：

```sh
cd android
./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :core:test :data:testDebugUnitTest :feature:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug --no-daemon
```

debug APK 可从 CI 的 `app-debug` artifact 下载，附带实际包名 / SDK、签名检查输出和校验和。VPS 发布路径为 `/var/www/html/dl/dining/shiji-debug.apk`，校验记录为同目录 `SHA256SUMS`；P4 产物来源见 [P4_DONE.md](P4_DONE.md)。常规 `ci` 与手动 `android-ui` 使用 API 26/35 模拟器运行 Keystore、认证、实际业务流程、明暗色值、200% 字体和键盘遮挡检查，报告与截图保留为 artifact。端到端使用 Actions 内的本地 Worker / D1 和公开合成测试数据；正式 Cloudflare、真实设备和稳定 release 签名验证留到 P5。

普通 CI 使用已提交的依赖锁，不在检查时改写。更新依赖时，仅在 Actions 的 `android-ui` 手动启用 `refresh_dependency_locks`，下载 `android-dependency-locks` artifact 并提交核对后的锁文件；该维护模式不作为业务模拟器验收证据。

## 部署

待 P5 补充。

## 许可

项目代码使用 [MIT](LICENSE)。Gradle Wrapper 保留上游 Apache-2.0 声明；字体保留各自许可证。
