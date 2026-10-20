#!/usr/bin/env bash
# DPP-Prov-Ver — optimized chaincode regression, after accepted historical correction.
# Run from the dpp-prov-ver repository root:
#   bash tests/concurrency/optimized-two-client-curl.sh
# Prerequisites: bash, curl, od, date, and a running dpp-prov-ver-api + Fabric test network.
# Two cases: competing INSTALL requests for one slot, followed by independent slots.
# Every run uses unique IDs. All requests and responses are combined into ONE log.
# This script grades both scenarios and does NOT retry: we need first-attempt results.
# WARNING: Run on a test network only. Every successful write persists to the ledger.

set -u
BASE_URL="${BASE_URL:-http://localhost:8081}"
BASE_URL="${BASE_URL%/}"
RESULT_DIR="${RESULT_DIR:-results/concurrency}"
RUN_ID="opt-conc-$(date -u +%Y%m%dT%H%M%SZ)-$(od -An -N6 -tx1 /dev/urandom | tr -d '[:space:]')"
LOG_FILE="${RESULT_DIR}/optimized-two-client-${RUN_ID}.log"
ISSUED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
EFFECTIVE_AT="2026-10-20T10:00:00Z"
QUERY_AT="2026-10-20T11:00:00Z"

ACTOR_A="${RUN_ID}-actor-a"
ACTOR_B="${RUN_ID}-actor-b"
HOST_S="${RUN_ID}-host-shared"
HOST_I="${RUN_ID}-host-independent"
MOTOR_A="${RUN_ID}-motor-a"
MOTOR_B="${RUN_ID}-motor-b"
MOTOR_C="${RUN_ID}-motor-c"
MOTOR_D="${RUN_ID}-motor-d"
DPP_S="${RUN_ID}-dpp-shared"
DPP_I="${RUN_ID}-dpp-independent"
EVT_A="${RUN_ID}-install-same-a"
EVT_B="${RUN_ID}-install-same-b"
EVT_C="${RUN_ID}-install-different-c"
EVT_D="${RUN_ID}-install-different-d"

mkdir -p "$RESULT_DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
exec > >(tee "$LOG_FILE") 2>&1
printf 'DPP-Prov-Ver — optimized two-client concurrency regression\n'
printf 'RUN_ID=%s\nBASE_URL=%s\nLOG_FILE=%s\n' "$RUN_ID" "$BASE_URL" "$LOG_FILE"
printf 'EFFECTIVE_AT=%s\nQUERY_AT=%s\n' "$EFFECTIVE_AT" "$QUERY_AT"
printf 'Important: VALID + REJECTED is a semantic rejection; HTTP 409/422/502 may indicate a Fabric failure.\n'
printf 'PREREQUISITE: Run populate-corrected-history-curl.sh successfully on THIS ledger first.\n'
printf 'Expected A: exactly one accepted install in one slot.\n'
printf 'Expected B: both distinct-slot installs ACCEPTED at first attempt (no automatic retry).\n'
printf 'MVCC is not guaranteed in a two-client run, even if requests overlap.\n'

printf '\n===== SETUP: API health (HTTP 200 expected) =====\n'
curl -sS -i --connect-timeout 10 --max-time 30 -w '\nHTTP_STATUS=%{http_code}\n' "$BASE_URL/api/health"
if ! curl -fsS --connect-timeout 5 --max-time 10 "$BASE_URL/api/health" > /dev/null; then
  printf 'API is unavailable. Aborting before creating test entities.\n'
  exit 1
fi

# All setup calls are sequential; only the INSTALL pairs below are concurrent.
printf '\n===== SETUP: Create actor A (201 expected) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/actors" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ACTOR\",\"id\":\"$ACTOR_A\",\"organizationId\":\"Org1MSP\",\"role\":\"MANUFACTURER\"}" > "$TMP/setup-1.log"
cat "$TMP/setup-1.log"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/setup-1.log" || ! grep -Fq '"fabricStatus":"VALID"' "$TMP/setup-1.log"; then
  printf 'RESULT=ABORT_SETUP_FAILED_1\n'; exit 1
fi
printf '\n===== SETUP: Create actor B (201 expected) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/actors" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ACTOR\",\"id\":\"$ACTOR_B\",\"organizationId\":\"Org2MSP\",\"role\":\"MANUFACTURER\"}" > "$TMP/setup-2.log"
cat "$TMP/setup-2.log"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/setup-2.log" || ! grep -Fq '"fabricStatus":"VALID"' "$TMP/setup-2.log"; then
  printf 'RESULT=ABORT_SETUP_FAILED_2\n'; exit 1
