#!/bin/bash
# PR13 independent review dispatcher.
set -uo pipefail

cd /root/dining_log || exit 1
mkdir -p logs

REVIEW_BRANCH="pr13-review"
MAX_ATTEMPTS=12

PROMPT="You are an INDEPENDENT REVIEWER for pull request #13 of an Android app called 食记 (dining log).

Your full instruction is in design/PR13_REVIEW_TASK.md - read it completely first.

Repository facts:
- The PR is branch fix/top3-ux-fixes -> master, one commit b5a9456, based on master 92e5f53.
- Read the change with: git diff 92e5f53..b5a9456
- It claims to fix three things: (1) unify user-facing timestamps into local time instead of raw ISO strings, (2) stop rendering an empty beige panel when a record has no extra fields, (3) stop showing an unrated record as if it were rated 4.0 in the rating slider.
- The PR author explicitly stated the build was NEVER compiled in their sandbox, so compilation failure is a live risk.

Be skeptical. Verify every claim by reading the actual code and every call site, not the PR description.

Hard rules:
1. Do NOT modify the PR branch fix/top3-ux-fixes. Review only.
2. Create a NEW branch named ${REVIEW_BRANCH} from master, put the report at design/pr13-review/REVIEW.md, commit and push it there.
3. Do NOT merge any PR and do NOT trigger any release.
4. Every finding must cite file:line or a reproducible calculation. No vague statements.
5. Grade findings BLOCKER / MAJOR / MINOR / NIT, give an overall merge verdict, and give a minimal concrete fix (file:line) for every BLOCKER and MAJOR.
6. Answer ALL numbered questions in design/PR13_REVIEW_TASK.md. Do not skip the ones about timezone/Locale, the Slider(value = 1f) ambiguity, accessibility SetProgress semantics when unrated, the deletedAt null case, and dark-mode contrast of the unrated thumb.

You cannot run gradle or the emulator. You may run python for contrast calculations and grep-like inspection. Reason carefully.

Finish by writing a short summary to logs/pr13_review_summary.md."

echo "=== PR13 independent review ==="
attempt=0
while [ $attempt -lt $MAX_ATTEMPTS ]; do
    attempt=$((attempt + 1))
    echo "=== attempt $attempt $(date +%H:%M:%S) ==="
    git checkout master -q && git pull -q origin master 2>/dev/null

    source "$HOME/.codex/env"
    codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
        -m gpt-6.1-sol -C /root/dining_log \
        --output-last-message logs/pr13_review_last.txt \
        "$PROMPT" > "logs/pr13_review_attempt${attempt}.log" 2>&1
    code=$?

    if [ $code -eq 0 ]; then
        echo "attempt $attempt exit=0"
        if grep -qi "token rate limit exceeded" "logs/pr13_review_attempt${attempt}.log"; then
            echo "rate limited, backoff 180s"; sleep 180; continue
        fi
        echo "SUCCESS on attempt $attempt"
        break
    fi
    echo "attempt $attempt exit=$code"
    if grep -qi "token rate limit exceeded" "logs/pr13_review_attempt${attempt}.log"; then
        echo "rate limited, backoff 180s"; sleep 180; continue
    fi
    sleep 30
done

echo "=== review branch state ==="
git log --oneline origin/master.."$REVIEW_BRANCH" 2>/dev/null | head -10
