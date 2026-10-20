#!/usr/bin/env bash
# DPP-Prov-Ver API — isolated causal-conflict and resolution scenario.
# Run from the repository root: bash tests/functional/end-to-end/conflict-resolution-curl.sh
# Requires: curl, GNU date, Linux /proc/sys/kernel/random/uuid, running dpp-prov-ver-api.
# Each run uses new ledger identifiers and stores all HTTP responses in one log file.
# This is a manual-response suite; expected outcomes are comments, not automatic assertions.

set -u
BASE_URL="${BASE_URL:-http://localhost:8081}"
RESULT_DIR="${RESULT_DIR:-results/functional/end-to-end}"
RUN_ID="cr-$(date -u +%Y%m%dT%H%M%SZ)-$(cut -c1-12 /proc/sys/kernel/random/uuid)"
ACTOR_A="${RUN_ID}-actor-a"
ACTOR_B="${RUN_ID}-actor-b"
HOST="${RUN_ID}-host"
MOTOR="${RUN_ID}-motor"
DPP="${RUN_ID}-dpp"
EVENT_A="${RUN_ID}-inspection-passed"
EVENT_B="${RUN_ID}-inspection-failed"
CONFLICT_ID="causal:${EVENT_B}"
RESOLUTION_ID="${RUN_ID}-resolution"
ISSUED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
mkdir -p "$RESULT_DIR"
LOG_FILE="${RESULT_DIR}/conflict-resolution-${RUN_ID}.log"
exec > >(tee "$LOG_FILE") 2>&1

printf 'DPP-Prov-Ver — isolated causal conflict and resolution test\n'
printf 'RUN_ID=%s\nBASE_URL=%s\nLOG_FILE=%s\n' "$RUN_ID" "$BASE_URL" "$LOG_FILE"
printf 'Note: HTTP 201 with semanticOutcome=REJECTED is an expected VALID Fabric commit.\n'
printf 'Note: This test uses two actors with independent vector clocks and opposing inspection results.\n'

# C001 — HTTP API health only (does not establish Fabric connectivity). Expected: 200.
printf '\n===== C001: API health | expected HTTP 200 =====\n'
curl -sS -i -w '\nHTTP_STATUS=%{http_code}\n' "$BASE_URL/api/health"

# C002 — Register the first actor. Expected: HTTP 201; fabricStatus VALID.
printf '\n===== C002: Create actor A | expected HTTP 201, VALID =====\n'
curl -sS -i -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/actors" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ACTOR\",\"id\":\"$ACTOR_A\",\"organizationId\":\"Org1MSP\",\"role\":\"INSPECTOR\"}"

# C003 — Register a different actor. Expected: HTTP 201; fabricStatus VALID.
printf '\n===== C003: Create actor B | expected HTTP 201, VALID =====\n'
curl -sS -i -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/actors" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ACTOR\",\"id\":\"$ACTOR_B\",\"organizationId\":\"Org2MSP\",\"role\":\"INSPECTOR\"}"

# C004 — Create the DPP root asset. Expected: HTTP 201; fabricStatus VALID.
printf '\n===== C004: Create host asset | expected HTTP 201, VALID =====\n'
curl -sS -i -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/assets" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ASSET\",\"id\":\"$HOST\",\"assetType\":\"MACHINE\",\"identityPolicyRef\":\"POLICY-V1\"}"

# C005 — Create a component asset. Expected: HTTP 201; fabricStatus VALID.
printf '\n===== C005: Create motor asset | expected HTTP 201, VALID =====\n'
curl -sS -i -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/assets" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"ASSET\",\"id\":\"$MOTOR\",\"assetType\":\"MOTOR\",\"identityPolicyRef\":\"POLICY-V1\"}"

# C006 — Create a passport for the host. Expected: HTTP 201; fabricStatus VALID.
printf '\n===== C006: Create passport | expected HTTP 201, VALID =====\n'
curl -sS -i -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/passports" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"DIGITAL_PRODUCT_PASSPORT\",\"id\":\"$DPP\",\"schemaVersion\":\"1.0\",\"rootAssetId\":\"$HOST\"}"

# C007 — First actor inspects the motor: PASSED. Expected: HTTP 201, VALID, ACCEPTED.
# No parentEventIds or explicit vectorClock: chaincode assigns the actor's causal clock.
printf '\n===== C007: Inspection PASSED by actor A | expected HTTP 201, VALID, ACCEPTED =====\n'
curl -sS -i -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/events" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"LIFECYCLE_EVENT\",\"id\":\"$EVENT_A\",\"operationId\":\"op-$EVENT_A\",\"type\":\"INSPECT\",\"actorId\":\"$ACTOR_A\",\"dppIds\":[\"$DPP\"],\"affectedAssetIds\":[\"$MOTOR\"],\"parentEventIds\":[],\"effectiveTime\":\"2026-10-20T10:00:00Z\",\"issuedAt\":\"$ISSUED_AT\",\"data\":{\"assetId\":\"$MOTOR\",\"inspectionResult\":\"PASSED\"}}"

