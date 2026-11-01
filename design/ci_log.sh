#!/bin/bash
TOKEN=$(grep -o 'https://[^@]*@github.com' ~/.git-credentials | head -1 | sed 's|https://||; s|@github.com||' | cut -d: -f2)
RUN=${1:-41269219497}
echo "== jobs =="
JOBID=$(curl -sS -H "Authorization: token $TOKEN" \
  "https://api.github.com/repos/vampuke/dining-log/actions/runs/$RUN/jobs" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print([j['id'] for j in d['jobs'] if j['conclusion']=='failure'][0])")
echo "failed job id: $JOBID"
echo "== log =="
curl -sSL -H "Authorization: token $TOKEN" \
  "https://api.github.com/repos/vampuke/dining-log/actions/jobs/$JOBID/logs" \
  -o /tmp/ci_job.log
wc -l /tmp/ci_job.log
echo "--- error lines ---"
grep -nE "error:|FAILURE|What went wrong|Execution failed|Caused by|> Task .* FAILED|e: " /tmp/ci_job.log | head -40
