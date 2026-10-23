# What I still need to build

The Spring Boot setup and supporting tools are scaffolded. These are the parts I want to design and write myself. Each Java starting point has a `HANDS_ON` comment and throws `HandsOnRequiredException` for now. I can change the method signatures as the design takes shape.

| Part | Where to start | What's left |
| --- | --- | --- |
| Local file pipeline | `client/.../core/ClientFileOperations.java` | Read chunks with bounded memory, encrypt, encode and reconstruct |
| Keys and nonces | Same client entry point; add classes as needed | Key derivation/wrapping, nonce allocation, AAD and handling secrets in memory |
| Manifest | `coordinator/.../core/ManifestService.java` | Format, versions, trusted integrity data and when an upload counts as committed |
| Placement | `coordinator/.../core/PlacementPolicy.java` | Rendezvous hashing, separate nodes per shard, spare selection and re-placement |
| Failure detection | `coordinator/.../core/FailureDetector.java` | Heartbeat state, time handling and how long to wait before declaring a node dead |
| Repair | `coordinator/.../core/RepairService.java` | Queue, priorities, rebuilding, retries, bandwidth cap and safe metadata updates |
| Shard integrity | `storage-node/.../core/ShardIntegrityPolicy.java` | Trusted expected hashes, verification and treating damaged shards as missing |
| Scrubbing | `storage-node/.../core/Scrubber.java` | Scan stored shards, check them on a schedule and report damage |
| Peer permissions | `common/.../core/UnfinishedPeerIdentityPolicy.java` | Match certificates to roles/node IDs and check which resources they can access |
| Durability model | `durability/analysis.ipynb` | Choose failure-rate assumptions, write the model and check its results |

`...` stands for `src/main/java/dev/healstore/<module-package>`. The packages are `client`, `coordinator`, `node` and `common`.

## A few things to keep straight

Spring checks whether a certificate is trusted. I still need to decide what that identity is allowed to do. The interceptor passes the certificate chain, HTTP method and path to the policy. That may need more context later, for example to check whether a caller can register a particular node ID or write a particular shard. File keys should stay out of coordinator requests and database tables.

The disk helper calls an integrity hook before publishing a shard and when reading it. The tests use a no-op hook to check the file I/O, but the application doesn't. I still need a way to supply trusted expected hashes. Accepting a hash from the same untrusted source as the bytes wouldn't solve that.

## Order I'm planning to follow

1. Get a local chunk/encrypt/encode/reconstruct round trip working, with property tests. Use the existing Reed-Solomon library, Bouncy Castle and JDK crypto APIs.
2. Work out the manifest, keys, nonces and integrity checks. Test empty files, exact chunk boundaries, tampering and cleanup after failures.
3. Add peer permissions, including a trusted certificate with the wrong role or node ID.
4. Add placement and metadata updates, then connect upload/download to the services. Keep memory use bounded as files grow.
5. Stop two actual shard holders and check that a download still works. Stop three and check that it fails without publishing a partial file.
6. Add manual repair, then scrubbing, heartbeat detection and automatic repair. Time repairs through to verified shards and committed metadata.
7. Run the failure experiments and use those repair times in the durability model.

## The six-node catch

A 3+2 chunk uses five nodes, so six nodes leave one spare. That's enough to replace one missing holder. If two holders are permanently lost, only four live nodes remain. The three surviving shards can still reconstruct the chunk, but there's nowhere to put five shards on five separate live nodes until a node returns, gets replaced or a seventh node is added.

The six-node setup can show that downloads survive two stopped holders. To show full repair after two permanent failures, I'll need that extra capacity. Putting two shards from the same chunk on one node wouldn't restore the same failure tolerance.

Clearing keys in Java is also best effort: the runtime can keep copies. Clearing the CLI's TLS password buffer doesn't finish the file-key handling work.
