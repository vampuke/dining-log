#!/bin/bash
# P6 dispatcher: account endpoints + migration 0002 + UI fixes + in-app update
set -uo pipefail

cd /root/dining_log || exit 1
mkdir -p logs

TASK_FILE="design/P6_TASK.md"
BRANCH="p6-account-endpoints-ui-update"
MAX_ATTEMPTS=12

PROMPT="You are the sole implementer for P6 of the 食记 (dining-log) project.

Working directory: /root/dining_log (git repo, currently on master).

READ design/P6_TASK.md IN FULL FIRST. It contains three workflows (A, B, C) and you MUST complete all three.

CRITICAL SAFETY RULES:
1. NEVER modify backend/migrations/0001_initial.sql. Its SHA-256 must remain 191d1a80a7411ba828effc9041fbd144fa4d10abb372f61c793a8f6dc1a5b80b. Verify with sha256sum before and after.
2. Workflow B (migration 0002) has a documented data-loss trap. Read section B0 and B1 carefully. A naive DROP TABLE restaurants will CASCADE-delete dishes, visits, restaurant_search and revisions while PRAGMA foreign_key_check still reports zero issues. You MUST use the backup-and-restore pattern in B1, and you MUST write and actually run the verification script from B2.
3. Do not write any secret, token, password or keystore material into the repo, logs, or docs.
4. Do not weaken test assertions to make things pass. If something is unverified, say so honestly in P6_DONE.md with the reason.

WORKFLOW:
- Create branch ${BRANCH} from master and do all work there. Do NOT push to master.
- Commit in logical chunks with English commit messages.
- Run the backend test suite and the Android build/tests that you can run. CI will run the full emulator matrix.
- When done, write P6_DONE.md at repo root: go through design/P6_TASK.md item by item and mark each as verified or unverified with concrete evidence (command output, file path, line number). No hand-waving.

Do the work now. Be thorough; this is a production codebase with real data in production D1."

attempt=1
while [ $attempt -le $MAX_ATTEMPTS ]; do
  ts=$(date +%H:%M:%S)
  echo "=== attempt $attempt $ts ==="
  log="logs/p6_attempt${attempt}.log"

  # ensure branch exists / checkout
  if git show-ref --verify --quiet "refs/heads/${BRANCH}"; then
    git checkout "${BRANCH}" >/dev/null 2>&1
  else
    git checkout -b "${BRANCH}" >/dev/null 2>&1
  fi

  source "$HOME/.codex/env" 2>/dev/null
  codex exec --skip-git-repo-check \
    --dangerously-bypass-approvals-and-sandbox \
    -m gpt-6.1-sol \
    --output-last-message "logs/p6_summary.md" \
    "$PROMPT" > "$log" 2>&1
  rc=$?

  echo "attempt $attempt exit=$rc"

  if [ $rc -eq 0 ]; then
    echo "SUCCESS on attempt $attempt"
    exit 0
  fi

  if grep -qiE "rate limit|429|too many requests|quota" "$log"; then
    echo "rate limited, backoff 180s"
    sleep 180
  else
    echo "non-rate-limit failure; inspect $log"
    sleep 30
  fi

  attempt=$((attempt + 1))
done

echo "ALL FAILED"
exit 1
