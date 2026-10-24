# Validation and measured results

I ran the checks below on 24 October 2026 against the committed Java 21 build. These results describe this machine and these small runs; they are not a durability or performance guarantee.

## Checks that passed

- `bash scripts/check.sh`: Maven reactor build and tests passed (35 Java tests across the modules, including jqwik properties and the Testcontainers restart test), 9 Python tests passed, Compose configuration validation passed, and both coordinator and storage-node mTLS smoke tests passed. The smoke tests rejected anonymous/untrusted clients and trusted certificates with the wrong service role.
- `python3 scripts/e2e.py`: the real-process cluster passed encrypted empty and multi-chunk round trips; reads after one and two holder losses; clean failure without a partial download after three holder losses; insufficient-spare reporting and recovery with node seven; corruption detection, scrub and verified repair; metadata recovery after coordinator restart; and heartbeat-triggered repair.
- `python3 scripts/e2e.py --measure --benchmark-mib 1 8 128 --repeats 2`: both trials at each size completed upload, download and repair of a stopped node, with downloaded bytes verified. Repair restored all shards for that object's placement.
- `python3 scripts/e2e.py --measure`: the final committed build also passed the full process suite and recorded single-shard repair observations.
- `python3 scripts/record-demo.py --benchmark-mib 1 8 128 --repeats 2`: recorded a real process-cluster run and rendered the local terminal replay at `measurements/demo.mp4`. It is a terminal replay, not Docker footage.

The measured benchmark used Java 21.0.13, Linux 7.2.11 on an Intel Core i7-8665U (8 logical CPUs), private temporary disks and H2 metadata. The client JVM had `-Xmx128m`. The CSV records each CLI command's wall time and peak child-process RSS; upload/download times include process startup, TLS and passphrase KDF work. Plain copies use the same local filesystem and cache, so they are only a rough reference.

| Input size | Upload mean (range) | Download mean (range) | Stop-one-node repair mean (range) |
| ---: | ---: | ---: | ---: |
| 1 MiB | 3.36 s (3.24–3.49) | 3.25 s (3.08–3.42) | 2.39 s (2.19–2.60) |
| 8 MiB | 7.36 s (6.05–8.66) | 5.18 s (4.93–5.43) | 3.74 s (3.57–3.92) |
| 128 MiB | 30.10 s (28.96–31.24) | 17.42 s (17.41–17.42) | 28.13 s (27.34–28.92) |

There are two observations per size. Peak child RSS ranged from about 148 MiB to 249 MiB across the measured CLI commands. These measurements show the 128 MiB transfer completed with the configured 128 MiB Java heap cap; they are too few runs to establish a general memory or throughput curve. The raw, timestamped data and video are generated locally under `measurements/` and ignored by Git, so rerun the commands above to reproduce them.

## Durability model

I regenerated the model curves using the measured single-shard repairs, adding a declared 30-second detection allowance. The source rate is Backblaze's 2025 aggregate of 4,317 failures over 115,638,676 drive-days, or about 1.36% annualized per-drive failure intensity. Treating this rate as a node rate is a sensitivity assumption. The model assumes independent exponential failures, one serial exponential repair worker per chunk, and unlimited replacement capacity. It is an illustrative per-chunk model, not a prediction for this six- or seven-node lab. The generated curves and assumptions JSON are under ignored `durability/results/`; run the commands in [the durability notes](../durability/README.md) to reproduce them.

## Checks still unverified

`./mvnw -Pintegration verify` was attempted, but Testcontainers could not find a Docker environment because this host's Docker socket was inaccessible. The container-kill, PostgreSQL container, and Toxiproxy partition scenarios are implemented in the integration tests, but they did not execute here. I did not change host Docker permissions. Run that command on a machine with an accessible Docker daemon before claiming those scenarios passed.

The validation does not cover power-loss campaigns, full disks, concurrent coordinators, production certificate rotation/revocation, correlated rack failures, or performance at larger cluster scale. The README lists the project's other known limits.
