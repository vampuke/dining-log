#!/bin/bash
# P9b dispatcher: R9 - allow screenshots outside sensitive screens.
set -uo pipefail

cd /root/dining_log || exit 1
mkdir -p logs

BRANCH="p8-ux-fixes"
MAX_ATTEMPTS=12

PROMPT="You are adding one more fix (R9) to phase P9 of an Android app called 食记.

FIRST read design/P9_R9_SCREENSHOT.md completely. That is your instruction.

Core problem: android/app/src/main/kotlin/top/alanvampuke/dininglog/MainActivity.kt line 58 does window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) globally in onCreate. This was a P2 security measure (protect password / recovery-code / session-token display) but it blocks screenshots and screen recording EVERYWHERE in the app. The user wants screenshots to work.

Required outcome (do NOT just delete the line):
1. Remove the global addFlags in onCreate so the default state is screenshot-allowed.
2. Keep FLAG_SECURE ONLY while these screens are visible: the auth screens (AuthPage.LOGIN / REGISTER / RECOVER), the one-time recovery-code display screen, and the change-password flow.
3. Implement it as a reusable composable (e.g. SecureScreen(enabled: Boolean) { content }) using DisposableEffect that addFlags when entering a sensitive screen and clearFlags when leaving. Clearing on exit is the part most likely to be botched - a leftover flag would keep every later screen un-screenshotable.
4. Do not change the existing sensitive-clipboard handling for recovery codes.

Verification you must add:
- androidTest assertions reading activity.window.attributes.flags against WindowManager.LayoutParams.FLAG_SECURE: assert SET on a sensitive screen, ASSERT CLEAR on a normal screen (restaurant list), and assert it returns to CLEAR after navigating sensitive -> normal.

Hard rules:
1. Work on the existing branch ${BRANCH}; do not create a new branch.
2. Do NOT touch backend/ or contracts/; do NOT modify backend/migrations/*.sql.
3. Do NOT change versionCode or versionName (keep 7 / 0.7.0).
4. Dark mode must stay correct.
5. Do NOT delete existing test assertions to make tests pass.
6. Do NOT merge the PR and do NOT trigger any release.

You cannot run gradle or the emulator locally. Commit in small logical steps, push the branch.

Finish by writing a summary to logs/p9b_summary.md with exact file:line of each change and what could not be verified."

echo "=== P9b R9 screenshots ==="
attempt=0
while [ $attempt -lt $MAX_ATTEMPTS ]; do
    attempt=$((attempt + 1))
    echo "=== attempt $attempt $(date +%H:%M:%S) ==="
    git checkout "$BRANCH" -q && git pull -q origin "$BRANCH" 2>/dev/null

    source "$HOME/.codex/env"
    codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
        -m gpt-6.1-sol -C /root/dining_log \
        --output-last-message logs/p9b_last.txt \
        "$PROMPT" > "logs/p9b_attempt${attempt}.log" 2>&1
    code=$?

    if [ $code -eq 0 ]; then
        echo "attempt $attempt exit=0"
        if grep -qi "token rate limit exceeded" "logs/p9b_attempt${attempt}.log"; then
            echo "rate limited, backoff 180s"; sleep 180; continue
        fi
        echo "SUCCESS on attempt $attempt"
        break
    fi

    echo "attempt $attempt exit=$code"
    if grep -qi "token rate limit exceeded" "logs/p9b_attempt${attempt}.log"; then
        echo "rate limited, backoff 180s"; sleep 180; continue
    fi
    sleep 30
done

echo "=== branch state ==="
git log --oneline -8 "$BRANCH"
