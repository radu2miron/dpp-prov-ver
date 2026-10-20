# Functional Test Results

This directory stores **actual captured output** from the functional tests of the DPP-Prov-Ver Hyperledger Fabric chaincode. Test definitions remain in [`tests/functional/`](../../tests/functional/README.md). The historical suites are retained there for documentation, but their original execution logs are unavailable and are **not represented as current experimental results**.

## Available run

| Field | Value |
| --- | --- |
| Test suite | `tests/functional/current/08-remove-global-sequence.txt` |
| Execution identifier | `gs20261020114721` |
| Recorded CLI timestamp | 2026-10-20, 14:47:22–14:47:29 EEST (UTC+03:00) |
| Channel | `dpp-prov-ver-channel` |
| Chaincode | `dpp-prov-ver-chaincode` |
| Endorsing/commit peers shown in log | `localhost:7051`, `localhost:9051` |
| Commands | 17 (12 invokes, 4 successful queries, 1 intentionally failing query) |
| Observed expected response types | **17 / 17** |
| Full suite automated assertion run | **No**; classification was performed retrospectively against the captured CLI output |

## Files

- [`08-regression-gs20261020114721.log`](08-regression-gs20261020114721.log): unmodified terminal output (includes ANSI formatting codes).
- [`summary.csv`](summary.csv): one row per command, expected outcome versus observed response and evidence.
- [`08-regression-gs20261020114721-report.md`](08-regression-gs20261020114721-report.md): human-readable verification report and limitations.
- [`08-regression-gs20261020114721-commands-reconstructed.txt`](08-regression-gs20261020114721-commands-reconstructed.txt): test commands reconstructed from the tracked current suite using the unique-run identifier. These are **not** independently captured terminal history; do not rerun unchanged against the same ledger.
- [`08-regression-gs20261020114721-manifest.json`](08-regression-gs20261020114721-manifest.json): provenance metadata and SHA-256 checksum of the original log.

## Interpretation

All 12 invocations returned Fabric CLI success (`status:200`), with transactions reported `VALID` by both peers. Two inspection decisions were queried and returned `ACCEPTED` without a `ledgerPosition`. The installed motor appeared active at 11:30 UTC and absent from the active containment set at 12:30 UTC; the closed interval records `validTo=12:00 UTC`. The final query with nonzero `asOfPosition` produced an expected HTTP-style `500` chaincode query error.

**Important qualification:** the error message is generic (`Error during contract method execution`), so its precise internal cause is not established from the log alone. The file is an output-level smoke/regression record, not a comprehensive functional, concurrency, causal-conflict, or performance evaluation. The original terminal log does not include executed command strings, per-command exit codes, runtime version details, Git commit hashes, or independent ledger audit evidence. No values for those fields have been fabricated.

The identifiers contain a time-based prefix so the run does not collide with earlier test documents. The exact wall-clock suffix in the filename corresponds to the user-generated run ID; the log itself supplies the recorded EEST timestamps.

## Running additional tests

Use a new unique identifier prefix or a fresh test ledger for each execution. Record both command outputs and exit codes, including expected negative tests. A transaction marked `VALID` by Fabric may still represent a semantic `REJECTED` outcome at the application layer.
