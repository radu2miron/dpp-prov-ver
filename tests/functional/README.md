# Functional CLI Tests — DPP-Prov-Ver

This directory contains eight **development-stage command suites** for the Hyperledger Fabric DPP provenance and versioning prototype. It is intended to accompany the source repository `dpp-prov-ver` and its Java chaincode project `dpp-prov-ver-chaincode`.

## Important: historical versus current tests

- `current/08-remove-global-sequence.txt` targets the latest **no-global-sequence** contract interface. Treat it as a **manual smoke/regression candidate**, not as an automated test or proof of multi-client concurrency. Its commands have not been rerun by the authoring assistant against a live Fabric network.
- `historical/01-...` through `historical/07-...` document the development and earlier functional-validation stages. Preserve them for traceability, **but do not run them wholesale against the current contract**. Some API methods and expected behaviors changed as the prototype evolved.
- Earlier suites were reported as passing at their respective development stages, but no complete terminal-output archive was provided. The stage-08 smoke suite has not been independently confirmed after the most recent change. This repository **does not contain the original terminal logs**, transaction receipts, or machine-verifiable assertion outputs. It must not present those as archived raw observations.

## Suite index

| Stage | File | Commands | Purpose | Compatibility |
|---|---|---:|---|---|
| 01 | `historical/01-crud-foundation.txt` | 48 | Initial CRUD persistence and negative validation | **Legacy API**; direct event/decision creation methods removed |
| 02 | `historical/02-lifecycle-basics.txt` | 56 | INSTALL/REMOVE/INSPECT and structural errors | Earlier lifecycle policy; REUSE later became mandatory for reinstall |
| 03 | `historical/03-repair-reuse.txt` | 63 | Eligibility and reuse lifecycle | Earlier build; regression not rerun on current build |
| 04 | `historical/04-causal-versioning.txt` | 67 | Vector clocks, causal parents, semantic conflicts | Earlier build; regression not rerun on current build |
| 05 | `historical/05-conflict-resolution.txt` | 65 | Conflict adjudication and idempotency | Earlier build; regression not rerun on current build |
| 06 | `historical/06-historical-corrections.txt` | 54 | Temporal correction and prior logical positions | **Legacy as-of API**, some commands obsolete |
| 07 | `historical/07-dpp-asset-event-links.txt` | 27 | Root-asset associations and reconstruction | Earlier build; regression not rerun on current build |
| 08 | `current/08-remove-global-sequence.txt` | 17 | No global sequence, read/submit smoke tests | Current API smoke-test candidate |

Total: **397 commands**, including Fabric queries, invokes, and two auxiliary shell captures. Commands retain their original synthetic IDs and chronological ordering. Inline comments and documentation are in English. Original command payloads are preserved, including obsolete commands where necessary to document prototype evolution.

## Examples of intentionally retained obsolete cases

1. **`createLifecycleEvent`, `createContainmentInterval`, `createInspection`, `createAcceptanceDecision`, `createConflict`, and `createResolution`** were part of an early, persistence-only facade. They are absent from the current `DppProvVerContract`. Current lifecycle assertions are submitted with `submitEvent()`; conflict resolutions use `resolveConflict()`.
2. The stage-01 `Asset` fixtures include example-only `name`, `owner`, and `status` fields. These were removed from the production domain model. The historical requests are preserved verbatim rather than silently rewriting previous tests.
3. Stage-02 expected a direct reinstall after removal. Later lifecycle policies require an accepted `REUSE` event before reinstallation.
4. Stage-06 extracts `ledgerPosition` from acceptance decisions and queries historical `asOfPosition` values. After removing the global logical sequence, ledger positions are determined from Fabric block events, and chaincode-side `reconstructState(dppId, effectiveTime, "0")` provides only the latest-known view. The old historical-position tests belong to the archived suite until an off-chain projection is implemented.

## Executing the current smoke suite

Deploy the corresponding chaincode first, then open a terminal in `fabric-samples/test-network`. Configure `CORE_PEER_*`, `FABRIC_CFG_PATH`, and `PATH` as described in the [chaincode deployment guide](../../dpp-prov-ver-chaincode/README.md) when this directory is located at `tests/functional/`.

Copy **one command at a time**, in order, from `current/08-remove-global-sequence.txt`. Do not blindly execute every command with `set -e`: the suite includes an intentionally failing negative query. `CREATE` operations are not repeatable against a ledger containing those IDs. A new test-network ledger, different fixture IDs, or an appropriate test setup is required for reruns.

A successful Fabric commit (`VALID`) does not automatically mean a business event was accepted. Check the `AcceptanceDecision.outcome` field (`ACCEPTED` / `REJECTED`) and the resulting state. For expected execution errors, verify that no prohibited state change was committed.

## Evidence and reproducibility

The command suites specify **test inputs and expected behavior**, not captured output files. For a paper, archive actual test outputs, test date, chaincode Git commit hash, Fabric/channel configuration, endorsement policy, peer/container logs if relevant, and JMeter raw `.jtl` data for performance experiments. The expected outcomes in the comments should not be counted as independently stored measured results.

Use [`results/README.md`](../../results/README.md) for the evidence-capture convention. Large or performance-critical datasets should be archived with a versioned release and optionally a Zenodo DOI after the final experiments.

## License

This material accompanies the repository's Apache-2.0 license.
