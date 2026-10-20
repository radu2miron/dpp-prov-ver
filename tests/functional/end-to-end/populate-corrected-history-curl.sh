#!/usr/bin/env bash
# DPP-Prov-Ver — minimal ledger population after a CLEAN chaincode deployment.
# Run from repository root: bash tests/functional/end-to-end/populate-corrected-history-curl.sh
# Purpose: create one accepted INSTALL / REMOVE / CORRECT lifecycle and activate
# DPP_HISTORICAL_CORRECTIONS_PRESENT before the concurrency regression test.
# Requires bash, curl, date, od; writes ONE log under results/functional/.
# WARNING: Writes persist on the Fabric test ledger. Unique IDs allow reruns.

set -u
BASE_URL="${BASE_URL:-http://localhost:8081}"
BASE_URL="${BASE_URL%/}"
RESULT_DIR="${RESULT_DIR:-results/functional/end-to-end}"
RUN_ID="seed-$(date -u +%Y%m%dT%H%M%SZ)-$(od -An -N6 -tx1 /dev/urandom | tr -d '[:space:]')"
LOG_FILE="${RESULT_DIR}/populate-corrected-history-${RUN_ID}.log"
ISSUED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
ACTOR="${RUN_ID}-actor"
HOST="${RUN_ID}-host"
MOTOR="${RUN_ID}-motor"
DPP="${RUN_ID}-dpp"
INSTALL="${RUN_ID}-install"
REMOVE="${RUN_ID}-remove"
CORRECT="${RUN_ID}-correct"

mkdir -p "$RESULT_DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
exec > >(tee "$LOG_FILE") 2>&1
printf 'DPP-Prov-Ver — corrected-history seed (run after clean deploy)\n'
printf 'RUN_ID=%s\nBASE_URL=%s\nLOG_FILE=%s\nISSUED_AT=%s\n' "$RUN_ID" "$BASE_URL" "$LOG_FILE" "$ISSUED_AT"
printf 'Expected: 3 accepted lifecycle events and two historically consistent queries.\n'

printf '\n===== S001: API health (HTTP 200; does NOT test Fabric connectivity) =====\n'
curl -sS -i --connect-timeout 10 --max-time 30 -w '\nHTTP_STATUS=%{http_code}\n' "$BASE_URL/api/health" > "$TMP/health"
cat "$TMP/health"
if ! grep -qx 'HTTP_STATUS=200' "$TMP/health"; then printf 'RESULT=ABORT_API_NOT_READY\n'; exit 1; fi

printf '\n===== S002: Create actor (201, VALID) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' \
  -X POST "$BASE_URL/api/actors" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ACTOR\",\"id\":\"$ACTOR\",\"organizationId\":\"Org1MSP\",\"role\":\"MANUFACTURER\"}" > "$TMP/actor"
cat "$TMP/actor"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/actor" || ! grep -Fq '"fabricStatus":"VALID"' "$TMP/actor"; then
  printf 'RESULT=ABORT_ACTOR_CREATE_FAILED (check Spring Boot certificate paths after clean Fabric deployment)\n'; exit 1
fi

printf '\n===== S003: Create host asset (201, VALID) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' \
  -X POST "$BASE_URL/api/assets" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ASSET\",\"id\":\"$HOST\",\"assetType\":\"MACHINE\"}" > "$TMP/host"
cat "$TMP/host"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/host"; then printf 'RESULT=ABORT_HOST_CREATE_FAILED\n'; exit 1; fi

printf '\n===== S004: Create motor asset (201, VALID) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' \
  -X POST "$BASE_URL/api/assets" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ASSET\",\"id\":\"$MOTOR\",\"assetType\":\"MOTOR\"}" > "$TMP/motor"
cat "$TMP/motor"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/motor"; then printf 'RESULT=ABORT_MOTOR_CREATE_FAILED\n'; exit 1; fi

printf '\n===== S005: Create passport (201, VALID) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' \
  -X POST "$BASE_URL/api/passports" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"DIGITAL_PRODUCT_PASSPORT\",\"id\":\"$DPP\",\"rootAssetId\":\"$HOST\",\"schemaVersion\":\"1.0\"}" > "$TMP/passport"
cat "$TMP/passport"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/passport"; then printf 'RESULT=ABORT_PASSPORT_CREATE_FAILED\n'; exit 1; fi

printf '\n===== S006: INSTALL motor at 11:00 (201, VALID, ACCEPTED) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' \
  -X POST "$BASE_URL/api/events" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"LIFECYCLE_EVENT\",\"id\":\"$INSTALL\",\"operationId\":\"operation:$INSTALL\",\"type\":\"INSTALL\",\"actorId\":\"$ACTOR\",\"dppIds\":[\"$DPP\"],\"affectedAssetIds\":[\"$HOST\",\"$MOTOR\"],\"parentEventIds\":[],\"effectiveTime\":\"2026-10-20T11:00:00Z\",\"issuedAt\":\"$ISSUED_AT\",\"data\":{\"componentId\":\"$MOTOR\",\"hostId\":\"$HOST\",\"slotId\":\"drive\"}}" > "$TMP/install"
