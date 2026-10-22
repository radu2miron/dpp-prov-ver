# DPP-Prov-Ver

**Temporal provenance, causal versioning, and conflict resolution for distributed Digital Product Passports**

DPP-Prov-Ver is a research prototype for maintaining consistent Digital Product Passport (DPP) lifecycle records when components are installed, removed, inspected, repaired, and reused across products. It combines effective-time provenance, causally related lifecycle events, and explicit conflict decisions on a permissioned **Hyperledger Fabric** network.

The repository is intended to accompany an academic study and to make its implementation, test scenarios, configuration, and experimental evidence reproducible.

> **Project status:** The Java chaincode and functional CLI scenarios have been developed and tested on a local Fabric network. Consolidated concurrency benchmarks, the Spring Boot historical projection, and publication-ready raw performance datasets are being prepared. Only files actually committed to this repository should be treated as publicly available.

## Research scope

The prototype addresses three complementary requirements:

1. **Temporal provenance:** keep stable component identities and reconstruct product composition at a specified effective time, even after removal, repair, or reuse.
2. **Causal consistency:** record event dependencies with vector clocks and distinguish causally ordered updates from concurrent ones.
3. **Semantic conflict management:** identify incompatible lifecycle assertions, preserve acceptance decisions, and record explicit conflict resolutions and historical corrections.

The model uses persistent `Asset` identities, `LifecycleEvent` assertions, `ContainmentInterval` records, and separate `AcceptanceDecision` documents. Physical components are not deleted when their lifecycle state changes.

## Implementation

| Component | Technology / role |
| --- | --- |
| Permissioned blockchain | Hyperledger Fabric 2.5.x, two-organization test network |
| Smart contracts | Java 21 chaincode, Fabric Java Contract API — **dpp-prov-ver-chaincode** |
| State database | CouchDB |
| Build | Maven |
| Contract facade | `edu.tucn.dppprovver.contract.DppProvVerContract` |
| Client API | Spring Boot / Fabric Gateway integration — **dpp-prov-ver-api** |
| Functional testing | chaincode-only & end-to-end — **tests/functional** & **results/functiona** |
| Concurrency testing | end-to-end — **tests/concurrency** & **results/concurrency** |
| Load testing | Apache JMeter — end-to-end — **tests/performance** & **results/performance** |
| Historical projection | Commit-ordered index derived from Fabric block events |

The chaincode separates each entity's persistence service from the contract facade. The principal lifecycle entry point is `submitEvent()`. Implemented lifecycle processing covers `INSTALL`, `REMOVE`, `INSPECT`, `REPAIR`, `REUSE`, and the supported form of `CORRECT`; unsupported enum values should not be interpreted as implemented operations.

Conflict resolution is exposed through `resolveConflict()`. Currently supported resolution actions confirm an existing accepted or rejected event; they do not retroactively apply a previously rejected lifecycle operation.

## Repository layout

The intended repository layout is shown below. Directories for the API, benchmark workloads, and datasets will be populated as those artifacts become available.

```text
dpp-prov-ver/
├── dpp-prov-ver-chaincode/     Java chaincode, Maven project, deployment instructions
├── dpp-prov-ver-api/           Spring Boot API and historical projection (planned)
├── tests/
│   ├── functional/             CLI scenarios, expected outcomes, regression tests
│   ├── concurrency/            Concurrent transaction and MVCC tests (planned)
│   └── performance/            JMeter test plans (planned)
├── results/
│   ├── functional/             Saved validation logs and summaries
│   ├── concurrency/            Raw concurrency measurements (planned)
│   └── performance/            JTL, CSV, and analysis outputs (planned)
├── configs/                    Network and workload configurations
├── docs/                       Data model, methodology, and diagrams
└── README.md
```

## Prerequisites

- JDK **21** and Maven
- Docker with Docker Compose
- Hyperledger Fabric **2.5** samples, binaries, and Docker images
- A Fabric test network configured with **CouchDB**
- Bash on Linux (the provided CLI scenarios use Unix shell quoting)

The chaincode Maven project declares `fabric-chaincode-shim` version `2.5.11`. The reference Fabric network used during development was based on Fabric `2.5.16`. Record the exact runtime versions when publishing a benchmark dataset.

## Chaincode Build and Deployment

The Hyperledger Fabric chaincode is implemented in Java 21
and uses Maven for building and packaging.

For detailed instructions on building, deploying, upgrading,
and testing the chaincode on a Hyperledger Fabric network,
please refer to the
[Chaincode Build and Deployment Guide](dpp-prov-ver-chaincode/README.md)

