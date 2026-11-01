#!/bin/bash
TOKEN=$(grep -o 'https://[^@]*@github.com' ~/.git-credentials | head -1 | sed 's|https://||; s|@github.com||' | cut -d: -f2)
echo "== workflow files =="
ls /root/dining_log/.github/workflows/
echo
echo "== runs =="
curl -sS -H "Authorization: token $TOKEN" \
  "https://api.github.com/repos/vampuke/dining-log/actions/runs?per_page=5" \
  | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('total:',d.get('total_count'))
for r in d.get('workflow_runs',[])[:5]:
    print(f\"{r['name'][:28]:30} {r['status']:10} {str(r.get('conclusion')):10} {r['head_sha'][:8]} {r['created_at']}\")
    print('   url:',r['html_url'])
"
