#!/bin/bash
# PR16 (carried from PR14) independent review dispatcher.
set -uo pipefail

cd /root/dining_log || exit 1
mkdir -p logs

MAX_ATTEMPTS=12

PROMPT="You are an INDEPENDENT REVIEWER for a pull request. You did NOT write this code.

Repository: /root/dining_log (git). Work from that directory.

TARGET: PR #16 (a rebase carry of PR #14) — branch pr14-rebased -> master.
Review range: git diff origin/master...origin/pr14-rebased  (8 files, +111 -24)

STEP 1: Read the full brief at design/PR16_REVIEW_TASK.md and follow it exactly.
STEP 2: Read every changed file IN FULL, not just the diff hunks. The diff is small
        but the surrounding code matters, especially in QuietGridComponents.kt,
        ListScreens.kt and DetailEditScreens.kt.
STEP 3: Answer all 25 numbered questions. Every answer needs file:line evidence.

CRITICAL CONTEXT:
- The author states this batch was NEVER COMPILED (no Gradle cache, wrapper download
  failed, compileSdk 35 missing in their sandbox). Compile errors are therefore the
  single most likely defect class. Check for @Composable invocations in non-composable
  scopes (DrawScope lambdas are NOT composable), missing imports, wrong signatures.
- A previous review of PR #13 found exactly that bug: LocalQuietGridColors.current
  called inside Canvas's DrawScope lambda. Check whether this batch repeats it.
- PR #13 (just merged) changed the same files. Verify this batch did not lose the
  unrated-rating empty-track logic in QuietGridComponents.kt, and that
  formatAbsoluteTime is still used in ListScreens.kt.

You may run: git, grep, sed, python3, bash. You may NOT run gradle/npm/docker or
anything heavy — this host only has ~956MB RAM. Static analysis and careful reading
only. Do not claim to have compiled or tested anything you did not.

Be adversarial. Your job is to find what is wrong, not to approve.

Write the report to design/pr16-review/REVIEW.md, then:
  git checkout -B pr16-review origin/master
  git add design/pr16-review/REVIEW.md
  git commit -m 'docs: independently review PR #16'
  git push origin pr16-review

Note: create the branch AFTER writing the file, or write to a temp path and copy."

source "$HOME/.codex/env"

for attempt in $(seq 1 $MAX_ATTEMPTS); do
    echo "=== attempt $attempt $(date +%H:%M:%S) ===" | tee -a logs/pr16_review.log
    if timeout 3600 codex exec \
        --skip-git-repo-check \
        --dangerously-bypass-approvals-and-sandbox \
        -m gpt-6.1-sol \
        --output-last-message logs/pr16_review_summary.md \
        "$PROMPT" >> logs/pr16_review.log 2>&1; then
        echo "SUCCESS on attempt $attempt" | tee -a logs/pr16_review.log
        exit 0
    fi
    rc=$?
    echo "attempt $attempt exit=$rc" | tee -a logs/pr16_review.log
    if grep -q "token rate limit exceeded" logs/pr16_review.log; then
        echo "rate limited, backing off" | tee -a logs/pr16_review.log
        sleep 180
    else
        sleep 30
    fi
done

echo "FAILED after $MAX_ATTEMPTS attempts" | tee -a logs/pr16_review.log
exit 1
