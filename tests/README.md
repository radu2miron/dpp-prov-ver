# Test Suites

The tests are organized by **purpose**, matching the repository's experimental workflow:

| Directory | Contents | Status |
|---|---|---|
| [`functional/`](functional/README.md) | Hyperledger Fabric CLI scenarios, expected outcomes, regressions and historical development tests | Eight supplied suites, 397 commands |
| [`concurrency/`](concurrency/README.md) | Concurrent transaction, guard-key, and MVCC tests | Planned; no test artifacts included yet |
| [`performance/`](performance/README.md) | Apache JMeter plans, workload configurations and runners | Planned; no JMeter artifacts included yet |

**Input scripts are not observed results.** Keep captured logs, observed assertions and measurements in the separate repository-root [`results/`](../results/README.md) directory.

For chaincode deployment instructions, see [`dpp-prov-ver-chaincode/README.md`](../dpp-prov-ver-chaincode/README.md).
