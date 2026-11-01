#!/bin/bash
TOKEN=$(grep -o 'https://[^@]*@github.com' ~/.git-credentials | head -1 | sed 's|https://||; s|@github.com||' | cut -d: -f2)
if [ -z "$TOKEN" ]; then echo "NO_TOKEN"; exit 1; fi
echo "== user =="
curl -sS -H "Authorization: token $TOKEN" https://api.github.com/user | python3 -c "import sys,json; d=json.load(sys.stdin); print('login:',d.get('login'),'| scopes-ok')"
echo "== dining-log repo =="
curl -sS -o /dev/null -w "%{http_code}\n" -H "Authorization: token $TOKEN" https://api.github.com/repos/vampuke/dining-log
echo "== repo list (first 5) =="
curl -sS -H "Authorization: token $TOKEN" "https://api.github.com/user/repos?per_page=5&sort=updated" | python3 -c "import sys,json; [print(r['full_name'], 'private' if r['private'] else 'public') for r in json.load(sys.stdin)]"
