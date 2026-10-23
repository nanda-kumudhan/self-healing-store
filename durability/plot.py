#!/usr/bin/env python3
"""Plot measurements or model results from a CSV. Needs actual rows to work with."""
import argparse
import csv
import math
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=["repair", "throughput", "memory", "durability", "parity"])
    parser.add_argument("csv", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    with args.csv.open(newline="") as source:
        rows = list(csv.DictReader(source))
    if not rows:
        parser.error("CSV has no observations; collect real measurements first")
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(figsize=(7, 4), layout="constrained")
    groups = {}
    try:
        for row in rows:
            if args.kind in ("durability", "parity"):
                label = row["scheme"]
                x = float(row["repair_seconds"]) if args.kind == "durability" else int(row["parity_shards"])
                y = float(row["annual_loss_probability"])
                if not math.isfinite(x) or x < 0 or not 0 <= y <= 1:
                    raise ValueError("invalid probability or repair duration")
            else:
                if int(row["exit_code"]) != 0:
                    continue
                label = row["label"]
                x = int(row["data_bytes"]) / (1024 * 1024)
                seconds = float(row["elapsed_seconds"])
                if not math.isfinite(seconds) or seconds <= 0 or x < 0:
                    raise ValueError("invalid measurement")
                y = seconds if args.kind == "repair" else x / seconds if args.kind == "throughput" else int(row["peak_rss_kib"]) / 1024
                if not math.isfinite(y) or y < 0:
                    raise ValueError("invalid measurement")
            groups.setdefault(label, []).append((x, y))
    except (KeyError, ValueError) as error:
        parser.error(f"Invalid CSV: {error}")
    if not groups:
        parser.error("No successful observations to plot")
    for label, points in groups.items():
        x, y = zip(*sorted(points))
        ax.plot(x, y, "o-", label=label)
    ax.set_xlabel("Repair time (seconds)" if args.kind == "durability" else "Parity shards" if args.kind == "parity" else "Input data (MiB)")
    ax.set_ylabel({"repair": "Elapsed time (seconds)", "throughput": "Throughput (MiB/s)",
                   "memory": "Peak RSS (MiB)", "durability": "Annual chunk loss probability",
                   "parity": "Annual chunk loss probability"}[args.kind])
    ax.grid(alpha=0.25)
    ax.legend()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(args.output, dpi=180)


if __name__ == "__main__":
    main()
