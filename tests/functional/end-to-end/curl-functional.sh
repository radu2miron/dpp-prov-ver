#!/usr/bin/env bash
# DPP-Prov-Ver API — plain curl functional scenarios.
# Each scenario is a direct curl command. All HTTP headers/bodies are logged.
# Run: bash tests/functional/api/curl-functional.sh
# Optional: BASE_URL=http://localhost:8081 LOG_FILE=/path/to/log bash ...
# The script does NOT automatically grade PASS/FAIL and intentionally includes
# expected HTTP 400 responses and semantic REJECTED events.
# Use only with a test Fabric network: POST/PUT operations mutate the ledger.
set -u
BASE_URL="${BASE_URL:-http://localhost:8081}"
BASE_URL="${BASE_URL%/}"
# Timestamp plus random bytes: fresh entity IDs and operationIds on every run.
RUN_ID="e2e-$(date -u +%Y%m%dT%H%M%SZ)-$(od -An -N6 -tx1 /dev/urandom | tr -d "[:space:]")"
LOG_FILE="${LOG_FILE:-results/functional/end-to-end/api-curl-${RUN_ID}.log}"
mkdir -p "$(dirname "$LOG_FILE")"
exec > >(tee -a "$LOG_FILE") 2>&1
printf 'DPP-Prov-Ver API curl functional run\nRUN_ID=%s\nBASE_URL=%s\nLOG_FILE=%s\n' "$RUN_ID" "$BASE_URL" "$LOG_FILE"

# All references use this run's unique prefix. No ledger reset is required.
ACTOR="${RUN_ID}-actor"
HOST1="${RUN_ID}-host1"
HOST2="${RUN_ID}-host2"
MOTOR1="${RUN_ID}-motor1"
MOTOR2="${RUN_ID}-motor2"
DPP1="${RUN_ID}-dpp1"
DPP2="${RUN_ID}-dpp2"
INSTALL1="${RUN_ID}-install1"
OCCUPIED="${RUN_ID}-occupied-slot"
BAD_REMOVE="${RUN_ID}-remove-uninstalled"
REMOVE1="${RUN_ID}-remove1"
INSPECT_FAIL="${RUN_ID}-inspect-failed"
BAD_REUSE="${RUN_ID}-reuse-without-pass"
REPAIR1="${RUN_ID}-repair1"
INSPECT_PASS="${RUN_ID}-inspect-passed"
REUSE1="${RUN_ID}-reuse1"
INSTALL2="${RUN_ID}-install2"
CORRECT1="${RUN_ID}-correct1"
RESOLUTION="${RUN_ID}-resolution"

# BEGIN TEST CASES. Run sequentially, because later scenarios use earlier writes.

# === Health and Actor ===

# F001: HTTP health (not a Fabric connection test). Expected: 200.
printf '\n===== F001: HTTP health (not a Fabric connection test) | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/health"

# F002: Create actor. Expected: 201.
printf '\n===== F002: Create actor | expected: 201 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/actors" <<JSON
{"docType":"ACTOR","id":"${ACTOR}","organizationId":"Org1MSP","role":"MANUFACTURER"}
JSON

# F003: Read actor. Expected: 200.
printf '\n===== F003: Read actor | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/actors/${ACTOR}"

# F004: List actors. Expected: 200.
printf '\n===== F004: List actors | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/actors"

# F005: Update actor role. Expected: 200.
printf '\n===== F005: Update actor role | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X PUT \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/actors/${ACTOR}" <<JSON
{"docType":"ACTOR","id":"${ACTOR}","organizationId":"Org1MSP","role":"SERVICE_PROVIDER"}
JSON

# F006: Read updated actor. Expected: 200.
printf '\n===== F006: Read updated actor | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/actors/${ACTOR}"

# === Assets ===

# F007: Create host 1. Expected: 201.
printf '\n===== F007: Create host 1 | expected: 201 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/assets" <<JSON
{"docType":"ASSET","id":"${HOST1}","externalId":"urn:e2e:${HOST1}","assetType":"MACHINE","identityPolicyRef":"POLICY-V1"}
JSON

# F008: Create host 2. Expected: 201.
printf '\n===== F008: Create host 2 | expected: 201 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/assets" <<JSON
{"docType":"ASSET","id":"${HOST2}","externalId":"urn:e2e:${HOST2}","assetType":"MACHINE","identityPolicyRef":"POLICY-V1"}
JSON