# C008 — Different actor inspects the same motor: FAILED, with no causal parent.
# Conflicting inspection outcomes and concurrent actor vector clocks should record
# Conflict id causal:<EVENT_B>. Expected: HTTP 201, VALID, semanticOutcome REJECTED.
printf '\n===== C008: Inspection FAILED by actor B | expected HTTP 201, VALID, REJECTED =====\n'
curl -sS -i -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/events" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"LIFECYCLE_EVENT\",\"id\":\"$EVENT_B\",\"operationId\":\"op-$EVENT_B\",\"type\":\"INSPECT\",\"actorId\":\"$ACTOR_B\",\"dppIds\":[\"$DPP\"],\"affectedAssetIds\":[\"$MOTOR\"],\"parentEventIds\":[],\"effectiveTime\":\"2026-10-20T10:05:00Z\",\"issuedAt\":\"$ISSUED_AT\",\"data\":{\"assetId\":\"$MOTOR\",\"inspectionResult\":\"FAILED\"}}"

# C009 — Accepted decision for the first event. Expected: HTTP 200; outcome ACCEPTED.
printf '\n===== C009: Read accepted decision | expected HTTP 200, ACCEPTED =====\n'
curl -sS -i -w '\nHTTP_STATUS=%{http_code}\n' "$BASE_URL/api/decisions/decision:${EVENT_A}"

# C010 — Rejected decision for the second event. Expected: HTTP 200; outcome REJECTED.
printf '\n===== C010: Read rejected decision | expected HTTP 200, REJECTED =====\n'
curl -sS -i -w '\nHTTP_STATUS=%{http_code}\n' "$BASE_URL/api/decisions/decision:${EVENT_B}"

# C011 — The causal conflict must exist independently of INSTALL/REMOVE validation.
# Expected: HTTP 200, status PENDING, eventIds include both inspection event IDs.
printf '\n===== C011: Read causal conflict | expected HTTP 200, PENDING, two eventIds =====\n'
curl -sS -i -w '\nHTTP_STATUS=%{http_code}\n' "$BASE_URL/api/conflicts/$CONFLICT_ID"

# C012 — Confirm the already accepted inspection, without modifying lifecycle decisions.
# Expected: HTTP 201, fabricStatus VALID, resolution action ACCEPT_EVENT.
printf '\n===== C012: Resolve causal conflict | expected HTTP 201, VALID =====\n'
curl -sS -i -w '\nHTTP_STATUS=%{http_code}\n' -X POST "$BASE_URL/api/conflicts/$CONFLICT_ID/resolve" -H 'Content-Type: application/json' \
  -d "{\"docType\":\"RESOLUTION\",\"id\":\"$RESOLUTION_ID\",\"conflictId\":\"$CONFLICT_ID\",\"selectedEventId\":\"$EVENT_A\",\"actorId\":\"$ACTOR_A\",\"action\":\"ACCEPT_EVENT\",\"decidedAt\":\"$ISSUED_AT\",\"policyRef\":\"CONFLICT_RESOLUTION_V1\",\"reason\":\"Confirm the accepted inspection and preserve the rejected conflicting assertion.\"}"

# C013 — Read the immutable resolution. Expected: HTTP 200, selectedEventId = EVENT_A.
printf '\n===== C013: Read resolution | expected HTTP 200, ACCEPT_EVENT =====\n'
curl -sS -i -w '\nHTTP_STATUS=%{http_code}\n' "$BASE_URL/api/resolutions/$RESOLUTION_ID"

# C014 — Conflict should be closed and linked to the stored resolution.
# Expected: HTTP 200, status RESOLVED, resolutionId = RESOLUTION_ID.
printf '\n===== C014: Read resolved conflict | expected HTTP 200, RESOLVED =====\n'
curl -sS -i -w '\nHTTP_STATUS=%{http_code}\n' "$BASE_URL/api/conflicts/$CONFLICT_ID"

# C015 — Resolution must not promote the rejected assertion to ACCEPTED.
# Expected: HTTP 200, outcome REJECTED (unchanged).
printf '\n===== C015: Verify rejected decision unchanged | expected HTTP 200, REJECTED =====\n'
curl -sS -i -w '\nHTTP_STATUS=%{http_code}\n' "$BASE_URL/api/decisions/decision:${EVENT_B}"

printf '\n===== TEST FINISHED =====\n'
printf 'Log: %s\n' "$LOG_FILE"
printf 'Inspect C007-C015: the script captures responses but does not assign PASS/FAIL.\n'
