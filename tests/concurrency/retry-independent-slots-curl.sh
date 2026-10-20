#!/usr/bin/env bash
# DPP-Prov-Ver — retry after a transient Fabric commit conflict.
# Run from the repository root: bash tests/concurrency/retry-independent-slots-curl.sh
# Requirements: Bash, curl, grep, sed, od, date; running dpp-prov-ver-api and Fabric network.
# Every run creates unique IDs. Requests and responses go to ONE log.
# Test network only: successful ledger writes are permanent.
# No retry of semantic REJECTED, generic HTTP errors, or ambiguous timeouts.

set -u
BASE_URL="${BASE_URL:-http://localhost:8081}"
BASE_URL="${BASE_URL%/}"
RESULT_DIR="${RESULT_DIR:-results/concurrency}"
RUN_ID="retry-$(date -u +%Y%m%dT%H%M%SZ)-$(od -An -N8 -tx1 /dev/urandom | tr -d '[:space:]')"
LOG_FILE="${RESULT_DIR}/retry-independent-slots-${RUN_ID}.log"
ISSUED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
EFFECTIVE_AT="2026-10-20T10:00:00Z"
QUERY_AT="2026-10-20T11%3A00%3A00Z"

ACTOR_A="${RUN_ID}-actor-a"
ACTOR_B="${RUN_ID}-actor-b"
HOST="${RUN_ID}-host"
MOTOR_A="${RUN_ID}-motor-a"
MOTOR_B="${RUN_ID}-motor-b"
DPP="${RUN_ID}-dpp"
EVENT_A="${RUN_ID}-install-a"
EVENT_B="${RUN_ID}-install-b"

mkdir -p "$RESULT_DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
exec > >(tee "$LOG_FILE") 2>&1

printf 'DPP-Prov-Ver — concurrent independent slots followed by conditional retry\n'
printf 'RUN_ID=%s\nBASE_URL=%s\nLOG_FILE=%s\n' "$RUN_ID" "$BASE_URL" "$LOG_FILE"
printf 'EFFECTIVE_AT=%s\nISSUED_AT=%s\n' "$EFFECTIVE_AT" "$ISSUED_AT"
printf 'A=actor A / motor A / drive-a; B=actor B / motor B / drive-b; same host, same DPP.\n'
printf 'Retry policy: only one verified COMMIT-stage MVCC_READ_CONFLICT or PHANTOM_READ_CONFLICT.\n'
printf 'The retry resubmits EXACTLY the same event ID, operationId and JSON body, but creates a new Fabric transaction ID.\n'

printf '\n===== SETUP: Health (expected HTTP 200) =====\n'
curl -sS -i --connect-timeout 10 --max-time 30 -w '\nHTTP_STATUS=%{http_code}\n' "$BASE_URL/api/health" > "$TMP/health.txt"
cat "$TMP/health.txt"
if ! grep -qx 'HTTP_STATUS=200' "$TMP/health.txt"; then printf 'ABORT: API health request failed.\n'; exit 1; fi

printf '\n===== SETUP: Create actor A (expected HTTP 201) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/actors" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ACTOR\",\"id\":\"$ACTOR_A\",\"organizationId\":\"Org1MSP\",\"role\":\"MANUFACTURER\"}" > "$TMP/create-actor-a.txt"
cat "$TMP/create-actor-a.txt"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/create-actor-a.txt"; then printf 'ABORT: actor A was not created.\n'; exit 1; fi

printf '\n===== SETUP: Create actor B (expected HTTP 201) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/actors" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ACTOR\",\"id\":\"$ACTOR_B\",\"organizationId\":\"Org2MSP\",\"role\":\"MANUFACTURER\"}" > "$TMP/create-actor-b.txt"
cat "$TMP/create-actor-b.txt"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/create-actor-b.txt"; then printf 'ABORT: actor B was not created.\n'; exit 1; fi