# F009: Create motor 1. Expected: 201.
printf '\n===== F009: Create motor 1 | expected: 201 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/assets" <<JSON
{"docType":"ASSET","id":"${MOTOR1}","externalId":"urn:e2e:${MOTOR1}","assetType":"MOTOR","identityPolicyRef":"POLICY-V1"}
JSON

# F010: Create motor 2. Expected: 201.
printf '\n===== F010: Create motor 2 | expected: 201 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/assets" <<JSON
{"docType":"ASSET","id":"${MOTOR2}","externalId":"urn:e2e:${MOTOR2}","assetType":"MOTOR","identityPolicyRef":"POLICY-V1"}
JSON

# F011: Read motor 1. Expected: 200.
printf '\n===== F011: Read motor 1 | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/assets/${MOTOR1}"

# F012: List assets. Expected: 200.
printf '\n===== F012: List assets | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/assets"

# F013: Update motor 1. Expected: 200.
printf '\n===== F013: Update motor 1 | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X PUT \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/assets/${MOTOR1}" <<JSON
{"docType":"ASSET","id":"${MOTOR1}","externalId":"urn:e2e:${MOTOR1}","assetType":"MOTOR","identityPolicyRef":"POLICY-V1"}
JSON

# F014: Read updated motor 1. Expected: 200.
printf '\n===== F014: Read updated motor 1 | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/assets/${MOTOR1}"

# === Digital Product Passports ===

# F015: Create DPP 1. Expected: 201.
printf '\n===== F015: Create DPP 1 | expected: 201 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/passports" <<JSON
{"docType":"DIGITAL_PRODUCT_PASSPORT","id":"${DPP1}","rootAssetId":"${HOST1}","schemaVersion":"1.0"}
JSON

# F016: Create DPP 2. Expected: 201.
printf '\n===== F016: Create DPP 2 | expected: 201 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/passports" <<JSON
{"docType":"DIGITAL_PRODUCT_PASSPORT","id":"${DPP2}","rootAssetId":"${HOST2}","schemaVersion":"1.0"}
JSON

# F017: Read DPP 1. Expected: 200.
printf '\n===== F017: Read DPP 1 | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/passports/${DPP1}"

# F018: List DPPs. Expected: 200.
printf '\n===== F018: List DPPs | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/passports"

# F019: Update DPP schema version. Expected: 200.
printf '\n===== F019: Update DPP schema version | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X PUT \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/passports/${DPP1}" <<JSON
{"docType":"DIGITAL_PRODUCT_PASSPORT","id":"${DPP1}","rootAssetId":"${HOST1}","schemaVersion":"1.1"}
JSON

# F020: Read updated DPP. Expected: 200.
printf '\n===== F020: Read updated DPP | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/passports/${DPP1}"

# === Install and initial provenance ===

# F021: Install motor 1 into host 1. Expected: 201; semantic ACCEPTED.
printf '\n===== F021: Install motor 1 into host 1 | expected: 201; semantic ACCEPTED =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/events" <<JSON
{"docType":"LIFECYCLE_EVENT","id":"${INSTALL1}","operationId":"operation:${INSTALL1}","type":"INSTALL","actorId":"${ACTOR}","dppIds":["${DPP1}"],"affectedAssetIds":["${HOST1}","${MOTOR1}"],"parentEventIds":[],"effectiveTime":"2026-10-20T11:00:00Z","issuedAt":"2026-10-20T22:00:00Z","data":{"componentId":"${MOTOR1}","hostId":"${HOST1}","slotId":"drive"}}
JSON

# F022: Read installation event. Expected: 200.
printf '\n===== F022: Read installation event | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/events/${INSTALL1}"

# F023: List lifecycle events. Expected: 200.
printf '\n===== F023: List lifecycle events | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/events"

# F024: Read accepted installation decision. Expected: 200; outcome ACCEPTED.
printf '\n===== F024: Read accepted installation decision | expected: 200; outcome ACCEPTED =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/decisions/decision:${INSTALL1}"

# F025: Read open containment interval. Expected: 200; validTo absent.
printf '\n===== F025: Read open containment interval | expected: 200; validTo absent =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/containment-intervals/${INSTALL1}"

# F026: State during installation. Expected: 200; motor 1 present.
printf '\n===== F026: State during installation | expected: 200; motor 1 present =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/passports/${DPP1}/state?effectiveTime=2026-10-20T11%3A30%3A00Z&asOfPosition=0"

