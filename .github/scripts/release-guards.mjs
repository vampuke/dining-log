export function requireValue(condition, message) {
  if (!condition) throw new Error(message);
}

export function validateInputs(commit, tag, repo, ref) {
  requireValue(typeof commit === 'string' && commit.length === 40 && /^[0-9a-fA-F]{40}$/.test(commit), 'commit_sha must be 40 hexadecimal characters');
  requireValue(typeof tag === 'string' && tag.trim() === tag && /^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(tag), 'release_tag must be vX.Y.Z');
  requireValue(repo === 'vampuke/dining-log', 'Unexpected repository');
  requireValue(ref === 'refs/heads/master', 'Dispatch release from master');
  return commit.toLowerCase();
}

export function sourceVersion(gradle, tag) {
  const versionCode = Number(gradle.match(/^\s*versionCode\s*=\s*(\d+)\s*$/m)?.[1]);
  const versionName = gradle.match(/^\s*versionName\s*=\s*"([^"]+)"\s*$/m)?.[1];
  requireValue(Number.isSafeInteger(versionCode) && versionCode > 0 && `v${versionName}` === tag, 'Source version and release tag differ');
  return { versionCode, versionName };
}

export function requireCandidateJobs(jobs) {
  for (const name of ['backend', 'android', 'android-auth-instrumentation (26)', 'android-auth-instrumentation (35)']) {
    requireValue(jobs.some(job => job.name === name && job.status === 'completed' && job.conclusion === 'success'), `Required candidate job has not passed: ${name}`);
  }
}

export function requireIncreasingTag(tag, previousTag) {
  const old = /^v(\d+)\.(\d+)\.(\d+)$/.exec(previousTag);
  requireValue(old, 'Previous stable release does not have a supported version tag');
  const current = tag.slice(1).split('.').map(Number), former = old.slice(1).map(Number);
  requireValue([...current, ...former].every(Number.isSafeInteger), 'Release version components are outside the supported range');
  const difference = current.findIndex((part, index) => part !== former[index]);
  requireValue(difference >= 0 && current[difference] > former[difference], 'Release version must increase');
}

export function requireTimeTravelBookmark(info) {
  requireValue(typeof info?.bookmark === 'string' && info.bookmark.trim().length > 0,
    'Time Travel returned no usable checkpoint bookmark');
}

export function permitsEmptyHistory(tableCount, diagnostic) {
  return tableCount === 0 && /(?:no (?:available )?(?:history|bookmark)|database (?:is )?empty|no time.?travel history)/i.test(diagnostic)
    && !/(?:unauthori[sz]ed|forbidden|permission|authentication|timed? ?out|network|fetch failed|unknown argument)/i.test(diagnostic);
}

export function requireDomainChangeset(changeset) {
  requireValue(Array.isArray(changeset?.updated) && Array.isArray(changeset?.conflicting),
    'Custom Domain changeset has an unexpected response shape');
  requireValue(changeset.conflicting.length === 0, 'Custom Domain conflicts with existing DNS; deployment stopped');
  requireValue(changeset.updated.every(domain => domain.modified === false),
    'Custom Domain would replace an existing origin; deployment stopped');
}
