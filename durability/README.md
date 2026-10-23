# Repair observations and a durability model

I use real repair durations as inputs to a small model, and keep assumptions separate from measurements. The model is implemented in `model.py`; `analysis.ipynb` explains the calculation and plots. No ML or failure prediction is included.

## Inputs and assumptions

[Backblaze Drive Stats](https://www.backblaze.com/cloud-storage/resources/hard-drive-test-data) publishes daily drive observations and aggregate failure/exposure counts. `backblaze-2025.json` records the published 2025 counts: 4,317 failures over 115,638,676 drive-days. The intensity estimate is failures × 365.25 / drive-days, in failures per drive-year. An AFR fraction is a rate estimate, not an annual event probability. Backblaze asks for attribution and prohibits selling the data itself; I do not redistribute the data.

To calculate an input from downloaded, distinct daily CSV files:

```bash
python3 durability/model.py drive-stats durability/data/DAILY.csv
```

Each row counts one drive-day and its binary failure indicator. Do not include the same daily observations twice. These are datacentre disks; using their intensity for a node is a sensitivity assumption, not a claim about this machine or cluster.

The continuous-time Markov chain has states 0 through m missing holders, plus absorbing loss at m+1. In state i, failure intensity is (k+m-i)λ. A single serial worker repairs one holder at rate μ=1/T. Failures and repairs are independent exponential processes, the chunk starts healthy, failed holders are replaced with identical independent ones, and spare capacity is unlimited. Three replicas use k=1,m=2. These are per-chunk probabilities, without whole-file independence claims.

I calculate the transition matrix with positive uniformization terms and scaling/squaring, and read the absorbing probability directly to avoid subtracting two almost equal numbers. Tests check the no-failure and instantaneous-repair limits, the no-parity analytic result, the no-repair binomial tail and monotonicity. This is a teaching implementation, not a reliability solver validated for all parameter ranges.

## Measured durations

```bash
python3 scripts/e2e.py --measure --benchmark-mib 1 8 128 --repeats 2
```

The process harness uses mTLS, H2 metadata and private temporary disks. It verifies downloaded hashes and checks pending counts after repair. The CSV records actual wall time, exit status and Linux child peak RSS. The client has `-Xmx128m`; its RSS also includes native memory. Timing includes CLI startup, TLS and KDF overhead, so it is not steady-state codec throughput. Plain copies share the filesystem/cache and are not equivalent workloads.

`repair-one` repairs one deliberately corrupt shard. `repair-node` stops one actual holder and repairs that object's missing shards, which vary with rendezvous placement. The input bytes column is original file size, not network bytes or the size of one repaired shard. `repair-capacity-restored` is a separate recovery scenario. These labels must not be pooled as samples of the same distribution. Manual durations do not include automatic detection/queue delay; the model command adds a declared detection allowance to `repair-one` observations.

```bash
python3 -m venv /tmp/healstore-notebooks
/tmp/healstore-notebooks/bin/pip install -r durability/requirements.txt
/tmp/healstore-notebooks/bin/python durability/model.py curves \
  --repair-seconds 1 30 60 300 3600 86400 \
  --measured-csv measurements/runs.csv --detection-seconds 30 \
  --output durability/results/model.csv
```

The explicit repair grid is a sensitivity experiment. The measured single-shard rows receive the stated detection allowance. `--afr` overrides the sourced rate; an adjacent assumptions JSON records provenance and model limitations. The curves compare 3+2, 4+2 and three replicas, plus a parity sweep. They hold a per-holder repair time fixed rather than simulating equal total bandwidth or rebuilding a whole deployment.

```bash
/tmp/healstore-notebooks/bin/python durability/plot.py throughput measurements/runs.csv --output measurements/throughput.png
/tmp/healstore-notebooks/bin/python durability/plot.py memory measurements/runs.csv --output measurements/memory.png
/tmp/healstore-notebooks/bin/python durability/plot.py repair measurements/runs.csv --output measurements/repair.png
/tmp/healstore-notebooks/bin/python durability/plot.py durability durability/results/model.csv --output measurements/durability.png
/tmp/healstore-notebooks/bin/python durability/plot.py parity durability/results/model.csv --repair-seconds 60 --output measurements/parity.png
```

Failed runs stay in the CSV and are excluded from successful-throughput plots. Repair plots include only repair labels. Small repeated samples show this run's behavior; they do not establish confidence bounds, hardware generality or long-term failure rates. Correlated host/rack/software outages, repair queues shared across chunks, non-exponential times, temporary partitions, silent corruption and finite spares can dominate the independent model. In particular, six nodes after two permanent failures violate the unlimited-spare assumption.
