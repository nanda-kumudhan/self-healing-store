# What I implemented and how to study it

The first version stopped at explicit hands-on placeholders. The later request authorized implementing those parts too. I removed the placeholders after adding real behavior and tests. This is a guide to the implementation, not a list of fake-success adapters.

| Part | Starting point | Decision |
| --- | --- | --- |
| Streaming pipeline | `client/.../core/ClientFileOperations.java` | Read one 1 MiB chunk, encrypt, code, upload; download to a private temporary file and publish only after full verification |
| Keys and nonces | `client/.../core/FileCrypto.java` | Fresh random root key per upload, Argon2id key wrapping, separate derived data/MAC keys, chunk-index nonces, object/index/length AAD |
| Manifest | `common/.../model/Manifest.java`, `coordinator/.../core/ManifestService.java` | Version 1, authenticated immutable chunk descriptors, final authenticated length/count, mutable node locations stored separately |
| Placement | `coordinator/.../core/PlacementPolicy.java` | SHA-256 rendezvous ranking; five distinct live holders; repair excludes existing holders |
| Failure detection | `coordinator/.../core/FailureDetector.java` | Persisted last-seen times, timeout plus repair delay, restart grace and fresh-heartbeat placement |
| Repair | `coordinator/.../core/RepairService.java` | Hash-checked ciphertext reconstruction, target reservation, read-back verification, conditional assignment update, one capped worker and persisted retry deadlines |
| Integrity | `storage-node/.../core/ShardIntegrityPolicy.java` | Reserved expected hash/length from coordinator; clients check authenticated hashes independently |
| Scrubbing | `storage-node/.../core/Scrubber.java` | Stream directory entries, detect corrupt and deleted shard bytes using sidecars, report assigned damage |
| Peer permissions | `common/.../core/CertificateIdentity.java`, `CertificatePeerIdentityPolicy.java` | Exact private-CA roles, derived node IDs, certificate fingerprint ownership, per-node assignment grants |
| Probability model | `durability/model.py`, `analysis.ipynb` | Continuous-time Markov chain with explicit assumptions and analytic tests |

`...` means `src/main/java/dev/healstore/<package>`. Packages are `client`, `coordinator`, `node` and `common`.

I would start with the encrypted property tests and local round trip, then read the manifest canonicalization and commit path, placement tests, failure detector clock tests, repair target transitions and real process harness. The most useful questions are why nonce scope is a key scope, why corruption must become an erasure before coding, and why read availability is different from restored redundancy.

The current [protocol ADR](adrs/0002-store-protocol.md) explains trust and crash behavior. The [validation notes](VALIDATION.md) separate executed tests from container tests that need Docker. Remaining limits are listed in the README; they include cleanup of abandoned uploads and obsolete shards, metadata replication, key rotation, certificate-owner migration and correlated-failure modeling.