printf '\n===== SETUP: Create host (expected HTTP 201) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/assets" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ASSET\",\"id\":\"$HOST\",\"assetType\":\"MACHINE\"}" > "$TMP/create-host.txt"
cat "$TMP/create-host.txt"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/create-host.txt"; then printf 'ABORT: host was not created.\n'; exit 1; fi

printf '\n===== SETUP: Create motor A (expected HTTP 201) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/assets" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ASSET\",\"id\":\"$MOTOR_A\",\"assetType\":\"MOTOR\"}" > "$TMP/create-motor-a.txt"
cat "$TMP/create-motor-a.txt"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/create-motor-a.txt"; then printf 'ABORT: motor A was not created.\n'; exit 1; fi

printf '\n===== SETUP: Create motor B (expected HTTP 201) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/assets" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ASSET\",\"id\":\"$MOTOR_B\",\"assetType\":\"MOTOR\"}" > "$TMP/create-motor-b.txt"
cat "$TMP/create-motor-b.txt"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/create-motor-b.txt"; then printf 'ABORT: motor B was not created.\n'; exit 1; fi

printf '\n===== SETUP: Create DPP (expected HTTP 201) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/passports" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"DIGITAL_PRODUCT_PASSPORT\",\"id\":\"$DPP\",\"rootAssetId\":\"$HOST\",\"schemaVersion\":\"1.0\"}" > "$TMP/create-dpp.txt"
cat "$TMP/create-dpp.txt"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/create-dpp.txt"; then printf 'ABORT: DPP was not created.\n'; exit 1; fi

# The payloads remain identical on any retry (event ID and operationId are unchanged).
JSON_A="{\"docType\":\"LIFECYCLE_EVENT\",\"id\":\"$EVENT_A\",\"operationId\":\"op-$EVENT_A\",\"type\":\"INSTALL\",\"actorId\":\"$ACTOR_A\",\"dppIds\":[\"$DPP\"],\"affectedAssetIds\":[\"$HOST\",\"$MOTOR_A\"],\"parentEventIds\":[],\"effectiveTime\":\"$EFFECTIVE_AT\",\"issuedAt\":\"$ISSUED_AT\",\"data\":{\"componentId\":\"$MOTOR_A\",\"hostId\":\"$HOST\",\"slotId\":\"drive-a\"}}"
JSON_B="{\"docType\":\"LIFECYCLE_EVENT\",\"id\":\"$EVENT_B\",\"operationId\":\"op-$EVENT_B\",\"type\":\"INSTALL\",\"actorId\":\"$ACTOR_B\",\"dppIds\":[\"$DPP\"],\"affectedAssetIds\":[\"$HOST\",\"$MOTOR_B\"],\"parentEventIds\":[],\"effectiveTime\":\"$EFFECTIVE_AT\",\"issuedAt\":\"$ISSUED_AT\",\"data\":{\"componentId\":\"$MOTOR_B\",\"hostId\":\"$HOST\",\"slotId\":\"drive-b\"}}"

printf '\n===== CONCURRENT ATTEMPT: Two independent slots (A and B) =====\n'
GATE="$TMP/start"
(
  while [ ! -f "$GATE" ]; do sleep 0.005; done
  printf 'REQUEST=A\nSTART_UTC=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)"
  curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\nTIME_TOTAL_SECONDS=%{time_total}\n' \
    -X POST "$BASE_URL/api/events" -H 'Content-Type: application/json' -d "$JSON_A"
  printf 'CURL_EXIT=%s\nEND_UTC=%s\n' "$?" "$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)"
) > "$TMP/attempt-a.txt" 2>&1 &
PID_A=$!
(
  while [ ! -f "$GATE" ]; do sleep 0.005; done
  printf 'REQUEST=B\nSTART_UTC=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)"
  curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\nTIME_TOTAL_SECONDS=%{time_total}\n' \
    -X POST "$BASE_URL/api/events" -H 'Content-Type: application/json' -d "$JSON_B"
  printf 'CURL_EXIT=%s\nEND_UTC=%s\n' "$?" "$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)"
) > "$TMP/attempt-b.txt" 2>&1 &
PID_B=$!
sleep 0.25
touch "$GATE"
wait "$PID_A"; EXIT_A=$?
wait "$PID_B"; EXIT_B=$?
printf '\n----- Initial A response -----\n'; cat "$TMP/attempt-a.txt"
printf '\n----- Initial B response -----\n'; cat "$TMP/attempt-b.txt"
printf 'INITIAL_PROCESS_EXIT_A=%s\nINITIAL_PROCESS_EXIT_B=%s\n' "$EXIT_A" "$EXIT_B"

