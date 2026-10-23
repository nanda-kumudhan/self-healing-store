#!/usr/bin/env python3
"""Break things in the local cluster. Checking recovery is a separate step."""
import argparse
import csv
import datetime as dt
import json
from pathlib import Path
import subprocess
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
NODES = tuple(f"node-{i}" for i in range(1, 8))


def compose(*args, **kwargs):
    return subprocess.run(["docker", "compose", "--project-directory", str(ROOT),
                           "--env-file", str(ROOT / "infra/certs/generated/dev.env"),
                           "-f", str(ROOT / "compose.yml"), "-f", str(ROOT / "compose.chaos.yml"), *args], check=True, **kwargs)


def api(path, payload=None, method=None):
    request = urllib.request.Request("http://127.0.0.1:8474" + path,
        data=None if payload is None else json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"}, method=method)
    with urllib.request.urlopen(request, timeout=10) as response:
        return response.read()


def record(action, nodes):
    destination = ROOT / "measurements/faults.csv"
    destination.parent.mkdir(exist_ok=True)
    fresh = not destination.exists() or destination.stat().st_size == 0
    with destination.open("a", newline="") as output:
        writer = csv.writer(output)
        if fresh:
            writer.writerow(["timestamp_utc", "action", "nodes"])
        writer.writerow([dt.datetime.now(dt.timezone.utc).isoformat(), action, ";".join(nodes)])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    for action in ("stop", "start", "partition", "heal-network"):
        command = sub.add_parser(action)
        command.add_argument("nodes", nargs="+", choices=NODES)
    latency = sub.add_parser("latency")
    latency.add_argument("nodes", nargs="+", choices=NODES)
    latency.add_argument("--milliseconds", type=int, required=True)
    corrupt = sub.add_parser("corrupt")
    corrupt.add_argument("node", choices=NODES)
    corrupt.add_argument("shard_id", type=uuid.UUID)
    corrupt.add_argument("--offset", type=int, default=0)
    args = parser.parse_args()
    if args.action in ("stop", "start"):
        compose(args.action, *args.nodes)
    elif args.action == "partition":
        for node in args.nodes:
            api(f"/proxies/{node}", {"enabled": False}, "POST")
            api(f"/proxies/{node}-heartbeat", {"enabled": False}, "POST")
    elif args.action == "heal-network":
        for node in args.nodes:
            api(f"/proxies/{node}", {"enabled": True}, "POST")
            api(f"/proxies/{node}-heartbeat", {"enabled": True}, "POST")
            try:
                api(f"/proxies/{node}/toxics/experiment-latency", method="DELETE")
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    raise
    elif args.action == "latency":
        if args.milliseconds < 0:
            parser.error("--milliseconds must be nonnegative")
        for node in args.nodes:
            api(f"/proxies/{node}/toxics", {"name": "experiment-latency", "type": "latency",
                "stream": "downstream", "toxicity": 1.0,
                "attributes": {"latency": args.milliseconds, "jitter": 0}}, "POST")
    elif args.action == "corrupt":
        if args.offset < 0:
            parser.error("--offset must be nonnegative")
        shard = f"/data/shards/{args.shard_id}.shard"
        original = compose("exec", "-T", args.node, "dd", f"if={shard}", "bs=1",
            f"skip={args.offset}", "count=1", "status=none", capture_output=True).stdout
        if len(original) != 1:
            parser.error("offset must refer to an existing byte; shard was not modified")
        compose("exec", "-T", args.node, "dd", f"of={shard}", "bs=1", f"seek={args.offset}",
            "count=1", "conv=notrunc", "status=none", input=bytes([original[0] ^ 1]))
        args.nodes = [args.node]
    record(args.action, args.nodes)
    print(f"Applied {args.action}; event recorded in measurements/faults.csv")


if __name__ == "__main__":
    main()
