import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { execFileSync, spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { validateInputs, sourceVersion, requireCandidateJobs, requireIncreasingTag,
  requireTimeTravelBookmark, permitsEmptyHistory, requireDomainChangeset,
  acceptanceWaiver, requireCandidate } from './release-guards.mjs';

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
  for (const branch of ['refs/heads/feature', 'refs/pull/5/merge']) {
    assert.throws(() => validateInputs(sha, 'v0.5.0', repo, branch));
  }
  // A tag ref is accepted only when it names the requested release tag.
  assert.doesNotThrow(() => validateInputs(sha, 'v0.5.0', repo, 'refs/tags/v0.5.0'));
  assert.throws(() => validateInputs(sha, 'v0.5.0', repo, 'refs/tags/v0.6.0'));
});

test('source version is read from the reviewed file and cannot differ from tag', () => {
  const gradle = readFileSync(new URL('../../android/app/build.gradle.kts', import.meta.url), 'utf8');
  assert.deepEqual(sourceVersion(gradle, 'v0.8.3'), { versionCode: 11, versionName: '0.8.3' });
  assert.throws(() => sourceVersion(gradle, 'v0.8.2'));
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

test('tooling regression: absent/false opt-in preserves exact successful CI and all four job gates', () => {
  const names = ['backend', 'android', 'android-auth-instrumentation (26)', 'android-auth-instrumentation (35)'];
  const candidate = { head_sha: sha, head_branch: 'master', event: 'push', status: 'completed', conclusion: 'success' };
  const jobs = names.map(name => ({ name, status: 'completed', conclusion: 'success' }));
  for (const flag of [undefined, 'false']) {
    requireCandidate(candidate, jobs, sha, 'v0.8.3', 'apk-only', flag);
    for (const state of [{ conclusion: 'failure' }, { status: 'in_progress', conclusion: null }]) {
      assert.throws(() => requireCandidate({ ...candidate, ...state }, jobs, sha, 'v0.8.3', 'apk-only', flag));
    }
    for (const name of names) {
      assert.throws(() => requireCandidate(candidate, jobs.filter(job => job.name !== name), sha, 'v0.8.3', 'apk-only', flag));
      for (const conclusion of ['failure', 'skipped', 'cancelled', null]) {
        const failed = jobs.map(job => job.name === name ? { ...job, conclusion } : job);
        assert.throws(() => requireCandidate(candidate, failed, sha, 'v0.8.3', 'apk-only', flag));
      }
    }
  }
});

test('tooling regression: waiver is strictly v0.8.3 apk-only and never changes CI/job results', () => {
  assert.equal(acceptanceWaiver(undefined, 'v0.8.3', 'apk-only'), null);
  assert.equal(acceptanceWaiver('false', 'v0.8.4', 'full'), null);
  for (const value of ['', 'TRUE', 'True', '1', 'yes', 'on', ' true', 'true\n', true, false, null]) {
    assert.throws(() => acceptanceWaiver(value, 'v0.8.3', 'apk-only'));
  }
  for (const [tag, mode] of [['v0.8.2', 'apk-only'], ['v0.8.4', 'apk-only'], ['v0.8.3', 'full'],
    ['v0.8.3', 'APK-ONLY'], ['v0.8.3', undefined]]) {
    assert.throws(() => acceptanceWaiver('true', tag, mode));
  }
  for (const [status, conclusion] of [['completed', 'failure'], ['in_progress', null], ['queued', null]]) {
    const candidate = { head_sha: sha, head_branch: 'master', event: 'push', status, conclusion };
    const jobs = [{ name: 'android-auth-instrumentation (35)', status, conclusion }];
    const before = JSON.stringify({ candidate, jobs });
    requireCandidate(candidate, jobs, sha, 'v0.8.3', 'apk-only', 'true');
    assert.equal(JSON.stringify({ candidate, jobs }), before);
    for (const mismatch of [{ head_sha: 'b'.repeat(40) }, { head_branch: 'feature' }, { event: 'pull_request' }]) {
      assert.throws(() => requireCandidate({ ...candidate, ...mismatch }, jobs, sha, 'v0.8.3', 'apk-only', 'true'));
    }
    assert.throws(() => requireCandidate(null, jobs, sha, 'v0.8.3', 'apk-only', 'true'));
  }
  const workflow = readFileSync(new URL('../workflows/release.yml', import.meta.url), 'utf8');
  assert.match(workflow, /accept_known_issues:\n        description: [^\n]+\n        required: true\n        type: boolean\n        default: false\n/);
  assert.ok(workflow.includes('      ACCEPT_KNOWN_ISSUES: ${{ inputs.accept_known_issues }}\n'));
});

test('tooling regression: real validator retains waiver evidence and binds publish identity and approved Git tree', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'dining-release-waiver-'));
  const script = fileURLToPath(new URL('./validate-release.mjs', import.meta.url));
  const preload = join(fixture, 'fake-services.mjs');
  const selectionPath = join(fixture, 'dining-release-selection.json');
  // Only local synthetic Git/API responses; no CI dispatch, network, build or device work.
  writeFileSync(preload, `
    import childProcess from 'node:child_process';
    import { syncBuiltinESMExports } from 'node:module';
    childProcess.execFileSync = (command, args) => {
      if (command !== 'git') throw new Error('Unexpected command');
      if (args[0] === 'fetch') return '';
      if (args[0] === 'merge-base') {
        if (args[3] !== 'origin/master' && process.env.GUARD_NOT_ANCESTOR) throw new Error('Not approved ancestry');
        return '';
      }
      if (args[0] === 'rev-parse' && args[1] === 'HEAD') return process.env.RELEASE_COMMIT;
      if (args[0] === 'rev-parse' && args[1] === process.env.RELEASE_COMMIT + ':android') return 'c'.repeat(40);
      if (args[0] === 'rev-parse' && args[1] === 'be8aaf8d60aa5513227a102d4cdbfcb2377b778d:android') {
        return (process.env.GUARD_CHANGED_ANDROID ? 'd' : 'c').repeat(40);
      }
      throw new Error('Unexpected Git check');
    };
    syncBuiltinESMExports();
    globalThis.fetch = async url => {
      const path = new URL(url).pathname;
      let data;
      if (path.includes('/releases/tags/')) return { status: 404 };
      if (path.includes('/git/ref/tags/')) {
        data = { object: { sha: process.env.GUARD_REPLACED_TAG ? 'b'.repeat(40) : process.env.RELEASE_COMMIT } };
      } else if (path.endsWith('/runs')) {
        data = { workflow_runs: [{ id: 7, head_sha: process.env.RELEASE_COMMIT, head_branch: 'master', event: 'push',
          status: 'in_progress', conclusion: null, html_url: 'https://example.invalid/runs/7' }] };
      } else if (path.endsWith('/jobs')) {
        data = { total_count: 1, jobs: [{ id: 8, name: 'android-auth-instrumentation (26)', status: 'completed',
          conclusion: 'failure', html_url: 'https://example.invalid/jobs/8' }] };
      } else if (path.endsWith('/releases')) {
        data = [{ tag_name: 'v0.8.2', html_url: 'https://example.invalid/v0.8.2', assets: [{ name: 'app-release.apk', id: 9 }] }];
      } else if (path.endsWith('/assets/9')) {
        return { ok: true, arrayBuffer: async () => new Uint8Array([1, 2, 3]).buffer };
      } else throw new Error('Unexpected API request');
      return { ok: true, json: async () => data };
    };
  `);
  const run = (argument, overrides = {}) => spawnSync(process.execPath, ['--import', preload, script, ...(argument ? [argument] : [])], {
    cwd: fileURLToPath(new URL('../../', import.meta.url)), encoding: 'utf8', timeout: 10000,
    env: { PATH: process.env.PATH, RELEASE_COMMIT: sha, RELEASE_TAG: 'v0.8.3', GITHUB_REPOSITORY: repo,
      GITHUB_REF: ref, RUNNER_TEMP: fixture, GH_TOKEN: 'synthetic-test-token',
      PRODUCTION_MODE: 'apk-only', ACCEPT_KNOWN_ISSUES: 'true', ...overrides },
  });
  const passed = result => { assert.ifError(result.error); assert.equal(result.status, 0, result.stdout + result.stderr); };
  const denied = result => { assert.ifError(result.error); assert.equal(result.status, 1, result.stdout + result.stderr); };
  try {
    passed(run());
    const saved = readFileSync(selectionPath, 'utf8'), selection = JSON.parse(saved);
    assert.deepEqual(selection.acceptance_waiver, acceptanceWaiver('true', 'v0.8.3', 'apk-only'));
    assert.equal(selection.android_tree, 'c'.repeat(40));
    assert.equal(selection.candidate_ci, 'https://example.invalid/runs/7');
    assert.equal(selection.candidate.status, 'in_progress');
    assert.equal(selection.candidate.conclusion, null);
    assert.deepEqual(selection.candidate.jobs, [{ id: 8, name: 'android-auth-instrumentation (26)',
      status: 'completed', conclusion: 'failure', url: 'https://example.invalid/jobs/8' }]);
    assert.deepEqual(selection.previous, { tag: 'v0.8.2', url: 'https://example.invalid/v0.8.2' });
    assert.deepEqual([...readFileSync(join(fixture, 'dining-previous-release.apk'))], [1, 2, 3]);
    passed(run('publish'));
    assert.equal(readFileSync(selectionPath, 'utf8'), saved, 'Publish preserves the original CI snapshot');
    for (const overrides of [{ ACCEPT_KNOWN_ISSUES: 'false' }, { RELEASE_COMMIT: 'b'.repeat(40) },
      { RELEASE_TAG: 'v0.8.4' }, { PRODUCTION_MODE: 'full' }, { GUARD_CHANGED_ANDROID: '1' },
      { GUARD_NOT_ANCESTOR: '1' }, { GUARD_REPLACED_TAG: '1' }]) denied(run('publish', overrides));
    for (const overrides of [{ ACCEPT_KNOWN_ISSUES: 'false' }, { GUARD_CHANGED_ANDROID: '1' },
      { GUARD_NOT_ANCESTOR: '1' }, { GUARD_REPLACED_TAG: '1' }]) denied(run('', overrides));
    writeFileSync(selectionPath, JSON.stringify({ ...selection, acceptance_waiver: null }));
    denied(run('publish'));
    rmSync(selectionPath);
    denied(run('publish'));
  } finally {
    rmSync(fixture, { recursive: true, force: true });
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
