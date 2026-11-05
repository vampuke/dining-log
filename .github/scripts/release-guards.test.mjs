import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { execFileSync, spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { validateInputs, sourceVersion, requireCandidateJobs, requireIncreasingTag,
  requireTimeTravelBookmark, permitsEmptyHistory, requireDomainChangeset } from './release-guards.mjs';

const sha = 'a'.repeat(40), repo = 'vampuke/dining-log', ref = 'refs/heads/master';

test('workflow validates real Git ancestry before it can execute candidate code', () => {
  const workflow = readFileSync(new URL('../workflows/release.yml', import.meta.url), 'utf8');
  const marker = 'Validate checkout before executing any candidate scripts';
  const match = workflow.match(/^      - name: Validate checkout before executing any candidate scripts\n        shell: bash\n        run: \|\n((?:          .*\n)+)/m);
  assert.ok(match, 'The workflow must contain its own trusted checkout gate');
  assert.ok(workflow.indexOf(marker) < workflow.indexOf('run: node .github/scripts/validate-release.mjs'));
  const gate = match[1].replace(/^ {10}/gm, '');
  const fixture = mkdtempSync(join(tmpdir(), 'dining-release-ancestry-'));
  const origin = join(fixture, 'origin.git'), checkout = join(fixture, 'checkout');
  const sentinel = join(fixture, 'candidate-executed');
  try {
    execFileSync('git', ['init', '--quiet', '--bare', origin]);
    execFileSync('git', ['init', '--quiet', '--initial-branch=master', checkout]);
    const git = (...args) => execFileSync('git', args, {
      cwd: checkout, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
    }).trim();
    git('config', 'user.name', 'Release guard tests');
    git('config', 'user.email', 'release-guard@example.invalid');
    git('remote', 'add', 'origin', origin);
    git('commit', '--quiet', '--allow-empty', '-m', 'Reviewed ancestor');
    const ancestor = git('rev-parse', 'HEAD');
    git('commit', '--quiet', '--allow-empty', '-m', 'Reviewed master');
    const master = git('rev-parse', 'HEAD');
    git('push', '--quiet', 'origin', 'master');
    git('checkout', '--quiet', '-b', 'unreviewed');
    git('commit', '--quiet', '--allow-empty', '-m', 'Unreviewed candidate');
    const unreviewed = git('rev-parse', 'HEAD');
    const cases = [
      { name: 'reviewed ancestor', head: ancestor, accepted: true },
      { name: 'reviewed master', head: master, accepted: true },
      { name: 'uppercase SHA', head: master, commit: master.toUpperCase(), accepted: true },
      { name: 'unreviewed branch', head: unreviewed },
      { name: 'mismatched HEAD', head: master, commit: ancestor },
      { name: 'malformed SHA', head: master, commit: 'g'.repeat(40) },
      { name: 'short SHA', head: master, commit: master.slice(0, 39) },
      { name: 'unsafe tag', head: master, tag: 'v0.5.0;touch candidate-executed' },
      { name: 'fork', head: master, repository: 'fork/dining-log' },
      { name: 'non-master dispatch', head: master, dispatchRef: 'refs/heads/unreviewed' },
    ];
    for (const item of cases) {
      git('checkout', '--quiet', item.head);
      rmSync(sentinel, { force: true });
      const result = spawnSync('bash', ['-c', gate + '\ntouch "$RELEASE_GUARD_SENTINEL"\n'], {
        cwd: checkout, encoding: 'utf8',
        env: { ...process.env, GITHUB_REPOSITORY: item.repository ?? repo,
          GITHUB_REF: item.dispatchRef ?? ref, RELEASE_COMMIT: item.commit ?? item.head,
          RELEASE_TAG: item.tag ?? 'v0.5.0', RELEASE_GUARD_SENTINEL: sentinel },
      });
      assert.ifError(result.error);
      assert.equal(result.status === 0, item.accepted === true, item.name);
      assert.equal(existsSync(sentinel), item.accepted === true, `${item.name}: candidate execution`);
    }
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});

test('release accepts only a full SHA, stable version tag, own repository and master dispatch', () => {
  assert.equal(validateInputs(sha, 'v0.5.0', repo, ref), sha);
  assert.equal(validateInputs(sha.toUpperCase(), 'v0.5.0', repo, ref), sha);
  for (const value of ['', 'a'.repeat(39), 'a'.repeat(41), 'g'.repeat(40), 'master', ` ${sha}`, `${sha}\n`]) {
    assert.throws(() => validateInputs(value, 'v0.5.0', repo, ref));
  }
  for (const tag of ['0.5.0', 'v01.5.0', 'v0.5', 'v0.5.0-rc1', 'v0.5.0;echo unsafe', 'v0.5.0\n']) {
    assert.throws(() => validateInputs(sha, tag, repo, ref));
  }
  assert.throws(() => validateInputs(sha, 'v0.5.0', 'fork/dining-log', ref));
  for (const branch of ['refs/heads/feature', 'refs/pull/5/merge', 'refs/tags/v0.5.0']) {
    assert.throws(() => validateInputs(sha, 'v0.5.0', repo, branch));
  }
});

test('source version is read from the reviewed file and cannot differ from tag', () => {
  const gradle = readFileSync(new URL('../../android/app/build.gradle.kts', import.meta.url), 'utf8');
  assert.deepEqual(sourceVersion(gradle, 'v0.8.2'), { versionCode: 10, versionName: '0.8.2' });
  assert.throws(() => sourceVersion(gradle, 'v0.8.0'));
  assert.throws(() => sourceVersion(gradle, 'v0.6.0'));
  assert.throws(() => sourceVersion(gradle, 'v0.5.0'));
  assert.throws(() => sourceVersion(gradle, 'v0.4.0'));
  assert.throws(() => sourceVersion('versionCode = 0\nversionName = "0.5.0"', 'v0.5.0'));
  assert.throws(() => sourceVersion('versionCode = 9007199254740992\nversionName = "0.5.0"', 'v0.5.0'));
});

test('both device APIs and backend/Android checks are mandatory; skipped is a failure', () => {
  const names = ['backend', 'android', 'android-auth-instrumentation (26)', 'android-auth-instrumentation (35)'];
  const jobs = names.map(name => ({ name, status: 'completed', conclusion: 'success' }));
  requireCandidateJobs(jobs);
  for (const name of names) {
    assert.throws(() => requireCandidateJobs(jobs.filter(job => job.name !== name)));
    for (const conclusion of ['failure', 'cancelled', 'skipped', null]) {
      assert.throws(() => requireCandidateJobs(jobs.map(job => job.name === name ? { ...job, conclusion } : job)));
    }
    assert.throws(() => requireCandidateJobs(jobs.map(job => job.name === name ? { ...job, status: 'in_progress' } : job)));
  }
});

test('stable releases must increase numerically, including minor and major boundaries', () => {
  for (const [next, previous] of [['v0.5.0', 'v0.4.9'], ['v1.10.0', 'v1.9.9'], ['v2.0.0', 'v1.99.99']]) {
    requireIncreasingTag(next, previous);
  }
  for (const [next, previous] of [['v0.5.0', 'v0.5.0'], ['v0.5.0', 'v1.0.0'], ['v0.5.0', 'v0.6.0'], ['v0.5.0', 'v0.4.0-rc1']]) {
    assert.throws(() => requireIncreasingTag(next, previous));
  }
});

test('empty bootstrap history cannot hide failed permissions, network or malformed checkpoints', () => {
  requireTimeTravelBookmark({ bookmark: '00000000-00000000-00000000-00000000' });
  for (const info of [null, {}, { bookmark: '' }, { bookmark: ' ' }, { bookmark: 12 }]) {
    assert.throws(() => requireTimeTravelBookmark(info));
  }
  assert.equal(permitsEmptyHistory(0, 'No Time Travel history is available'), true);
  assert.equal(permitsEmptyHistory(0, 'database is empty; no bookmark'), true);
  assert.equal(permitsEmptyHistory(14, 'No Time Travel history is available'), false);
  for (const diagnostic of ['Authentication error', 'Network timed out', 'Unknown argument: json',
    'No history: permission denied', 'Database is empty; fetch failed']) {
    assert.equal(permitsEmptyHistory(0, diagnostic), false);
  }
});

test('noninteractive Wrangler cannot silently replace an origin or conflicting DNS record', () => {
  requireDomainChangeset({ updated: [], conflicting: [] });
  requireDomainChangeset({ updated: [{ hostname: 'dining.alanvampuke.top', modified: false }], conflicting: [] });
  for (const changeset of [null, {}, { updated: [], conflicting: [{ hostname: 'dining.alanvampuke.top' }] },
    { updated: [{ modified: true }], conflicting: [] }, { updated: [{}], conflicting: [] }]) {
    assert.throws(() => requireDomainChangeset(changeset));
  }
});
