# P5 签名、部署与发布验收

依据：完整阅读的 [P5_TASK.md](design/P5_TASK.md) 与只读 [PLAN 10.5、11.1～11.5](design/PLAN.md)。只将真实执行或复核通过的事项列为 verified；源码实现、模拟器和生产验证分别记录。

P5 实现经 [PR #5](https://github.com/vampuke/dining-log/pull/5) 合并，master 为 `dc8a40a4deeeec49c4be0a464b5b5ba2a267541d`。后续验收分支为 `p5/production-acceptance-0.5.0`：补上 workflow 自身的来源检查，保留现场已有改动并补齐生产证据，不重建 D1、不重新生成或覆盖既有密钥。

## PLAN 10.5 逐条映射

| PLAN 10.5 交付条目 | 对应交付 | verified | unverified |
| --- | --- | --- | --- |
| public repo 完整实现、MIT、README、架构 ADR、OpenAPI、迁移与运行说明 | [public repo](https://github.com/vampuke/dining-log)、LICENSE、README、ADR、OpenAPI、migrations、OPERATIONS.md | public / master 经 API 确认；P5 PR #5 已合并，PR 和 master CI 全部成功；29 领域 +9 auth 实现与既有验收保留 | 本次验收分支的 PR / CI / 合并待追加 |
| 唯一生产 API | https://dining.alanvampuke.top/api/v1；workers.dev / preview_urls=false | 公网 HTTPS health HTTP 200、正文精确匹配；真实完整 smoke；唯一该 Worker 的 Custom Domain | 三网 / 跨地域性能与长期可用性观察 |
| 可安装 signed release APK、SHA-256、版本号、更新说明、GitHub Release | versionCode=5 / versionName=0.5.0、固定签名、release.yml、CHANGELOG 0.5.0 | 签名配置、四种缺变量失败、debug `.debug` 与 API 26/35 产品测试已由 Actions 验证；发布与验签脚本已复核 | 实际 signed APK、正式验签 / API 26/35 安装 / GitHub Release 待 release Actions；debug 成功不代替这些事项 |
| 正式 Cloudflare 配置、secret 名称、日志 / Cron / Time Travel | wrangler.toml、production environment、OPERATIONS.md、生产检查脚本 | 四个 Worker secrets；五个环境 secrets 与 master policy；Cron、observability 配置；有效 bookmark；D1 14 应用表 / migration / 完整性 | 实际 Cron 清理成本、告警、Worker rollback / D1 restore、离线备份恢复 |
| README 本地 / 测试 / 签名 / 部署 / 回滚步骤 | README、OPERATIONS.md、固定命令与依赖锁 | Actions 后端检查 / 测试 / DB verify / dry-run、Android JVM / lint / 模拟器；bootstrap 实际完成；VPS 仅轻量核验 | 正式签名发布待 Actions；本轮未在开发机重做本地启动，未执行生产回滚 / D1 restore |
| P0～P5 验收与仍需验证最终状态 | P1～P4_DONE、既有 BLOCKERS、本文件 | 保留既有 CI 证据，新增真实生产证据；各类证据范围明确 | 下方列出的真机、生产性能、恢复与首次发布升级缺项 |

## verified

- [P5 PR CI](https://github.com/vampuke/dining-log/actions/runs/41306167734) 与 [合并后 master CI](https://github.com/vampuke/dining-log/actions/runs/41307015620) 均 completed / success；backend、android、android-auth-instrumentation (26)、android-auth-instrumentation (35) 四个必需 job 均 success。backend 的 `npm ci → check → test → db:verify → wrangler deploy --dry-run` 实际执行通过。
- Gradle 读取四个 `DINING_SIGNING_*` 变量，release 绑定固定签名并保留 R8 / ProGuard；Actions 分别移除每个变量，四次均按预期报 `Release signing requires environment variables: <NAME>`。debug package 已验证为 `top.alanvampuke.dininglog.debug`，versionCode=5 / versionName=0.5.0，模拟器包名引用已同步。
- 发布 workflow 自身先检查仓库、dispatch master、40 位 SHA、tag、HEAD、master 祖先，再执行候选脚本；生产 secrets 仅注入 release job 的必要步骤。七个轻量 guards 测试通过，真实临时 Git 仓库覆盖未合并分支 / 错误 HEAD / 非法输入 / fork / 非 master 的拒绝，跳过候选检查不能通过。actionlint 与 `git diff --check` 通过。
- 初次 Worker 初始化已有成功操作日志：建立 Worker → stdin 写入四个既有 secrets → 二次 deploy → D1 checkpoint / 迁移 / 完整性 → 无冲突 Domain changeset → 正式域名 deploy。所有阶段完成，无 failed stage；本次只读复核，不重复初始化。
- 生产 D1 沿用 dining-log-prod / `90d3c1ef-5ea0-4308-a866-89a96e0411d8`；2026-11-01 再次远程 SQL 核对全部 14 应用表、`foreign_key_check=[]`、`quick_check=ok` 与 `0001_initial.sql` 迁移记录。Cloudflare 元数据 `num_tables=15` 包含迁移记录表。
- 实际 curl 再次取得生产 health HTTP 200 与精确 JSON；初次正式域名 smoke 全链路成功，包含评分历史 40→43、两个同日 visit、删除 / 恢复、refresh、登出及旧 access 401。
- `wrangler secret list` 与 API 均显示四个正确名称，不读回值。GitHub production 仅 branch `master` 可部署，五个环境 secret 名称正确，repository secret 数量 0。创建脚本使用环境 public key 与 PyNaCl / libsodium `SealedBox` 加密后 PUT；已有配置未重写。
- 原始 migration SHA-256=`191d1a80a7411ba828effc9041fbd144fa4d10abb372f61c793a8f6dc1a5b80b`；PLAN SHA-256=`8346ddfac5231fb10528b330bcaea170b8925b8b6ed441ccff9e9577b18519f0`，两文件保持原样。既有 key 未重生成；凭据值未进入仓库、汇报或日志。
- 本机未安装 backend 依赖、未运行 Gradle、Docker 或 Vitest。Wrangler 使用已缓存的 lock 对应版本 4.23.0；Android 构建与完整 backend 测试只在 Actions。

## unverified

- 本次验收分支的 PR / CI / 合并；实际 release workflow、signed APK 验签、API 26/35 安装与 GitHub Release，等待真实结果再更新。
- Worker rollback 与 D1 Time Travel restore 演练、真实 Cron 清理 CPU / 扫描成本、告警、离线加密备份与恢复。Time Travel info 成功不能称 restore 通过。
- 生产免费 Worker 密码 CPU 冷 / 热每场景 ≥30 样本、6,000 / 18,000 数据集的真实 D1 / 公网 p95、跨地域限流、共享额度与实际告警。
- P4 真机弱网 / 切网、三网、TalkBack、真实进程终止、厂商 Keystore、系统大字体 / 输入法；首次发布没有历史 signed APK 可做真实版本升级。
- 既有额外账号生命周期占位接口仍不计入已实现的 29 领域 +9 auth 范围。

## 真实证据

以下是实际 curl、SQL、Cloudflare / GitHub 的非敏感结果；不收录密码、token、恢复码、keystore、账号资料或 DB export。

### 验收分支首次 CI 失败记录

[run 41309979156](https://github.com/vampuke/dining-log/actions/runs/41309979156) 的 API 26 产品测试在注册后的 `empty-state` 断言失败，backend / android job 成功。此 run 未用于合并或发布，未触发新的生产写入。失败截图显示列表视口只容纳前面的输入与按钮，空态属于后续 LazyColumn item。数据就绪不保证视口外的 lazy item 已被组合，注册键盘关闭的窗口调整使该缺口在 API 26 暴露。

修正同一用例：保留原数据等待条件，然后滚动至空态；保留原 assertExists，增加 assertIsDisplayed 与精确正文断言。45 秒超时保持，未删除用例、未 skip、未降低任何断言或性能阈值。修正后的新提交须由完整 Actions 验证，不能将失败 run 重标为成功。

### 公网 health

2026-11-01 再次执行：

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

实际 `wrangler d1 execute DB --remote --command <上述 SQL> --json` 成功结果，仅整理表清单、完整性与迁移元数据：

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

这是正式域名真实 auth / D1 / revision 测试；合成账号资料不保存，测试餐厅最终软删除、两个 session 撤销，保留的数据遵循正常 30 天清理策略。