# === Negative installation and conflict resolution ===

# F027: Install motor 2 into occupied slot (negative). Expected: 201; semantic REJECTED.
printf '\n===== F027: Install motor 2 into occupied slot (negative) | expected: 201; semantic REJECTED =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/events" <<JSON
{"docType":"LIFECYCLE_EVENT","id":"${OCCUPIED}","operationId":"operation:${OCCUPIED}","type":"INSTALL","actorId":"${ACTOR}","dppIds":["${DPP1}"],"affectedAssetIds":["${HOST1}","${MOTOR2}"],"parentEventIds":[],"effectiveTime":"2026-10-20T11:10:00Z","issuedAt":"2026-10-20T22:00:00Z","data":{"componentId":"${MOTOR2}","hostId":"${HOST1}","slotId":"drive"}}
JSON

# F028: Read rejected decision. Expected: 200; outcome REJECTED.
printf '\n===== F028: Read rejected decision | expected: 200; outcome REJECTED =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/decisions/decision:${OCCUPIED}"

# F029: Read pending structural conflict. Expected: 200; status PENDING.
printf '\n===== F029: Read pending structural conflict | expected: 200; status PENDING =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/conflicts/${OCCUPIED}"

# F030: Resolve structural conflict. Expected: 201.
printf '\n===== F030: Resolve structural conflict | expected: 201 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/conflicts/${OCCUPIED}/resolve" <<JSON
{"docType":"RESOLUTION","id":"${RESOLUTION}","action":"ACCEPT_EVENT","decidedAt":"2026-10-20T21:00:00Z","policyRef":"REVIEW_V1","reason":"Confirm the existing accepted installation.","conflictId":"${OCCUPIED}","selectedEventId":"${INSTALL1}","actorId":"${ACTOR}"}
JSON

# F031: Read resolution. Expected: 200.
printf '\n===== F031: Read resolution | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/resolutions/${RESOLUTION}"

# F032: Read resolved conflict. Expected: 200; status RESOLVED.
printf '\n===== F032: Read resolved conflict | expected: 200; status RESOLVED =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/conflicts/${OCCUPIED}"

# === Removal ===

# F033: Remove uninstalled motor 2 (negative). Expected: 201; semantic REJECTED.
printf '\n===== F033: Remove uninstalled motor 2 (negative) | expected: 201; semantic REJECTED =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/events" <<JSON
{"docType":"LIFECYCLE_EVENT","id":"${BAD_REMOVE}","operationId":"operation:${BAD_REMOVE}","type":"REMOVE","actorId":"${ACTOR}","dppIds":["${DPP1}"],"affectedAssetIds":["${HOST1}","${MOTOR2}"],"parentEventIds":[],"effectiveTime":"2026-10-20T11:15:00Z","issuedAt":"2026-10-20T22:00:00Z","data":{"componentId":"${MOTOR2}","hostId":"${HOST1}","slotId":"drive"}}
JSON

# F034: Remove motor 1. Expected: 201; semantic ACCEPTED.
printf '\n===== F034: Remove motor 1 | expected: 201; semantic ACCEPTED =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/events" <<JSON
{"docType":"LIFECYCLE_EVENT","id":"${REMOVE1}","operationId":"operation:${REMOVE1}","type":"REMOVE","actorId":"${ACTOR}","dppIds":["${DPP1}"],"affectedAssetIds":["${HOST1}","${MOTOR1}"],"parentEventIds":[],"effectiveTime":"2026-10-20T12:00:00Z","issuedAt":"2026-10-20T22:00:00Z","data":{"componentId":"${MOTOR1}","hostId":"${HOST1}","slotId":"drive"}}
JSON

# F035: Read closed containment interval. Expected: 200; validTo 12:00.
printf '\n===== F035: Read closed containment interval | expected: 200; validTo 12:00 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/containment-intervals/${INSTALL1}"

# F036: State after removal. Expected: 200; motor 1 absent.
printf '\n===== F036: State after removal | expected: 200; motor 1 absent =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/passports/${DPP1}/state?effectiveTime=2026-10-20T12%3A05%3A00Z&asOfPosition=0"

# === Inspection, repair and reuse ===