fi
printf '\n===== SETUP: Create shared-slot host (201 expected) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/assets" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ASSET\",\"id\":\"$HOST_S\",\"assetType\":\"MACHINE\"}" > "$TMP/setup-3.log"
cat "$TMP/setup-3.log"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/setup-3.log" || ! grep -Fq '"fabricStatus":"VALID"' "$TMP/setup-3.log"; then
  printf 'RESULT=ABORT_SETUP_FAILED_3\n'; exit 1
fi
printf '\n===== SETUP: Create independent-slot host (201 expected) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/assets" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ASSET\",\"id\":\"$HOST_I\",\"assetType\":\"MACHINE\"}" > "$TMP/setup-4.log"
cat "$TMP/setup-4.log"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/setup-4.log" || ! grep -Fq '"fabricStatus":"VALID"' "$TMP/setup-4.log"; then
  printf 'RESULT=ABORT_SETUP_FAILED_4\n'; exit 1
fi
printf '\n===== SETUP: Create motor A (201 expected) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/assets" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ASSET\",\"id\":\"$MOTOR_A\",\"assetType\":\"MOTOR\"}" > "$TMP/setup-5.log"
cat "$TMP/setup-5.log"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/setup-5.log" || ! grep -Fq '"fabricStatus":"VALID"' "$TMP/setup-5.log"; then
  printf 'RESULT=ABORT_SETUP_FAILED_5\n'; exit 1
fi
printf '\n===== SETUP: Create motor B (201 expected) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/assets" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ASSET\",\"id\":\"$MOTOR_B\",\"assetType\":\"MOTOR\"}" > "$TMP/setup-6.log"
cat "$TMP/setup-6.log"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/setup-6.log" || ! grep -Fq '"fabricStatus":"VALID"' "$TMP/setup-6.log"; then
  printf 'RESULT=ABORT_SETUP_FAILED_6\n'; exit 1
fi
printf '\n===== SETUP: Create motor C (201 expected) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/assets" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ASSET\",\"id\":\"$MOTOR_C\",\"assetType\":\"MOTOR\"}" > "$TMP/setup-7.log"
cat "$TMP/setup-7.log"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/setup-7.log" || ! grep -Fq '"fabricStatus":"VALID"' "$TMP/setup-7.log"; then
  printf 'RESULT=ABORT_SETUP_FAILED_7\n'; exit 1
fi
printf '\n===== SETUP: Create motor D (201 expected) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/assets" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ASSET\",\"id\":\"$MOTOR_D\",\"assetType\":\"MOTOR\"}" > "$TMP/setup-8.log"
cat "$TMP/setup-8.log"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/setup-8.log" || ! grep -Fq '"fabricStatus":"VALID"' "$TMP/setup-8.log"; then
  printf 'RESULT=ABORT_SETUP_FAILED_8\n'; exit 1
fi
printf '\n===== SETUP: Create DPP for shared-slot host (201 expected) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/passports" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"DIGITAL_PRODUCT_PASSPORT\",\"id\":\"$DPP_S\",\"rootAssetId\":\"$HOST_S\",\"schemaVersion\":\"1.0\"}" > "$TMP/setup-9.log"
cat "$TMP/setup-9.log"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/setup-9.log" || ! grep -Fq '"fabricStatus":"VALID"' "$TMP/setup-9.log"; then
  printf 'RESULT=ABORT_SETUP_FAILED_9\n'; exit 1
fi
printf '\n===== SETUP: Create DPP for independent-slot host (201 expected) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/passports" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"DIGITAL_PRODUCT_PASSPORT\",\"id\":\"$DPP_I\",\"rootAssetId\":\"$HOST_I\",\"schemaVersion\":\"1.0\"}" > "$TMP/setup-10.log"
cat "$TMP/setup-10.log"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/setup-10.log" || ! grep -Fq '"fabricStatus":"VALID"' "$TMP/setup-10.log"; then
  printf 'RESULT=ABORT_SETUP_FAILED_10\n'; exit 1
fi

