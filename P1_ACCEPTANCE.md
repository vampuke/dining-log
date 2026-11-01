# P1 验收对照

验收范围：design/P1_FINISH_TASK.md 的容量测试与 debug APK 分发收尾；方案 design/PLAN.md 只读。下面逐条区分 CI 模拟器、实际下载产物与未完成的远端 / 真机验证。

本次实测 / APK 来源：[CI run 41275936653](https://github.com/vampuke/dining-log/actions/runs/41275936653)，commit `6cce7953c17c71c2367e6f33a0ae4a5c73c28a64`，GitHub API 返回 `status=completed, conclusion=success`，backend 与 android 两个 job 均 `success`。
基线 [CI run 41270748412](https://github.com/vampuke/dining-log/actions/runs/41270748412) 已通过；本轮沿用原有迁移与基础测试。

## PLAN.md 10.1：P0 六条门槛

| 条目 | 状态 | 证据与缺口 |
| --- | --- | --- |
| 1. Compose + Hilt/KSP + Room + Retrofit debug / release 版本链 | **未验证**（完整门槛） | CI 执行 `./gradlew :app:assembleDebug :core:test :app:testDebugUnitTest :app:lintDebug --write-locks --no-daemon`，实际 `BUILD SUCCESSFUL in 1m 7s`；4 模块与固定 JDK 17 / AGP 8.9.2 / Kotlin 2.1.21 / Gradle 8.11.1 已编译。release 未构建，release workflow 仍为 P5 占位；Room / Keystore 业务链未实现。 |
| 2. PBKDF2 10,000 次标准向量及真实免费 Worker 冷 / 热 CPU | **未验证**（真实 CPU） | CI `npm test` 输出 `PASS: RFC 7914 and 4 independent PBKDF2 + HMAC fixtures (3 at 10000 iterations, 1 at 12000)`，74 个原有 Vitest 用例全通过，标准 JS 路线 / pepper 验证已有证据。未部署 Worker，未做 1k / 10k 冷热各 ≥30 次 CPU / wall；不能宣称满足免费档 10 ms CPU。 |
| 3. 真实 D1 约束、trigger、JSON、日期、0 行 CAS batch 回滚 | **未验证**（真实平台） | GitHub Actions 的 Workers D1 pool 与 sqlite3 CLI 已通过。`bash tests/sql/verify.sh` 实际 `14\|30\|1`、46 次预期拒绝、FK 无异常、`quick_check=ok`；`npm test` 含 migrations 的 D1 batch 回滚用例。真实 Cloudflare D1 未创建 / 验证，不能把本地 pool 当远端。 |
| 4. 无图片新视觉全页面原型、配色与大字体 | **未验证**（完整门槛） | 已有 `design/proto/d2-quiet-grid.html` 与 `design/proto/DONE.md` 的明暗 5 画板 / 滑杆 / 搜索记录；本轮只读引用，没有重跑原型。原生认证 / 全页面、200% 字体、TalkBack、API 26/35 真机仍未验收。 |
| 5. 已知账号 / zone、单子域名绑定、限定凭据与免费档 | **未验证** | 本轮仅调用 GitHub API，未调用 Cloudflare API、创建资源、写 secret 或部署；正式 D1 ID 仍是占位值。真实云权限、套餐、DNS/TLS/域名绑定须在获准的后续阶段验证。 |
| 6. 合成数据、临时资源清理与共享额度边界 | **已验证**（本次 CI 范围） | 固定 seed 的合成 user / 餐厅 / 菜品 / 到店 / 历史在两个独立 Workers-pool 进程生成，开始前断言全部 14 张应用表为 0 行；初始化 batch ≤40 SQL。CI 临时存储随 runner / pool 结束释放，本轮没有远端资源或真实账号额度消耗。真实临时 D1 的创建 / 清理与共享账户用量未验证；报告按实测写行数明确全量初始化所需额度日。 |

P0 的真实 CPU、真实 D1、云权限及完整 Android / 可访问性门槛仍有缺口，本轮收尾完成不构成 P0 全部通过声明。

## PLAN.md 10：P1 验收标准与交付物

| 条目 | 状态 | 命令 / 实际证据 |
| --- | --- | --- |
| minSdk 26 / targetSdk 35 编译 | **已验证** | 上述 CI debug 构建 / lint 成功；`aapt2 dump badging app-debug.apk` 实际 `minSdkVersion:'26'`、`targetSdkVersion:'35'`；本机解析下载 APK 的二进制 manifest 得到同样 SDK。release / 安装属于未验证项。 |
| 本地 migration 与完整性检查 | **已验证** | GitHub Actions 执行 `npm run check`、`npm test`、`bash tests/sql/verify.sh` 均成功；74 个原有用例通过，SQL CLI 14 表 / 30 显式索引 / 1 trigger / 46 次预期拒绝，`foreign_key_check` 无输出、`quick_check=ok`。两组容量数据写入后同样 FK=0、quick_check=ok。原 migration SHA-256 `191d1a80a7411ba828effc9041fbd144fa4d10abb372f61c793a8f6dc1a5b80b` 未变。 |
| 独立包名、库名与 secret 绑定声明 | **已验证**（文件 / 成品范围） | APK 成品包名精确为 `top.alanvampuke.dininglog`。`backend/wrangler.toml` 声明 `dining-log-api` / `dining-log-prod` / `DB`；`backend/src/env.ts` 声明独立 JWT / pepper / refresh / rate-limit key 绑定，无真实值；CI `permissions.contents=read`，构建与测试不需要生产 secret。 |
| 正式数据库及真实 secret 隔离 | **未验证** | `database_id=REPLACE_WITH_CREATED_D1_DATABASE_ID`，本轮未创建生产库或写入真实 secret；P2/P5 前需验证真正资源隔离。 |
| 4,500 业务对象容量可重现 | **已验证** | `npm run test:capacity` 的 p1-4500 场景：1 user、500 restaurants、2,500 dishes、1,500 visits、13,500 revisions / change_sets；每家餐厅 11 条历史。固定 seed / 数据 SHA-256、9 种查询各 30 个原始样本、存储和写入 D1 meta 见 `backend/tests/capacity/RESULTS.md` / `measurements.json`。 |
| PLAN 8.6 / 10.3.14 的 6,000 / 18,000 SQL 容量目标 | **已验证**（CI SQL） | 500 restaurants、4,000 dishes、1,500 visits、18,000 revisions / change_sets；每家餐厅 14 条历史；9 种查询各 30 次，wall / SQL p95 均 ≤100 ms，断言未放宽。真实 Worker CPU 与端到端 ≤1.5 秒尚未验证。 |
| public 新仓库、README / MIT、Android 4 模块、Hono / OpenAPI 初稿与无 secret CI | **已验证**（P1 骨架范围） | GitHub run 的 `repository.private=false`；`LICENSE` / `README.md` / `android/settings.gradle.kts` / `backend/src/index.ts` / `contracts/openapi.yaml` 已入库，`app/core/data/feature` 编译通过。CI health 响应为 HTTP 200、`{"status":"ok","api_version":"v1"}`，其余契约业务路由仍为 501；P2/P3 实际业务没有被声明完成。 |
| debug APK 已从 CI 下载并发布 | **已验证** | GitHub API 获取最近成功 master run 的 `app-debug` artifact，核对其 commit；下载 / 解压后 SHA-256、MD5、字节大小均与 CI 原件一致，DEX / manifest / res 与包名 / SDK 实际验证。见下节。 |

## 容量核心实测数字

| 数据集 | 完整数据库 bytes | 最大 wall p95 ms | 最大 SQL p95 ms | 最贵查询 rows_read | 完整 batch rows_written | batch wall ms | 初始化 rows_written |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 4500 / 13500 | 196009984 | 15.000 | 10.000 | 1000 | 143 | 25.000 | 194003 |
| 6000 / 18000 | 255528960 | 27.000 | 12.000 | 1000 | 143 | 8.000 | 258503 |

完整体积来自 D1 `meta.size_after`，文本有效载荷另逐表使用 `length(CAST(... AS BLOB))` 统计。D1 不允许的 dbstat / sqlite_version 或内部表元数据明确记录原因；没有用字符长度、估算或零值墙钟冒充真实数据库字节 / 耗时。
容量原始 artifact：[13071268395](https://github.com/vampuke/dining-log/actions/runs/41275936653/artifacts/13071268395)；生成报告和原始样本已归档到仓库，artifact 到期不影响固定 seed 复跑。

## APK 产物与实际验证

- CI run：41275936653；artifact `app-debug` id=13070619660；源码 commit `6cce7953c17c71c2367e6f33a0ae4a5c73c28a64`。
- 版本文件：`/var/www/html/dl/dining/shiji-6cce795.apk`；最新入口：`/var/www/html/dl/dining/shiji-debug.apk`（相对软链指向版本文件）。
- 大小：**11360277 bytes**；SHA-256：`aad2404cdd28f04f4c7be67110440ed540fe30897d9d08f3990dc65533ea9422`；MD5：`0a86b65ebe4d6749ea4eb9e2b8e58305`。
- `/var/www/html/dl/dining/SHA256SUMS` 与 `MD5SUMS` 记录版本文件和最新入口；`APK_METADATA.json`、`APK_BADGING.txt`、`APK_SIGNATURE.txt` 保存来源与校验输出。

GitHub API（认证从 `~/.git-credentials` 读入内存，不打印 token）：

```text
GET /repos/vampuke/dining-log/actions/workflows/ci.yml/runs?branch=master&status=success
GET /repos/vampuke/dining-log/actions/runs/41275936653 -> status=completed, conclusion=success
GET /repos/vampuke/dining-log/actions/runs/41275936653/artifacts -> app-debug id=13070619660
GET /repos/vampuke/dining-log/actions/artifacts/13070619660/zip -> 下载并按单个 APK entry 流式解压
```

CI 执行原生工具（VPS 没有运行构建）：

```text
$ "$ANDROID_HOME/build-tools/35.0.0/aapt2" dump badging app-debug.apk
package: name='top.alanvampuke.dininglog' versionCode='1' versionName='0.1.0' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
minSdkVersion:'26'
targetSdkVersion:'35'
$ "$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose app-debug.apk
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
```

VPS 已执行的产物核对：

```text
$ unzip -l /var/www/html/dl/dining/shiji-6cce795.apk
 17834904  1981-01-01 01:01   classes.dex
     6128  1981-01-01 01:01   AndroidManifest.xml
      364  1981-01-01 01:01   res/anim-v21/fragment_fast_out_extra_slow_in.xml
     1128  1981-01-01 01:01   res/animator/fragment_close_enter.xml
     1128  1981-01-01 01:01   res/animator/fragment_close_exit.xml
res/ entry 数量：87
二进制 AndroidManifest.xml 解析: {"packageName": "top.alanvampuke.dininglog", "minSdk": 26, "targetSdk": 35, "versionName": "0.1.0", "versionCode": 1}
$ stat -Lc %s /var/www/html/dl/dining/shiji-debug.apk
11360277
$ sha256sum /var/www/html/dl/dining/shiji-debug.apk
aad2404cdd28f04f4c7be67110440ed540fe30897d9d08f3990dc65533ea9422  /var/www/html/dl/dining/shiji-debug.apk
$ md5sum /var/www/html/dl/dining/shiji-debug.apk
0a86b65ebe4d6749ea4eb9e2b8e58305  /var/www/html/dl/dining/shiji-debug.apk
$ curl --head https://ora.vampuck.com/dl/dining/shiji-debug.apk
HTTP/2 200
server: nginx
date: Sun, 01 Nov 2026 05:18:02 GMT
content-type: application/octet-stream
content-length: 11360277
last-modified: Sun, 01 Nov 2026 05:17:06 GMT
etag: "6ae6cb52-ad5815"
strict-transport-security: max-age=31536000
accept-ranges: bytes
```

debug APK 成品已核对和分发；安装、API 26/35 真机运行、release 签名 / 原签名升级与全部业务端到端未验证。静态分发沿用现有 VPS 服务，没有部署 Cloudflare 或修改云配置。

## 本次完成判据

1. CI 含容量测试 step 全绿：run 41275936653，backend / android 均 success。
2. RESULTS.md 来自 CI 实测自动报告，measurements.json 保留原始样本与数据指纹。
3. shiji-debug.apk 存在，大小 / SHA-256 / MD5 与下载的 CI APK 原件一致，SHA256SUMS 已写入。
4. P0 六条与 P1 验收逐条标注，未验证项及阶段缺口如上。