# F037: Record FAILED inspection. Expected: 201; semantic ACCEPTED.
printf '\n===== F037: Record FAILED inspection | expected: 201; semantic ACCEPTED =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/events" <<JSON
{"docType":"LIFECYCLE_EVENT","id":"${INSPECT_FAIL}","operationId":"operation:${INSPECT_FAIL}","type":"INSPECT","actorId":"${ACTOR}","dppIds":["${DPP1}"],"affectedAssetIds":["${MOTOR1}"],"parentEventIds":[],"effectiveTime":"2026-10-20T12:30:00Z","issuedAt":"2026-10-20T22:00:00Z","data":{"assetId":"${MOTOR1}","inspectionResult":"FAILED"}}
JSON

# F038: Read failed inspection. Expected: 200; result FAILED.
printf '\n===== F038: Read failed inspection | expected: 200; result FAILED =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/inspections/${INSPECT_FAIL}"

# F039: Reuse without passing inspection (negative). Expected: 201; semantic REJECTED.
printf '\n===== F039: Reuse without passing inspection (negative) | expected: 201; semantic REJECTED =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/events" <<JSON
{"docType":"LIFECYCLE_EVENT","id":"${BAD_REUSE}","operationId":"operation:${BAD_REUSE}","type":"REUSE","actorId":"${ACTOR}","dppIds":["${DPP1}"],"affectedAssetIds":["${MOTOR1}"],"parentEventIds":[],"effectiveTime":"2026-10-20T12:40:00Z","issuedAt":"2026-10-20T22:00:00Z","data":{"assetId":"${MOTOR1}"}}
JSON

# F040: Repair motor after failed inspection. Expected: 201; semantic ACCEPTED.
printf '\n===== F040: Repair motor after failed inspection | expected: 201; semantic ACCEPTED =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/events" <<JSON
{"docType":"LIFECYCLE_EVENT","id":"${REPAIR1}","operationId":"operation:${REPAIR1}","type":"REPAIR","actorId":"${ACTOR}","dppIds":["${DPP1}"],"affectedAssetIds":["${MOTOR1}"],"parentEventIds":[],"effectiveTime":"2026-10-20T13:00:00Z","issuedAt":"2026-10-20T22:00:00Z","data":{"assetId":"${MOTOR1}","evidenceRef":"urn:e2e:repair-evidence"}}
JSON

# F041: Record PASSED inspection. Expected: 201; semantic ACCEPTED.
printf '\n===== F041: Record PASSED inspection | expected: 201; semantic ACCEPTED =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/events" <<JSON
{"docType":"LIFECYCLE_EVENT","id":"${INSPECT_PASS}","operationId":"operation:${INSPECT_PASS}","type":"INSPECT","actorId":"${ACTOR}","dppIds":["${DPP1}"],"affectedAssetIds":["${MOTOR1}"],"parentEventIds":[],"effectiveTime":"2026-10-20T13:30:00Z","issuedAt":"2026-10-20T22:00:00Z","data":{"assetId":"${MOTOR1}","inspectionResult":"PASSED"}}
JSON

# F042: Approve motor reuse. Expected: 201; semantic ACCEPTED.
printf '\n===== F042: Approve motor reuse | expected: 201; semantic ACCEPTED =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/events" <<JSON
{"docType":"LIFECYCLE_EVENT","id":"${REUSE1}","operationId":"operation:${REUSE1}","type":"REUSE","actorId":"${ACTOR}","dppIds":["${DPP1}"],"affectedAssetIds":["${MOTOR1}"],"parentEventIds":[],"effectiveTime":"2026-10-20T14:00:00Z","issuedAt":"2026-10-20T22:00:00Z","data":{"assetId":"${MOTOR1}"}}
JSON

# F043: Install reused motor into host 2. Expected: 201; semantic ACCEPTED.
printf '\n===== F043: Install reused motor into host 2 | expected: 201; semantic ACCEPTED =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/events" <<JSON
{"docType":"LIFECYCLE_EVENT","id":"${INSTALL2}","operationId":"operation:${INSTALL2}","type":"INSTALL","actorId":"${ACTOR}","dppIds":["${DPP2}"],"affectedAssetIds":["${HOST2}","${MOTOR1}"],"parentEventIds":[],"effectiveTime":"2026-10-20T15:00:00Z","issuedAt":"2026-10-20T22:00:00Z","data":{"componentId":"${MOTOR1}","hostId":"${HOST2}","slotId":"drive"}}
JSON