# SCENARIO A: Two motors attempt to occupy the SAME physical slot concurrently.
# The key invariant is that both installations must not end up ACCEPTED in the ledger.
# Depending on endorsement/commit timing, expect ACCEPTED + REJECTED or one
# accepted transaction plus one Fabric MVCC/endorsement error. Do not preassign
# which request wins. A two-client race is not guaranteed to trigger MVCC.
printf '\n===== A: SAME-SLOT CONCURRENT INSTALLS (two HTTP clients) =====\n'
GATE_A="$TMP/gate-a"
(
  while [ ! -f "$GATE_A" ]; do sleep 0.005; done
  printf 'REQUEST=A1\nSTART_UTC=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)"
  curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\nTIME_TOTAL_SECONDS=%{time_total}\n' \
    -X POST "$BASE_URL/api/events" -H 'Content-Type: application/json' \
    -d "{\"docType\":\"LIFECYCLE_EVENT\",\"id\":\"$EVT_A\",\"operationId\":\"op-$EVT_A\",\"type\":\"INSTALL\",\"actorId\":\"$ACTOR_A\",\"dppIds\":[\"$DPP_S\"],\"affectedAssetIds\":[\"$HOST_S\",\"$MOTOR_A\"],\"parentEventIds\":[],\"effectiveTime\":\"$EFFECTIVE_AT\",\"issuedAt\":\"$ISSUED_AT\",\"data\":{\"componentId\":\"$MOTOR_A\",\"hostId\":\"$HOST_S\",\"slotId\":\"drive\"}}"
  printf 'CURL_EXIT=%s\nEND_UTC=%s\n' "$?" "$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)"
) > "$TMP/a1.log" 2>&1 &
PID_A1=$!
(
  while [ ! -f "$GATE_A" ]; do sleep 0.005; done
  printf 'REQUEST=A2\nSTART_UTC=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)"
  curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\nTIME_TOTAL_SECONDS=%{time_total}\n' \
    -X POST "$BASE_URL/api/events" -H 'Content-Type: application/json' \
    -d "{\"docType\":\"LIFECYCLE_EVENT\",\"id\":\"$EVT_B\",\"operationId\":\"op-$EVT_B\",\"type\":\"INSTALL\",\"actorId\":\"$ACTOR_B\",\"dppIds\":[\"$DPP_S\"],\"affectedAssetIds\":[\"$HOST_S\",\"$MOTOR_B\"],\"parentEventIds\":[],\"effectiveTime\":\"$EFFECTIVE_AT\",\"issuedAt\":\"$ISSUED_AT\",\"data\":{\"componentId\":\"$MOTOR_B\",\"hostId\":\"$HOST_S\",\"slotId\":\"drive\"}}"
  printf 'CURL_EXIT=%s\nEND_UTC=%s\n' "$?" "$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)"
) > "$TMP/a2.log" 2>&1 &
PID_A2=$!
sleep 0.25
touch "$GATE_A"
wait "$PID_A1"; WAIT_A1=$?
wait "$PID_A2"; WAIT_A2=$?
printf '\n----- A1 response -----\n'; cat "$TMP/a1.log"
printf '\n----- A2 response -----\n'; cat "$TMP/a2.log"
printf 'PROCESS_EXIT_A1=%s\nPROCESS_EXIT_A2=%s\n' "$WAIT_A1" "$WAIT_A2"

printf '\n===== A: Read decision for event A (may be missing if tx invalidated) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' "$BASE_URL/api/decisions/decision:${EVT_A}"
printf '\n===== A: Read decision for event B (may be missing if tx invalidated) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' "$BASE_URL/api/decisions/decision:${EVT_B}"
printf '\n===== A: Reconstruct shared-slot state (MUST contain at most ONE active interval) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' \
  "$BASE_URL/api/passports/$DPP_S/state?effectiveTime=2026-10-20T11%3A00%3A00Z&asOfPosition=0" > "$TMP/state-a.log"
cat "$TMP/state-a.log"

