import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { validateInputs, sourceVersion, acceptanceWaiver, requireCandidate, requireIncreasingTag, requireValue } from './release-guards.mjs';

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
  const mode = process.env.PRODUCTION_MODE;
  const waiver = acceptanceWaiver(process.env.ACCEPT_KNOWN_ISSUES, tag, mode);
  let androidTree = null;
  if (waiver) {
    execFileSync('git', ['merge-base', '--is-ancestor', waiver.approved_commit, commit]);
    androidTree = execFileSync('git', ['rev-parse', `${commit}:android`], { encoding: 'utf8' }).trim();
    const approvedTree = execFileSync('git', ['rev-parse', `${waiver.approved_commit}:android`], { encoding: 'utf8' }).trim();
    requireValue(androidTree === approvedTree, 'Waived Android tree differs from the approved PR18 head');
  }
  const gradle = readFileSync('android/app/build.gradle.kts', 'utf8');
  const { versionCode, versionName } = sourceVersion(gradle, tag);
  await requireUnusedTag();
  if (process.argv[2] === 'publish') {
    const selection = JSON.parse(readFileSync(join(temp, 'dining-release-selection.json'), 'utf8'));
    requireValue(selection.commit === commit && selection.tag === tag && selection.versionCode === versionCode
      && selection.versionName === versionName && selection.production_mode === mode
      && isDeepStrictEqual(selection.acceptance_waiver, waiver) && selection.android_tree === androidTree,
    'Publish identity or acceptance waiver differs from the recorded release selection');
    process.exit(0);
  }
  const runs = await api(`/actions/workflows/ci.yml/runs?head_sha=${commit}&event=push&per_page=100`);
  const candidate = runs.workflow_runs.find(run => run.head_sha === commit && run.head_branch === 'master' && run.event === 'push'
    && (waiver || (run.status === 'completed' && run.conclusion === 'success')));
  requireValue(candidate, 'No matching acceptable exact-master push CI candidate');
  const jobs = await api(`/actions/runs/${candidate.id}/jobs?per_page=100`);
  requireValue(!jobs.total_count || jobs.total_count <= jobs.jobs.length, 'Candidate job list is incomplete');
  requireCandidate(candidate, jobs.jobs, commit, tag, mode, process.env.ACCEPT_KNOWN_ISSUES);
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
    production_mode: mode, acceptance_waiver: waiver, android_tree: androidTree,
    candidate: { id: candidate.id, head_sha: candidate.head_sha, head_branch: candidate.head_branch,
      event: candidate.event, status: candidate.status, conclusion: candidate.conclusion, url: candidate.html_url,
      jobs: jobs.jobs.map(job => ({ id: job.id, name: job.name, status: job.status,
        conclusion: job.conclusion, url: job.html_url })) },
    previous: previous ? { tag: previous.tag_name, url: previous.html_url } : null };
  writeFileSync(join(temp, 'dining-release-selection.json'), `${JSON.stringify(record, null, 2)}\n`);
  console.log(JSON.stringify(record));
} catch (error) {
  console.error(error instanceof Error ? error.message : 'Release selection validation failed');
  process.exitCode = 1;
}
