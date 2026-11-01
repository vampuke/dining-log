#!/bin/bash
source "$HOME/.codex/env"
echo "== key prefixes =="
echo "AGENTROUTER prefix: ${AGENTROUTER_API_KEY:0:12} len:${#AGENTROUTER_API_KEY}"
echo
echo "== test /v1/responses with gpt-6.1-sol =="
curl -sS -m 60 -o /tmp/ar_resp.json -w "HTTP %{http_code}\n" \
  -H "Authorization: Bearer $AGENTROUTER_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-6.1-sol","input":"say OK","stream":false}' \
  https://agentrouter.org/v1/responses
head -c 600 /tmp/ar_resp.json; echo
echo
echo "== test /v1/models =="
curl -sS -m 30 -o /tmp/ar_models.json -w "HTTP %{http_code}\n" \
  -H "Authorization: Bearer $AGENTROUTER_API_KEY" https://agentrouter.org/v1/models
head -c 400 /tmp/ar_models.json; echo
