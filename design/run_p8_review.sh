#!/bin/bash
# P8 independent review dispatcher. Runs in a FRESH codex session.
set -uo pipefail

cd /root/dining_log || exit 1
mkdir -p logs

REVIEW_BRANCH="p8-review"
MAX_ATTEMPTS=12

PROMPT="You are an INDEPENDENT REVIEWER, not an implementer, for phase P8 of an Android app called 食记.

Your full instruction is in design/P8_REVIEW_TASK.md - read it completely first.

Context you need:
- The implementation lives on branch p8-ux-fixes (15 commits ahead of master). Read that branch with: git diff master..p8-ux-fixes
- The specification is design/P8_TASK.md
- Visual evidence is in design/bite/p8-evidence/
- You are reviewing someone else's work. Be skeptical. Verify claims by reading actual code, not commit messages.

Hard rules:
1. Do NOT modify code on branch p8-ux-fixes. You only write a review report.
2. Create a NEW branch named ${REVIEW_BRANCH} from p8-ux-fixes, put your report at design/p8-review/REVIEW.md, commit it there, and push it.
3. Do NOT merge any PR and do NOT trigger any release.
4. Every finding must cite file:line or a screenshot filename. No vague statements.
5. Grade each item PASS / PARTIAL / FAIL and label issues BLOCKER / MAJOR / MINOR / NIT.
6. For every BLOCKER or MAJOR, give a minimal concrete fix suggestion with file:line.

You cannot run gradle or the emulator. Read code, read evidence.json, read the screenshots' descriptions, and reason carefully.

Finish by writing a short summary to logs/p8_review_summary.md."

echo "=== P8 independent review ==="
attempt=0
while [ $attempt -lt $MAX_ATTEMPTS ]; do
    attempt=$((attempt + 1))
    echo "=== attempt $attempt $(date +%H:%M:%S) ==="

    source "$HOME/.codex/env"
    codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
        -m gpt-6.1-sol -C /root/dining_log \
        --output-last-message logs/p8_review_last.txt \
        "$PROMPT" > "logs/p8_review_attempt${attempt}.log" 2>&1
    code=$?

    if [ $code -eq 0 ]; then
        echo "attempt $attempt exit=0"
        if grep -qi "token rate limit exceeded" "logs/p8_review_attempt${attempt}.log"; then
            echo "rate limited, backoff 180s"; sleep 180; continue
        fi
        echo "SUCCESS on attempt $attempt"
        break
    fi

    echo "attempt $attempt exit=$code"
    if grep -qi "token rate limit exceeded" "logs/p8_review_attempt${attempt}.log"; then
        echo "rate limited, backoff 180s"; sleep 180; continue
    fi
    sleep 30
done

echo "=== review branch state ==="
git log --oneline p8-ux-fixes.."$REVIEW_BRANCH" 2>/dev/null | head -10
