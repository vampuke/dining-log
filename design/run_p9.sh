#!/bin/bash
# P9 dispatcher: fix all findings from the independent P8 review.
set -uo pipefail

cd /root/dining_log || exit 1
mkdir -p logs

BRANCH="p8-ux-fixes"
MAX_ATTEMPTS=12

PROMPT="You are the implementer for phase P9 of an Android app called 食记. P9 fixes the findings of an independent review of phase P8.

FIRST read these completely, in order:
1. design/P9_FIX_TASK.md       (your instruction: R1 through R8)
2. design/p8-review/REVIEW.md  (the review report being acted on)

Then switch to the existing branch ${BRANCH} and commit the fixes THERE. Do not create a new branch.

Summary of what must be fixed (details and exact file:line are in design/P9_FIX_TASK.md):
- R1 MAJOR: UpdateDialog.kt:23 uses fillMaxHeight(0.9f) leaving ~half the panel empty. Make it content-sized with a capped heightIn and keep internal scrolling.
- R2 MAJOR: Rotating the device discards unsaved editor input because LaunchedEffect re-runs openEditor which unconditionally resets state. Make openEditor idempotent per (ref, create) key and preserve input/saving/errors in memory.
- R3 MAJOR: Moving labels outside the field removed the accessible field name. Attach a real accessibleLabel to the editable node and suppress the duplicate standalone label semantics.
- R4 MAJOR: In landscape the floating capsule ignores horizontal safe insets. Apply WindowInsets.safeDrawing horizontal insets before the 20dp margins.
- R5 MINOR: Interrupted downloads leave orphan partial APKs. Stream to a .part file, rename after hash verification, and sweep orphans on init.
- R6 MINOR: The input-state matrix lacks 200% font coverage and only asserts colors. Add font scales 1 and 2 and assert layout bounds (no overlap/clipping).
- R7 MINOR: choose-restaurant / clear-restaurant menu branches are unreachable after the chooser route was deleted. Remove them and assert absence.
- R8 NIT: Remove unused baseVersion param, unused imports in ProductRoot.kt and MainActivity.kt.

Hard rules:
1. Do NOT touch backend/ or contracts/. Do NOT modify backend/migrations/*.sql.
2. Do NOT change versionCode or versionName (keep 7 / 0.7.0).
3. No bitmap/photo assets; VectorDrawable only.
4. Verify every visual fix in BOTH light and dark mode.
5. Do NOT delete existing test assertions to make tests pass - update them to match new behavior, and ADD assertions for the newly fixed behavior.
6. Do NOT merge the PR and do NOT trigger any release.

You cannot run gradle or the emulator locally. Rely on careful reading, adding correct tests, and CI on push. Commit in small logical steps per finding (R1..R8), push the branch.

Finish by writing a summary to logs/p9_summary.md mapping each of R1-R8 to the fix location (file:line) and stating what could not be verified."

echo "=== P9 fixes ==="
attempt=0
while [ $attempt -lt $MAX_ATTEMPTS ]; do
    attempt=$((attempt + 1))
    echo "=== attempt $attempt $(date +%H:%M:%S) ==="
    git checkout "$BRANCH" -q && git pull -q origin "$BRANCH" 2>/dev/null

    source "$HOME/.codex/env"
    codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
        -m gpt-6.1-sol -C /root/dining_log \
        --output-last-message logs/p9_last.txt \
        "$PROMPT" > "logs/p9_attempt${attempt}.log" 2>&1
    code=$?

    if [ $code -eq 0 ]; then
        echo "attempt $attempt exit=0"
        if grep -qi "token rate limit exceeded" "logs/p9_attempt${attempt}.log"; then
            echo "rate limited, backoff 180s"; sleep 180; continue
        fi
        echo "SUCCESS on attempt $attempt"
        break
    fi

    echo "attempt $attempt exit=$code"
    if grep -qi "token rate limit exceeded" "logs/p9_attempt${attempt}.log"; then
        echo "rate limited, backoff 180s"; sleep 180; continue
    fi
    sleep 30
done

echo "=== branch state ==="
git log --oneline -12 "$BRANCH"
