#!/bin/bash
# P9c: fix the flaky ScreenshotSecurityTest navigation step. Test-only change.
set -uo pipefail

cd /root/dining_log || exit 1
mkdir -p logs

BRANCH="p8-ux-fixes"
MAX_ATTEMPTS=10

PROMPT="You must fix ONE failing androidTest in an Android app called 食记. This is a TEST-ONLY fix; do not change product behavior.

Failing test: android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/ScreenshotSecurityTest.kt
Test name: passwordDismissalAndNavigationRestoreScreenshotsInBothThemes

CI failure on BOTH API 26 and API 35:
  java.lang.AssertionError: Failed to inject touch input.
  Reason: Expected exactly '1' node but could not find any node that satisfies: (TestTag = 'tab:restaurants')

The failing line is near the end of the loop:
  compose.onNodeWithTag(\"tab:restaurants\").performClick()

Context: every FLAG_SECURE assertion BEFORE that line passed (restaurant list = not secure, settings = not secure, password dialog = secure, settings after dismissal = not secure). So the product behavior is correct; only the navigation step is broken.

Diagnosis to start from (verify it yourself, do not blindly trust it):
- The test harness composes a minimal fake tree in setContent: if tab==\"settings\" it renders SettingsScreen(...) with a tabs lambda, else RecordListScreen(...) with the same tabs lambda.
- The tabs lambda calls BottomTabs(tab) { tab = it }, defined in android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/ProductRoot.kt around line 196-217. Each tab item is a Surface with Modifier.weight(1f) and a testTag of the form tab:<id>.
- Both RecordListScreen and SettingsScreen render their tab bar through ScreenFrame(..., floatingBottomBar = true), where the capsule is drawn as an overlay inside a Box rather than as Scaffold.bottomBar.
- The password dialog is a Dialog (separate window). After dismissing it, the assertions still pass, so the composition is alive.

Your job:
1. Reproduce the reasoning: determine why tab:restaurants is not resolvable at that exact point (merged vs unmerged semantics tree, node inside the Dialog window, conditional composition, or the overlay Box). State the actual cause in the commit message.
2. Fix the test so it navigates robustly. Acceptable approaches: query with useUnmergedTree = true; assertExists() before clicking to fail with a clear message; or restructure the harness so the tabs live outside the ScreenFrame overlay. Choose the one that keeps the test meaningful.
3. Do NOT weaken or delete the FLAG_SECURE assertions. They are the point of this test and they currently pass. Keep all four: normal screen not secure, sensitive screen secure, after dismissal not secure, and after navigating away not secure.
4. Keep the test running in BOTH light and dark themes.

Hard rules:
- Test-only change. Do not modify product source under android/*/src/main.
- Do NOT touch backend/ or contracts/.
- Do NOT change versionCode or versionName (keep 7 / 0.7.0).
- Do NOT merge the PR and do NOT trigger any release.
- Do NOT delete assertions to make CI green.

You cannot run gradle or the emulator locally, so reason carefully from the Compose semantics behavior and CI logs. Commit, push branch ${BRANCH}, and confirm CI reasoning in your summary.

Write a summary to logs/p9c_summary.md with the root cause and the exact fix."

echo "=== P9c fix screenshot test ==="
attempt=0
while [ $attempt -lt $MAX_ATTEMPTS ]; do
    attempt=$((attempt + 1))
    echo "=== attempt $attempt $(date +%H:%M:%S) ==="
    git checkout "$BRANCH" -q && git pull -q origin "$BRANCH" 2>/dev/null

    source "$HOME/.codex/env"
    codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
        -m gpt-6.1-sol -C /root/dining_log \
        --output-last-message logs/p9c_last.txt \
        "$PROMPT" > "logs/p9c_attempt${attempt}.log" 2>&1
    code=$?

    if [ $code -eq 0 ]; then
        echo "attempt $attempt exit=0"
        if grep -qi "token rate limit exceeded" "logs/p9c_attempt${attempt}.log"; then
            echo "rate limited, backoff 180s"; sleep 180; continue
        fi
        echo "SUCCESS on attempt $attempt"
        break
    fi
    echo "attempt $attempt exit=$code"
    if grep -qi "token rate limit exceeded" "logs/p9c_attempt${attempt}.log"; then
        echo "rate limited, backoff 180s"; sleep 180; continue
    fi
    sleep 30
done

echo "=== branch state ==="
git log --oneline -5 "$BRANCH"