# SCENARIO B: Two motors installed concurrently into DISTINCT slots in the same host.
# This is a low-contention control case; ordinarily both should be ACCEPTED/VALID.
printf '\n===== B: DISTINCT-SLOT CONCURRENT INSTALLS (two HTTP clients) =====\n'
GATE_B="$TMP/gate-b"
(
  while [ ! -f "$GATE_B" ]; do sleep 0.005; done
  printf 'REQUEST=B1\nSTART_UTC=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)"
  curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\nTIME_TOTAL_SECONDS=%{time_total}\n' \
    -X POST "$BASE_URL/api/events" -H 'Content-Type: application/json' \
    -d "{\"docType\":\"LIFECYCLE_EVENT\",\"id\":\"$EVT_C\",\"operationId\":\"op-$EVT_C\",\"type\":\"INSTALL\",\"actorId\":\"$ACTOR_A\",\"dppIds\":[\"$DPP_I\"],\"affectedAssetIds\":[\"$HOST_I\",\"$MOTOR_C\"],\"parentEventIds\":[],\"effectiveTime\":\"$EFFECTIVE_AT\",\"issuedAt\":\"$ISSUED_AT\",\"data\":{\"componentId\":\"$MOTOR_C\",\"hostId\":\"$HOST_I\",\"slotId\":\"drive-a\"}}"
  printf 'CURL_EXIT=%s\nEND_UTC=%s\n' "$?" "$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)"
) > "$TMP/b1.log" 2>&1 &
PID_B1=$!
(
  while [ ! -f "$GATE_B" ]; do sleep 0.005; done
  printf 'REQUEST=B2\nSTART_UTC=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)"
  curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\nTIME_TOTAL_SECONDS=%{time_total}\n' \
    -X POST "$BASE_URL/api/events" -H 'Content-Type: application/json' \
    -d "{\"docType\":\"LIFECYCLE_EVENT\",\"id\":\"$EVT_D\",\"operationId\":\"op-$EVT_D\",\"type\":\"INSTALL\",\"actorId\":\"$ACTOR_B\",\"dppIds\":[\"$DPP_I\"],\"affectedAssetIds\":[\"$HOST_I\",\"$MOTOR_D\"],\"parentEventIds\":[],\"effectiveTime\":\"$EFFECTIVE_AT\",\"issuedAt\":\"$ISSUED_AT\",\"data\":{\"componentId\":\"$MOTOR_D\",\"hostId\":\"$HOST_I\",\"slotId\":\"drive-b\"}}"
  printf 'CURL_EXIT=%s\nEND_UTC=%s\n' "$?" "$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)"
) > "$TMP/b2.log" 2>&1 &
PID_B2=$!
sleep 0.25
touch "$GATE_B"
wait "$PID_B1"; WAIT_B1=$?
wait "$PID_B2"; WAIT_B2=$?
printf '\n----- B1 response -----\n'; cat "$TMP/b1.log"
printf '\n----- B2 response -----\n'; cat "$TMP/b2.log"
printf 'PROCESS_EXIT_B1=%s\nPROCESS_EXIT_B2=%s\n' "$WAIT_B1" "$WAIT_B2"

printf '\n===== B: Reconstruct independent-slot state (expect TWO active intervals) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' \
  "$BASE_URL/api/passports/$DPP_I/state?effectiveTime=2026-10-20T11%3A00%3A00Z&asOfPosition=0" > "$TMP/state-b.log"
cat "$TMP/state-b.log"

# Evaluate semantic result separately from Fabric commit validation.
A1_OK=0; A2_OK=0; A1_REJECT=0; A2_REJECT=0; A1_COMMIT_CONFLICT=0; A2_COMMIT_CONFLICT=0
B1_OK=0; B2_OK=0
if grep -qx 'HTTP_STATUS=201' "$TMP/a1.log" && grep -Fq '"fabricStatus":"VALID"' "$TMP/a1.log" && grep -Fq '"semanticOutcome":"ACCEPTED"' "$TMP/a1.log"; then A1_OK=1; fi
if grep -qx 'HTTP_STATUS=201' "$TMP/a2.log" && grep -Fq '"fabricStatus":"VALID"' "$TMP/a2.log" && grep -Fq '"semanticOutcome":"ACCEPTED"' "$TMP/a2.log"; then A2_OK=1; fi
if grep -qx 'HTTP_STATUS=201' "$TMP/a1.log" && grep -Fq '"fabricStatus":"VALID"' "$TMP/a1.log" && grep -Fq '"semanticOutcome":"REJECTED"' "$TMP/a1.log"; then A1_REJECT=1; fi
if grep -qx 'HTTP_STATUS=201' "$TMP/a2.log" && grep -Fq '"fabricStatus":"VALID"' "$TMP/a2.log" && grep -Fq '"semanticOutcome":"REJECTED"' "$TMP/a2.log"; then A2_REJECT=1; fi
if grep -qx 'HTTP_STATUS=409' "$TMP/a1.log" && grep -Fq '"stage":"COMMIT"' "$TMP/a1.log" && grep -Eq '"validationCode":"(MVCC_READ_CONFLICT|PHANTOM_READ_CONFLICT)"' "$TMP/a1.log"; then A1_COMMIT_CONFLICT=1; fi
if grep -qx 'HTTP_STATUS=409' "$TMP/a2.log" && grep -Fq '"stage":"COMMIT"' "$TMP/a2.log" && grep -Eq '"validationCode":"(MVCC_READ_CONFLICT|PHANTOM_READ_CONFLICT)"' "$TMP/a2.log"; then A2_COMMIT_CONFLICT=1; fi
if grep -qx 'HTTP_STATUS=201' "$TMP/b1.log" && grep -Fq '"fabricStatus":"VALID"' "$TMP/b1.log" && grep -Fq '"semanticOutcome":"ACCEPTED"' "$TMP/b1.log"; then B1_OK=1; fi
if grep -qx 'HTTP_STATUS=201' "$TMP/b2.log" && grep -Fq '"fabricStatus":"VALID"' "$TMP/b2.log" && grep -Fq '"semanticOutcome":"ACCEPTED"' "$TMP/b2.log"; then B2_OK=1; fi

