#!/usr/bin/env python3
"""Time a command and save its exit status and peak memory, including failed runs."""
import argparse
import csv
import datetime as dt
from pathlib import Path
import resource
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", required=True)
    parser.add_argument("--bytes", type=int, required=True, dest="data_bytes")
    parser.add_argument("--output", type=Path, default=Path("measurements/runs.csv"))
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command or args.data_bytes < 0:
        parser.error("supply a command after -- and a nonnegative --bytes value")
    # One child per invocation: Linux reports this command's peak RSS in KiB.
    start = time.perf_counter()
    result = subprocess.run(command)
    elapsed = time.perf_counter() - start
    peak = int(resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fresh = not args.output.exists() or args.output.stat().st_size == 0
    with args.output.open("a", newline="") as output:
        writer = csv.writer(output)
        if fresh:
            writer.writerow(["timestamp_utc", "label", "data_bytes", "elapsed_seconds", "exit_code", "peak_rss_kib"])
        writer.writerow([dt.datetime.now(dt.timezone.utc).isoformat(), args.label, args.data_bytes,
                         f"{elapsed:.9f}", result.returncode, peak])
    print(f"Recorded exit={result.returncode}, elapsed={elapsed:.3f}s, peak RSS={peak} KiB in {args.output}")
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
