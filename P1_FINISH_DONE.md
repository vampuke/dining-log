# P1 收尾完成记录

本次两项遗留已完成：可重现容量测试进入 CI、真实结果入库，debug APK 从成功 CI 下载并发布；验收详见 P1_ACCEPTANCE.md。

## 修改文件

| 文件 | 改动 |
| --- | --- |
| `.github/workflows/ci.yml` | 容量 step、原始报告 artifact、成品 APK 包名 / SDK / 签名 / 校验和与 app-debug artifact。 |
| `.gitignore` | 忽略 CI 容量 output 目录。 |
| `README.md` | 容量重现入口、真实结果链接、APK artifact / 发布路径和准确包名。 |
| `android/app/build.gradle.kts` | 移除 debug 的 .debug 后缀，使成品符合指定包名。 |
| `android/tools/verify_apk.py` | 读取实际 DEX / res / 二进制 manifest，精确断言包名及 minSdk=26 / targetSdk=35，无第三方依赖。 |
| `backend/package.json` | 容量命令分别启动两组独立 Workers-pool 进程。 |
| `backend/tsconfig.json` | 把容量 Vitest 配置纳入现有 CI 类型检查。 |
| `backend/vitest.capacity.config.ts` | 容量专用配置 / 超时；独立进程存储隔离，避开大样本快照回滚。 |
| `backend/tests/capacity/capacity_test.ts` | 确定性数据、两组精确行数、30 次 SQL、索引计划、存储、完整写 batch 与原始证据；100 ms 断言。 |
| `backend/tests/capacity/report.mjs` | 从真实 stdout 生成报告和原始 JSON，比较四项免费额度。 |
| `backend/tests/capacity/RESULTS.md` | 从 GitHub Actions artifact 归档的实际结果。 |
| `backend/tests/capacity/measurements.json` | 原始样本 / SQL / 参数 / 数据与测试脚本 SHA-256 / CI 版本证据。 |
| `P1_ACCEPTANCE.md` | 逐条对照 P0 / P1，记录实际 APK 和未验证项。 |
| `P1_FINISH_DONE.md` | 本次收尾完成与证据索引。 |

## CI run 与结论

- 实测 / APK 来源：[41275936653](https://github.com/vampuke/dining-log/actions/runs/41275936653)，commit `6cce7953c17c71c2367e6f33a0ae4a5c73c28a64`，API `status=completed, conclusion=success`。
- 原有类型 / lint / 74 个测试 / SQL 迁移检查通过；新增两组容量测试各 9 类查询 ×30 次、完整写 batch 与完整性断言通过；Android 构建 / core 单测 / lint、原生 APK 身份及签名核对通过。
- `:app:testDebugUnitTest` 仍为 `NO-SOURCE`，没有夸大为 app 层业务用例通过。
- 失败尝试均保留：41274977568（D1 内部表结构权限）；41275108783（D1 sqlite_version 权限、runner 未装 rg）；41275309293（池的 stacked-storage 大数据库回滚断连）；41275567371（容量全绿，aapt2 输出 minSdkVersion 导致旧 SDK 文本匹配失败）。已按日志修复根因，测试目标仍为 100 ms，没有降低断言、删测试或改 migration。

## RESULTS.md 核心实测数字

| 对象 / 历史 | 数据库 bytes | 最大 wall p95 ms | 最大 SQL p95 ms | 最大 rows_read | batch rows_written | batch wall ms | 初始化 rows_written |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 4500 / 13500 | 196009984 | 15.000 | 10.000 | 1000 | 143 | 25.000 | 194003 |
| 6000 / 18000 | 255528960 | 27.000 | 12.000 | 1000 | 143 | 8.000 | 258503 |

D1 免费额度按 PLAN.md 8.5：5M 读行/日、100K 写行/日、5GB 总存储、单库 500MB。完整体积与常用操作额度比较、初始化所需额度日、各表行数 / UTF-8 bytes / 每种 p50/p95 均在 RESULTS.md；这是 GitHub Actions 中的本地 D1 模拟器实测。

## APK 路径、大小和摘要

- 文件：`/var/www/html/dl/dining/shiji-6cce795.apk`。
- 最新入口：`/var/www/html/dl/dining/shiji-debug.apk`。
- 大小：**11360277 bytes**。
- SHA-256：`aad2404cdd28f04f4c7be67110440ed540fe30897d9d08f3990dc65533ea9422`。
- MD5：`0a86b65ebe4d6749ea4eb9e2b8e58305`。
- 校验文件：`/var/www/html/dl/dining/SHA256SUMS`；来源 / manifest：同目录 `APK_METADATA.json`。
- 验证：classes.dex / AndroidManifest.xml / 87 个 res entry 存在；包名 `top.alanvampuke.dininglog`、minSdk 26、targetSdk 35；下载件 SHA-256 / MD5 / bytes 与 CI 原件一致。
- 下载：`https://ora.vampuck.com/dl/dining/shiji-debug.apk`，实际 HTTP 响应和 Content-Length 见 P1_ACCEPTANCE.md。

## 未完成项与边界

- 本次 P1_FINISH_TASK.md 的两项收尾与四项完成判据：无未完成项。
- P0 / 后续阶段仍未验证：真实免费 Worker 冷热 CPU、真实 Cloudflare D1、账号 / zone / 真实 secret / 套餐、release 构建与正式签名、真机安装 / UI / 大字体 / TalkBack、实际业务 CRUD / 国内网络 / 端到端延迟。
- PLAN.md CAPACITY-01 的“每个实体均 10 条历史”增长变体尚未测；本轮只要求每家餐厅 ≥10 条，已测 11 / 14 条。
- 本机仅做文件 / Git / GitHub API / 下载与 APK 核对，没有执行 npm install / npm ci / Gradle / Docker / Vitest，所有构建与容量 / 业务测试执行在 GitHub Actions。没有部署 Cloudflare、创建远端资源或提交真实 secret。
- `design/P1_FINISH_TASK.md` 与 `design/run_p1_finish.sh` 是原有未跟踪的用户任务文件；保留原样。方案、migration 与已通过的基础测试保持原内容。