The guide includes network initialization, channel creation,
chaincode installation, approval, commitment, and CLI testing.

## Functional testing

Functional scenarios are organized by model feature:

| Test group | Main checks |
| --- | --- |
| CRUD | Document creation, reads, updates, duplicate identifiers, document-type isolation |
| Lifecycle | Installation, removal, inspection, repair eligibility, reuse, invalid transitions |
| Causal versioning | Vector clocks, parent dependencies, compatible and incompatible concurrent assertions |
| Conflict resolution | Pending disputes, resolution actions, invalid resolutions, idempotent retries |
| Historical corrections | Accepted corrections, rejected temporal overlaps, preservation of original assertions |
| DPP–Asset–Event association | Correct reconstruction when components move between products |
| Regression | Core behavior after changes to the chaincode and ledger event handling |

Run CLI scripts **in their documented order**, on a clean test dataset or with a new ID prefix. The scenarios include intentional failures. Interpret outcomes as follows:

- **`ACCEPTED`**: a lifecycle operation has been semantically accepted, assuming the corresponding Fabric transaction committed as valid.
- **`REJECTED`**: a semantic rejection was recorded as an `AcceptanceDecision`; it is not a command failure.
- **Endorsement/contract error**: the invocation was rejected before a valid lifecycle decision was committed, typically for malformed input or an invalid operation.
- **Invalid Fabric commit / MVCC conflict**: the transaction was not applied, even if endorsement initially succeeded; inspect commit status before counting it as success.

When using Fabric CLI, prefer `peer chaincode query` for read-only operations and `peer chaincode invoke --waitForEvent` for state-changing operations. Manual test success is not a substitute for storing machine-readable evidence; the public results directory will distinguish test definitions from actual observed outputs.

## Temporal queries and current limitations

The chaincode supports reconstruction of the **latest accepted historical interpretation** at a supplied effective time, using `reconstructState(dppId, effectiveTime, "0")`.

A true *as-known-at* query also needs the real Fabric commit position (block number and transaction index). After removing a global logical-event counter from the transaction path, **nonzero `asOfPosition` queries are not currently supported by the chaincode**. They are intended to be served by an off-chain historical projection that consumes **valid committed** Fabric block events.

Other prototype boundaries:

- The prototype assumes a permissioned, trusted consortium. Fine-grained participant authorization is outside the chaincode research focus.
- Physical identity claims and off-chain inspection evidence are not independently verified by the ledger.
- Historical corrections currently cover a bounded set of temporal adjustments; they do not provide unrestricted replay of all possible lifecycle semantics.
- The current prototype should not be represented as validated for high-throughput concurrent workloads until the dedicated tests have been executed and published.

## Experimental data and reproducibility

The study is designed to compare **Baseline**, **Temporal-only**, **Causal-only**, and **Unified** configurations, subject to implementing and validating genuinely comparable configurations. Planned workloads include varying concurrency, semantic conflict rates, ledger sizes, and historical query patterns.

For each reported experiment, the accompanying results should include:

- the source-code release/tag and chaincode package version;
- exact Fabric, CouchDB, Java, operating system, and JMeter versions;
- hardware and network topology, ordering parameters, and endorsement policies;
- workload definitions, random seeds (where relevant), iteration counts, and warm-up conditions;
- raw request/commit logs or JMeter `.jtl` files and processed `.csv` files;
- scripts that regenerate the tables and figures, plus a manifest linking each result to its configuration.

**No benchmark result should be inferred from the presence of a test script.** Quantitative claims will be added only after the corresponding measurements and processing code have been deposited.

## Availability, archival, and citation

The GitHub repository is intended for code, scripts, documentation, and working experimental artifacts. Once the evaluated version and supporting datasets are complete, a tagged release can be archived in **Zenodo**, which will provide a persistent DOI for the publication's Data Availability Statement.

- **Repository URL:** https://github.com/radu2miron/dpp-prov-ver
- **Archived release / DOI:** pending; do not cite a DOI until it has been issued.
- **Associated paper:** details will be added when a citable publication is available.

A `CITATION.cff` file and a software license will be added before public release. Until a license is selected, public visibility should not be taken to imply unrestricted reuse rights.

## Research context

Developed as an academic research prototype at the **Technical University of Cluj-Napoca**, Romania. The project focuses on lifecycle consistency and reproducible evaluation rather than production-ready identity management, storage governance, or user-facing applications.
