# 食记

食记是一款私人餐厅记录应用：餐厅档案、餐厅级菜品库、多次到店日期及字段级修改记录，账号之间完全隔离。Android 提供餐厅 / 到店 / 我的三 tab，采用 D2 Quiet Grid 明暗主题。

唯一生产 API 为 **https://dining.alanvampuke.top/api/v1**。发布版本 `0.5.0` / versionCode `5`；正式包名 `top.alanvampuke.dininglog`，debug 包名 `top.alanvampuke.dininglog.debug`。签名 APK 与校验和通过 [GitHub Releases](https://github.com/vampuke/dining-log/releases) 分发，实际发布与部署状态以 [P5_DONE.md](P5_DONE.md) 为准。

```text
Android (Kotlin / Compose)
  app → feature → core ← data (Retrofit / Room / Keystore)
                            │ HTTPS JSON
                            ▼
dining.alanvampuke.top/api/v1
  TypeScript / Hono Worker → D1
  routes → services → owner-scoped repositories
```

餐厅与菜品评分独立，存储为可空的 `rating_tenths` 整数（10–50），页面显示 `★ 4.3`，按 0.1 步进。到店日期使用 `LocalDate`，同日多次分别保留；菜品只归餐厅。项目不提供图片、头像、文件上传、地图或社交功能。

页面先显示账号隔离的 Room 缓存，再刷新网络。输入约 500 ms 后保留为本机草稿，点击保存才提交；响应未知时保留原请求与 Idempotency-Key，超过 7 天先比较当前值。联网恢复只刷新第一页与已打开详情，不自动上传草稿；409 冲突显示 base / server / draft 三栏，同字段必须选择。

## 本地启动

**956 MB 生产 VPS 禁止执行 npm install / npm ci、Gradle、Docker、Vitest、模拟器。以下开发与构建命令仅在独立、内存充足的开发机或 GitHub Actions 执行。** VPS 只做文件、Git、API、固定版本 Wrangler 与轻量 smoke 操作。

后端使用 Node.js 22，依赖固定在 [package-lock.json](backend/package-lock.json)，Wrangler 为 `4.23.0`。本地数据库和密钥与生产隔离：

```sh
cd backend
npm ci
cp .dev.vars.example .dev.vars
# 为 .dev.vars 填写独立的开发密钥；不得填入生产密钥或提交此文件。
npx --no-install wrangler d1 migrations apply DB --local
npm run dev -- --local
```

`.dev.vars.example` 只列四个名称。前三项是版本到 Base64url 32 字节密钥的 JSON，第四项是独立 Base64url 32 字节密钥；对应 active 版本为 `v1`。开发者自行准备开发材料；生产已有密钥与签名 key 必须沿用。Wrangler 默认监听 `http://127.0.0.1:8787`：

```sh
curl --fail --silent --show-error http://127.0.0.1:8787/api/v1/health
```

Android 固定 JDK 17、SDK 35、AGP 8.9.2、Gradle Wrapper 8.11.1、Kotlin 2.1.21。开发机安装 SDK 后可构建并运行 debug；应用正常入口使用上述唯一生产 API，Actions 端到端测试另外注入隔离的本地 Worker：

```sh
cd android
./gradlew :app:assembleDebug --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n top.alanvampuke.dininglog.debug/top.alanvampuke.dininglog.MainActivity
```

## 测试与 CI

所有改动走新分支 → PR → `master`。常规 [ci.yml](.github/workflows/ci.yml) 仅持只读权限，生产 secret 只在 release job 的具体签名 / 部署步骤注入；fork PR 不执行生产操作。Actions 固定到提交 SHA，npm / Gradle 使用已提交的依赖锁。

```sh
cd backend
npm ci
node --test ../.github/scripts/release-guards.test.mjs
npm run check
node tests/contracts.mjs
npm test
npm run db:verify
bash tests/sql/verify.sh
npx --no-install wrangler deploy --dry-run
npm run test:capacity
```

容量检查仍使用 4,500 / 6,000 对象、13,500 / 18,000 历史，每项 30 次、p95 ≤100 ms。原始测量位于 `capacity-results` artifact；既有记录见 [RESULTS.md](backend/tests/capacity/RESULTS.md)。这是 Actions 的本地 D1 测量，不能代表生产 CPU、生产 SQL 或公网 p95。