A_ACCEPTED=0
B_ACCEPTED=0
A_COMMIT_CONFLICT=0
B_COMMIT_CONFLICT=0
if grep -qx 'HTTP_STATUS=201' "$TMP/attempt-a.txt" && grep -Fq '"semanticOutcome":"ACCEPTED"' "$TMP/attempt-a.txt" && grep -Fq '"fabricStatus":"VALID"' "$TMP/attempt-a.txt"; then A_ACCEPTED=1; fi
if grep -qx 'HTTP_STATUS=201' "$TMP/attempt-b.txt" && grep -Fq '"semanticOutcome":"ACCEPTED"' "$TMP/attempt-b.txt" && grep -Fq '"fabricStatus":"VALID"' "$TMP/attempt-b.txt"; then B_ACCEPTED=1; fi
if grep -qx 'HTTP_STATUS=409' "$TMP/attempt-a.txt" && grep -Fq '"stage":"COMMIT"' "$TMP/attempt-a.txt" && grep -Eq '"validationCode":"(MVCC_READ_CONFLICT|PHANTOM_READ_CONFLICT)"' "$TMP/attempt-a.txt"; then A_COMMIT_CONFLICT=1; fi
if grep -qx 'HTTP_STATUS=409' "$TMP/attempt-b.txt" && grep -Fq '"stage":"COMMIT"' "$TMP/attempt-b.txt" && grep -Eq '"validationCode":"(MVCC_READ_CONFLICT|PHANTOM_READ_CONFLICT)"' "$TMP/attempt-b.txt"; then B_COMMIT_CONFLICT=1; fi
printf 'INITIAL_A_ACCEPTED=%s\nINITIAL_B_ACCEPTED=%s\n' "$A_ACCEPTED" "$B_ACCEPTED"
printf 'INITIAL_A_COMMIT_CONFLICT=%s\nINITIAL_B_COMMIT_CONFLICT=%s\n' "$A_COMMIT_CONFLICT" "$B_COMMIT_CONFLICT"

# Only retry the confirmed loser after BOTH first attempts have completed.
RETRIED="none"
RETRY_ACCEPTED="not_applicable"
if [ "$A_ACCEPTED" -eq 1 ] && [ "$B_ACCEPTED" -eq 1 ]; then
  printf '\n===== RETRY: SKIPPED; both initial transactions were accepted =====\n'
elif [ "$A_COMMIT_CONFLICT" -eq 1 ] && [ "$B_ACCEPTED" -eq 1 ]; then
  RETRIED="A"
  printf '\n===== RETRY A: Resubmit the SAME id and operationId after B committed =====\n'
  curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\nTIME_TOTAL_SECONDS=%{time_total}\n' \
    -X POST "$BASE_URL/api/events" -H 'Content-Type: application/json' -d "$JSON_A" > "$TMP/retry.txt"
  cat "$TMP/retry.txt"
elif [ "$B_COMMIT_CONFLICT" -eq 1 ] && [ "$A_ACCEPTED" -eq 1 ]; then
  RETRIED="B"
  printf '\n===== RETRY B: Resubmit the SAME id and operationId after A committed =====\n'
  curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\nTIME_TOTAL_SECONDS=%{time_total}\n' \
    -X POST "$BASE_URL/api/events" -H 'Content-Type: application/json' -d "$JSON_B" > "$TMP/retry.txt"
  cat "$TMP/retry.txt"