# Read only activeIntervals, not all historical intervals.
A_ACTIVE="$(sed -n 's/.*"activeIntervals":\(\[[^]]*\]\).*/\1/p' "$TMP/state-a.log" | head -n 1)"
B_ACTIVE="$(sed -n 's/.*"activeIntervals":\(\[[^]]*\]\).*/\1/p' "$TMP/state-b.log" | head -n 1)"
A_COUNT="$(printf '%s' "$A_ACTIVE" | grep -o '"docType":"CONTAINMENT_INTERVAL"' | wc -l | tr -d '[:space:]')"
B_COUNT="$(printf '%s' "$B_ACTIVE" | grep -o '"docType":"CONTAINMENT_INTERVAL"' | wc -l | tr -d '[:space:]')"
A_RESULT=FAIL_OR_INCONCLUSIVE; B_RESULT=FAIL_OR_INCONCLUSIVE
if grep -qx 'HTTP_STATUS=200' "$TMP/state-a.log" && [ "$A_COUNT" = 1 ] && [ $((A1_OK + A2_OK)) -eq 1 ] && \
  { [ $((A1_REJECT + A2_REJECT)) -eq 1 ] || [ $((A1_COMMIT_CONFLICT + A2_COMMIT_CONFLICT)) -eq 1 ]; }; then
  A_RESULT=PASS
fi
if grep -qx 'HTTP_STATUS=200' "$TMP/state-b.log" && [ "$B_COUNT" = 2 ] && [ "$B1_OK" = 1 ] && [ "$B2_OK" = 1 ] && \
  [[ "$B_ACTIVE" == *'"slotId":"drive-a"'* ]] && [[ "$B_ACTIVE" == *'"slotId":"drive-b"'* ]] && \
  [[ "$B_ACTIVE" == *"\"componentId\":\"$MOTOR_C\""* ]] && [[ "$B_ACTIVE" == *"\"componentId\":\"$MOTOR_D\""* ]]; then
  B_RESULT=PASS
fi

printf '\n===== SUMMARY =====\n'
printf 'A1_ACCEPTED=%s\nA2_ACCEPTED=%s\nA1_REJECTED=%s\nA2_REJECTED=%s\n' "$A1_OK" "$A2_OK" "$A1_REJECT" "$A2_REJECT"
printf 'A1_COMMIT_CONFLICT=%s\nA2_COMMIT_CONFLICT=%s\n' "$A1_COMMIT_CONFLICT" "$A2_COMMIT_CONFLICT"
printf 'A_ACTIVE_INTERVAL_COUNT=%s\nA_RESULT=%s\n' "$A_COUNT" "$A_RESULT"
printf 'B1_ACCEPTED=%s\nB2_ACCEPTED=%s\nB_ACTIVE_INTERVAL_COUNT=%s\nB_RESULT=%s\n' "$B1_OK" "$B2_OK" "$B_COUNT" "$B_RESULT"
printf 'AUTOMATIC_RETRIES=0\n'
if [ "$A_RESULT" = PASS ] && [ "$B_RESULT" = PASS ]; then
  printf 'RESULT=PASS\n'; exit 0
fi
printf 'RESULT=FAIL_OR_INCONCLUSIVE\n'
printf 'Use initial HTTP / Fabric validation codes and final states to diagnose.\n'
exit 1
