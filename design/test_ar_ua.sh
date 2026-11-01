#!/bin/bash
source "$HOME/.codex/env"
K="$AGENTROUTER_API_KEY"
for UA in "curl/8.0" "codex_cli_rs/0.166.0" "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36" ""; do
  echo "--- UA: ${UA:-<none>} ---"
  curl -sS -m 40 -o /tmp/r.json -w "HTTP %{http_code} " \
    -H "Authorization: Bearer $K" -H "Content-Type: application/json" \
    ${UA:+-H "User-Agent: $UA"} \
    -d '{"model":"gpt-6.1-sol","input":"say OK","stream":false}' \
    https://agentrouter.org/v1/responses
  head -c 200 /tmp/r.json; echo; echo
done