```sh
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :core:test :data:testDebugUnitTest :feature:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug --no-daemon
./gradlew :app:connectedDebugAndroidTest --no-daemon
```

常规 CI 分别移除四个签名变量，断言 release 在配置阶段失败。API 26/35 instrumentation 包含认证、Keystore、完整业务闭环、实际颜色、200% 字体与真实模拟器键盘；报告与截图保留为 artifact。独立 `npm run smoke:production` 也在 Actions 的本地 Worker 上执行一次；正式发布时调用同一脚本访问生产域名，生成一次性合成账号，不打印密码、token、恢复码或响应正文。

手动 [android-ui](.github/workflows/android-ui.yml) 默认跑相同产品 suite；`refresh_dependency_locks=true` 仅用于在 Actions 生成并验证依赖锁，再下载 `android-dependency-locks` artifact，经 PR 提交。锁维护模式不算 UI 验收。

## Release 签名与 APK 校验

沿用现有 RSA 3072 release keystore，正式证书 SHA-256：

```text
A8:18:06:DB:CD:D4:C5:A2:12:91:5F:8A:6F:76:7B:37:1C:96:D6:6C:C6:1B:13:93:D0:16:B7:7D:B9:CE:70:93
```

Gradle 必须读取 `DINING_SIGNING_STORE_FILE`、`DINING_SIGNING_STORE_PASSWORD`、`DINING_SIGNING_KEY_ALIAS`、`DINING_SIGNING_KEY_PASSWORD`。缺少任一变量、keystore 文件不存在、密码 / alias 不正确都会失败；正式构建绑定 release signingConfig 并保留 R8 与 ProGuard。不要重新生成正式 key，也不要用 debug key 签名正式包。

在允许构建的环境中，从受控材料设置环境变量后执行：

```sh
export DINING_SIGNING_STORE_FILE=/secure/dining-release.jks
export DINING_SIGNING_STORE_PASSWORD="${ANDROID_STORE_PASSWORD:?}"
export DINING_SIGNING_KEY_ALIAS="${ANDROID_KEY_ALIAS:?}"
export DINING_SIGNING_KEY_PASSWORD="${ANDROID_KEY_PASSWORD:?}"
cd android
./gradlew :core:test :data:testDebugUnitTest :feature:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease --no-daemon
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

Actions 从 environment secret 解码 keystore 到 `$RUNNER_TEMP`，使用 `umask 077` 和退出 / 中断 trap 清理。验签脚本要求 v2、唯一 signer 和固定指纹；`aapt2 dump badging` 检查包名、SDK、源文件 versionCode / versionName、tag，并与上个正式 APK 比较 versionCode。API 26/35 模拟器必须安装并启动 signed APK 后才部署。

从 GitHub Release 下载 `app-release.apk` 与 `app-release.apk.sha256`，放在同一目录：

```sh
sha256sum --check app-release.apk.sha256
```

Windows 可执行 `Get-FileHash .\app-release.apk -Algorithm SHA256` 并与 checksum 比较。checksum 的稳定文件名为 `app-release.apk`；证书摘要是签名身份，文件 SHA-256 是这一版本的下载完整性，两项都检查。

## 生产部署与发布

正式资源：Worker `dining-log-api`，D1 `dining-log-prod` / `90d3c1ef-5ea0-4308-a866-89a96e0411d8`。Wrangler 关闭 workers.dev / preview URLs，裸主机 Custom Domain 只绑定 `dining.alanvampuke.top`。非秘密配置在 [wrangler.toml](backend/wrangler.toml)，secret 名称、首次初始化与运维命令见 [OPERATIONS.md](docs/OPERATIONS.md)，决策见 [ADR](design/adr/0001-production-release.md)。

首次初始化顺序为：**先用已通过 CI 的 bundle 建立 Worker → stdin 写入四个既有 Worker secrets → 再 deploy → 记录 D1 Time Travel → 迁移 → 验证 14 张应用表 / migration / foreign_key_check / quick_check → 绑定 Custom Domain → health → 独立 smoke**。每一步失败即停止。GitHub `production` environment 只允许 master，保存四个 Android 签名 secrets 与 `CLOUDFLARE_API_TOKEN`；Worker 密钥不放入 GitHub。

SHA 格式、HEAD 和 master 祖先关系由 workflow 内的命令先检查，通过后才执行所选提交中的脚本；不能让尚未确认来源的代码自行决定是否可以使用生产 secrets。

日常发布从 `master` 手动触发 [release.yml](.github/workflows/release.yml)，明确输入已审查的 40 位 SHA 和 `vX.Y.Z` tag，例如：

```sh
git fetch origin master
DINING_RELEASE_COMMIT="$(git rev-parse origin/master)"
gh workflow run release.yml --repo vampuke/dining-log --ref master \
  -f commit_sha="$DINING_RELEASE_COMMIT" -f release_tag=v0.5.0
