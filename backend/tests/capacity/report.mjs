// Executed on GitHub Actions, after Vitest. Never substitutes generated estimates for measurements.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import process from 'node:process';
import console from 'node:console';

const logPath = process.argv[2];
assert.ok(logPath, 'Pass the capacity test log path');
const source = readFileSync(logPath, 'utf8');
const scenarios = source.split(/\r?\n/).flatMap((line) => {
  const match = line.match(/CAPACITY_RESULT (\{.*\})/);
  return match ? [JSON.parse(match[1])] : [];
});
assert.equal(scenarios.length, 2, 'Both measured scenarios must exist; retain the log on test failure');
assert.deepEqual(scenarios.map((result) => result.scenario.name).sort(), ['p1-4500', 'plan-6000']);
for (const result of scenarios) {
  assert.equal(result.repetitions, 30);
  assert.equal(result.p95LimitMs, 100);
  assert.equal(result.queries.length, 9);
  for (const query of result.queries) assert.equal(query.samples.length, 30);
}

const lock = JSON.parse(readFileSync('package-lock.json', 'utf8'));
const version = (name) => lock.packages[`node_modules/${name}`]?.version ?? 'not present in lock';
const fileHash = (path) => createHash('sha256').update(readFileSync(path)).digest('hex');
const metadata = {
  runId: process.env.GITHUB_RUN_ID ?? null,
  runAttempt: process.env.GITHUB_RUN_ATTEMPT ?? null,
  headSha: process.env.GITHUB_SHA ?? null,
  repository: process.env.GITHUB_REPOSITORY ?? null,
  ref: process.env.GITHUB_REF ?? null,
  runUrl: process.env.GITHUB_RUN_ID
    ? `https://github.com/${process.env.GITHUB_REPOSITORY}/actions/runs/${process.env.GITHUB_RUN_ID}` : null,
  measuredAtUtc: new Date().toISOString(),
  node: process.version,
  runnerOs: process.env.RUNNER_OS ?? null,
  runnerImage: process.env.ImageVersion ?? null,
  vitest: version('vitest'), workersPool: version('@cloudflare/vitest-pool-workers'),
  miniflare: version('miniflare'), workerd: version('workerd'),
  compatibilityDate: '2025-06-17',
  testSha256: fileHash('tests/capacity/capacity_test.ts'),
  migrationSha256: fileHash('migrations/0001_initial.sql'),
};
const archive = { metadata, scenarios };
const output = dirname(logPath);
mkdirSync(output, { recursive: true });
writeFileSync(join(output, 'measurements.json'), JSON.stringify(archive, null, 2) + '\n');

