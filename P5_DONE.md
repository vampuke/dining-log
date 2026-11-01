# P5 签名、部署与发布验收

依据：完整阅读的 [P5_TASK.md](design/P5_TASK.md) 与只读 [PLAN 10.5、11.1～11.5](design/PLAN.md)。只将真实执行或复核通过的事项列为 verified；源码实现、模拟器和生产验证分别记录。

`v0.5.0` 已于 **2026-11-01 14:40:38 UTC** 从 `ad6101ce8666aa4f42df7519dec7230cff87795a` 正式发布：[GitHub Release](https://github.com/vampuke/dining-log/releases/tag/v0.5.0)、[signed APK](https://github.com/vampuke/dining-log/releases/download/v0.5.0/app-release.apk)、[SHA-256](https://github.com/vampuke/dining-log/releases/download/v0.5.0/app-release.apk.sha256)。发布后只读生产复核截止 2026-11-01 14:41:51 UTC；证据见下文。

P5 实现经 [PR #5](https://github.com/vampuke/dining-log/pull/5) 合并；来源检查、生产证据与 API 26 空态测试修正经 [PR #6](https://github.com/vampuke/dining-log/pull/6) 合并；SDK 字段解析修正经 [PR #7](https://github.com/vampuke/dining-log/pull/7) 合并；API 26 日志采集修正经 [PR #8](https://github.com/vampuke/dining-log/pull/8) 合并。正式发布提交为 `ad6101ce8666aa4f42df7519dec7230cff87795a`，验收收尾分支为 `p5/release-completion-evidence-ad6101c`。沿用全部既有生产资源与密钥；每次失败与成功结果分别记录。

## PLAN 10.5 逐条映射

| PLAN 10.5 交付条目 | 对应交付 | verified | unverified |
| --- | --- | --- | --- |
| public repo 完整实现、MIT、README、架构 ADR、OpenAPI、迁移与运行说明 | [public repo](https://github.com/vampuke/dining-log)、LICENSE、README、ADR、OpenAPI、migrations、[OPERATIONS.md](docs/OPERATIONS.md) | public / master 经 API 确认；P5 PR #5～#8 已合并；最终发布候选 master 四个必需 job 全成功；29 领域 +9 auth 实现与既有验收保留 | 没有独立人类 approve 记录；源码复核与 CI 的范围明确 |
| 唯一生产 API | https://dining.alanvampuke.top/api/v1；workers.dev / preview_urls=false | 公网 HTTPS health HTTP 200、正文精确匹配；真实完整 smoke；唯一该 Worker 的 Custom Domain | 三网 / 跨地域性能与长期可用性观察 |
| 可安装 signed release APK、SHA-256、版本号、更新说明、GitHub Release | versionCode=5 / versionName=0.5.0、固定签名、release.yml、CHANGELOG 0.5.0、上方实际产物 | release 构建 / 固定证书 / v2 / tag / SDK 检查；API 26/35 正式安装、启动与重装；Release 与 tag 均指向选定 SHA；公网下载 checksum 与二进制 manifest 验证通过 | 历史正式版本升级、真实手机安装与产品体验 |
| 正式 Cloudflare 配置、secret 名称、日志 / Cron / Time Travel | wrangler.toml、production environment、OPERATIONS.md、生产检查脚本 | 四个 Worker secrets；五个环境 secrets 与 master policy；Cron、observability 配置；有效 bookmark；D1 14 应用表 / migration / 完整性 | 实际 Cron 清理成本、告警、Worker rollback / D1 restore、离线备份恢复 |
| README 本地 / 测试 / 签名 / 部署 / 回滚步骤 | README、OPERATIONS.md、固定命令与依赖锁 | Actions 后端检查 / 测试 / DB verify / dry-run、Android JVM / lint / 模拟器；bootstrap 与正式 release 实际完成；VPS 仅轻量核验 | 本轮未在独立开发机逐条重做 README 启动步骤；未执行生产回滚 / D1 restore |
| P0～P5 验收与仍需验证最终状态 | P1～P4_DONE、既有 BLOCKERS、本文件 | 保留既有 CI 证据，新增真实生产证据；各类证据范围明确 | 下方列出的真机、生产性能、恢复与首次发布升级缺项 |

## verified

- [P5 PR CI](https://github.com/vampuke/dining-log/actions/runs/41306167734) 与 [合并后 master CI](https://github.com/vampuke/dining-log/actions/runs/41307015620) 均 completed / success；backend、android、android-auth-instrumentation (26)、android-auth-instrumentation (35) 四个必需 job 均 success。backend 的 `npm ci → check → test → db:verify → wrangler deploy --dry-run` 实际执行通过。
- [PR #6 修正提交 CI](https://github.com/vampuke/dining-log/actions/runs/41310736332) 的源码 `49a0cb881873783b28321b4f90b5cb22fa205952`，四个必需 job 均 completed / success；2026-11-01 经 PR 合并为 `e03d675568c6ce90f261bf2bc10198386f52f8ec`。合并依据为源码复核与完整 CI；GitHub reviews 没有独立人类 approve 记录，不将代理复核称为人类批准。
- 首次失败发布使用 [master CI 41311292752](https://github.com/vampuke/dining-log/actions/runs/41311292752) 验证的 `e03d675568c6ce90f261bf2bc10198386f52f8ec`，event=push、head_branch=master；四个必需 job 均 completed / success。该发布在 SDK 解析阶段失败，下方保留失败与阻断证据。
- [SDK 修正 PR CI 41312989155](https://github.com/vampuke/dining-log/actions/runs/41312989155) 的源码 `b909bf15a6accdac7c5152b7578bc901e8c7bf1a`，四个必需 job 均 completed / success；已通过 PR #7 合并为 `fb1bf5ec451dc653afb435782e6a9c25c775af6e`。七个 SDK 解析 unittest 在 Actions 实际通过，生产签名 / SDK 26/35 / 版本 / 性能断言保留。
- SDK 修正后候选 [master CI 41313553349](https://github.com/vampuke/dining-log/actions/runs/41313553349) 的 head_sha 精确为 `fb1bf5ec451dc653afb435782e6a9c25c775af6e`，event=push、head_branch=master；backend、android、API 26、API 35 四个必需 job 均 completed / success。第二次发布使用这一 SHA；两次失败 run 均未重跑。
- 第二次发布实际完成 signed `assembleRelease`、JVM / lint、固定证书 apksigner v2 验签、package / versionCode=5 / versionName=0.5.0 / SDK 26/35 与 SHA-256 检查。API 26 在安装前的日志清理失败，正式安装及后续生产 / Release 步骤未通过，详见下方失败证据。
- [PR #8 CI 41315422132](https://github.com/vampuke/dining-log/actions/runs/41315422132) 的源码 `b8135322aaadab5e83a337ac2580539ee513e383`，四个必需 job 均 completed / success；已经 PR 合并为 `ad6101ce8666aa4f42df7519dec7230cff87795a`。18 个 Python SDK / 安装脚本测试与 7 个 release guards 全通过；源码安装断言保留，合成 adb 测试不代替正式 APK 的真实安装。
- 最终候选 [master CI 41316085551](https://github.com/vampuke/dining-log/actions/runs/41316085551) 的 head_sha 精确为 `ad6101ce8666aa4f42df7519dec7230cff87795a`，event=push、head_branch=master，四个必需 job 均 completed / success。新 [release 41316626452](https://github.com/vampuke/dining-log/actions/runs/41316626452) 选定这一提交；旧失败 run 均保留。
- 正式 release 41316626452 于 2026-11-01 14:40:42 UTC completed / success；全部发布步骤 success。实际后端候选验证 → signed 构建 / 验签 → API 26/35 安装 → Worker / D1 checkpoint → 迁移兼容性 / 域名冲突检查 → migrations apply → 14 表完整性 → deploy → HTTP 200 → 独立生产 smoke → GitHub Release，顺序和同一源码 SHA 均已核对。
- 实际发布 APK 为 2,541,529 bytes，package=`top.alanvampuke.dininglog`、min SDK=26、target SDK=35、versionCode=5、versionName=0.5.0；v2 与唯一 signer 的固定证书指纹通过。实际下载的 SHA-256=`d77da09cd64b914076693d0bcfc03916a655d6b8cc5a24ce59b73a3e1d43d678`，与 Actions、checksum asset 和 GitHub asset digest 三者一致；`sha256sum --check` 输出 `app-release.apk: OK`。
- Gradle 读取四个 `DINING_SIGNING_*` 变量，release 绑定固定签名并保留 R8 / ProGuard；Actions 分别移除每个变量，四次均按预期报 `Release signing requires environment variables: <NAME>`。debug package 已验证为 `top.alanvampuke.dininglog.debug`，versionCode=5 / versionName=0.5.0，模拟器包名引用已同步。
- 发布 workflow 自身先检查仓库、dispatch master、40 位 SHA、tag、HEAD、master 祖先，再执行候选脚本；生产 secrets 仅注入 release job 的必要步骤。七个轻量 guards 测试通过，真实临时 Git 仓库覆盖未合并分支 / 错误 HEAD / 非法输入 / fork / 非 master 的拒绝，跳过候选检查不能通过。actionlint 与 `git diff --check` 通过。
- 初次 Worker 初始化已有成功操作日志：建立 Worker → stdin 写入四个既有 secrets → 二次 deploy → D1 checkpoint / 迁移 / 完整性 → 无冲突 Domain changeset → 正式域名 deploy。所有初始化阶段完成，无 failed stage；只复核已有 journal。本次随后完成一次日常 release deploy，未重建资源或重写密钥。
- 生产 D1 沿用 dining-log-prod / `90d3c1ef-5ea0-4308-a866-89a96e0411d8`；2026-11-01 再次远程 SQL 核对全部 14 应用表、`foreign_key_check=[]`、`quick_check=ok` 与 `0001_initial.sql` 迁移记录。Cloudflare 元数据 `num_tables=15` 包含迁移记录表。
- 发布后独立 curl 再次取得生产 health HTTP 200 与精确 JSON；初次及本次正式域名 smoke 全链路成功，包含评分历史 40→43、两个同日 visit、删除 / 恢复、refresh、登出及旧 access 401。部署 version `529e6305-536f-4327-9556-26d2d604a192` 与 Wrangler / Cloudflare API 一致。
- `wrangler secret list` 与 API 均显示四个正确名称，不读回值。GitHub production 仅 branch `master` 可部署，五个环境 secret 名称正确，repository secret 数量 0。创建脚本使用环境 public key 与 PyNaCl / libsodium `SealedBox` 加密后 PUT；已有配置未重写。
- 原始 migration SHA-256=`191d1a80a7411ba828effc9041fbd144fa4d10abb372f61c793a8f6dc1a5b80b`；PLAN SHA-256=`8346ddfac5231fb10528b330bcaea170b8925b8b6ed441ccff9e9577b18519f0`，两文件保持原样。既有 key 未重生成；凭据值未进入仓库、汇报或日志。
- 本机未安装 backend 依赖、未运行 Gradle、Docker 或 Vitest。Wrangler 使用已缓存的 lock 对应版本 4.23.0；Android 构建与完整 backend 测试只在 Actions。
- 已核对现有凭据文件均 0600；277 个仓库文件及 46 份本机 Wrangler / npm 操作日志未检出既有凭据值。发布 `release-evidence` 的 14 个文件也经逐文件检查；实际账号凭据、auth 响应、DB export 和 keystore 未进入该 artifact。

## unverified

- 本轮未在独立开发机逐条重做 README 启动步骤；受限 VPS 按要求只运行轻量检查，完整构建 / 测试已由 Actions 完成。
- Worker rollback 与 D1 Time Travel restore 演练、真实 Cron 清理 CPU / 扫描成本、告警、离线加密备份与恢复。Time Travel info 成功不能称 restore 通过。
- 生产免费 Worker 密码 CPU 冷 / 热每场景 ≥30 样本、6,000 / 18,000 数据集的真实 D1 / 公网 p95、跨地域限流、共享额度与实际告警。
- P4 真机弱网 / 切网、三网、TalkBack、真实进程终止、厂商 Keystore、系统大字体 / 输入法；首次发布没有历史 signed APK 可做真实版本升级。
- 既有额外账号生命周期占位接口仍不计入已实现的 29 领域 +9 auth 范围。

## 真实证据

以下是实际 curl、SQL、Cloudflare / GitHub 的非敏感结果；不收录密码、token、恢复码、keystore、账号资料或 DB export。

### 验收分支首次 CI 失败记录

[run 41309979156](https://github.com/vampuke/dining-log/actions/runs/41309979156) 的 API 26 产品测试在注册后的 `empty-state` 断言失败，backend / android job 成功。此 run 未用于合并或发布，未触发新的生产写入。失败截图显示列表视口只容纳前面的输入与按钮，空态属于后续 LazyColumn item。数据就绪不保证视口外的 lazy item 已被组合，注册键盘关闭的窗口调整使该缺口在 API 26 暴露。

修正同一用例：保留原数据等待条件，然后滚动至空态；保留原 assertExists，增加 assertIsDisplayed 与精确正文断言。45 秒超时保持，未删除用例、未 skip、未降低任何断言或性能阈值。修正后的完整 PR / master CI 已通过，旧失败 run 保留。

### 首次 release 的 SDK 解析失败记录

[release 41311895560](https://github.com/vampuke/dining-log/actions/runs/41311895560) 在 `identity(apk)` 报 `APK SDK levels differ from the reviewed configuration`。`assembleRelease`、JVM 与 debug/release lint 已实际成功，日志为 `BUILD SUCCESSFUL in 4m 45s`；但 apksigner、checksum、API 26/35 安装、checkpoint、迁移、deploy、health、smoke 与 Release 创建均因该失败阻断，不能标为通过。此次没有新的生产写入，也没有已发布 tag / APK 被覆盖。

源码检查定位为字段名误识别：脚本仅识别 `sdkVersion:'26'`，相同 build-tools 35 的 [master Android job](https://github.com/vampuke/dining-log/actions/runs/41311292752/job/124862054474) 实际输出为：

```text
package: name='top.alanvampuke.dininglog.debug' versionCode='5' versionName='0.5.0' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
minSdkVersion:'26'
targetSdkVersion:'35'
```

修正只接受两种明确的 min SDK 字段名，仍严格要求单一 min SDK=26、target SDK=35；缺失、错误值、重复或冲突字段全部失败。固定证书 / v2 / package / version / tag 断言保留。新增七个轻量 unittest，包含这份真实输出与拒绝案例，并接入无生产凭据的 CI 和 release 后端候选阶段；未来失败会保留公开 package / SDK badging。未重跑旧 run、未降阈值；PR #7 的完整 CI 已成功，后续候选和发布必须使用修正后的新 SHA。

### 第二次 release 的 API 26 日志清理失败记录

[release 41314095287](https://github.com/vampuke/dining-log/actions/runs/41314095287) 使用 `fb1bf5ec451dc653afb435782e6a9c25c775af6e`。实际构建和验签日志：

```text
BUILD SUCCESSFUL in 4m 40s
Verified using v2 scheme (APK Signature Scheme v2): true
Signer #1 certificate SHA-256 digest: a81806dbcdd4c5a212915f8a6f767b371c96d66cc61b1393d016b77db9ce7093
```

API 26 模拟器成功启动后，`release-install.sh` 在 `adb logcat -c` 报 `failed to clear the 'main' log`，exit=1；APK 的 install / launch 命令尚未执行。API 35、checkpoint、迁移、deploy、health、smoke、Release 创建全部 skipped。Cloudflare 再次只读确认 deployment 仍为 `5f36eb85-ead0-4a09-8d58-f5f8c1aef7ea`，GitHub tag / Release 均不存在；失败未写入生产，未覆盖产物。

修正安装检查直接读取全新、未登录模拟器的 AndroidRuntime 日志，保持启动 Status: ok、三秒观察、进程存活、无 FATAL EXCEPTION 和 reinstall 的所有断言；安装 / 启动早期失败时也保存运行日志。新增轻量脚本测试覆盖两个 SDK、旧版本安装与升级顺序、安装 / 启动 / 进程 / 崩溃 / 日志读取 / 重装失败，以及无法清理日志的 API 26 准备场景；不使用本机 Android SDK 或模拟器。PR #8 和合并后 master 完整 CI 已通过；之后 release 41316626452 也实际通过两个 SDK 的正式安装，合成测试与实际安装证据分别保留。

### 公网 health

2026-11-01 14:41 UTC，正式 release 后再次独立执行：

```sh
curl --fail --silent --show-error --max-time 20 --write-out '\nHTTP %{http_code}\n' https://dining.alanvampuke.top/api/v1/health
```

```text
{"status":"ok","api_version":"v1"}
HTTP 200
```

### 远程 D1 SQL

```sql
SELECT name FROM sqlite_master
WHERE type='table' AND name NOT GLOB 'sqlite_*'
  AND name NOT GLOB '_cf_*' AND name<>'d1_migrations'
ORDER BY name;
PRAGMA foreign_key_check;
PRAGMA quick_check;
SELECT id,name,applied_at FROM d1_migrations ORDER BY id;
```

2026-11-01 14:41 UTC，实际 `wrangler d1 execute DB --remote --command <上述 SQL> --json` 再次成功；结果与发布 artifact 一致，仅整理表清单、完整性与迁移元数据：

```json
{
  "table_count": 14,
  "tables": ["auth_rate_limits","change_sets","dishes","idempotency_keys","mutation_guards","recovery_codes","refresh_rotation_results","refresh_tokens","restaurant_search","restaurants","revisions","sessions","users","visits"],
  "foreign_key_check": [],
  "quick_check": [{"quick_check":"ok"}],
  "migrations": [{"id":1,"name":"0001_initial.sql","applied_at":"2026-11-01 12:57:00"}]
}
```

### Worker、域名、Cron 与 checkpoint

Cloudflare API 复核的 bootstrap deployment / version：

| 阶段 | UTC | deployment ID | version ID |
| --- | --- | --- | --- |
| 首次建立 Worker | 2026-11-01 12:39:43 | 2165ca6b-36f5-4c6b-aac4-e176e85d72dc | 226e30ea-e67f-4406-9cff-c6e2af8e9072 |
| 四次 secret put 后二次 deploy | 2026-11-01 12:56:17 | 3dc33fe8-217c-4063-abd2-15aa5b61a388 | 14fa5683-ae2a-4e1b-8117-4739ee0ddaaa |
| 迁移后正式域名 deploy | 2026-11-01 12:57:27 | 5f36eb85-ead0-4a09-8d58-f5f8c1aef7ea | c9733146-ac71-434b-9dc5-e8ab74063804 |

初次空库实际取得 bookmark `00000009-00000000-00005116-91938ffa68c47fba61e5ad6d64923a7a`，没有忽略 checkpoint 失败。本次只读 `wrangler d1 time-travel info DB --json` 返回 `0000000b-00000000-00005116-8dd76ac2184064b0d234edd88db0f5dc`；bookmark 是恢复位置元数据。

Domain changeset 为 `updated=[]`、`removed=[]`、`conflicting=[]`，只增加 `dining.alanvampuke.top`。绑定 ID `653b2ec07a5f31224037974bc6cd42c291e9909d`，service `dining-log-api`、environment `production`、enabled=true、previews_enabled=false。workers.dev `enabled=false` / `previews_enabled=false`。Cron API 为 `17 * * * *`，配置时间 2026-11-01T12:57:32.618791Z；配置成功不代替真实清理验收。

预检 DNS listing 曾返回 HTTP 403 / code 10000，该查询已停止；未将权限限制当作无记录证据。随后 Worker Domain changeset 检查无冲突，成功部署，公网 TLS / health 实测通过。

### Worker secret list 与 GitHub environment

实际 `wrangler secret list`：

```json
[
  {"name":"JWT_HS256_KEYS","type":"secret_text"},
  {"name":"PASSWORD_PEPPERS","type":"secret_text"},
  {"name":"RATE_LIMIT_HMAC_KEY","type":"secret_text"},
  {"name":"REFRESH_RETRY_KEYS","type":"secret_text"}
]
```

GitHub API 实际复核：

```json
{
  "environment":"production",
  "deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true},
  "branch_policies":[{"name":"master","type":"branch"}],
  "environment_secret_count":5,
  "environment_secret_names":["ANDROID_KEYSTORE_B64","ANDROID_KEY_ALIAS","ANDROID_KEY_PASSWORD","ANDROID_STORE_PASSWORD","CLOUDFLARE_API_TOKEN"],
  "repository_secret_count":0
}
```

### 初次正式域名独立 smoke

`node backend/scripts/smoke-production.mjs` 的真实状态序列：health 200 → register 201 → login 200 → create restaurant / dish / visit×2 均 201 → rating update 200 → history / rating diff 均 200 → delete 200 → deleted hidden 404 → read deleted 200 → restore 200 → restored detail / delete-restore history 均 200 → refresh / session 均 200 → cleanup delete 200 → registration-session logout 204 → logout 204 → old access 401。

```json
{"smoke":"passed","rating_diff":[40,43],"visits":2,"cleanup":"test restaurant soft-deleted; both sessions revoked; synthetic account retained"}
```

这是正式域名真实 auth / D1 / revision 测试；脚本不保存合成账号凭据，测试餐厅最终软删除、两个 session 撤销。合成用户行保留；测试餐厅及关联软删除记录遵循正常 30 天清理策略，用户行不在这项清理范围。

### 正式 signed APK 与 GitHub Release

[release run 41316626452](https://github.com/vampuke/dining-log/actions/runs/41316626452) 全部步骤 success。Release ID `430643980`，发布于 2026-11-01T14:40:38Z；Release target 和真实 tag ref 均为 `ad6101ce8666aa4f42df7519dec7230cff87795a`。只发布 `app-release.apk` 与 `app-release.apk.sha256` 两个 asset，下载后独立检查通过。

实际 aapt2 badging 摘录：

```text
package: name='top.alanvampuke.dininglog' versionCode='5' versionName='0.5.0' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
minSdkVersion:'26'
targetSdkVersion:'35'
```

实际 apksigner / checksum 输出摘录：

```text
BUILD SUCCESSFUL in 4m 47s
Verified using v2 scheme (APK Signature Scheme v2): true
Signer #1 certificate SHA-256 digest: a81806dbcdd4c5a212915f8a6f767b371c96d66cc61b1393d016b77db9ce7093
d77da09cd64b914076693d0bcfc03916a655d6b8cc5a24ce59b73a3e1d43d678  app-release.apk
app-release.apk: OK
```

下载的二进制 manifest 另外由 `android/tools/verify_apk.py` 轻量解析：package=`top.alanvampuke.dininglog`、minSdk=26、targetSdk=35、versionCode=5、versionName=0.5.0、resourceEntries=87、sizeBytes=2541529。此解析没有执行本机 Android 构建。

两个模拟器的 launch artifact 均有 `Status: ok` 和 `Activity: top.alanvampuke.dininglog/.MainActivity`；实际安装脚本完成全部断言后输出：

```text
2026-11-01T14:38:45.5523825Z signed APK installed, launched and reinstalled on API 26
2026-11-01T14:39:53.0283618Z signed APK installed, launched and reinstalled on API 35
```

两个 AndroidRuntime artifact 均无 `FATAL EXCEPTION`。`previous=null` 表明这是首次正式签名发布；同版本 reinstall 仅验证固定证书可接受，历史正式版本升级和真机结果仍为 unverified。

### 正式发布的 checkpoint、部署与完整性

2026-11-01T14:39:55.878Z，发布脚本在任何生产 migration / deploy 前记录：

```json
{
  "empty_database": false,
  "previous_deployment_id": "5f36eb85-ead0-4a09-8d58-f5f8c1aef7ea",
  "previous_version_id": "c9733146-ac71-434b-9dc5-e8ab74063804",
  "time_travel_bookmark": "0000000e-00000000-00005116-a4f6d95b8660253c0ecab5a505f12f8c"
}
```

此处仅整理 `dining-production-checkpoint.json` 的当前回滚目标和 bookmark；原 artifact 同时保留历史 deployment 元数据。没有执行 Worker rollback 或 Time Travel restore。

域名 changeset 检查通过：`added=[]`、`removed=[]`、`conflicting=[]`，唯一 updated 域名为 `dining.alanvampuke.top` 且 `modified=false`。既有 binding ID 保持 `653b2ec07a5f31224037974bc6cd42c291e9909d`。

| 实际生产步骤 | UTC | 结果 |
| --- | --- | --- |
| checkpoint | 14:39:53～14:39:57 | success，有效 bookmark 与上一 Worker version |
| immutable migration / 当前 schema 兼容性 | 14:39:57～14:40:00 | success，已应用的 0001 与本地冻结策略一致 |
| Custom Domain 冲突检查 | 14:40:00～14:40:04 | success，无新增或被覆盖的主机 |
| migrations apply | 14:40:04～14:40:05 | success，既有迁移记录保持 |
| 14 表 / FK / quick_check | 14:40:05～14:40:08 | success，结果与上方 SQL 及发布后再次查询一致 |
| deploy | 14:40:08～14:40:12 | success |
| health | 14:40:12 | HTTP 200，精确 JSON |
| 独立生产 smoke | 14:40:12～14:40:35 | success，完整 auth / 领域 / 历史链路 |
| GitHub Release create | 14:40:35～14:40:38 | success，随后实际下载验证 |

发布后 Cloudflare GET API 再次核对当前 deployment：

```json
{
  "id": "b97427ba-facf-432f-ac13-aa5bcf10676e",
  "created_on": "2026-11-01T14:40:10.394428Z",
  "versions": [{"version_id":"529e6305-536f-4327-9556-26d2d604a192","percentage":100}]
}
```

Wrangler 日志的 `Current Version ID` 与 API 相同。只读复核还确认 workers.dev / previews 均 disabled，Cron 仍为 `17 * * * *`；observability enabled、head sampling=0.1、invocation_logs=false。Cron 配置时间仍是初始化时的 2026-11-01T12:57:32.618791Z；没有将配置存在称为真实清理成本验收。

### 本次正式发布的独立生产 smoke

下面摘自 `dining-production-smoke.txt` 的真实输出；只列固定步骤名和状态码，未保存账号凭据或响应正文：

```text
{"step":"register","method":"POST","status":201}
{"step":"login","method":"POST","status":200}
{"step":"create-restaurant","method":"POST","status":201}
{"step":"update-rating","method":"PATCH","status":200}
{"step":"list-history","method":"GET","status":200}
{"step":"read-rating-diff","method":"GET","status":200}
{"step":"delete-restaurant","method":"DELETE","status":200}
{"step":"deleted-hidden","method":"GET","status":404}
{"step":"restore-restaurant","method":"POST","status":200}
{"step":"read-delete-restore-history","method":"GET","status":200}
{"step":"refresh","method":"POST","status":200}
{"step":"logout","method":"POST","status":204}
{"step":"old-access-rejected","method":"GET","status":401}
{"smoke":"passed","rating_diff":[40,43],"visits":2,"cleanup":"test restaurant soft-deleted; both sessions revoked; synthetic account retained"}
```

同一 artifact 还记录菜品、两个同日 visit、read-deleted / read-restored、refreshed-session 与两个 session 的清理。合成用户行保留；测试餐厅最终软删除，凭据不保存，软删除领域记录按正常保留策略清理。

证据 artifact `release-evidence` ID=`13088205636`，保留期 30 天，共 14 个文件：source selection、APK badging / metadata / signature / checksum、production checkpoint / domain / integrity / health / smoke、两个 SDK 的 launch 与 runtime。GitHub Release 的 APK 和 checksum 是长期分发产物，本文件保留必要的非敏感验收摘录。

## PLAN 12.1 最终验证矩阵

下表更新截至 P5 的验证范围；P0～P4 文档是历史阶段记录，生产缺口以本文件的真实证据更新，未覆盖的门槛继续保留。

| ID | verified 范围 | unverified / 限制 |
| --- | --- | --- |
| UI-01 | P4 与本次 API 26/35 产品测试：实际明暗色、对比度、200% Compose 字体与模拟器 IME | TalkBack、真实手机系统字号与厂商输入法 |
| DB-01 | Actions D1 pool / sqlite3 的约束、跨账号 FK、日期、trigger、失败 batch；生产 14 表、migration、FK / quick_check 与业务 smoke | 远程失败注入 / 并发约束的完整独立矩阵 |
| AUTH-01 | 独立 PBKDF2 / HMAC 向量、10,000 次配置与真实生产注册 / 登录 / refresh | 免费 Worker 冷 / 热 CPU 每场景 ≥30 次、10 ms 门槛 |
| AUTH-02 | Actions D1 全局原子窗口与并发规则；生产 auth 链路可用 | 账户边缘 binding / WAF 套餐、跨地域行为 |
| AUTH-03 | JVM refresh / 丢响应 / 损坏与 API 26/35 Keystore；生产 refresh / logout | 真机杀进程、重装 / 迁移 / 备份、厂商实现 |
| ANDROID-01 | 锁定依赖的 debug / release、Hilt + KSP / Room 编译、JVM / lint；正式 APK 安装见发布证据 | 历史正式版本升级与真实设备差异 |
| DEPLOY-01 | lock 中 Wrangler dry-run、唯一 Custom Domain、实际 TLS / health / smoke、关闭 workers.dev / preview；源码日志不打印凭据或 q | 长期日志运行观察 |
| DEPLOY-02 | 正确 account / zone / D1 已核对，实际 Worker / D1 / environment 操作成功 | 全部最小权限和共享账单 / 用量独立审计；DNS listing 403 已记录 |
| CAPACITY-01 | Actions 4,500/13,500 与 6,000/18,000、每项 30 次、p95≤100 ms、体积及 rows meta，阈值保留 | 远程计费 / p95、每个实体均 10 历史增长变体 |
| NETWORK-01 | 当前 VPS 实际生产 HTTPS、auth / CRUD / 历史 smoke | 大陆移动 / 联通 / 电信样本、稳定公网 p95≤1.5s |
| CI-01 | 实际 PR / master 四个必需 job；发布 guards、正式 release 结果见发布证据 | 独立人类批准无记录；实际真机测试不在 Actions 范围 |
| OPS-01 | Actions 30 天边界、分块中断与账号隔离；生产 Cron 配置、有效 Time Travel bookmark、部署前 Worker version 记录 | 生产清理成本、Worker rollback、隔离库 Time Travel restore、告警 / 离线备份恢复演练 |

历史依据：[P0/P1 对照](P1_ACCEPTANCE.md)、[P1 收尾](P1_FINISH_DONE.md)、[P2](P2_DONE.md)、[P3](P3_DONE.md)、[P4](P4_DONE.md)、[P2 缺口](P2_BLOCKERS.md)、[P3 缺口](P3_BLOCKERS.md)、[P4 真机清单](P4_BLOCKERS.md)。额外账号生命周期 501 接口仍不计入 29 领域 +9 auth 的已实现范围。
