#!/bin/bash
# PR16 third CI fix dispatcher (DpRect extension property, explicit rewrite).
set -uo pipefail

cd /root/dining_log || exit 1
mkdir -p logs

MAX_ATTEMPTS=8

read -r -d '' PROMPT <<'END_PROMPT'
You are the IMPLEMENTER fixing a Kotlin androidTest compile error on PR #16
(vampuke/dining-log). The previous attempt failed, so this brief specifies the
exact code to write. Do not invent a different approach.

Repository: /root/dining_log
Target branch: pr14-rebased  <-- PR #16's head. DO NOT create a new branch.

STEP 1: Read design/PR16_CI_FIX3_TASK.md in full and follow it exactly.
STEP 2: git fetch origin && git checkout pr14-rebased && git pull origin pr14-rebased
STEP 3: In android/app/src/androidTest/kotlin/top/alanvampuke/dininglog/product/Pr16UiTest.kt

  (a) Replace line 125
        assertTrue(toggle.getUnclippedBoundsInRoot().height >= 48.dp)
      with
        val bounds = toggle.getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top >= 48.dp)

  (b) Delete the now-useless import line
        import androidx.compose.ui.unit.DpRect

STEP 4: Run: cd /root/dining_log && python3 -B android/tools/verify_p6_visuals.py; echo exit=$?
        It MUST print exit=0.
STEP 5: Commit and push to pr14-rebased.

WHY THE LAST FIX FAILED: DpRect is a class, but DpRect.height is a TOP-LEVEL
extension property in package androidx.compose.ui.unit. Importing the class does
NOT import the extension property, so .height stayed unresolved and the compiler
fell back to GestureScope.height (Int) with a receiver mismatch. The prescribed
rewrite uses DpRect's MEMBER properties bottom/top plus the Dp minus/compareTo
member operators, which need no imports at all.

HARD RULES:
- Change ONLY those two spots. Do not touch any other assertion, any test logic,
  or any product code.
- Do NOT modify verify_bite_visuals.py, p7_baseline.json, p8_protected_hashes.json,
  backend/ or contracts/.
- Do NOT change versionCode/versionName. Do NOT merge the PR. Do NOT release.
- This host has ~956MB RAM: NO gradle, NO npm, NO docker, NO heavy builds.
  Static edits + python3 + git only. You cannot compile, so follow the brief verbatim.

Report: the exact final diff of the changed lines, the verify_p6_visuals.py exit
code, and the new commit SHA.
END_PROMPT

source "$HOME/.codex/env"

for attempt in $(seq 1 $MAX_ATTEMPTS); do
    echo "=== attempt $attempt $(date +%H:%M:%S) ===" | tee -a logs/pr16_cifix3.log
    if timeout 2400 codex exec \
        --skip-git-repo-check \
        --dangerously-bypass-approvals-and-sandbox \
        -m gpt-6.1-sol \
        --output-last-message logs/pr16_cifix3_summary.md \
        "$PROMPT" >> logs/pr16_cifix3.log 2>&1; then
        echo "SUCCESS on attempt $attempt" | tee -a logs/pr16_cifix3.log
        exit 0
    fi
    rc=$?
    echo "attempt $attempt exit=$rc" | tee -a logs/pr16_cifix3.log
    if grep -q "token rate limit exceeded" logs/pr16_cifix3.log; then
        sleep 180
    else
        sleep 30
    fi
done

echo "FAILED after $MAX_ATTEMPTS attempts" | tee -a logs/pr16_cifix3.log
exit 1
