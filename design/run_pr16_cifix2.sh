#!/bin/bash
# PR16 second CI fix dispatcher (missing DpRect import in Pr16UiTest).
set -uo pipefail

cd /root/dining_log || exit 1
mkdir -p logs

MAX_ATTEMPTS=8

read -r -d '' PROMPT <<'END_PROMPT'
You are the IMPLEMENTER fixing a Kotlin androidTest compile error on PR #16
(vampuke/dining-log).

Repository: /root/dining_log
Target branch: pr14-rebased  <-- PR #16's head. DO NOT create a new branch.

STEP 1: Read design/PR16_CI_FIX2_TASK.md in full and follow it exactly.
STEP 2: git fetch origin && git checkout pr14-rebased && git pull origin pr14-rebased
STEP 3: Add the missing import to Pr16UiTest.kt, then scan the whole file for other
        missing extension imports of the same class.
STEP 4: Run: cd /root/dining_log && python3 -B android/tools/verify_p6_visuals.py; echo exit=$?
        It MUST print exit=0.
STEP 5: Commit and push to pr14-rebased.

THE BUG: Pr16UiTest.kt:124 calls toggle.getUnclippedBoundsInRoot().height >= 48.dp.
DpRect.height is an extension property declared in androidx.compose.ui.unit.DpRect,
so the file needs "import androidx.compose.ui.unit.DpRect". Without it the compiler
tries to match Compose's GestureScope.height (Int) and then Int.compareTo, producing
"Unresolved reference ... receiver type mismatch". P8VisualTest.kt:32 already imports
DpRect for the same reason.

HARD RULES:
- Change imports ONLY. Do not alter any assertion or test logic, do not touch product
  code.
- Do NOT modify verify_bite_visuals.py, p7_baseline.json, p8_protected_hashes.json,
  backend/ or contracts/.
- Do NOT change versionCode/versionName. Do NOT merge the PR. Do NOT release.
- This host has ~956MB RAM: NO gradle, NO npm, NO docker, NO heavy builds.
  Static edits + python3 + git only.
- Be careful with Kotlin syntax and Compose scopes.

Report: the exact import lines you added, the verify_p6_visuals.py exit code, and the
new commit SHA.
END_PROMPT

source "$HOME/.codex/env"

for attempt in $(seq 1 $MAX_ATTEMPTS); do
    echo "=== attempt $attempt $(date +%H:%M:%S) ===" | tee -a logs/pr16_cifix2.log
    if timeout 2400 codex exec \
        --skip-git-repo-check \
        --dangerously-bypass-approvals-and-sandbox \
        -m gpt-6.1-sol \
        --output-last-message logs/pr16_cifix2_summary.md \
        "$PROMPT" >> logs/pr16_cifix2.log 2>&1; then
        echo "SUCCESS on attempt $attempt" | tee -a logs/pr16_cifix2.log
        exit 0
    fi
    rc=$?
    echo "attempt $attempt exit=$rc" | tee -a logs/pr16_cifix2.log
    if grep -q "token rate limit exceeded" logs/pr16_cifix2.log; then
        sleep 180
    else
        sleep 30
    fi
done

echo "FAILED after $MAX_ATTEMPTS attempts" | tee -a logs/pr16_cifix2.log
exit 1
