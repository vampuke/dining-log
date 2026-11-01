# P5 任务：签名、部署与发布

工作目录 `/root/dining_log`。方案 `/root/dining_log/design/PLAN.md` **只读**（11.1 ~ 11.5 节是本期主依据）。
开工前读：`11.1 单仓库布局及脚本契约`、`11.2 常规 CI 流程`、`11.3 签名方案`、`11.4 发布顺序与环境控制`、`11.5 Release workflow 关键示例`、`10.5 P5 交付清单`。

## 已由我完成的前置工作（**不要重做**）

1. **生产 D1 已创建**：`dining-log-prod`，UUID `90d3c1ef-5ea0-4308-a866-89a96e0411d8`，已写入 `backend/wrangler.toml`。
2. **生产密钥已生成**：`/root/.dining_secrets/worker_secrets.json`（0600），含 `JWT_HS256_KEYS` / `PASSWORD_PEPPERS` / `REFRESH_RETRY_KEYS` / `RATE_LIMIT_HMAC_KEY`，全部已解码验证为 32 字节。**不要重新生成、不要把这些值写进仓库或日志。**
3. **Release keystore 已生成**：`/root/release_key/dining-release.jks`（RSA 3072，有效期至 2054-03-19），参数在 `/root/release_key/signing.env`，Base64 在 `/root/release_key/dining-release.jks.b64`。证书 SHA-256 指纹 `A8:18:06:DB:CD:D4:C5:A2:12:91:5F:8A:6F:76:7B:37:1C:96:D6:6C:C6:1B:13:93:D0:16:B7:7D:B9:CE:70:93`。
4. **Cloudflare 凭据**：`/root/.cloudflare_env` 里的 `CLOUDFLARE_API_TOKEN`，账号 `49eee8b8033579863adb3b7cf0b1852c`。区 `alanvampuke.top` id `e046faddc69173d411003bb43f93ea83`，状态 active。
5. **GitHub**：仓库 `vampuke/dining-log`，token 在 `~/.git-credentials`（账号 vampuke）。**当前 repo secrets 和 environments 都是空的**，需要你创建 environment + secrets。

## 你的任务

### 一、Gradle release 签名配置（`android/app/build.gradle.kts`）

按 PLAN 11.3：

- `signingConfigs` 读取环境变量：`DINING_SIGNING_STORE_FILE` / `DINING_SIGNING_STORE_PASSWORD` / `DINING_SIGNING_KEY_ALIAS` / `DINING_SIGNING_KEY_PASSWORD`
- **release 构建缺任一变量时必须直接失败**，绝不 fallback 到 debug key。用 `error()` 或显式抛异常。
- `buildTypes.release` 绑定该 signingConfig，保留现有 `isMinifyEnabled = true` + proguard
- **debug 加 `applicationIdSuffix = ".debug"`**（PLAN 11.3 要求，避免与正式用户数据/签名混淆）。注意：这会改变 debug APK 包名为 `top.alanvampuke.dininglog.debug`，需要同步检查既有的 androidTest 是否依赖旧包名（`android/app/src/androidTest`、`adb` 相关脚本、`ProductVisualTest` 里的 tag）。如果会破坏现有模拟器测试，**改测试里的包名引用，不要去掉 suffix**。
- `versionCode` 从 4 升到 **5**，`versionName` 从 `0.4.0` 升到 **`0.5.0`**（与 tag `v0.5.0` 对齐）

### 二、`.github/workflows/release.yml` 完整实现

现在是 12 行占位，**要替换成完整实现**，严格按 PLAN 11.4 的顺序和 11.5 的骨架，并补齐 PLAN 里点名的"完整落实要求"：

- `workflow_dispatch` 输入 `commit_sha`（40 位 hex）和 `release_tag`（`vX.Y.Z`）
- **校验**：`commit_sha` 格式、`git rev-parse HEAD` 匹配、是 `master` 的祖先
- `environment: production`，`concurrency: group: dining-production, cancel-in-progress: false`
- 权限最小化；**签名和 Cloudflare secret 只注入 release job，绝不进常规 CI**
- 步骤顺序：
  1. checkout 指定 SHA
  2. 校验选择
  3. 后端候选验证（`npm ci` → `npm run check` → `npm test` → `npm run db:verify` → `npx wrangler deploy --dry-run`）
  4. 解码 keystore 到 `$RUNNER_TEMP`，`umask 077`，`trap` 清理
  5. `./gradlew :app:assembleRelease` + JVM 测试 + lint
  6. **验签**：`apksigner verify --verbose --print-certs`，**断言证书 SHA-256 等于上面给的指纹**
  7. **校验 versionCode/versionName 与 tag 一致**（用 `aapt2 dump badging` 或 `apkanalyzer`）
  8. `sha256sum` 生成 checksum
  9. 部署前记录 `wrangler d1 time-travel info DB`（初次空库要能处理"无历史"的情况而不失败）
  10. **迁移完整性检查**：应用后跑 `PRAGMA foreign_key_check` / `quick_check`
  11. `wrangler d1 migrations apply DB --remote`
  12. `wrangler deploy`
  13. `/api/v1/health` 检查
  14. **独立 smoke**：不只是 health，要真跑一次注册→登录→建餐厅→改评分→查历史→删除→恢复→登出的完整链路（用合成账号，不打印密码/token）
  15. `gh release create` 发布 signed APK + checksum
