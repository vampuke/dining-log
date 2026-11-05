import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { validateInputs, sourceVersion, requireCandidateJobs, requireIncreasingTag, requireValue } from './release-guards.mjs';

const commit = validateInputs(process.env.RELEASE_COMMIT, process.env.RELEASE_TAG, process.env.GITHUB_REPOSITORY, process.env.GITHUB_REF);
const tag = process.env.RELEASE_TAG;
const repo = process.env.GITHUB_REPOSITORY;
const temp = process.env.RUNNER_TEMP;
requireValue(temp && process.env.GH_TOKEN, 'Missing runner directory or read-only GitHub authentication');

async function api(path, absentOK = false) {
  const response = await fetch(`https://api.github.com/repos/${repo}${path}`, {
    headers: { Authorization: `Bearer ${process.env.GH_TOKEN}`, Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28' }, signal: AbortSignal.timeout(20000),
  });
  if (absentOK && response.status === 404) return null;
  requireValue(response.ok, `GitHub release validation failed (HTTP ${response.status})`);
  return response.json();
}
async function requireUnusedTag() {
  requireValue(await api(`/releases/tags/${encodeURIComponent(tag)}`, true) === null, 'Release already exists; refusing to overwrite');
  // The dispatch may pass a pre-existing tag ref (checkout by tag avoids
  // GitHub's "not our ref" SHA fetch refusal). That is safe only when the tag
  // points exactly at the reviewed commit; otherwise refuse to overwrite.
  const ref = await api(`/git/ref/tags/${encodeURIComponent(tag)}`, true);
  if (ref !== null) requireValue(ref.object?.sha === commit, 'Tag already exists; refusing to replace it');
}

try {
  requireValue(execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim() === commit, 'Checked-out HEAD does not match commit_sha');
  execFileSync('git', ['fetch', 'origin', 'master', '--no-tags'], { stdio: 'inherit' });
  execFileSync('git', ['merge-base', '--is-ancestor', commit, 'origin/master']);
  await requireUnusedTag();
  if (process.argv[2] === 'publish') process.exit(0);
  const runs = await api(`/actions/workflows/ci.yml/runs?head_sha=${commit}&event=push&per_page=100`);
  const candidate = runs.workflow_runs.find(run => run.head_sha === commit && run.head_branch === 'master' && run.event === 'push'
    && run.status === 'completed' && run.conclusion === 'success');
  requireValue(candidate, 'This master commit has no successful completed regular CI candidate');
  const jobs = await api(`/actions/runs/${candidate.id}/jobs?per_page=100`);
  requireCandidateJobs(jobs.jobs);
  const gradle = readFileSync('android/app/build.gradle.kts', 'utf8');
  const { versionCode, versionName } = sourceVersion(gradle, tag);
  const releases = await api('/releases?per_page=100');
  const previous = releases.find(release => !release.draft && !release.prerelease);
  if (previous) {
    requireIncreasingTag(tag, previous.tag_name);
    const asset = previous.assets.find(item => item.name === 'app-release.apk');
    requireValue(asset, 'Previous stable release has no signed APK to compare');
    const response = await fetch(`https://api.github.com/repos/${repo}/releases/assets/${asset.id}`, {
      headers: { Authorization: `Bearer ${process.env.GH_TOKEN}`, Accept: 'application/octet-stream',
        'X-GitHub-Api-Version': '2022-11-28' }, signal: AbortSignal.timeout(60000),
    });
    requireValue(response.ok, `Previous APK download failed (HTTP ${response.status})`);
    writeFileSync(join(temp, 'dining-previous-release.apk'), Buffer.from(await response.arrayBuffer()), { mode: 0o600 });
  }
  const record = { commit, tag, versionCode, versionName, candidate_ci: candidate.html_url,
    previous: previous ? { tag: previous.tag_name, url: previous.html_url } : null };
  writeFileSync(join(temp, 'dining-release-selection.json'), `${JSON.stringify(record, null, 2)}\n`);
  console.log(JSON.stringify(record));
} catch (error) {
  console.error(error instanceof Error ? error.message : 'Release selection validation failed');
  process.exitCode = 1;
}
