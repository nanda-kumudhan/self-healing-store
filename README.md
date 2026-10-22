# Self-healing encrypted store

A Java storage project I'm building to learn more about distributed systems and how data can survive node failures.

The idea is to upload an encrypted file, stop a couple of storage nodes, and still download the original file. Missing or corrupted shards should then be rebuilt onto spare nodes.

This is a learning project, not a product. Tahoe-LAFS, Storj and MinIO already cover this space. I want to understand the placement, failure detection and repair decisions by building a small version myself.

**Current status:** folder structure only. Nothing is implemented yet.

## Planned design

```text
Java CLI ──mTLS──> Coordinator + SQLite
    │              node registry, shard map, repair queue
    │
    └──────mTLS──> 6 storage nodes
```

The client will process files in chunks, encrypt them with AES-GCM, and split each encrypted chunk into three data shards and two parity shards using Reed-Solomon coding. Any three valid shards should be enough to recover a chunk.

Encryption and decryption will happen on the client. Storage nodes and the coordinator should never receive plaintext or encryption keys. Each shard will have an integrity hash so corruption can be treated as a missing shard before reconstruction.

Five shards will be placed on separate nodes, with a sixth node available for repairs. The coordinator will track placement and node health. It will be a single point of failure for this version.

## Stack

- Java for the CLI, coordinator and storage nodes.
- gRPC with mutual TLS for communication.
- SQLite for coordinator metadata.
- Existing libraries for Reed-Solomon coding and Argon2id, and Java's crypto APIs for AES-GCM.
- Docker Compose for running six nodes locally, with OpenSSL scripts for development certificates.
- Python for chaos tests and a durability model.

## Folder layout

```text
client/              CLI: chunking, encryption, upload and download
coordinator/         Metadata, placement, node health and repair scheduling
storage-node/        Shard storage and retrieval
common/              Shared types and utilities
proto/               gRPC service definitions
infra/
  docker/            Container and Compose configuration
  certs/             Development certificate scripts
  toxiproxy/         Network fault configuration
scripts/             Local setup and development helpers
tests/
  integration/       Tests involving multiple services
  chaos/             Node failure, corruption and partition scenarios
durability/          Python model, notebooks and measured repair times
docs/
  adrs/              Notes on design decisions and trade-offs
```

Each Java module has `src/main/java` and `src/test/java` directories. Build files and dependencies will come with the implementation.

## First working version

- Upload and download through the CLI, with bounded memory use for large files.
- Run six storage nodes with Docker Compose.
- Recover files with any two shard-holding nodes unavailable.
- Detect corrupted shards using hashes.
- Provide a manual repair command to rebuild missing shards onto spares.
- Fail clearly when fewer than three valid shards remain, without returning corrupt data.

Automatic repair will come after that. The plan is to use heartbeats plus a delay before declaring a node dead, prioritise chunks with the fewest surviving shards, and cap repair bandwidth to avoid overwhelming the cluster.

## Build order

1. Local chunking, encryption, sharding and reconstruction.
2. Property tests for random file sizes, chunk boundaries and shard loss.
3. Storage node and coordinator services over mutual TLS.
4. Six-node Compose setup, upload/download and manual repair.
5. Scrubbing and heartbeat-based automatic repair.
6. Chaos tests, measurements and the durability model.

## What I want to measure

- Behaviour when one, two or three shard-holding nodes fail.
- Detection and recovery after shard corruption or a network partition.
- Rejection of clients and nodes without trusted certificates.
- Repair time as stored data grows.
- Upload/download throughput compared with a plain copy.
- Memory use while streaming a large file.

The Python model will explore how repair time and parity count affect the chance of losing a chunk over a year. It will compare 3+2, 4+2 and three-copy replication, using measured repair times and cited Backblaze Drive Stats data for failure-rate assumptions. Its assumptions, especially independent failures, will need to be explicit.

Coordinator replication and drive-failure prediction are outside the first version. Key cleanup in Java will be best-effort because the runtime can retain copies in memory.
