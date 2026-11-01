# 生产运维

唯一入口 `https://dining.alanvampuke.top/api/v1`；Worker `dining-log-api`；D1 `dining-log-prod` / `90d3c1ef-5ea0-4308-a866-89a96e0411d8`。Account `49eee8b8033579863adb3b7cf0b1852c`，zone `alanvampuke.top` / `e046faddc69173d411003bb43f93ea83`。这些是资源标识，不是凭据。

## Secret 名称与保管

| 名称 | 位置 / 用途 |
| --- | --- |
| JWT_HS256_KEYS | Worker secret；版本到 HS256 key 的 JSON |
| PASSWORD_PEPPERS | Worker secret；版本到独立密码 pepper 的 JSON |
| REFRESH_RETRY_KEYS | Worker secret；版本到 refresh 短期 AES-256 key 的 JSON |
| RATE_LIMIT_HMAC_KEY | Worker secret；独立账号 / IP 限流 HMAC key |
| ANDROID_KEYSTORE_B64 | GitHub production environment；已有 release keystore |
| ANDROID_STORE_PASSWORD | 同 environment；keystore 密码 |
| ANDROID_KEY_PASSWORD | 同 environment；key 密码 |
| ANDROID_KEY_ALIAS | 同 environment；固定 alias `dining-log` |
| CLOUDFLARE_API_TOKEN | 同 environment；限定账号 / zone 的发布权限 |

现有生产材料在受控的 `/root/.dining_secrets/worker_secrets.json`、`/root/release_key/`、`/root/.cloudflare_env`，权限 0600。不要重新生成 key、复制到仓库、输出值或把它们放进 command arguments / shell tracing。签名材料保留离线加密备份；备份完成与恢复演练若无实证仍记为 unverified。

GitHub 环境配置为 custom deployment branch policy，只允许名称 `master`、类型 `branch`；repository secrets 保持为空。使用环境 public-key endpoint 与 PyNaCl `SealedBox(PublicKey(...))` 加密后 PUT 每个 secret；验证 API 只返回名称和更新时间，不能读回明文。后续修改凭据必须保留版本兼容性，不能自动清空旧 JWT / pepper。

## 首次 Worker 初始化

生产 Worker、四个 secrets、D1 迁移和 Custom Domain 已在 2026-11-01 完成初始化；真实证据见 [P5_DONE.md](../P5_DONE.md)。以下步骤用于解释首次初始化顺序，现有生产环境应使用日常 release 流程，不能重复创建资源或覆盖密钥。

本 VPS 不安装 backend 依赖。先从**相同 master SHA、全部通过的 CI run** 下载 `worker-candidate` artifact，核对 `SOURCE_COMMIT` 与 `SHA256SUMS`；它由 lock 中 Wrangler 的真实 `deploy --dry-run --outdir build/worker` 生成。已缓存 Wrangler 4.23.0 可直接调用 `node /root/.npm/_npx/14ed28167b94b12b/node_modules/wrangler/bin/wrangler.js`；缓存路径改变时查 package.json 的版本，不能换浮动版本。

准备外部临时目录，设置 `DINING_WORKER_BUNDLE` 为验证后的 `index.js` 绝对路径、`DINING_OPS_TEMP` 为仓库外的 0700 临时目录、`DINING_WRANGLER_SCRIPT` 为上述已核实版本入口。凭据仅导出到本次进程环境：

```sh
set -a
. /root/.cloudflare_env
set +a
export WRANGLER_SEND_METRICS=false
```

开始前只读检查 Worker 不存在、目标域名没有其他 Worker 绑定、D1 为空、原始 migration SHA 正确。当前 token 的 DNS listing 返回 403（错误码 10000）；zone / Worker / D1 查询可用，公开 DNS 查询只能确认解析。绑定前必须用 Worker Domain changeset API 检查 DNS / origin 冲突，不能把 DNS listing 权限限制当作不存在记录的证据。生成**仓库外** bootstrap config，将 routes 设为空并关闭 Cron；main / migration 路径指向已核实文件，保留原 account / D1 / vars 配置：

```sh
python3 - <<'PY'
import os, pathlib, re
root = pathlib.Path('/root/dining_log')
config = (root / 'backend/wrangler.toml').read_text()
config = re.sub(r'routes = \[.*?\]', 'routes = []', config, count=1, flags=re.S)
config = config.replace('crons = ["17 * * * *"]', 'crons = []')
config = config.replace('main = "src/index.ts"', 'main = "' + os.environ['DINING_WORKER_BUNDLE'] + '"')
config = config.replace('migrations_dir = "migrations"', 'migrations_dir = "' + str(root / 'backend/migrations') + '"')
path = pathlib.Path(os.environ['DINING_OPS_TEMP']) / 'bootstrap.toml'
path.write_text(config)
path.chmod(0o600)
PY
node "$DINING_WRANGLER_SCRIPT" deploy "$DINING_WORKER_BUNDLE" --no-bundle --config "$DINING_OPS_TEMP/bootstrap.toml"
```

