# CI design

The workflow validates a fixed, reviewed snapshot. It does not fetch current upstream data and does not commit changes, so a changing API cannot silently alter the published statistical record.

1. Check out the aggregate repository and the four source repositories at the immutable revisions in `sources.lock.edn`.
2. Verify every raw artifact against the SHA-256 recorded in its source catalog.
3. Rebuild the Datascript transaction EDN and Kotoba EAVT export solely from those local source snapshots.
4. Fail if generated files differ from committed output.
5. Materialize the Datascript database and run coverage/query invariants. Snapshot verification and projection use NBB; the Datascript compatibility test uses the canonical Clojure Datascript library.

An ingest refresh is a separate, reviewed operation: download into the source DataLad dataset, update its catalog hash and release metadata, publish that source commit, update `sources.lock.edn`, rebuild this repository, then let this CI prove the resulting contract. A future scheduled workflow may open a report about upstream drift, but must never overwrite a snapshot or publish a new data release automatically.