```

workflow 校验 SHA、HEAD、master 祖先、相同 SHA 的 master CI 和 API 26/35 检查、tag / Release 不存在及版本递增；按顺序验证后端、签名 APK、有效 checkpoint、迁移兼容性及域名冲突、apply、完整性、deploy、health、独立 smoke，最后 `gh release create`。`concurrency=dining-production`、`cancel-in-progress=false` 串行化生产发布；迁移、部署、smoke 失败不会自动重试、回滚 D1 或覆盖已有 Release。

```sh
curl --fail --silent --show-error --max-time 20 https://dining.alanvampuke.top/api/v1/health
cd backend
npm run smoke:production
```

health 必须 HTTP 200 且正文为 `{"status":"ok","api_version":"v1"}`。smoke 真正注册、登录、建餐厅 / 菜品 / 两次同日到店、改分 4.0→4.3、核对历史 40→43、删除 / 恢复、刷新、登出并验证旧 access 401；仅输出步骤与状态码。结束软删除测试餐厅、撤销两个 session，保留无可登录凭据的合成账号，后续遵循 30 天清理策略。

## 回滚

先从 `release-evidence` artifact 取部署前 Worker deployment / version ID 和 D1 bookmark，确认旧 Worker 与当前 schema 兼容。Worker 回滚和 D1 恢复是两项不同操作：

```sh
cd backend
npx --no-install wrangler deployments list
npx --no-install wrangler versions view "$DINING_PREVIOUS_VERSION_ID"
npx --no-install wrangler rollback "$DINING_PREVIOUS_VERSION_ID"
curl --fail --silent --show-error https://dining.alanvampuke.top/api/v1/health
npm run smoke:production
```

**D1 不随 Worker 自动回滚。** Time Travel 恢复会改变所选时间之后的数据；先暂停生产写入、记录当前 bookmark 和恢复影响，再按 [运维步骤](docs/OPERATIONS.md#time-travel-与数据恢复) 执行指定 bookmark 的恢复并验证完整性。首次空库可能无历史，没有可承诺的旧 Worker 或旧 APK。APK 不覆盖已有 tag、不降低 versionCode；必要时沿用同 key 以更高 versionCode 发布修复版本。生产回滚 / 数据恢复演练是否实际完成见 P5_DONE.md。

## 验收、契约与许可

方案只读：[PLAN.md](design/PLAN.md)。实现与约束见 [OpenAPI](contracts/openapi.yaml)、[迁移验证](backend/migrations/VERIFY.md)、[P1](P1_DONE.md)、[P1 收尾](P1_FINISH_DONE.md)、[P2](P2_DONE.md)、[P3](P3_DONE.md)、[P4](P4_DONE.md)、[P5](P5_DONE.md)。后端已实现 29 个领域与 9 个 auth 端点；额外账号生命周期占位不计入已实现范围，详见 [P3_BLOCKERS.md](P3_BLOCKERS.md)。

真实手机、三网、TalkBack、厂商 Keystore、物理弱网 / 杀进程等仍需按 [P4_BLOCKERS.md](P4_BLOCKERS.md) 验证；旧阶段的生产缺口以 P5 的真实证据更新，不由模拟器结果代替。

项目代码使用 [MIT](LICENSE)。Gradle Wrapper 保留上游 Apache-2.0 声明；字体保留各自许可证。