const fmt = (value) => Number(value).toFixed(3);
const mib = (bytes) => `${bytes.toLocaleString('en-US')} bytes / ${fmt(bytes / 1048576)} MiB`;
const pct = (value, maximum) => `${fmt(value / maximum * 100)}%`;
const label = {
  updated_page: '列表分页', rating_page: '评分排序', aggregate_search_hit: '聚合搜索：20 个命中',
  aggregate_search_miss: '聚合搜索：无命中，完整账号扫描', restaurant_detail: '单店详情（长文）',
  restaurant_dishes: '单店菜品', restaurant_visits: '单店到店',
  detail_children_batch: '详情 + 菜品 + 到店 batch', entity_revisions_10: '单实体 10 条历史',
};
const lines = [
  '# 容量测试实测结果', '',
  `来源：[GitHub Actions run ${metadata.runId}](${metadata.runUrl})，attempt ${metadata.runAttempt}，commit \`${metadata.headSha}\`。`,
  `采集时间：${metadata.measuredAtUtc}。测试状态：${scenarios.every((result) => result.overLimit.length === 0) ? '两组容量 / 性能断言通过' : '存在超标，见待决；不得视作验收通过'}。整个 workflow 结论另以 GitHub API 和 P1_ACCEPTANCE.md 为准。`, '',
  `环境：GitHub ${metadata.runnerOs} runner（image ${metadata.runnerImage}），Node ${metadata.node}，Vitest ${metadata.vitest}，Workers pool ${metadata.workersPool}，Miniflare ${metadata.miniflare}，workerd ${metadata.workerd}；compatibility_date=${metadata.compatibilityDate}。`,
  '**这是 GitHub Actions 内的本地 Workers / D1 模拟器实测，不是真实 Cloudflare D1 延迟、计费、Worker CPU 或端到端网络实测。** 本机 VPS 未运行构建或测试。', '',
  '## 重现和计量方法', '',
  '以下命令仅在 GitHub Actions 或获准的充足内存环境执行；956 MB VPS 禁止执行。', '',
  '```sh', 'cd backend', 'npm ci', 'npm run check', 'mkdir -p tests/capacity/output',
  'npm run test:capacity 2>&1 | tee tests/capacity/output/capacity.log',
  'node tests/capacity/report.mjs tests/capacity/output/capacity.log', '```', '',
  '- 固定 mulberry32 seed `0x51a71c`，固定业务时刻 `2026-10-31T12:00:00.000Z`；UUID、评分、文本、日期及版本历史都确定。npm 脚本顺序启动两个独立 Vitest / Workers-pool 进程，各选择一个场景，从 14 张应用表全空的独立 D1 初始化；使用现有 migrations。大数据库禁用池内逐测试快照，避免 stacked-storage 回滚时断连，普通测试配置不受影响。',
  '- 文本混合英文短文、中文短文与接近字段上限（98–99.9%）的中文长文；中文 UTF-8 约 3 bytes/字，存储统计使用 BLOB 长度而非字符数。评分全为 10–50 整数。',
  '- 每种查询连续串行执行 30 次，不丢弃首次样本、不重试挑选结果。nearest-rank p50 是第 15 个、p95 是第 29 个排序后样本。wall 使用 `Date.now()`（1 ms 分辨率）测 await D1 的调用耗时；当前 Workers 的 `performance.now()` 返回零，不能用作墙钟。SQL 为 D1 `meta.duration`（组合 batch 相加）；两者 p95 均断言 ≤100 ms，并断言全量生成和完整写入的 wall >0。',
  '- 原始 30 次样本、SQL / 参数、EXPLAIN QUERY PLAN 和所有统计归档于同目录 `measurements.json`，CI artifact `capacity-results` 另含原始日志及自动生成报告；仓库 RESULTS.md 来自该报告。',
  '- 表行数 / 存储与查询在额外写入 benchmark 前采集；写入后的对象增量和完整性另行断言，不混入基线容量。',
  `- 测试脚本 SHA-256：\`${metadata.testSha256}\`；migration SHA-256：\`${metadata.migrationSha256}\`。`, '',
  '任务的 P1 样本为 500 × (1 餐厅 + 5 菜品 + 3 到店) = 4,500 对象。只读 PLAN.md 8.6 / 10.3.14 使用 8 菜品，即 6,000 对象；本次两组都实测，未修改方案或性能目标。', '',
];