else
  printf '\n===== RETRY: SKIPPED; not exactly one VALID/ACCEPTED and one known Fabric MVCC/phantom COMMIT conflict =====\n'
  printf 'This is not automatically retried; preserve responses for investigation.\n'
fi
if [ "$RETRIED" != "none" ]; then
  RETRY_ACCEPTED=0
  if grep -qx 'HTTP_STATUS=201' "$TMP/retry.txt" && grep -Fq '"semanticOutcome":"ACCEPTED"' "$TMP/retry.txt" && grep -Fq '"fabricStatus":"VALID"' "$TMP/retry.txt"; then RETRY_ACCEPTED=1; fi
fi
printf 'RETRIED=%s\nRETRY_ACCEPTED=%s\n' "$RETRIED" "$RETRY_ACCEPTED"

printf '\n===== VERIFY: Read decision for event A =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' \
  "$BASE_URL/api/decisions/decision:$EVENT_A"
printf '\n===== VERIFY: Read decision for event B =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' \
  "$BASE_URL/api/decisions/decision:$EVENT_B"

printf '\n===== VERIFY: Reconstruct final DPP state (expect two active intervals) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' \
  "$BASE_URL/api/passports/$DPP/state?effectiveTime=$QUERY_AT&asOfPosition=0" > "$TMP/final-state.txt"
cat "$TMP/final-state.txt"

# Inspect only activeIntervals, not the complete history stored in intervals.
ACTIVE_INTERVALS="$(sed -n 's/.*"activeIntervals":\(\[[^]]*\]\).*/\1/p' "$TMP/final-state.txt" | head -n 1)"
ACTIVE_COUNT="$(printf '%s' "$ACTIVE_INTERVALS" | grep -o '"docType":"CONTAINMENT_INTERVAL"' | wc -l | tr -d '[:space:]')"
FINAL_OK=0
if grep -qx 'HTTP_STATUS=200' "$TMP/final-state.txt" \
  && [ "$ACTIVE_COUNT" = '2' ] \
  && [[ "$ACTIVE_INTERVALS" == *'"slotId":"drive-a"'* ]] \
  && [[ "$ACTIVE_INTERVALS" == *'"slotId":"drive-b"'* ]] \
  && [[ "$ACTIVE_INTERVALS" == *"\"componentId\":\"$MOTOR_A\""* ]] \
  && [[ "$ACTIVE_INTERVALS" == *"\"componentId\":\"$MOTOR_B\""* ]]; then
  FINAL_OK=1
fi
printf '\n===== SUMMARY =====\n'
printf 'INITIAL_A_ACCEPTED=%s\nINITIAL_B_ACCEPTED=%s\n' "$A_ACCEPTED" "$B_ACCEPTED"
printf 'INITIAL_A_COMMIT_CONFLICT=%s\nINITIAL_B_COMMIT_CONFLICT=%s\n' "$A_COMMIT_CONFLICT" "$B_COMMIT_CONFLICT"
printf 'RETRIED=%s\nRETRY_ACCEPTED=%s\n' "$RETRIED" "$RETRY_ACCEPTED"
printf 'FINAL_ACTIVE_INTERVAL_COUNT=%s\nFINAL_TWO_DISTINCT_COMPONENTS_AND_SLOTS=%s\n' "$ACTIVE_COUNT" "$FINAL_OK"
if [ "$FINAL_OK" -eq 1 ] && { [ "$A_ACCEPTED" -eq 1 ] && [ "$B_ACCEPTED" -eq 1 ] || [ "$RETRY_ACCEPTED" = 1 ]; }; then
  printf 'RESULT=PASS\n'
  printf 'Interpretation: final state contains both independent installs; retry recovery was demonstrated only if RETRIED is A or B.\n'
  exit 0
fi
printf 'RESULT=FAIL_OR_INCONCLUSIVE\n'
printf 'Interpretation: inspect initial responses, retry response, and activeIntervals.\n'
exit 1