cat "$TMP/install"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/install" || ! grep -Fq '"semanticOutcome":"ACCEPTED"' "$TMP/install" || ! grep -Fq '"fabricStatus":"VALID"' "$TMP/install"; then
  printf 'RESULT=ABORT_INSTALL_NOT_ACCEPTED\n'; exit 1
fi

printf '\n===== S007: REMOVE motor at 12:00 (201, VALID, ACCEPTED) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' \
  -X POST "$BASE_URL/api/events" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"LIFECYCLE_EVENT\",\"id\":\"$REMOVE\",\"operationId\":\"operation:$REMOVE\",\"type\":\"REMOVE\",\"actorId\":\"$ACTOR\",\"dppIds\":[\"$DPP\"],\"affectedAssetIds\":[\"$HOST\",\"$MOTOR\"],\"parentEventIds\":[],\"effectiveTime\":\"2026-10-20T12:00:00Z\",\"issuedAt\":\"$ISSUED_AT\",\"data\":{\"componentId\":\"$MOTOR\",\"hostId\":\"$HOST\",\"slotId\":\"drive\"}}" > "$TMP/remove"
cat "$TMP/remove"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/remove" || ! grep -Fq '"semanticOutcome":"ACCEPTED"' "$TMP/remove" || ! grep -Fq '"fabricStatus":"VALID"' "$TMP/remove"; then
  printf 'RESULT=ABORT_REMOVE_NOT_ACCEPTED\n'; exit 1
fi

printf '\n===== S008: CORRECT removal from 12:00 to 12:10 (201, VALID, ACCEPTED) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' \
  -X POST "$BASE_URL/api/events" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"LIFECYCLE_EVENT\",\"id\":\"$CORRECT\",\"operationId\":\"operation:$CORRECT\",\"type\":\"CORRECT\",\"actorId\":\"$ACTOR\",\"dppIds\":[\"$DPP\"],\"affectedAssetIds\":[\"$HOST\",\"$MOTOR\"],\"parentEventIds\":[\"$REMOVE\"],\"effectiveTime\":\"2026-10-20T16:00:00Z\",\"issuedAt\":\"$ISSUED_AT\",\"data\":{\"targetEventId\":\"$REMOVE\",\"correctedEffectiveTime\":\"2026-10-20T12:10:00Z\"}}" > "$TMP/correct"
cat "$TMP/correct"
if ! grep -qx 'HTTP_STATUS=201' "$TMP/correct" || ! grep -Fq '"semanticOutcome":"ACCEPTED"' "$TMP/correct" || ! grep -Fq '"fabricStatus":"VALID"' "$TMP/correct"; then
  printf 'RESULT=ABORT_CORRECTION_NOT_ACCEPTED\n'; exit 1
fi

printf '\n===== S009: Read correction decision (200, ACCEPTED) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' \
  "$BASE_URL/api/decisions/decision:$CORRECT" > "$TMP/decision"
cat "$TMP/decision"
if ! grep -qx 'HTTP_STATUS=200' "$TMP/decision" || ! grep -Fq '"outcome":"ACCEPTED"' "$TMP/decision"; then
  printf 'RESULT=ABORT_CORRECTION_DECISION_NOT_FOUND\n'; exit 1
fi

printf '\n===== S010: State at 12:05 (200, motor still installed after correction) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' \
  "$BASE_URL/api/passports/$DPP/state?effectiveTime=2026-10-20T12%3A05%3A00Z&asOfPosition=0" > "$TMP/before"
cat "$TMP/before"
if ! grep -qx 'HTTP_STATUS=200' "$TMP/before" || ! grep -Fq '"validTo":"2026-10-20T12:10:00Z"' "$TMP/before" || ! grep -Fq '"activeIntervals":[{"' "$TMP/before"; then
  printf 'RESULT=ABORT_CORRECTED_BEFORE_STATE_UNEXPECTED\n'; exit 1
fi

printf '\n===== S011: State at 12:15 (200, motor removed) =====\n'
curl -sS -i --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' \
  "$BASE_URL/api/passports/$DPP/state?effectiveTime=2026-10-20T12%3A15%3A00Z&asOfPosition=0" > "$TMP/after"
cat "$TMP/after"
if ! grep -qx 'HTTP_STATUS=200' "$TMP/after" || ! grep -Fq '"activeIntervals":[]' "$TMP/after"; then
  printf 'RESULT=ABORT_CORRECTED_AFTER_STATE_UNEXPECTED\n'; exit 1
fi

printf '\n===== SUMMARY =====\n'
printf 'ACCEPTED_EVENTS=3\nHISTORICAL_CORRECTION_COMMITTED=1\n'
printf 'CORRECTION_FLAG_EXPECTED=1 (set by optimized chaincode on accepted CORRECT; not directly exposed by REST API)\n'
printf 'RESULT=PASS\n'
