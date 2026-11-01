#!/usr/bin/env python3
import json, os, re, urllib.request

home = os.path.expanduser('~')
with open(os.path.join(home, '.git-credentials')) as f:
    m = re.search(r'https://([^@]+)@github\.com', f.read())
user, token = m.group(1).split(':', 1)

REPO = 'vampuke/dining-log'

def api(path, method='GET', body=None, accept='application/vnd.github+json'):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f'https://api.github.com{path}', data=data, method=method)
    req.add_header('Authorization', f'token {token}')
    req.add_header('Accept', accept)
    if data:
        req.add_header('Content-Type', 'application/json')
    try:
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read() or b'{}')
    except urllib.error.HTTPError as e:
        return {'_error': e.code, '_body': e.read().decode()[:400]}

pr = api(f'/repos/{REPO}/pulls/4')
print('pr state:', pr.get('state'), 'draft:', pr.get('draft'), 'mergeable:', pr.get('mergeable'))

if pr.get('draft'):
    gql = {'query': 'mutation($id:ID!){markPullRequestReadyForReview(input:{pullRequestId:$id}){pullRequest{isDraft state}}}',
           'variables': {'id': pr['node_id']}}
    res = api('/graphql', 'POST', gql)
    print('undraft:', json.dumps(res)[:200])

if pr.get('mergeable') is False:
    print('not mergeable yet, waiting not implemented'); raise SystemExit(1)

merged = api(f'/repos/{REPO}/pulls/4/merge', 'PUT',
             {'merge_method': 'merge',
              'commit_title': 'Merge P4 Android product loop, cache, drafts and conflict handling'})
print('merge result:', merged.get('merged'), merged.get('sha'), merged.get('message'))
