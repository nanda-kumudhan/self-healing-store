# Repair measurements and durability model

The plotting code and notebook are set up, but I still need to choose the failure-rate inputs, write the probability model and check it. There are no numerical results filled in yet.

The notebook and plots have optional dependencies in `requirements.txt`. Install them in a virtual environment. The Java build and Python measurement/fault scripts don't need them.

## Measuring runs

`scripts/measure.py` saves:

```csv
timestamp_utc,label,data_bytes,elapsed_seconds,exit_code,peak_rss_kib
```

It runs one command at a time. On Linux, peak RSS is in KiB and the timing includes process startup. I supply the data size, so I need to make sure that matches the workload. I'll also need to record machine/container limits, warmup, chunk/coding settings and repeat counts. Input file size and bytes moved during repair are different measurements.

Failed runs stay in the CSV. The plot script leaves them out of successful throughput, memory and repair plots. For repair, use a CSV of commands that actually wait for completed repair. A label alone can't tell the script whether repair finished.

```bash
python durability/plot.py repair measurements/repair-runs.csv --output measurements/repair.png
python durability/plot.py throughput measurements/runs.csv --output measurements/throughput.png
python durability/plot.py memory measurements/runs.csv --output measurements/memory.png
```

## CSV formats for the model

Once the model is working, each row should describe one scenario, with its assumptions saved alongside the results. The repair-time plot expects:

```csv
scheme,repair_seconds,annual_loss_probability
```

The schemes can be `3+2`, `4+2` and `3x replication`. Probabilities go in `[0,1]`. I'll need to be careful about rates, percentages and probabilities, and keep a record of where each failure-rate input came from.

The parity-count plot expects:

```csv
scheme,parity_shards,annual_loss_probability
```

Here, `scheme` should identify a fixed data-shard-count/model family. If the failure rate or repair assumptions change between rows, that isn't just a parity comparison anymore.

```bash
python durability/plot.py durability durability/results/model.csv --output durability/results/repair-risk.png
python durability/plot.py parity durability/results/parity.csv --output durability/results/parity-risk.png
```

The plots read probabilities; they don't calculate them. The model cell in `analysis.ipynb` still raises `NotImplementedError`.

## Questions I need to answer

- Am I treating AFR as a rate estimate or a one-year event probability? What are the units and conversions?
- Which drive models and date ranges am I using? How much exposure/drive-day data is there, especially for small groups? Keep the exact source/download.
- Am I assuming independent, constant-rate, memoryless failures? What happens with shared host, rack or software failures?
- Is repair time a fixed measurement or a distribution? What changes when repairs queue up or compete for bandwidth?
- How do temporary outages, permanent losses, finite spares and completed repairs fit into the model?
- Am I predicting loss of one chunk or a whole file? Chunks can share nodes, so their failures may be related.
- Is the three-replica comparison using fair capacity/bandwidth assumptions? 3+2 uses about 1.67x storage and three replicas use 3x, before framing and metadata.
- Do simple cases, such as a zero failure rate, behave sensibly? Can a separate calculation or simulation check the result?

I plan to start with [Backblaze Drive Stats](https://www.backblaze.com/cloud-storage/resources/hard-drive-test-data), cite Backblaze and follow its usage terms, including the restriction on selling the data itself. These are datacentre drives; I can't assume the rates or a future prediction model transfer to laptops or this container cluster. The optional ML part isn't implemented.
