# Experimental Results

This directory is reserved for **observed results** rather than input CLI commands or expected outcomes.

- [`functional/`](functional/README.md): real Fabric CLI logs, observed decisions, transaction IDs, commit validation results and run-level summaries.
- [`concurrency/`](concurrency/README.md): actual outcomes of concurrent proposals, MVCC validation codes, conflict/retry counts and run parameters.
- [`performance/`](performance/README.md): JMeter `.jtl` files, CSV summaries, run metadata, and plots or analysis scripts.

**No raw execution logs or measured performance results were supplied for this package.** These directories intentionally contain documentation only. Do not interpret test-case comments as measured outcomes.

Capture for every reproducible run: Git commit SHA, deployed chaincode package/version and Fabric network configuration, Fabric version, hardware and Docker versions, date/time, workload settings, peer validation status, and semantic `AcceptanceDecision.outcome` where applicable. Never publish private keys, credentials or other sensitive network material.