第一次 deploy 只建立 Worker，不公开域名、不启用空库 Cron；检查 Cloudflare settings / deployment 存在。然后四个 secrets 逐个通过 stdin 写入，每个响应成功才继续：

```sh
python3 - <<'PY'
import json, os, pathlib, subprocess
values = json.loads(pathlib.Path('/root/.dining_secrets/worker_secrets.json').read_text())
names = ['JWT_HS256_KEYS', 'PASSWORD_PEPPERS', 'REFRESH_RETRY_KEYS', 'RATE_LIMIT_HMAC_KEY']
for name in names:
    result = subprocess.run(['node', os.environ['DINING_WRANGLER_SCRIPT'], 'secret', 'put', name,
                             '--config', os.environ['DINING_OPS_TEMP'] + '/bootstrap.toml'],
                            input=values[name] + '\n', text=True, capture_output=True)
    if result.returncode:
        raise SystemExit('secret put failed: ' + name + '; stopped; inspect redacted diagnostics')
    print('secret put success: ' + name)
PY
node "$DINING_WRANGLER_SCRIPT" secret list --config "$DINING_OPS_TEMP/bootstrap.toml"
node "$DINING_WRANGLER_SCRIPT" deploy "$DINING_WORKER_BUNDLE" --no-bundle --config "$DINING_OPS_TEMP/bootstrap.toml"
```

必须确认四个名称全部存在。重新 deploy 成功后记录当前 deployment/version 和 Time Travel：

```sh
cd /root/dining_log/backend
node "$DINING_WRANGLER_SCRIPT" deployments list
node "$DINING_WRANGLER_SCRIPT" d1 time-travel info DB --json
node "$DINING_WRANGLER_SCRIPT" d1 migrations apply DB --remote
node "$DINING_WRANGLER_SCRIPT" d1 execute DB --remote --command "PRAGMA foreign_key_check; PRAGMA quick_check; SELECT id,name,applied_at FROM d1_migrations ORDER BY id;" --json
node "$DINING_WRANGLER_SCRIPT" d1 execute DB --remote --command "SELECT name FROM sqlite_master WHERE type='table' AND name NOT GLOB 'sqlite_*' AND name NOT GLOB '_cf_*' AND name<>'d1_migrations' ORDER BY name;" --json
```

初次空库只有在已确认无应用表、且 provider 明确报告无 history / bookmark 时，才记录 `no-history` 并继续；网络 / 权限 / 参数错误均停止。成功标准为 14 张应用表、0001 migration 记录、foreign_key_check 空、quick_check 唯一 `ok`。系统表与 d1_migrations 不计入 14。

迁移验证后，先调用 `POST /accounts/{account_id}/workers/scripts/dining-log-api/domains/changeset?replace_state=true`，请求为 `[{"hostname":"dining.alanvampuke.top","zone_id":"e046faddc69173d411003bb43f93ea83"}]`。这是差异预览，无配置写入；要求 `conflicting=[]`、`updated` 中每项 `modified=false`，同时检查 account Worker Domain 列表中该主机未绑定其他 Worker，当前 Worker 未绑定其他主机。Wrangler 4.23.0 在非 TTY 模式会自动启用 overwrite flags，因此缺少此检查就不能继续 deploy。日常 release 已由 `production-d1.mjs domain` 执行并保留证据。

```sh
python3 - <<'PY'
import json, os, urllib.request
base = 'https://api.cloudflare.com/client/v4/accounts/49eee8b8033579863adb3b7cf0b1852c'
headers = {'Authorization': 'Bearer ' + os.environ['CLOUDFLARE_API_TOKEN'],
           'Content-Type': 'application/json'}
def query(path, body=None):
    request = urllib.request.Request(base + path, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    with urllib.request.urlopen(request, timeout=20) as response:
        result = json.load(response)
    assert result['success'] is True
    return result['result']
domains = query('/workers/domains')
assert all((d['hostname'] != 'dining.alanvampuke.top' or d['service'] == 'dining-log-api')
           and (d['service'] != 'dining-log-api' or d['hostname'] == 'dining.alanvampuke.top')
           for d in domains)
changes = query('/workers/scripts/dining-log-api/domains/changeset?replace_state=true',
    [{'hostname': 'dining.alanvampuke.top', 'zone_id': 'e046faddc69173d411003bb43f93ea83'}])
assert changes['conflicting'] == []
assert all(d['modified'] is False for d in changes['updated'])
print(json.dumps({'domain_conflict_check': 'passed', 'changeset': changes}))
PY
```

通过冲突检查后按正式 config 再 deploy 同一 bundle，它才绑定裸主机 Custom Domain 并启用 `17 * * * *` Cron：

