import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { validateInputs, sourceVersion, requireCandidateJobs, requireIncreasingTag,
  requireTimeTravelBookmark, permitsEmptyHistory, requireDomainChangeset } from './release-guards.mjs';

const sha = 'a'.repeat(40), repo = 'vampuke/dining-log', ref = 'refs/heads/master';

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
  assert.deepEqual(sourceVersion(gradle, 'v0.5.0'), { versionCode: 5, versionName: '0.5.0' });
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
