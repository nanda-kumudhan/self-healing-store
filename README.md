# Self-healing encrypted store

I'm building this to understand how a storage system deals with failed nodes and corrupted data, while keeping files encrypted on the client. Tahoe-LAFS, Storj and MinIO already do this. I want to work through the design myself and see what happens when things break.

**So far, the backend setup runs, but encrypted upload/download and repair still need implementing.** I've used generated scaffolding for the Spring Boot setup and supporting tools. I'm leaving the core algorithms for myself. There's no Kafka, dashboard or ML part yet.

## What works now

- Java 21 build with Maven, two Spring Boot apps and a picocli command line client.
- REST endpoints, input validation, error responses and health checks. The status endpoint also shows that file operations are unfinished.
- Node registration stored in PostgreSQL using Spring Data JPA and Flyway. Local tests use H2. Being registered doesn't mean a node is alive.
- A disk storage helper that streams shard bytes with a size limit, refuses overwrites and cleans up after errors it catches. It still needs the integrity checks and crash handling below.
- A development CA script and separate certificates for each service. Spring requires mTLS, but I still need to check which operations each identity can perform.
- A six-node Docker Compose setup, Toxiproxy and scripts for stopping nodes, corrupting shards and timing commands.
- JUnit 5 tests, a jqwik test of the Backblaze codec, optional Testcontainers tests, CI and a script that checks real TLS connections.
- Plotting code and a notebook ready for measurements. There are no results filled in yet.

Protected endpoints return `501` with `code: HANDS_ON_REQUIRED` until the identity checks are written. The CLI's upload, download and local commands exit `3` before touching files. They're placeholders, so they shouldn't report success yet.

I've listed the remaining work and where to start in [HANDS_ON.md](docs/HANDS_ON.md).

## Planned setup

```text
Java CLI --mTLS--> Coordinator + PostgreSQL
    |              registry, shard map, failure detection, repair scheduling
    +----mTLS----> six storage nodes with separate disk volumes
```

The plan is to read files a chunk at a time, encrypt each chunk with AES-GCM and split it into three data shards plus two parity shards using Backblaze's Reed-Solomon library. Nodes and the coordinator should only see encrypted data, never the file keys. I still need to work out the manifest, key and nonce handling, integrity checks and reconstruction.

I'm keeping one coordinator for now, so it's a single point of failure. The services use REST; the empty `proto/` folder is left over from the earlier gRPC plan.

## Build and check it

Use a JDK 21 `JAVA_HOME`, Python 3.12+ and OpenSSL/keytool. The build targets Java 21 and accepts JDKs 21–25, within the selected Spring Boot line's support range. The Maven wrapper downloads pinned Maven 3.9.11 on first use (curl or wget required). Docker Engine and Compose are needed for containers.

```bash
./mvnw -B -ntp verify
java -jar client/target/client-0.1.0-SNAPSHOT.jar --help
python3 -m unittest discover -s tests/tooling -p '*_test.py'
# Include certificate generation, real TLS checks and Compose validation:
bash scripts/check.sh
```

`check.sh` needs Compose installed, but the Docker daemon doesn't need to be running. It starts and stops local Java processes to check TLS: a trusted client can connect, clients with no certificate or an untrusted one are rejected, and protected endpoints stay blocked. That covers the transport setup; the actual permission rules and file recovery still need tests.

## Start the development cluster

```bash
./infra/certs/dev-certs.sh
docker compose --env-file infra/certs/generated/dev.env up --build -d
./scripts/store status
docker compose --env-file infra/certs/generated/dev.env ps
```

The certificate script refuses to overwrite existing output. Certificates last 30 days. The encrypted CA key stays on the host; each service mounts only its own identity directory. Generated secrets, certificates, runtime data and measurements are ignored by Git and excluded from the Docker build context.

| Service | Host endpoint |
| --- | --- |
| Coordinator | `https://localhost:8443` |
| Nodes 1–6 | `https://localhost:8444` through `https://localhost:8449` |
| Toxiproxy control (chaos profile) | `http://127.0.0.1:8474` |
| Proxied node TLS ports | `18444` through `18449` |

PostgreSQL is internal to Compose. Published ports bind to loopback. Each node gets a separate volume. Certificates share a development-only PKCS12 password but have distinct keys. Java containers run as UID 10001 with read-only root filesystems.

A healthy service only means the process and its dependencies are available. The status response still says `fileOperationsReady: false`. Nodes don't register themselves or send heartbeats yet; that comes with the identity and failure-detection work.

Stop without deleting stored data:

```bash
docker compose --env-file infra/certs/generated/dev.env down
```

## Develop without containers

After building, start the services in separate terminals:

```bash
java -jar coordinator/target/coordinator-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
java -jar storage-node/target/storage-node-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
./scripts/store --coordinator http://127.0.0.1:8080 --allow-local-http status
```

The explicit `local` profile disables TLS, binds to loopback and uses in-memory H2 for coordinator metadata. It still refuses protected operations. Default profiles require certificate stores and mTLS.

## Endpoints and commands so far