# F044: Host 2 state after reuse. Expected: 200; motor 1 present.
printf '\n===== F044: Host 2 state after reuse | expected: 200; motor 1 present =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/passports/${DPP2}/state?effectiveTime=2026-10-20T15%3A30%3A00Z&asOfPosition=0"

# F045: Host 1 state after reuse. Expected: 200; motor 1 absent.
printf '\n===== F045: Host 1 state after reuse | expected: 200; motor 1 absent =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/passports/${DPP1}/state?effectiveTime=2026-10-20T15%3A30%3A00Z&asOfPosition=0"

# === Historical correction ===

# F046: Correct the removal effective time. Expected: 201; semantic ACCEPTED.
printf '\n===== F046: Correct the removal effective time | expected: 201; semantic ACCEPTED =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/events" <<JSON
{"docType":"LIFECYCLE_EVENT","id":"${CORRECT1}","operationId":"operation:${CORRECT1}","type":"CORRECT","actorId":"${ACTOR}","dppIds":["${DPP1}"],"affectedAssetIds":["${HOST1}","${MOTOR1}"],"parentEventIds":["${REMOVE1}"],"effectiveTime":"2026-10-20T16:00:00Z","issuedAt":"2026-10-20T22:00:00Z","data":{"targetEventId":"${REMOVE1}","correctedEffectiveTime":"2026-10-20T12:10:00Z"}}
JSON

# F047: Effective state before corrected removal. Expected: 200; motor 1 present.
printf '\n===== F047: Effective state before corrected removal | expected: 200; motor 1 present =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/passports/${DPP1}/state?effectiveTime=2026-10-20T12%3A05%3A00Z&asOfPosition=0"

# F048: Effective state after corrected removal. Expected: 200; motor 1 absent.
printf '\n===== F048: Effective state after corrected removal | expected: 200; motor 1 absent =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/passports/${DPP1}/state?effectiveTime=2026-10-20T12%3A15%3A00Z&asOfPosition=0"

# === Negative HTTP inputs and list queries ===

# F049: Nonzero asOfPosition (negative). Expected: 400; INVALID_INPUT (expected).
printf '\n===== F049: Nonzero asOfPosition (negative) | expected: 400; INVALID_INPUT (expected) =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/passports/${DPP1}/state?effectiveTime=2026-10-20T12%3A05%3A00Z&asOfPosition=1"

# F050: Missing lifecycle event identifiers (negative). Expected: 400; INVALID_INPUT (expected).
printf '\n===== F050: Missing lifecycle event identifiers (negative) | expected: 400; INVALID_INPUT (expected) =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X POST \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/events" <<JSON
{"docType":"LIFECYCLE_EVENT","id":"","operationId":"","type":"INSPECT","actorId":"${ACTOR}","dppIds":["${DPP1}"],"affectedAssetIds":["${MOTOR1}"],"effectiveTime":"2026-10-20T17:00:00Z","issuedAt":"2026-10-20T22:00:00Z","data":{"assetId":"${MOTOR1}","inspectionResult":"PASSED"}}
JSON

# F051: Asset URL/body ID mismatch (negative). Expected: 400; INVALID_INPUT (expected).
printf '\n===== F051: Asset URL/body ID mismatch (negative) | expected: 400; INVALID_INPUT (expected) =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X PUT \
  -H "Content-Type: application/json" --data-binary @- \
  "$BASE_URL/api/assets/${HOST2}" <<JSON
{"docType":"ASSET","id":"${MOTOR2}","externalId":"urn:e2e:${MOTOR2}","assetType":"MOTOR","identityPolicyRef":"POLICY-V1"}
JSON

# F052: List inspections. Expected: 200.
printf '\n===== F052: List inspections | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/inspections"

# F053: List decisions. Expected: 200.
printf '\n===== F053: List decisions | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/decisions"

# F054: List containment intervals. Expected: 200.
printf '\n===== F054: List containment intervals | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/containment-intervals"

# F055: List resolutions. Expected: 200.
printf '\n===== F055: List resolutions | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/resolutions"

# F056: List conflicts. Expected: 200.
printf '\n===== F056: List conflicts | expected: 200 =====\n'
curl -i -sS --connect-timeout 10 --max-time 120 -w '\nHTTP_STATUS=%{http_code}\n' -X GET \
  "$BASE_URL/api/conflicts"

printf "\n===== End of curl scenarios =====\n"