- **失败语义**：迁移/部署/smoke 任一步失败就停，不自动覆盖已有 release，不把失败标成功

### 三、Worker secrets 与 Cloudflare 部署

- **先部署一次 Worker（不带 secrets 会启动失败，所以顺序是：先 `wrangler deploy` 建立 Worker，再 `wrangler secret put`，再 `wrangler deploy` 一次让 secrets 生效）**
- secrets 从 `/root/.dining_secrets/worker_secrets.json` 读取，用 `wrangler secret put <NAME>` 写入（值通过 stdin，不要出现在命令行历史或日志里）
- 四个 secret：`JWT_HS256_KEYS`、`PASSWORD_PEPPERS`、`REFRESH_RETRY_KEYS`、`RATE_LIMIT_HMAC_KEY`
- 用 `/root/.cloudflare_env` 的 `CLOUDFLARE_API_TOKEN`
- 迁移 `backend/migrations/0001_initial.sql` 应用到远程 D1
- 绑定 Custom Domain `dining.alanvampuke.top`

### 四、GitHub environment 与 secrets

- 创建 environment `production`（限制仅 master 可部署）
- 写入 secrets（用 GitHub API 的 sealed box 加密，或 `gh secret set`）：
  - `ANDROID_KEYSTORE_B64` = `/root/release_key/dining-release.jks.b64` 内容
  - `ANDROID_STORE_PASSWORD` / `ANDROID_KEY_PASSWORD` = `/root/release_key/signing.env` 里的值
  - `ANDROID_KEY_ALIAS` = `dining-log`
  - `CLOUDFLARE_API_TOKEN` = `/root/.cloudflare_env` 的值
- **用 curl/python 调 GitHub API 时注意**：secrets 必须用 libsodium sealed box 加密（公钥从 `/repos/{owner}/{repo}/environments/production/secrets/public-key` 取）。`pynacl` 已装可用。

### 五、文档与交付

- `CHANGELOG.md` 加 `0.5.0` 段
- `README.md` 写：本地启动、测试、签名、部署、回滚步骤，以及 APK SHA-256 校验方法
- `docs/` 或 `design/adr/` 写一份运维说明：secret 名称清单（**不含值**）、日志/Cron/Time Travel 操作、回滚步骤
- `P5_DONE.md`：交付清单逐条对照 PLAN 10.5，verified vs unverified 分开

## 硬约束

- **本机 956MB**：禁止本机跑 `npm install` / `gradle` / `docker` / `vitest`。Android 构建必须在 Actions。
  后端方面：`wrangler` 命令和 `curl` smoke 可以在本机跑（轻量），但 `npm install` 不行 —— 如果 `backend/node_modules` 不存在，**改用 `npx wrangler` 并接受它可能需要安装**；不确定就先检查，实在不行把部署也交给 Actions。
- **不许把任何 secret 值写进仓库、日志、汇报**。`wrangler.toml` 只放 database_id（已放好）。
- 不许改 `backend/migrations/0001_initial.sql`（SHA `191d1a80a7411ba828effc9041fbd144fa4d10abb372f61c793a8f6dc1a5b80b`）和 `design/PLAN.md`。
- 不许降低断言阈值、删测试、skip。
- 走**新分支 → PR → 合并**，不直推 master。
- 部署是**真实生产操作**，每步做完要验证（health 返回 200、D1 表数 14、迁移记录存在）。失败就停下报告，不要盲目重试覆盖。

## 完成判据

1. release workflow 完整实现且代码审查通过（不一定要真跑一次 release，但 `wrangler deploy --dry-run` 要过）
2. Worker 已部署，`https://dining.alanvampuke.top/api/v1/health` 返回 `{"status":"ok","api_version":"v1"}`
3. 远程 D1 有 14 张表，`foreign_key_check` 干净
4. 四个 secret 已写入且 `wrangler secret list` 可见
5. GitHub production environment + 5 个 secrets 已配置
6. 真实 smoke 通过（注册→登录→CRUD→历史→删除→恢复→登出）
7. `P5_DONE.md` 有逐条对照与真实证据（curl 输出、SQL 查询结果）
