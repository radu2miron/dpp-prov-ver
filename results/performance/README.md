# Performance Results

This directory contains the recorded experimental results supporting the DPP-Prov-Ver research paper. It includes measurements for CREATE, UPDATE, GET, INSTALL, historical reconstruction, concurrent conflicts, and idempotent retransmissions.

- `*.jtl` — Apache JMeter request-level results, including response times and HTTP outcomes.
- `*-data.csv` — input fixtures and identifiers associated with individual runs.
- `*.json` — diagnostic and post-run validation reports, where available.
- Other `*.csv` files — experiment summaries and verification outputs.

A successful HTTP response, a Fabric validation error, and a semantic lifecycle rejection represent different outcomes. In particular, MVCC conflicts and endorsement failures must not be counted as committed semantic rejections. Synchronized burst results are not directly comparable to sustained-load throughput measurements.

For the experimental methodology and interpretation of these data, refer to the associated research paper (citation to be added upon publication) and the [repository README](../../README.md).
