# Run 08: Global Sequence Removal — Functional Verification

**Run ID:** `gs20261020114721`  
**Test definition:** `tests/functional/current/08-remove-global-sequence.txt`  
**Captured log:** `08-regression-gs20261020114721.log`  
**CLI timestamps:** 2026-10-20 14:47:22–14:47:29 EEST

## Results

All 17 command responses matched their documented expected **response type** in the captured CLI log:

- **12 state-changing operations** returned Fabric CLI `status:200`, with `VALID` confirmations from both participating peer addresses (24 peer-specific commit confirmations).
- **4 successful query operations** returned JSON.
- **1 negative query** returned the expected `status:500` generic contract execution error when the `asOfPosition` parameter was nonzero. The precise underlying exception is not visible in the CLI output.

## Per-command assessment

| ID | Scenario | Expected | Observed | Outcome |
| --- | --- | --- | --- | --- |
| `F08-01` | Create actor A | `SUCCESS_VALID` | VALID / 200 | **PASS** |
| `F08-02` | Create actor B | `SUCCESS_VALID` | VALID / 200 | **PASS** |
| `F08-03` | Create host asset P1 | `SUCCESS_VALID` | VALID / 200 | **PASS** |
| `F08-04` | Create host asset P2 | `SUCCESS_VALID` | VALID / 200 | **PASS** |
| `F08-05` | Create motor asset M1 | `SUCCESS_VALID` | VALID / 200 | **PASS** |
| `F08-06` | Create motor asset M2 | `SUCCESS_VALID` | VALID / 200 | **PASS** |
| `F08-07` | Create DPP for P1 | `SUCCESS_VALID` | VALID / 200 | **PASS** |
| `F08-08` | Create DPP for P2 | `SUCCESS_VALID` | VALID / 200 | **PASS** |
| `F08-09` | Submit inspection for M1 | `ACCEPTED_VALID` | VALID / 200 / ACCEPTED | **PASS** |
| `F08-10` | Read first inspection acceptance decision | `ACCEPTED_NO_LEDGER_POSITION` | ACCEPTED; ledgerPosition absent | **PASS** |
| `F08-11` | Submit inspection for M2 | `ACCEPTED_VALID` | VALID / 200 / ACCEPTED | **PASS** |
| `F08-12` | Read second inspection acceptance decision | `ACCEPTED_NO_LEDGER_POSITION` | ACCEPTED; ledgerPosition absent | **PASS** |
| `F08-13` | Install M1 in P1 | `ACCEPTED_VALID` | VALID / 200 / ACCEPTED | **PASS** |
| `F08-14` | Reconstruct state at 11:30 UTC | `ONE_ACTIVE_INTERVAL` | One active containment interval at 11:30 UTC | **PASS** |
| `F08-15` | Remove M1 from P1 | `ACCEPTED_VALID` | VALID / 200 / ACCEPTED | **PASS** |
| `F08-16` | Reconstruct state at 12:30 UTC | `NO_ACTIVE_INTERVAL_CLOSED_AT_12` | No active interval at 12:30 UTC; prior interval closed at 12:00 UTC | **PASS** |
| `F08-17` | Reject unsupported nonzero asOfPosition argument | `HTTP_500_QUERY_ERROR` | Query error: status 500 | **PASS** |

## Evidence highlights

1. The read operations for `decision:gs20261020114721-inspect1` and `decision:gs20261020114721-inspect2` return `outcome: ACCEPTED`, and neither JSON object contains a `ledgerPosition` field.
2. The response for `reconstructState(dpp1, 2026-10-20T11:30:00Z, 0)` contains one active containment interval for the first motor installed in the first machine, starting at `2026-10-20T11:00:00Z`.
3. The response for `reconstructState(dpp1, 2026-10-20T12:30:00Z, 0)` omits the `activeIntervals` member and includes the historical interval with `validTo: 2026-10-20T12:00:00Z`; the absence of active intervals is inferred from this response shape.
4. The response for `reconstructState(dpp1, 2026-10-20T12:30:00Z, 1)` is an endorsement query failure (`status:500`). The generic error does not prove *which specific validation rule* triggered it.

## Limitations and provenance

These are **observations derived from user-supplied terminal output**, not fresh Fabric transactions performed during report preparation. The mapping to the 17 command positions uses the order of the tracked original test suite, the observed CLI message sequence, and the unique-prefix substitution. The reconstructed commands document that mapping but were not captured from the terminal during the original run. There is no automated in-process PASS/FAIL counter, no recorded shell exit statuses, and no evidence here of concurrency, MVCC conflicts, throughput, or long-run stability. The chaincode Git commit, deployment sequence number, exact runtime versions, and machine configuration have not been supplied for this run.

The original log should be preserved unchanged as the primary source. Any further experimental evaluation should record structured test outcomes and environment metadata at execution time.