```sh
node "$DINING_WRANGLER_SCRIPT" deploy "$DINING_WORKER_BUNDLE" --no-bundle --config wrangler.toml
curl --fail --silent --show-error --max-time 20 https://dining.alanvampuke.top/api/v1/health
node scripts/smoke-production.mjs
unset CLOUDFLARE_API_TOKEN
```

Cloudflare 自动管理目标 DNS / TLS，不手工创建 A / AAAA 或假源站。再核对 domain API、workers.dev / preview disabled、secret list、D1 完整性。任何生产写入或验证失败立即停止、保存非敏感证据并报告；不得盲目重新 deploy、重写 secrets、恢复 D1 或删除已有 Release。

## 日常发布

按 [README](../README.md#生产部署与发布) 从 master dispatch release。禁止并行执行其他部署绕过 `dining-production`；发布只在相同 SHA 的候选 CI 与两级模拟器全部成功后进行。

`release-evidence` 保存部署前 Worker deployment/version IDs、有效 Time Travel bookmark（空库仅接受 provider 明确报告无历史）、域名冲突检查、应用后的 SQL 完整性、health、固定 smoke 步骤标签、APK 公开 package / SDK badging、签名 / 元数据 / SHA-256，以及未登录的全新模拟器上的启动结果与 AndroidRuntime 错误。安装检查读取运行日志；API 26 的 `logcat -c` 可能无法清理 main buffer，不能用该准备命令阻断尚未开始的安装。安装 / 启动 / 存活 / 崩溃 / 重装任何检查失败仍停止发布，失败时尽量保留运行日志。不保存 DB export、auth 响应、keystore 或合成账号资料。

## 日志、Cron 与监控

Wrangler 开启 observability、head sampling 0.1、`invocation_logs=false`。应用不输出请求正文、Authorization、密码、refresh token、恢复码、搜索 q 或数据库内容。读取实时日志时使用错误级别和必要时段，不把原始请求转存 artifact：

```sh
cd backend
npx --no-install wrangler tail dining-log-api --status error --format json
npx --no-install wrangler triggers deploy
```

VPS 对应使用已验证的 `node "$DINING_WRANGLER_SCRIPT" ...`，而非安装 backend 依赖。`triggers deploy` 是实际配置写入，只有需要修正 / 发布 Cron 时执行，随后通过 Cloudflare schedules API 核对。

Cron 每小时第 17 分钟调用同一 scheduled handler：清理过期 refresh 重试密文 / 幂等响应 / 限流窗口，再按 30 天保留期和预算分块清理软删除领域记录。测试已覆盖边界和中断；真实 Cron CPU、扫描成本和恢复演练另列 unverified，配置存在不等于实际清理已运行。

只读查看 Worker errors / CPU 与 D1 rows_read / rows_written / size，按账户共享额度监控。D1 存储达到免费单库容量约 70% 时应告警并评估容量；不能用本地 p95 报告代替生产性能。当前没有自动新建付费服务或新增监控 token。

## Time Travel 与数据恢复

发布前取 bookmark；Wrangler 4.23.0 的 Time Travel info 默认访问远端，不加不支持的 `--remote`：

```sh
cd backend
npx --no-install wrangler d1 time-travel info DB --json
npx --no-install wrangler d1 time-travel info DB --timestamp "$DINING_TARGET_TIMESTAMP" --json
```

恢复前暂停业务写入，记录当前 Worker version、当前 bookmark、目标时间、恢复影响与数据损失窗口，保留诊断。先在隔离验证库完成恢复演练；P5 不用真实生产数据做破坏性演练。确需恢复并已确认影响时才执行：

```sh
npx --no-install wrangler d1 time-travel restore DB --bookmark "$DINING_TARGET_BOOKMARK"
npx --no-install wrangler d1 execute DB --remote --command "PRAGMA foreign_key_check; PRAGMA quick_check; SELECT id,name,applied_at FROM d1_migrations ORDER BY id;" --json
curl --fail --silent --show-error https://dining.alanvampuke.top/api/v1/health
npm run smoke:production
```

保存恢复返回的后续 bookmark，核实目标 migration 与 Worker 兼容后再恢复写入。D1 Time Travel 可用窗口受实际账户套餐限制；只读 info 成功不等于 restore 已验收。

## Worker 与 APK 回滚

从 checkpoint 找到**版本 ID**，deployment ID 仅用于追溯。先 `wrangler versions view` 核对旧版本与当前 schema 的兼容性，再 `wrangler rollback "$DINING_PREVIOUS_VERSION_ID"`；随后 health、独立 smoke 与 SQL 完整性。D1 不自动倒退；失败停止，不循环尝试不同版本。

首次发布可能没有之前的版本和 APK。已发布 APK 不覆盖 / 不降 versionCode，沿用同 key 发布更高 versionCode 的修复。API 26/35 signed APK 安装与同版本 reinstall 不能冒充从历史正式版本升级，更不能冒充真机验证。

实际操作证据和未验证项目统一见 [P5_DONE.md](../P5_DONE.md)。
