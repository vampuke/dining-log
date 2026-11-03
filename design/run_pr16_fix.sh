#!/bin/bash
# PR16 fix dispatcher — applies the independent review findings.
set -uo pipefail

cd /root/dining_log || exit 1
mkdir -p logs

MAX_ATTEMPTS=12

PROMPT="You are the IMPLEMENTER. An independent review (design/pr16-review/REVIEW.md)
found that PR #16 must not merge. Your job is to fix every finding.

Repository: /root/dining_log
Target branch: pr16-rebased  (this is PR #16's head branch)

STEP 1: Read design/PR16_FIX_TASK.md in full and follow it exactly.
STEP 2: Read design/pr16-review/REVIEW.md for the evidence behind each finding.
STEP 3: git checkout pr16-rebased && git pull origin pr16-rebased
STEP 4: Fix B1, B2, B3, M1, M2, M3, M4, M5, M6, N1, N2, N4. Skip N3.
STEP 5: Add the tests listed under '必须补的测试'.
STEP 6: Commit with numbered findings in the message, push the branch.

HARD RULES:
- B1 is a hard compile error: AppUpdateChecker's `gateway` and `installedVersionCode`
  constructor params lack `val`, so checkNow() cannot reference them. Fix it properly.
- Do NOT touch backend/ or contracts/.
- Do NOT change versionCode / versionName.
- Do NOT merge the PR and do NOT release. Push the branch and stop.
- Do NOT lose the unrated-rating empty track logic in QuietGridComponents.kt
  (merged in #15) and keep formatAbsoluteTime in ListScreens.kt.
- Do NOT run gradle / npm / docker / any heavy build on this host: it has ~956MB RAM
  and would crash the machine. Static edits + git only. CI will compile.
- Because you cannot compile locally, be extra careful with Kotlin syntax,
  imports, and Compose scopes (DrawScope lambdas are NOT composable).
- If a finding turns out to be wrong, say so explicitly with evidence instead of
  silently skipping it.

Report at the end: what you fixed, what you did not, and anything you could not verify."

source "$HOME/.codex/env"

for attempt in $(seq 1 $MAX_ATTEMPTS); do
    echo "=== attempt $attempt $(date +%H:%M:%S) ===" | tee -a logs/pr16_fix.log
    if timeout 3600 codex exec \
        --skip-git-repo-check \
        --dangerously-bypass-approvals-and-sandbox \
        -m gpt-6.1-sol \
        --output-last-message logs/pr16_fix_summary.md \
        "$PROMPT" >> logs/pr16_fix.log 2>&1; then
        echo "SUCCESS on attempt $attempt" | tee -a logs/pr16_fix.log
        exit 0
    fi
    rc=$?
    echo "attempt $attempt exit=$rc" | tee -a logs/pr16_fix.log
    if grep -q "token rate limit exceeded" logs/pr16_fix.log; then
        echo "rate limited, backing off" | tee -a logs/pr16_fix.log
        sleep 180
    else
        sleep 30
    fi
done

echo "FAILED after $MAX_ATTEMPTS attempts" | tee -a logs/pr16_fix.log
exit 1
