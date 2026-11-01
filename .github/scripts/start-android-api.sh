#!/usr/bin/env bash
# Execute only on GitHub Actions, never on the 956 MB VPS.
set -euo pipefail
test "${GITHUB_ACTIONS:-}" = true
python3 - <<'PY'
from pathlib import Path
import base64, json
def key(offset):
    return base64.urlsafe_b64encode(bytes((offset+i) % 256 for i in range(32))).decode().rstrip('=')
# These PUBLIC synthetic fixtures are the same scheme used by backend tests.
values = {"JWT_HS256_KEYS": json.dumps({"v1": key(0)}),
          "PASSWORD_PEPPERS": json.dumps({"v1": key(32)}),
          "REFRESH_RETRY_KEYS": json.dumps({"v1": key(64)}),
          "RATE_LIMIT_HMAC_KEY": key(96)}
Path('.github/fixtures/.dev.vars').write_text('\n'.join(f"{name}='{value}'" for name,value in values.items())+'\n')
PY
cd backend
npx wrangler d1 migrations apply dining-log-android-ci --local --config ../.github/fixtures/android-worker.toml --persist-to /tmp/android-worker-state
npx wrangler dev --local --config ../.github/fixtures/android-worker.toml --persist-to /tmp/android-worker-state --ip 0.0.0.0 --port 8787 > /tmp/android-worker.log 2>&1 &
for attempt in $(seq 1 60); do
  if curl --fail --silent http://127.0.0.1:8787/api/v1/health > /dev/null; then exit 0; fi
  sleep 1
done
cat /tmp/android-worker.log
exit 1
