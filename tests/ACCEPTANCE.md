# Acceptance checks

The exact executed results are in [VALIDATION.md](../docs/VALIDATION.md). This table distinguishes test coverage from deployment validation.

| Check | Coverage |
| --- | --- |
| Empty, one-byte, chunk boundary −1/exact/+1, multi-chunk | Real encrypted local disk tests; empty/multi-chunk network tests |
| Every pair of missing shards | jqwik tests encrypt, code, reconstruct and authenticate random chunks; fixed boundary disk tests |
| One and two stopped holders | Real six-node Java process cluster, downloaded bytes match SHA-256 |
| Three stopped holders | Nonzero exit, no destination or leftover download temporary file |
| Corrupt shard | Flip a byte, reject by trusted hash, recover from survivors, scrub reports damage, verified repair |
| Missing file on a live node | Scrubber unit test using a real disk store and persisted integrity cache |
| Ciphertext, index and manifest changes | Authentication property/examples and wrong-password envelope test |
| Nonce scope | Fresh key/object per upload, deterministic index nonces, independent-key/index tests; interrupted upload restarts fresh |
| mTLS | Real trusted, anonymous and untrusted TLS tests |
| Trusted wrong identity | Role checks, wrong node heartbeat, object certificate ownership, wrong server role before HTTP data |
| Placement | Stable rendezvous rank, relative order under additions, distinct holders |
| Timeout and repair delay | Controlled clock checks at exact boundaries, coordinator restart grace |
| Manual repair | Synchronous result, read-back hash verification, pending capacity counts, retry-safe immutable shard writes |
| Automatic repair | Real process cluster after coordinator restart, fresh heartbeat leases and delayed replacement |
| Metadata commit | Transactional immutable plans, verified-chunk gate, final count/length checks, ownership and assignment grants |
| Container kill and Toxiproxy partition | Implemented in `EncryptedClusterIT`; requires Docker and was not runnable on this host |
| Throughput, repair time and RSS | Repeated real process measurements at 1, 8 and 128 MiB; startup/cache effects recorded |
| Probability model | No-failure/no-parity/no-repair analytic checks, parity and repair-time monotonicity, sourced rate assumptions |
| Power cuts, full disks, concurrent active coordinators | Full campaigns not completed; single active coordinator only |

Two permanent losses in six nodes leave four live nodes. The process test first verifies incomplete repair, then starts node seven and verifies full redundancy. No test claims that putting two shards on one node restores the original failure tolerance.

The container test deliberately routes the client's encrypted data through Toxiproxy. The Compose chaos override additionally routes the coordinator's data calls and per-node heartbeats through proxies so a whole-node partition affects failure detection as well as downloads. Direct connections bypass those proxies.
