#!/usr/bin/env bash
# One-shot local verification for AI Gateway requirements #2 (/index non-blocking) and
# #4 (proxy: direct Anthropic + OpenAI Responses->Anthropic) against the test pool.
#
# Usage:
#   export ANTHROPIC_AUTH_TOKEN=<your token>
#   bash verify-gateway.sh
#
# Requires the test pool reachable at http://127.0.0.1:15721 (Anthropic-compatible).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
BASE="http://localhost:8080"
POOL="http://127.0.0.1:15721"
PORT=8080
PASS=0; FAIL=0
ok(){ echo "  ✅ $1"; PASS=$((PASS+1)); }
bad(){ echo "  ❌ $1"; FAIL=$((FAIL+1)); }

echo "== 0. Preconditions =="
if [ -z "${ANTHROPIC_AUTH_TOKEN:-}" ]; then echo "  !! ANTHROPIC_AUTH_TOKEN not set — proxy tests will fail"; fi
curl -s -m 4 -o /dev/null "$POOL/v1/models" && ok "test pool reachable at $POOL" || echo "  !! test pool $POOL not reachable (start cc-switch / your pool)"

echo "== 1. Start the gateway =="
pkill -f "com.keel.samples.KeelSampleKt" 2>/dev/null
# Prefer gradle; fall back to a prebuilt run if you've assembled one.
( cd "$ROOT" && ./gradlew run -Dkeel.engine=cio >/tmp/keel-verify.log 2>&1 & )
echo -n "  waiting for $BASE ..."
for i in $(seq 1 60); do
  if curl -s -m 2 -o /dev/null "$BASE/api/_system/health" 2>/dev/null; then echo " up"; break; fi
  sleep 2; echo -n "."
  if [ "$i" = "60" ]; then echo " TIMEOUT"; tail -40 /tmp/keel-verify.log; exit 1; fi
done

echo "== 2. /index must not block (req #2) =="
code=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$BASE/index")
[ "$code" = "302" ] || [ "$code" = "301" ] && ok "/index -> $code redirect" || bad "/index -> $code (expected redirect)"
# Open all observability SSE streams, then confirm an unrelated API still answers fast.
for tab in topology traces logs nodes metrics openapi ai-gateway; do
  curl -s -N -m 2 "$BASE/api/plugins/observability/$tab" >/dev/null 2>&1 &
done
sleep 1
t0=$(python3 -c 'import time;print(int(time.time()*1000))')
code=$(curl -s -m 8 -o /dev/null -w '%{http_code}' "$BASE/api/plugins/airelay/v1/models")
t1=$(python3 -c 'import time;print(int(time.time()*1000))')
[ "$code" = "200" ] && ok "/v1/models -> 200 in $((t1-t0))ms with 7 SSE open (not blocked)" || bad "/v1/models -> $code (BLOCKED?)"

echo "== 3. Configure provider + auth (req #4 setup) =="
TOK=$(curl -s -m 8 "$BASE/api/plugins/account/v1/auth/login" -H 'content-type: application/json' \
  -d '{"email":"admin@example.com","password":"admin123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin).get("accessToken",""))' 2>/dev/null)
[ -n "$TOK" ] && ok "admin login" || bad "admin login failed"

# Create a channel pointing at the test pool (env-based key).
CH=$(curl -s -m 8 -X POST "$BASE/api/plugins/airelay/admin/channels" -H "Authorization: Bearer $TOK" -H 'content-type: application/json' -d '{
  "name":"Local Anthropic","protocol":"ANTHROPIC_MESSAGES","baseUrl":"'"$POOL"'",
  "apiKeyEnv":"ANTHROPIC_AUTH_TOKEN","enabled":true,
  "models":[{"publicModelName":"claude-sonnet-4-20250514"}]
}')
CHID=$(echo "$CH" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("channelId",""))' 2>/dev/null)
[ -n "$CHID" ] && ok "channel created ($CHID)" || bad "channel create failed: $CH"

# Live Test endpoint
TEST=$(curl -s -m 30 -X POST "$BASE/api/plugins/airelay/admin/channels/$CHID/test" -H "Authorization: Bearer $TOK")
echo "$TEST" | grep -q '"ok":true' && ok "channel Test passed: $TEST" || bad "channel Test failed: $TEST"

# Create a virtual key
KEY=$(curl -s -m 8 -X POST "$BASE/api/plugins/token/v1/keys" -H "Authorization: Bearer $TOK" -H 'content-type: application/json' \
  -d '{"displayName":"verify","maxBudgetUsd":100}' | python3 -c 'import sys,json;print(json.load(sys.stdin).get("rawKey",""))' 2>/dev/null)
[ -n "$KEY" ] && ok "virtual key issued" || bad "key issue failed"

echo "== 4a. Direct Anthropic (req #4) =="
R=$(curl -s -m 60 "$BASE/api/plugins/airelay/v1/messages" -H "Authorization: Bearer $KEY" -H 'content-type: application/json' \
  -d '{"model":"claude-sonnet-4-20250514","max_tokens":64,"messages":[{"role":"user","content":"Reply with exactly: pong"}]}')
echo "$R" | grep -q '"type"' && ok "direct Anthropic returned a message" || bad "direct Anthropic failed: ${R:0:300}"

echo "== 4b. OpenAI Responses -> Anthropic (req #4) =="
H=$(curl -s -m 60 -D - -o /tmp/keel-resp.json "$BASE/api/plugins/airelay/v1/responses" -H "Authorization: Bearer $KEY" -H 'content-type: application/json' \
  -d '{"model":"claude-sonnet-4-20250514","input":"Reply with exactly: pong","store":false,"max_output_tokens":64}')
echo "$H" | grep -qi 'X-Upstream-Protocol: *ANTHROPIC' && ok "Responses->Anthropic: upstream=ANTHROPIC header present" || bad "missing/incorrect X-Upstream-Protocol header"
grep -q '"object"' /tmp/keel-resp.json && ok "Responses envelope returned to client" || bad "client did not get Responses envelope: $(head -c 300 /tmp/keel-resp.json)"

echo ""
echo "== RESULT: $PASS passed, $FAIL failed =="
pkill -f "com.keel.samples.KeelSampleKt" 2>/dev/null
[ "$FAIL" = "0" ] && exit 0 || exit 1
