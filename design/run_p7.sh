#!/bin/bash
# P7 dispatcher: BITE! sticker-style visual redesign
set -uo pipefail

cd /root/dining_log || exit 1
mkdir -p logs

BRANCH="p7-bite-sticker-redesign"
MAX_ATTEMPTS=12

PROMPT="You are the sole implementer for P7 of the 食记 / dining-log Android app.

Working directory: /root/dining_log (git repo, currently on master).

FIRST: read design/P7_TASK.md IN FULL. Then open and read design/bite/BITE_DESIGN_REFERENCE.html — it is the authoritative visual reference and contains hand-drawn SVG icon paths you should convert to VectorDrawable rather than inventing your own.

GOAL: Restyle the Android app from the current D2 Quiet Grid look to the BITE! Neo-Brutalist pop-sticker look shown in the reference.

HARD CONSTRAINTS (violating any of these is failure):
1. VISUAL ONLY. Do not change business logic, API calls, data model semantics, Room caching, draft state machine, conflict handling, or auth flow.
2. Do NOT modify the backend at all: no new endpoints, no migrations, no contract changes.
3. The reference HTML is LIGHT MODE ONLY. You MUST design a complete dark theme with its own token table. Dark mode is a hard requirement — the user keeps their phone in dark mode. Do not just invert colors: off-black strokes on a dark background are invisible, so stroke and shadow colors must change, not just backgrounds.
4. Two color pairs in the reference FAIL WCAG contrast and must not ship as-is: white text on #FF595E (3.07:1) and white text on #3A86FF (3.48:1). Use near-black #111111 text on those fills instead (measured 6.15:1 and 5.43:1). All body text must be >=4.5:1 in BOTH themes.
5. NO bitmap assets. Vector XML / Compose Canvas only. No png/jpg/webp.
6. The reference prototype's data model does not match this app. Section 1.3 of the task lists the three mismatches (dish verdict tiers, restaurant visit date, aggregate stats). Derive them from existing fields or omit them — NEVER fabricate numbers and NEVER add a backend endpoint for them.
7. Preserve every existing testTag. Existing instrumentation tests depend on them.
8. Do not weaken test assertions. If a selector changes, explain why per item.

WORKFLOW:
- Create branch ${BRANCH} from master. Do not push to master.
- Commit in logical chunks, English commit messages.
- Run what you can locally: backend untouched so just confirm it is untouched; for Android run the unit tests and lint you can afford.
- Write P7_DONE.md with: light+dark token tables, a measured contrast table (both themes, real numbers, zero failures), screenshot evidence for list/detail/editor in both themes, and the list of VectorDrawable files you created.

Be concrete and honest. If something is unverified, say so with the reason. Do the work now."

attempt=1
while [ $attempt -le $MAX_ATTEMPTS ]; do
  ts=$(date +%H:%M:%S)
  echo "=== attempt $attempt $ts ==="
  log="logs/p7_attempt${attempt}.log"

  if git show-ref --verify --quiet "refs/heads/${BRANCH}"; then
    git checkout "${BRANCH}" >/dev/null 2>&1
  else
    git checkout master >/dev/null 2>&1
    git checkout -b "${BRANCH}" >/dev/null 2>&1
  fi

  source "$HOME/.codex/env" 2>/dev/null
  codex exec --skip-git-repo-check \
    --dangerously-bypass-approvals-and-sandbox \
    -m gpt-6.1-sol \
    --output-last-message "logs/p7_summary.md" \
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
