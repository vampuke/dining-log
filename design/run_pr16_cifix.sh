#!/bin/bash
# PR16 CI guard fix dispatcher.
set -uo pipefail

cd /root/dining_log || exit 1
mkdir -p logs

MAX_ATTEMPTS=8

read -r -d '' PROMPT <<'END_PROMPT'
You are the IMPLEMENTER fixing a CI failure on PR #16 (vampuke/dining-log).

Repository: /root/dining_log
Target branch: pr14-rebased  <-- this is PR #16's head. DO NOT create a new branch.

STEP 1: Read design/PR16_CI_FIX_TASK.md in full and follow it exactly.
STEP 2: git fetch origin && git checkout pr14-rebased && git pull origin pr14-rebased
STEP 3: Apply the one-line fix described in the brief.
STEP 4: Run: cd /root/dining_log && python3 -B android/tools/verify_p6_visuals.py; echo exit=$?
        It MUST print exit=0.
STEP 5: Commit and push to pr14-rebased.

CONTEXT: The guard script verify_bite_visuals.py extracts testTags with the regex
  testTag\(("(?:[^"\\]|\\.)*"|tag)\)
so only literal string arguments are detected. The current code passes a conditional
expression to testTag(), so the guard believes history-absolute was deleted. Fix the
call site, NEVER weaken the guard, never edit p7_baseline.json, never whitelist the tag.

HARD RULES:
- Do NOT modify android/tools/verify_bite_visuals.py, p7_baseline.json, or the
  permitted_tag_removals set.
- Do NOT touch backend/, contracts/, p8_protected_hashes.json, versionCode, versionName.
- This host has ~956MB RAM: NO gradle, NO npm, NO docker, NO heavy builds. Static
  edits + python3 + git only.
- Do NOT merge the PR and do NOT release. Push the branch and stop.
- Keep the unrated empty-track logic in QuietGridComponents.kt and formatAbsoluteTime.
- Be careful with Kotlin syntax, imports and Compose scopes.

Report: what you changed, the exact verify_p6_visuals.py exit code, and the new commit SHA.
END_PROMPT

source "$HOME/.codex/env"

for attempt in $(seq 1 $MAX_ATTEMPTS); do
    echo "=== attempt $attempt $(date +%H:%M:%S) ===" | tee -a logs/pr16_cifix.log
    if timeout 2400 codex exec \
        --skip-git-repo-check \
        --dangerously-bypass-approvals-and-sandbox \
        -m gpt-6.1-sol \
        --output-last-message logs/pr16_cifix_summary.md \
        "$PROMPT" >> logs/pr16_cifix.log 2>&1; then
        echo "SUCCESS on attempt $attempt" | tee -a logs/pr16_cifix.log
        exit 0
    fi
    rc=$?
    echo "attempt $attempt exit=$rc" | tee -a logs/pr16_cifix.log
    if grep -q "token rate limit exceeded" logs/pr16_cifix.log; then
        sleep 180
    else
        sleep 30
    fi
done

echo "FAILED after $MAX_ATTEMPTS attempts" | tee -a logs/pr16_cifix.log
exit 1