for (const result of scenarios) {
  const scenario = result.scenario;
  lines.push(`## ${scenario.name}：${scenario.objects.toLocaleString('en-US')} 对象 / ${scenario.history.toLocaleString('en-US')} 历史`, '',
    `SQLite 版本：${result.runtime.sqlite_version ?? `未读取（${result.runtime.sqliteVersionUnavailable}）`}；1 user、${scenario.restaurants} restaurants、${result.rowCounts.dishes} dishes、${result.rowCounts.visits} visits。每家餐厅 ${result.restaurantRevisionRange.min}–${result.restaurantRevisionRange.max} 条 revision；菜品 / 到店各 ${scenario.childRevisions} 条。每条基线 revision 有独立 change_set，保守计入分组存储。`,
    `生成数据 SHA-256：\`${result.seed.fixtureSha256}\`。初始化 ${result.seed.statements} 条 SQL、${result.seed.batches} 个 batch（每 batch ≤40 条 SQL），实测 wall ${fmt(result.seed.wallMs)} ms、rows_read=${result.seed.rowsRead}、rows_written=${result.seed.rowsWritten}。`, '',
    '| 文本类型 | restaurants | dishes | visits |', '| --- | ---: | ---: | ---: |');
  for (const kind of ['english_short', 'chinese_short', 'near_limit']) lines.push(`| ${kind} | ${result.seed.textCounts.restaurant[kind]} | ${result.seed.textCounts.dish[kind]} | ${result.seed.textCounts.visit[kind]} |`);
  lines.push('', '### 存储与行数', '',
    `\`SELECT SUM(pgsize) FROM dbstat\`：${result.storage.dbstatBytes === null ? `不可用（${result.storage.dbstatUnavailable.replace(/\s+/g, ' ')}）。采用逐表 TEXT/BLOB 的 \`SUM(length(CAST(column AS BLOB)))\`。` : mib(result.storage.dbstatBytes)}。`,
    `逐表 UTF-8 文本有效载荷合计：${mib(result.storage.textUtf8Bytes)}（不含数值、行头、索引与页空隙，不能直接当完整数据库存储）。`,
    `D1 \`meta.size_after\` 完整已分配数据库体积：${result.storage.allocatedBytes === null ? '该模拟器未提供，完整存储边界尚未验证' : mib(result.storage.allocatedBytes)}（含索引 / 元数据 / 空闲页，非远端账单）。`, '',
    `受 D1 保护、不直接读取的内部表：${result.storage.protectedTables.join(', ') || '无'}；其页仍计入完整体积。全部 14 张应用表逐表统计如下。`, '',
    '| 表 | 行数 | TEXT/BLOB UTF-8 bytes |', '| --- | ---: | ---: | ---: |');
  for (const table of result.storage.tableStats) lines.push(`| ${table.table} | ${table.rows} | ${table.textUtf8Bytes} |`);
  if (result.storage.pagesByObject.length) {
    lines.push('', '<details>', '<summary>dbstat 各表 / 索引页体积</summary>', '', '| 对象 | bytes |', '| --- | ---: |');
    for (const page of result.storage.pagesByObject) lines.push(`| ${page.name} | ${page.bytes} |`);
    lines.push('', '</details>');
  }
  lines.push('', '### 查询：每种 30 次', '',
    '| 查询 | wall p50 ms | wall p95 ms | SQL p50 ms | SQL p95 ms | rows_read min–max | 返回行数 |',
    '| --- | ---: | ---: | ---: | ---: | ---: | --- |');
  for (const query of result.queries) lines.push(`| ${label[query.id]} | ${fmt(query.wallMs.p50)} | ${fmt(query.wallMs.p95)} | ${fmt(query.sqlMs.p50)} | ${fmt(query.sqlMs.p95)} | ${query.rowsRead.min}–${query.rowsRead.max} | ${query.samples[0].returnedRows.join(' + ')} |`);
  lines.push('', '所有读取的 rows_written=0；断言验证返回的对象集合，分页 / 评分采用稳定 id 次序，历史另外验证连续降序版本。', '',
    '<details>', '<summary>实际 SQL 与查询计划</summary>', '');
  for (const query of result.queries) {
    lines.push(`**${label[query.id]}**`, '', '```sql', ...query.statements.map((statement) => statement.sql + ';'), '```', '',
      '```text', ...query.plans.flat(), '```', '');
  }
  lines.push('</details>', '', '### 一次完整写入 batch', '',
    '创建 1 餐厅 + 5 菜品 + 3 到店，9 条 create revision 加 1 次真实餐厅更新 / update revision，共 10 revision；同 batch 还写 1 change_set 与 1 restaurant_search。',
    `实测：${result.write.statements.length} 条 SQL，${result.write.changes} 次逻辑行变更，rows_read=${result.write.rowsRead}，**rows_written=${result.write.rowsWritten}**，**wall=${fmt(result.write.wallMs)} ms**，SQL duration 合计=${fmt(result.write.sqlMs)} ms。`,
    '这是直接 SQL 的容量 batch；当前 P1 业务 API 仍为 501，未计入鉴权、幂等与 CAS 守卫，不能声称完整产品写入性能已通过。',
    `写入后增量：restaurants=${result.write.rowDeltas.restaurants}，dishes=${result.write.rowDeltas.dishes}，visits=${result.write.rowDeltas.visits}，revisions=${result.write.rowDeltas.revisions}，change_sets=${result.write.rowDeltas.change_sets}，restaurant_search=${result.write.rowDeltas.restaurant_search}；其他业务表增量=0。foreign_key_check 无异常，quick_check=${result.integrity.quickCheck}。`, '',
    '### PLAN.md 8.5 免费档边界对照', '');
  const maxRead = Math.max(...result.queries.map((query) => query.rowsRead.max));
  const fullBytes = result.storage.allocatedBytes ?? result.storage.dbstatBytes;
  const writeCalls = Math.floor(100_000 / Math.max(1, result.write.rowsWritten));
  const readCalls = Math.floor(5_000_000 / Math.max(1, maxRead));
  lines.push('| 限额 | 本次实测 / 由实测折算 | 判断 |', '| --- | --- | --- |',
    `| 5,000,000 rows read/day | 最贵常用查询 ${maxRead} rows_read/次；仅重复该操作最多 ${readCalls} 次/日 | 单账号日常查询有余量；仍需计入其他账号和其他业务 |`,
    `| 100,000 rows written/day | 完整写入 batch ${result.write.rowsWritten} rows_written/次；仅重复该 batch 最多 ${writeCalls} 次/日；本次初始化 ${result.seed.rowsWritten} rows_written | 初始化至少 ${Math.ceil(result.seed.rowsWritten / 100_000)} 个额度日；分批不改变每日总额 |`,
    `| 5 GB 总存储（5,000,000,000 bytes/account） | ${fullBytes === null ? '只取得有效载荷下限' : `${mib(fullBytes)}，占 ${pct(fullBytes, 5_000_000_000)}`} | ${fullBytes === null ? '完整存储未验证' : fullBytes <= 5_000_000_000 ? '本次单账号样本以内；账户内其他库共享额度' : '超出'} |`,
    `| 单库 500 MB（500,000,000 bytes） | ${fullBytes === null ? '只取得有效载荷下限' : `${mib(fullBytes)}，占 ${pct(fullBytes, 500_000_000)}`} | ${fullBytes === null ? '完整存储未验证' : fullBytes <= 500_000_000 ? '本次完整样本可容纳；历史持续增长需监控' : '超出单库免费上限'} |`, '',
    fullBytes === null ? '结论：读写量可与额度直接折算，存储仅测得文本下限，不能确认完整单库是否够用。'
      : fullBytes <= 500_000_000 ? `结论：在本次混合长短文的单账号数据量下，模拟器完整存储低于单库上限，常用读写的额度折算有余量；全量初始化最低需 ${Math.ceil(result.seed.rowsWritten / 100_000)} 个额度日。远端 CPU、账单、共享用量和长期历史增长未验证，不能据此保证整个生产账号永久免费。`
        : '结论：实测完整存储超出免费单库上限，这个文本分布下免费单库不够用；不得降低样本量或删历史使测试通过。', '');
}