| Endpoint or command | What it does now |
| --- | --- |
| Both: `GET /api/v1/capabilities` | Reports what's unfinished |
| Both: `GET /actuator/health` | Framework health; mTLS in default profile |
| Coordinator: `GET/POST /api/v1/nodes` | Stores/lists registrations once identity checks are implemented |
| Coordinator: `POST /api/v1/nodes/{id}/heartbeats` | Blocked by identity checks; failure detection still to do |
| Coordinator: `POST /api/v1/uploads` | Blocked by identity checks; placement still to do |
| Coordinator: `GET /api/v1/objects/{id}` | Blocked by identity checks; manifest still to do |
| Coordinator: `POST /api/v1/objects/{id}/repairs` | Blocked by identity checks; repair still to do |
| Node: `PUT/GET /api/v1/shards/{id}` | Streams shard bytes once identity and integrity checks are implemented |
| Node: `POST /api/v1/scrubs` | Blocked by identity checks; scrubber still to do |
| CLI: `status`, `nodes`, `repair UUID` | Sends HTTP/mTLS requests; protected operations still return errors |
| CLI: `local SOURCE DEST`, `upload SOURCE`, `download UUID DEST` | Unfinished, exit `3` |

The endpoints are there, but I still need to decide how manifests, shard ownership, upload commits and trusted hashes fit together. `STORE_MAX_SHARD_BYTES` defaults to 16 MiB and can be changed. It's a request limit, not the final file chunk format.

The disk helper writes to a temporary file, then uses a hard link on the same filesystem to publish it without overwriting an existing shard. It still needs trusted hash checks, directory syncing for power failures, disk-space handling and cleanup after a crash.

## Tests and chaos tooling

```bash
# Requires a working Docker daemon; fails explicitly if it is inaccessible.
./mvnw -B -ntp -Pintegration verify
# Start proxy routes:
docker compose --env-file infra/certs/generated/dev.env --profile chaos up -d
python3 tests/chaos/chaos.py --help
```

The integration test starts PostgreSQL, the coordinator, one node and Toxiproxy. It checks the database migration, cuts and restores a connection, then stops and restarts the node. It uses HTTP just for this setup test. The encrypted two-node-loss test still needs writing.

Fault commands act on the development cluster and log events to `measurements/faults.csv`:

```bash
python3 tests/chaos/chaos.py stop node-1 node-2
python3 tests/chaos/chaos.py start node-1 node-2
python3 tests/chaos/chaos.py partition node-1
python3 tests/chaos/chaos.py heal-network node-1
python3 tests/chaos/chaos.py latency node-2 --milliseconds 150
python3 tests/chaos/chaos.py heal-network node-2
# Once shards exist: flip one existing byte without truncating.
python3 tests/chaos/chaos.py corrupt node-1 SHARD_UUID --offset 0
```

The partition commands only affect connections going through Toxiproxy. The client/node transport needs to use `https://toxiproxy:18444` etc. inside Compose, or `https://localhost:18444` etc. from the host. Connections straight to a node will bypass the proxy.

[tests/ACCEPTANCE.md](tests/ACCEPTANCE.md) is my checklist for file round trips, missing nodes and corrupted data. Passing the codec test is only one small part of that.

## Measurements and notebook

On Linux, wrap an actual command to capture duration, exit status and child peak RSS:

```bash
python3 scripts/measure.py --label plain-copy --bytes 1048576 -- cp INPUT COPY
```

For repair timings, the command needs to wait until the repair is actually finished and checked. Timing a request that just starts a repair would give the wrong result. Failed attempts stay in the CSV, but don't count towards the successful throughput plots.

```bash
python3 -m venv /tmp/healstore-notebooks
/tmp/healstore-notebooks/bin/pip install -r durability/requirements.txt
/tmp/healstore-notebooks/bin/python durability/plot.py throughput measurements/runs.csv --output measurements/throughput.png
/tmp/healstore-notebooks/bin/jupyter lab durability/analysis.ipynb
```

The notebook can load measurements and make plots. I still need to choose and justify the failure-rate inputs, write the probability model and check it. There are no example repair times or probabilities pretending to be results. The CSV formats are in [durability/README.md](durability/README.md).

I plan to use [Backblaze Drive Stats](https://www.backblaze.com/cloud-storage/resources/hard-drive-test-data) for failure-rate assumptions, with a citation and its usage terms followed. Those are datacentre disk statistics, so I'll need to explain how useful they are for this model rather than treating them as this cluster's measured failure rate.

## Design notes and dependencies

- [Setup decisions](docs/adrs/0001-scaffold-boundaries.md), [remaining work](docs/HANDS_ON.md) and [test checklist](tests/ACCEPTANCE.md).
- [Pinned Backblaze source and MIT licence](vendor/reed-solomon/UPSTREAM.md): existing library, not our field arithmetic.
- [Spring Boot SSL properties](https://docs.spring.io/spring-boot/3.5/appendix/application-properties/index.html): required client authentication, PKCS12 stores and TLS 1.2/1.3.
- Bouncy Castle is available for Argon2id; AES-GCM comes from Java crypto APIs. No primitive is handwritten.

Six nodes provide only one spare for five holders. After two permanent losses, restoring five distinct live holders needs replacement/returning capacity or a seventh node. Read availability and restored redundancy are separate milestones.

I haven't measured throughput, memory use or repair time yet. The demo video comes after there's a working upload/download path and real failures to show.
