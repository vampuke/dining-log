// Queries only schema/integrity/migration metadata. Never export application data.
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { requireValue, requireTimeTravelBookmark, permitsEmptyHistory, requireDomainChangeset } from './release-guards.mjs';

const repoRoot = process.env.GITHUB_WORKSPACE;
const temp = process.env.RUNNER_TEMP;
if (!repoRoot || !temp || process.env.GITHUB_REF !== 'refs/heads/master') throw new Error('Production D1 checks require the protected master release');
const policy = JSON.parse(readFileSync(join(repoRoot, '.github/release-policy.json'), 'utf8'));
const dir = join(repoRoot, 'backend/migrations');
const migrations = readdirSync(dir).filter(file => file.endsWith('.sql')).sort();
requireValue(JSON.stringify(migrations) === JSON.stringify(Object.keys(policy.migrations).sort()), 'Migration inventory differs from the reviewed release policy');
for (const name of migrations) {
  requireValue(createHash('sha256').update(readFileSync(join(dir, name))).digest('hex') === policy.migrations[name], `Frozen/reviewed migration hash mismatch: ${name}`);
}
const expectedTables = [...readFileSync(join(dir, '0001_initial.sql'), 'utf8').matchAll(/^CREATE TABLE (\w+)\s*\(/gm)].map(match => match[1]).sort();
requireValue(expectedTables.length === 14, 'P5 requires all 14 domain/auth tables');

function wrangler(args) {
  return execFileSync('npx', ['--no-install', 'wrangler', ...args], {
    cwd: join(repoRoot, 'backend'), encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, WRANGLER_SEND_METRICS: 'false', CI: 'true' },
  });
}
function sql(command) {
  const results = JSON.parse(wrangler(['d1', 'execute', 'DB', '--remote', '--command', command, '--json']));
  requireValue(Array.isArray(results) && results.length > 0 && results.every(result => result.success === true), 'D1 metadata/integrity query failed');
  return results.map(result => result.results);
}
function schema() {
  return sql("SELECT name FROM sqlite_master WHERE type='table' AND name NOT GLOB 'sqlite_*' AND name NOT GLOB '_cf_*' AND name<>'d1_migrations' ORDER BY name;")[0].map(row => row.name);
}
async function cloudflare(path, method = 'GET', body) {
  const response = await fetch(`https://api.cloudflare.com/client/v4/accounts/49eee8b8033579863adb3b7cf0b1852c${path}`, {
    method, headers: { Authorization: `Bearer ${process.env.CLOUDFLARE_API_TOKEN}`,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(20000),
  });
  requireValue(response.ok, `Cloudflare checkpoint query failed (HTTP ${response.status})`);
  const parsed = await response.json();
  requireValue(parsed.success === true, 'Cloudflare checkpoint API operation failed');
  return parsed.result;
}

try {
  const mode = process.argv[2];
  const tables = schema();
  if (mode === 'checkpoint') {
    const deployments = await cloudflare('/workers/scripts/dining-log-api/deployments');
    const record = { recorded_at: new Date().toISOString(), empty_database: tables.length === 0,
      previous_deployments: (deployments.deployments ?? deployments).map(({ id, created_on, versions }) => ({ id, created_on, versions })) };
    requireValue(record.previous_deployments.length > 0 && record.previous_deployments.every(deployment =>
      typeof deployment.id === 'string' && deployment.versions?.length > 0
      && deployment.versions.every(version => typeof version.version_id === 'string')),
    'Worker checkpoint returned no usable deployment/version IDs');
    try {
      record.time_travel = JSON.parse(wrangler(['d1', 'time-travel', 'info', 'DB', '--json']));
      requireTimeTravelBookmark(record.time_travel);
    } catch (error) {
      const diagnostic = `${error.stdout ?? ''}\n${error.stderr ?? ''}`;
      // Empty schema alone must never hide permissions, network, or syntax errors.
      if (!permitsEmptyHistory(tables.length, diagnostic)) {
        throw new Error('Time Travel checkpoint failed; deployment stopped');
      }
      record.time_travel = { state: 'no-history', reason: 'Confirmed empty bootstrap database; provider reports no bookmark/history' };
    }
    writeFileSync(join(temp, 'dining-production-checkpoint.json'), `${JSON.stringify(record, null, 2)}\n`);
    console.log(JSON.stringify(record));
  } else if (mode === 'pre-migration') {
    requireValue(tables.length === 0 || JSON.stringify(tables) === JSON.stringify(expectedTables), 'Unexpected production schema; migration stopped');
    if (tables.length !== 0) {
      const applied = sql('SELECT name FROM d1_migrations ORDER BY id;')[0].map(row => row.name);
      requireValue(JSON.stringify(applied) === JSON.stringify(migrations), 'P5 initial migration may only initialize an empty database or be already applied');
    } else {
      requireValue(migrations.every(name => policy.bootstrap_only_migrations.includes(name)), 'Unreviewed compatibility for a pending migration');
    }
    console.log(JSON.stringify({ migration_compatibility: 'passed', state: tables.length ? 'already-applied immutable P5 schema' : 'empty bootstrap database', migrations }));
  } else if (mode === 'domain') {
    // Wrangler 4.23.0 enables overwrite flags when stdout is not a TTY.
    // This API only calculates a changeset; reject conflicts before deployment.
    const domains = await cloudflare('/workers/domains');
    requireValue(Array.isArray(domains) && domains.every(domain =>
      (domain.hostname !== 'dining.alanvampuke.top' || domain.service === 'dining-log-api')
      && (domain.service !== 'dining-log-api' || domain.hostname === 'dining.alanvampuke.top')),
    'Unexpected production Custom Domain bindings; deployment stopped');
    const changeset = await cloudflare('/workers/scripts/dining-log-api/domains/changeset?replace_state=true', 'POST',
      [{ hostname: 'dining.alanvampuke.top', zone_id: 'e046faddc69173d411003bb43f93ea83' }]);
    requireDomainChangeset(changeset);
    const record = { custom_domain: 'dining.alanvampuke.top', conflict_check: 'passed', changeset };
    writeFileSync(join(temp, 'dining-production-domain-check.json'), `${JSON.stringify(record, null, 2)}\n`);
    console.log(JSON.stringify(record));
  } else if (mode === 'verify') {
    requireValue(JSON.stringify(tables) === JSON.stringify(expectedTables), 'Production D1 table inventory is not exactly the reviewed 14 tables');
    const [foreignKeys, quickCheck, applied] = sql('PRAGMA foreign_key_check; PRAGMA quick_check; SELECT id,name,applied_at FROM d1_migrations ORDER BY id;');
    requireValue(foreignKeys.length === 0, 'Production D1 foreign_key_check failed');
    requireValue(quickCheck.length === 1 && Object.values(quickCheck[0]).length === 1 && Object.values(quickCheck[0])[0] === 'ok', 'Production D1 quick_check failed');
    requireValue(JSON.stringify(applied.map(row => row.name)) === JSON.stringify(migrations), 'Production migration records differ from local migrations');
    const record = { table_count: tables.length, tables, foreign_key_check: foreignKeys, quick_check: quickCheck, migrations: applied };
    writeFileSync(join(temp, 'dining-production-integrity.json'), `${JSON.stringify(record, null, 2)}\n`);
    console.log(JSON.stringify(record));
  } else {
    throw new Error('Unknown production D1 validation mode');
  }
} catch (error) {
  console.error(error instanceof Error && !('stdout' in error) ? error.message : 'Production D1 operation failed; stopped without exporting application data');
  process.exitCode = 1;
}