const failures = scenarios.flatMap((result) => result.overLimit.map((id) => ({ result, query: result.queries.find((query) => query.id === id) })));
lines.push('## 待决', '');
if (failures.length === 0) lines.push('- 两组常用 SQL 的实测 p95 均 ≤100 ms，无性能超标；没有修改 migration、删测试或降低目标。');
else for (const { result, query } of failures) lines.push(`- ${result.scenario.name}/${query.id}：wall p95=${fmt(query.wallMs.p95)} ms，SQL p95=${fmt(query.sqlMs.p95)} ms。计划：${query.plans.flat().join('；')}。先分析扫描 / 排序 / 往返；如需要迁移或索引改动，保留原 migration 并在后续评审，当前验收不通过。`);
lines.push('- 聚合搜索的无命中场景必须扫描整个账号聚合文本；当前账号仅 500 家店。扩大账号餐厅数或持续增加长文时，需重新实测，不能由本报告外推无限容量。',
  '- 真实 Cloudflare D1、Workers 免费档 10 ms CPU、部署后网络与端到端 CRUD 均未验证，属于后续阶段；本轮没有创建或部署云资源。',
  '- 本报告仅对指定初始样本测量，未实测每个实体均 10 条历史的增长变体；该项仍见 PLAN.md CAPACITY-01 的后续验证，不包含在本次两项收尾完成声明中。', '');
writeFileSync(join(output, 'RESULTS.md'), lines.join('\n'));
console.log(`Saved ${join(output, 'RESULTS.md')} and measurements.json from ${scenarios.length} measured scenarios`);
for (const result of scenarios) console.log(JSON.stringify({ scenario: result.scenario.name,
  objects: result.scenario.objects, history: result.rowCounts.revisions,
  allocatedBytes: result.storage.allocatedBytes, textUtf8Bytes: result.storage.textUtf8Bytes,
  maxWallP95Ms: Math.max(...result.queries.map((query) => query.wallMs.p95)),
  maxSqlP95Ms: Math.max(...result.queries.map((query) => query.sqlMs.p95)),
  writeRows: result.write.rowsWritten, writeWallMs: result.write.wallMs, overLimit: result.overLimit }));
