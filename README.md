# Self-healing encrypted store

I built this to understand placement, failure detection, repair and encryption trade-offs. [Tahoe-LAFS](https://tahoe-lafs.org/), [Storj](https://www.storj.io/) and [MinIO](https://github.com/minio/minio) already explore this space. This is a Java learning project with coding assistance, not a new storage product.

The CLI streams files in 1 MiB chunks, encrypts each chunk with AES-256-GCM, and uses Backblaze's Reed-Solomon library to make three data and two parity shards. Any three valid shards recover a chunk. SHA-256 hashes turn corrupted shards into known erasures. The coordinator and nodes never receive the plaintext file key or file contents. The storage overhead approaches 5/3 for large files; three replicas use 3x, before encryption framing and metadata.

```text
Java CLI --mTLS--> Spring Boot coordinator -- JDBC/JPA --> PostgreSQL
    |                  registry, immutable chunk manifests, repair assignments
    +----mTLS----> six Spring Boot nodes + optional seventh spare
                       opaque shards, hash checks, scrubbing, heartbeats
```

The core algorithms are implemented, including the parts originally left as hands-on exercises. There is no Kafka, dashboard or ML feature. [The implementation notes](docs/HANDS_ON.md) explain each part and link to its code.

## Build and test

Use Java 21, Python 3.12+, OpenSSL and keytool. Maven targets Java 21. The wrapper downloads pinned Maven 3.9.11 if needed. Compose must be installed for the full check, but its daemon is not needed for config validation.

```bash
bash scripts/check.sh
# Real Java processes, mTLS, private temporary disks and persistent H2 metadata:
python3 scripts/e2e.py
# Real Docker containers, PostgreSQL and Toxiproxy; needs daemon access:
./mvnw -B -ntp -Pintegration verify
```

`check.sh` runs the Java tests, Python tooling/model tests, Compose validation and real TLS smoke tests. The process-cluster test separately checks encrypted round trips, empty and multi-chunk files, one/two stopped holders, failure without a partial output after three lost holders, capacity reporting, corruption, scrubbing, manual repair, coordinator restart and heartbeat-triggered repair. Docker tests are a separate result; see [the validation notes](docs/VALIDATION.md).

## Run the development cluster

```bash
./infra/certs/dev-certs.sh
docker compose --env-file infra/certs/generated/dev.env up --build -d
./scripts/store status
./scripts/store nodes
# Enter a long passphrase at the terminal prompt. Noninteractive runs use STORE_PASSPHRASE.
./scripts/store --node-host localhost upload INPUT
./scripts/store --node-host localhost download OBJECT_UUID OUTPUT
./scripts/store repair OBJECT_UUID
```

The upload command prints its object UUID. Save that UUID and the passphrase. Nodes and the coordinator cannot recover a forgotten passphrase. Download refuses to overwrite an existing destination. Repair waits for verified target shards and metadata updates: exit `0` means no shards remain pending, `2` means incomplete recovery/capacity, and `1` means a command error.

Compose advertises internal `node-N` addresses. `--node-host localhost` maps those to host ports 8444–8450. The coordinator listens on 8443. PostgreSQL stays internal. Ports bind to loopback; services have separate identities and disk volumes. Generated development certificates last 30 days. Rotate into a new directory rather than overwriting a live identity. Certificates generated before the seventh node was added need rotating before using that spare.

Five distinct live holders are required to commit uploads. Six nodes leave one spare. After two permanent losses, four live nodes can serve reads but cannot hold five distinct shards. Add the seventh node or return/replace capacity before claiming full redundancy:

```bash
docker compose --env-file infra/certs/generated/dev.env --profile spare up -d node-7
# Wait for node registration, then:
./scripts/store repair OBJECT_UUID
```

Stop the cluster without deleting data:

```bash
docker compose --env-file infra/certs/generated/dev.env down
```

For the local disk pipeline alone, no services, TLS credentials or passphrase are needed; it uses a temporary random key and temporary shard directory, drops two shards, reconstructs, and cleans up:

```bash
./scripts/store local INPUT OUTPUT
```

The explicit Spring `local` profile binds loopback HTTP and uses H2. Protected endpoints still require certificate identities; use the TLS process harness for full development round trips.

## Faults and repair

Heartbeats arrive every two seconds. A node stops receiving placements after ten seconds without a heartbeat. Automatic repair waits another twenty seconds to avoid transient-outage storms. Coordinator startup has the same grace period and requires fresh heartbeats before new placement. Scrubbing runs every thirty seconds. One repair worker prioritizes chunks with fewer surviving holders and caps its shard traffic at 10 MiB/s. Failed work persists in assignments and is retried after a delay. These intervals are configurable with Spring properties/environment variables.

For real partitions, start the cluster with the proxy override. It routes shard traffic and each node's control traffic through Toxiproxy; direct connections would bypass a partition:

```bash
docker compose --env-file infra/certs/generated/dev.env \
  -f compose.yml -f compose.chaos.yml --profile chaos --profile spare up --build -d
./scripts/store --node-host localhost --node-base-port 18443 upload INPUT
python3 tests/chaos/chaos.py stop node-1 node-2
python3 tests/chaos/chaos.py start node-1 node-2
python3 tests/chaos/chaos.py partition node-1
python3 tests/chaos/chaos.py heal-network node-1
python3 tests/chaos/chaos.py corrupt node-1 SHARD_UUID --offset 0
./scripts/store repair OBJECT_UUID
```

Look up actual holders with `GET /api/v1/objects/UUID/chunks/INDEX` before stopping nodes. The chaos script records events in `measurements/faults.csv`. Its corruption command flips an existing byte with `dd` and never truncates the shard. An anonymous or untrusted client fails TLS; a trusted certificate with the wrong role or node ID receives `403`.

## Keys, metadata and trust

Each upload generates a new random 256-bit root key and object UUID. Argon2id derives a wrapping key from the passphrase and a random salt; AES-GCM wraps the root key. Domain-separated HMAC derivations produce data and manifest keys. A chunk index determines a 96-bit nonce under that object's fresh data key. Object ID, index and length are authenticated as AAD. Retries never re-encrypt under the same key/index: this version starts a fresh upload after an interruption.

The client authenticates the final header and each immutable chunk descriptor with HMAC-SHA-256. Nodes ask the coordinator for reserved hash/size/ownership information, then verify bytes before publishing or serving. The client verifies hashes and MACs independently and authenticates reconstructed ciphertext before publishing a download. Repair changes locations, never the signed IDs or hashes, and needs no decryption keys.

The private development CA reserves exact certificate CNs: `client`, `coordinator`, and `node-N`. Node UUIDs derive from those names; object ownership uses the client certificate fingerprint. Each node may register/heartbeat only its own identity and access only assignments granted to it. This is a small private trust domain, without public signup, certificate rotation migration, revocation or multiuser administration. Keys are cleared best effort; Java and the environment can retain copies. Passphrases supplied by environment are for automation; interactive input avoids putting them on a command line.

## Measurements, model and demo

```bash
python3 scripts/e2e.py --measure --benchmark-mib 1 8 128 --repeats 2
python3 -m venv /tmp/healstore-notebooks
/tmp/healstore-notebooks/bin/pip install -r durability/requirements.txt
/tmp/healstore-notebooks/bin/python durability/model.py curves \
  --repair-seconds 1 30 60 300 3600 86400 --measured-csv measurements/runs.csv \
  --output durability/results/model.csv
/tmp/healstore-notebooks/bin/python durability/plot.py durability durability/results/model.csv \
  --output measurements/durability.png
/tmp/healstore-notebooks/bin/python durability/plot.py parity durability/results/model.csv --repair-seconds 60 \
  --output measurements/parity.png
```

The [notebook](durability/analysis.ipynb) explains the model and plots. It uses Backblaze's published 2025 failure/exposure counts as a documented assumption, and real repair observations when supplied. Independent exponential failures, serial exponential repairs and unlimited replacement capacity are simplifications. The output is annual loss probability for one chunk, not a measured durability guarantee or a whole-file prediction. [Durability notes](durability/README.md) cover units, assumptions and source terms.

`scripts/record-demo.py` records an actual process-cluster run as `measurements/demo.cast` and renders an accelerated terminal replay as `measurements/demo.mp4` using Pillow and ffmpeg. It states the environment and playback speed on screen. [Validation notes](docs/VALIDATION.md) record what was actually run; generated measurements and recordings stay local and are ignored by Git.

## Remaining limits

There is one coordinator and one metadata database. Shard reads currently consult that coordinator, so its outage blocks new downloads as well as writes and repair. Database/CA/key backups are essential for a useful deployment. At ten times the scale I would add failure-domain-aware placement, fair persistent repair queues across objects, metadata replication, independent backups and measured queue-delay budgets before increasing throughput.

This version has no object deletion, abandoned-upload collection, old-shard garbage collection, resumable upload, key rotation, certificate-owner migration, malicious-node proof protocol or rollback protection against an old valid object version. Disk-full and power-loss paths have local cleanup/sync checks but have not undergone a filesystem/power-cut campaign. Hashes detect corruption; they do not prevent deletion. Files under three valid holders fail cleanly.

[Protocol decisions](docs/adrs/0002-store-protocol.md), [acceptance checklist](tests/ACCEPTANCE.md), [vendored codec and licence](vendor/reed-solomon/UPSTREAM.md), and [Spring Boot's SSL configuration](https://docs.spring.io/spring-boot/3.5/how-to/webserver.html) provide further detail.
