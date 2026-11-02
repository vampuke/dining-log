#!/bin/bash
# P8 dispatcher: five UX/visual fixes, then an independent review session.
set -uo pipefail

cd /root/dining_log || exit 1
mkdir -p logs

BRANCH="p8-ux-fixes"
MAX_ATTEMPTS=12

PROMPT="You are the sole implementer for phase P8 of an Android app called 食记 (dining log).

FIRST read these files completely before writing any code:
- design/P8_TASK.md   (your instruction, sections A through F)
- design/bite/BITE_DESIGN_REFERENCE.html  (the visual reference)
- android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/theme/Colors.kt
- android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/components/BiteComponents.kt
- android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/ProductRoot.kt

Then implement ALL of sections A, B, C, D, E from design/P8_TASK.md on a NEW branch named ${BRANCH} created from the latest master.

Hard rules:
1. Do NOT touch backend/ or contracts/. Do NOT modify backend/migrations/*.sql.
2. Do NOT change versionCode or versionName.
3. Do NOT introduce any bitmap/photo resource; icons must be VectorDrawable only.
4. Dark mode must remain fully legible - verify every screen in BOTH light and dark.
5. Do NOT delete existing test assertions to make tests pass; update tests to match new behavior.
6. Do NOT merge the PR and do NOT trigger any release.

You cannot run gradle or the emulator locally. Rely on careful reading, local python/sql checks, and CI on push. Commit in small logical steps, push the branch, and open a pull request.

Finish by writing a summary to logs/p8_summary.md listing what changed per section A-E and what you could not verify."

echo "=== P8 implementation ==="
attempt=0
while [ $attempt -lt $MAX_ATTEMPTS ]; do
    attempt=$((attempt + 1))
    echo "=== attempt $attempt $(date +%H:%M:%S) ==="
    git checkout master -q && git pull -q origin master 2>/dev/null
    git branch -D "$BRANCH" -q 2>/dev/null

    source "$HOME/.codex/env"
    codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox \
        -m gpt-6.1-sol -C /root/dining_log \
        --output-last-message logs/p8_last.txt \
        "$PROMPT" > "logs/p8_attempt${attempt}.log" 2>&1
    code=$?

    if [ $code -eq 0 ]; then
        echo "attempt $attempt exit=0"
        if grep -qi "token rate limit exceeded" "logs/p8_attempt${attempt}.log"; then
            echo "rate limited, backoff 180s"; sleep 180; continue
        fi
        echo "SUCCESS on attempt $attempt"
        break
    fi

    echo "attempt $attempt exit=$code"
    if grep -qi "token rate limit exceeded" "logs/p8_attempt${attempt}.log"; then
        echo "rate limited, backoff 180s"; sleep 180; continue
    fi
    sleep 30
done

echo "=== branch state ==="
git log --oneline master.."$BRANCH" 2>/dev/null | head -20
