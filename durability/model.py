#!/usr/bin/env python3
"""Independent exponential failures and one serial exponential repair worker per chunk.

States count unavailable holders; m+1 is absorbing loss. This is an illustrative
model with unlimited replacement capacity, not a prediction for a six-node lab.
"""
import argparse
import csv
import json
import math
from pathlib import Path

YEAR_SECONDS = 365.25 * 86400
SOURCE = "https://www.backblaze.com/cloud-storage/resources/hard-drive-test-data"


def multiply(a, b):
    size = len(a)
    result = [[sum(a[i][k] * b[k][j] for k in range(size))
               for j in range(size)] for i in range(size)]
    # Preserve stochastic row sums during squaring. Compute absorption directly;
    # subtracting survival from one would discard small loss probabilities.
    for row in result:
        total = sum(row)
        for j in range(size):
            row[j] /= total
    return result


def annual_loss(k, m, failure_rate, repair_seconds):
    """failure_rate is failures per node-year (AFR fraction), not a probability.

    repair_seconds is the mean time to repair ONE unavailable holder, including
    detection and queue time. Additional missing holders wait for that worker.
    None means no repair. Zero is the ideal instantaneous-repair limit.
    """
    if (not isinstance(k, int) or not isinstance(m, int) or k < 1 or m < 0 or k + m > 64
            or not math.isfinite(failure_rate) or failure_rate < 0
            or (repair_seconds is not None and (not math.isfinite(repair_seconds) or repair_seconds < 0))):
        raise ValueError("Invalid coding scheme, failure rate or repair time")
    n = k + m
    if not failure_rate:
        return 0.0
    if m == 0:
        return -math.expm1(-n * failure_rate)
    if repair_seconds == 0:
        return 0.0
    repair_rate = 0 if repair_seconds is None else YEAR_SECONDS / repair_seconds
    size = m + 2
    q = [[0.0] * size for _ in range(size)]
    for down in range(m + 1):
        q[down][down + 1] = (n - down) * failure_rate
        if down:
            q[down][down - 1] = repair_rate
        q[down][down] = -sum(q[down])
    rate = max(-q[i][i] for i in range(size))
    if not math.isfinite(rate) or rate > 1e18:
        raise ValueError("Rates exceed the numerical range of this teaching model")
    squarings = max(0, math.ceil(math.log2(rate / 0.5)))
    step_rate = math.ldexp(rate, -squarings)
    identity = [[float(i == j) for j in range(size)] for i in range(size)]
    transition = [[identity[i][j] + q[i][j] / rate for j in range(size)] for i in range(size)]
    power = identity
    weight = math.exp(-step_rate)
    result = [[weight * value for value in row] for row in power]
    # Positive uniformization terms converge rapidly since step_rate <= 0.5.
    for term in range(1, 40):
        power = multiply(power, transition)
        weight *= step_rate / term
        for i in range(size):
            for j in range(size):
                result[i][j] += weight * power[i][j]
    for row in result:
        total = sum(row)
        for j in range(size):
            row[j] /= total
    for _ in range(squarings):
        result = multiply(result, result)
    return max(0.0, min(1.0, result[0][-1]))


def drive_stats(paths):
    """Count daily rows as drive-days. Pass distinct daily CSVs, without duplicates."""
    failures = days = 0
    for path in paths:
        with Path(path).open(newline="") as source:
            for row in csv.DictReader(source):
                failure = int(row["failure"])
                if failure not in (0, 1):
                    raise ValueError("failure must be zero or one")
                failures += failure
                days += 1
    if not days:
        raise ValueError("No drive-days")
    return {"failures": failures, "drive_days": days,
            "annual_failure_rate": failures * 365.25 / days, "source": SOURCE,
            "note": "Backblaze datacentre drives; not a measured node failure rate for this cluster."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    stats = sub.add_parser("drive-stats")
    stats.add_argument("csv", nargs="+", type=Path)
    curve = sub.add_parser("curves")
    curve.add_argument("--afr", type=float, help="Failures per node-year, as a fraction")
    curve.add_argument("--detection-seconds", type=float, default=30, help="Added to measured manual repair durations")
    curve.add_argument("--repair-seconds", type=float, nargs="+", required=True)
    curve.add_argument("--measured-csv", type=Path, help="Append successful repair-one observations from measure.py")
    curve.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "drive-stats":
        print(json.dumps(drive_stats(args.csv), indent=2))
        return
    provenance = json.loads((Path(__file__).parent / "backblaze-2025.json").read_text())
    afr = args.afr if args.afr is not None else provenance["failures"] * 365.25 / provenance["drive_days"]
    if not math.isfinite(args.detection_seconds) or args.detection_seconds < 0:
        parser.error("Detection delay must be finite and nonnegative")
    times = list(args.repair_seconds)
    if args.measured_csv:
        with args.measured_csv.open(newline="") as source:
            times += [float(row["elapsed_seconds"]) + args.detection_seconds for row in csv.DictReader(source)
                      if row["label"] == "repair-one" and int(row["exit_code"]) == 0]
    if not times or any(not math.isfinite(t) or t <= 0 for t in times):
        parser.error("Use positive finite mean repair durations")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", newline="") as output:
        writer = csv.writer(output)
        writer.writerow(["scheme", "data_shards", "parity_shards", "storage_multiple", "repair_seconds", "annual_failure_rate", "annual_loss_probability"])
        for label, k, m in [("3+2", 3, 2), ("4+2", 4, 2), ("3x replication", 1, 2)] + [(f"3+{m}", 3, m) for m in (0, 1, 3, 4)]:
            for seconds in sorted(set(times)):
                writer.writerow([label, k, m, (k + m) / k, seconds, afr, annual_loss(k, m, afr, seconds)])

    args.output.with_suffix(".assumptions.json").write_text(json.dumps({"annual_failure_rate": afr, "source": provenance if args.afr is None else "user supplied", "measured_csv": str(args.measured_csv) if args.measured_csv else None, "detection_seconds_added": args.detection_seconds, "model": "independent exponential failures; serial exponential per-holder repair; unlimited spare capacity"}, indent=2) + "\n")


if __name__ == "__main__":
    main()
