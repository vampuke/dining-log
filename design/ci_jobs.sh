#!/bin/bash
TOKEN=$(grep -o 'https://[^@]*@github.com' ~/.git-credentials | head -1 | sed 's|https://||; s|@github.com||' | cut -d: -f2)
RUN=${1:-41269219497}
curl -sS -H "Authorization: token $TOKEN" \
  "https://api.github.com/repos/vampuke/dining-log/actions/runs/$RUN/jobs" \
  | python3 -c "
import sys,json
d=json.load(sys.stdin)
for j in d.get('jobs',[]):
    print('JOB:',j['name'],'->',j['status'],j.get('conclusion'))
    for s in j.get('steps',[]):
        mark='FAIL' if s.get('conclusion')=='failure' else s.get('conclusion')
        print('   ',s['number'],s['name'][:60],'->',mark)
"
