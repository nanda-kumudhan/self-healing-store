#!/usr/bin/env python3
"""Run real Java services over mTLS with private temporary disks and H2 metadata.

This checks file recovery in processes. Container stops and Toxiproxy have their
own opt-in integration suite. All processes and temporary files are cleaned up.
"""
import argparse
import contextlib
import csv
import hashlib
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Cluster:
    def __init__(self, work):
        self.work = work
        self.certs = work / "certs"
        subprocess.run([str(ROOT / "infra/certs/dev-certs.sh"), str(self.certs)], check=True, capture_output=True)
        self.password = (self.certs / "store-password").read_text()
        self.java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
        self.env = dict(os.environ, TLS_STORE_PASSWORD=self.password, STORE_TLS_PASSWORD=self.password,
                        STORE_PASSPHRASE="temporary test file passphrase")
        self.port = free_port()
        self.origin = f"https://localhost:{self.port}"
        self.processes = {}
        self.outputs = []
        self.node_ports = {n: free_port() for n in range(1, 8)}
        self.contexts = {}

    def context(self, identity):
        if identity not in self.contexts:
            key = self.work / f"{identity}.key"
            subprocess.run(["openssl", "pkcs12", "-in", str(self.certs / identity / f"{identity}.p12"),
                            "-nocerts", "-noenc", "-out", str(key), "-passin", "env:TLS_STORE_PASSWORD"],
                           env=self.env, check=True, capture_output=True)
            ctx = ssl.create_default_context(cafile=str(self.certs / identity / "ca.crt"))
            ctx.load_cert_chain(str(self.certs / identity / "certificate.crt"), str(key))
            self.contexts[identity] = ctx
        return self.contexts[identity]

    def request(self, path, method="GET", body=None, identity="client", origin=None):
        req = urllib.request.Request((origin or self.origin) + path, method=method,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, context=self.context(identity), timeout=60) as res:
            value = res.read()
            return json.loads(value) if value else None

    def start(self, name, automatic=False):
        print(f"START {name}: real Java process over mTLS", flush=True)
        module = "coordinator" if name == "coordinator" else "storage-node"
        port = self.port if name == "coordinator" else self.node_ports[int(name[5:])]
        env = dict(self.env, SERVER_ADDRESS="127.0.0.1", SERVER_PORT=str(port),
                   TLS_KEY_STORE=f"file:{self.certs / name / (name + '.p12')}",
                   TLS_TRUST_STORE=f"file:{self.certs / name / 'truststore.p12'}",
                   SPRING_DATASOURCE_URL=f"jdbc:h2:file:{self.work / 'metadata'};MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE",
                   SPRING_DATASOURCE_USERNAME="sa", SPRING_DATASOURCE_PASSWORD="",
                   STORE_ALLOW_LOOPBACK_REGISTRATION="true", STORE_COORDINATOR=self.origin,
                   STORE_NODE_NAME=name if name != "coordinator" else "node-1",
                   STORE_ENDPOINT=f"https://localhost:{port}", STORE_DATA_DIRECTORY=str(self.work / name / "shards"),
                   STORE_REPAIR_POLL_MS="1000" if automatic else "3600000",
                   STORE_HEARTBEAT_TIMEOUT_SECONDS="4", STORE_REPAIR_DELAY_SECONDS="2", STORE_SCRUB_MS="3600000")
        output = (self.work / f"{name}.log").open("a")
        self.outputs.append(output)
        process = subprocess.Popen([self.java, "-Xmx192m", "-XX:ActiveProcessorCount=2", "-jar",
                                    str(ROOT / module / "target" / f"{module}-0.1.0-SNAPSHOT.jar")],
                                   cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
        self.processes[name] = process
        deadline = time.monotonic() + 60
        while True:
            try:
                result = self.request("/api/v1/capabilities", origin=f"https://localhost:{port}")
                assert result["fileOperationsReady"]
                return
            except (OSError, urllib.error.URLError):
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError(f"{name} failed: {(self.work / (name + '.log')).read_text()}")
                time.sleep(.2)

    def stop(self, name):
        proc = self.processes.pop(name, None)
        if proc:
            print(f"STOP {name}: terminating process {proc.pid}", flush=True)
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)

    def cli(self, *args, expected=0, label=None, data_bytes=0):
        cmd = [self.java, "-Xmx128m", "-jar", str(ROOT / "client/target/client-0.1.0-SNAPSHOT.jar"),
               "--coordinator", self.origin, "--key-store", str(self.certs / "client/client.p12"),
               "--trust-store", str(self.certs / "client/truststore.p12"), *map(str, args)]
        if label:
            cmd = ["python3", str(ROOT / "scripts/measure.py"), "--label", label, "--bytes", str(data_bytes), "--", *cmd]
        print("$ store " + " ".join(map(str, args)), flush=True)
        res = subprocess.run(cmd, cwd=ROOT, env=self.env, capture_output=True, text=True, timeout=240)
        if res.returncode != expected:
            raise AssertionError(f"CLI {args}: exit {res.returncode}; expected {expected}\n{res.stdout}\n{res.stderr}")
        if res.stdout.strip():
            print(res.stdout.strip(), flush=True)
        return res.stdout.strip().splitlines()[0] if res.stdout.strip() else ""

    def wait_nodes(self, count):
        deadline = time.monotonic() + 30
        while len(self.request("/api/v1/nodes")) < count:
            if time.monotonic() > deadline:
                raise AssertionError("Nodes did not register")
            time.sleep(.2)
        time.sleep(2.2)  # Let each node refresh its lease after slow process startup.

    def close(self):
        for name in list(self.processes):
            self.stop(name)
        for output in self.outputs:
            output.close()


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--measure", action="store_true", help="Record actual timings in measurements/runs.csv")
    parser.add_argument("--benchmark-mib", nargs="*", type=int, default=[], help="Additional measured file sizes")
    parser.add_argument("--repeats", type=int, default=2)
    args = parser.parse_args()
    if args.repeats < 1 or any(n < 1 for n in args.benchmark_mib):
        parser.error("Use positive sizes and repeat counts")
    with tempfile.TemporaryDirectory(prefix="healstore-e2e-") as temporary:
        c = Cluster(Path(temporary))
        try:
            c.start("coordinator")
            for n in range(1, 7):
                c.start(f"node-{n}")
            c.wait_nodes(6)
            for identity, path, method, body in [
                ("node-1", "/api/v1/nodes", "GET", None),
                ("client", "/api/v1/nodes", "POST", {}),
                ("node-1", f"/api/v1/nodes/{uuid.uuid4()}/heartbeats", "POST", None),
            ]:
                try:
                    c.request(path, method, body, identity)
                except urllib.error.HTTPError as err:
                    assert err.code == 403, err
                else:
                    raise AssertionError("Wrong role or node identity was accepted")
            print("PASS certificate role and node ID authorization", flush=True)
            empty = c.work / "empty"
            empty.touch()
            empty_id = c.cli("upload", empty)
            c.cli("download", empty_id, c.work / "empty-copy")
            assert (c.work / "empty-copy").stat().st_size == 0
            source = c.work / "input"
            source.write_bytes(os.urandom(2 * 1024 * 1024 + 37))
            size = source.stat().st_size
            if args.measure:
                subprocess.run(["python3", str(ROOT / "scripts/measure.py"), "--label", "plain-copy", "--bytes", str(size),
                                "--", "cp", str(source), str(c.work / "plain-copy")], check=True, capture_output=True)
            object_id = c.cli("upload", source, label="upload" if args.measure else None, data_bytes=size)
            def download(label=None):
                destination = c.work / ("copy-" + uuid.uuid4().hex)
                c.cli("download", object_id, destination, label=label, data_bytes=size)
                assert digest(source) == digest(destination)
            download("download" if args.measure else None)
            chunk = c.request(f"/api/v1/objects/{object_id}/chunks/0")
            mapping = {str(uuid.UUID(bytes=hashlib.md5(f"node-{n}".encode()).digest(), version=3)): f"node-{n}" for n in range(1, 8)}
            holders = [mapping[loc["nodeId"]] for loc in chunk["locations"]]
            print(f"Uploaded object {object_id}; first chunk holders: {holders}", flush=True)
            c.stop(holders[0]); download()
            c.stop(holders[1]); download()
            c.stop(holders[2])
            failed_dest = c.work / "must-not-exist"
            c.cli("download", object_id, failed_dest, expected=1)
            assert not failed_dest.exists()
            assert not list(c.work.glob(".download-*.part"))
            print("PASS encrypted empty/multi-chunk round trips, one/two stopped holders, clean failure with three", flush=True)
            c.start(holders[2]); c.wait_nodes(6)
            # Only four live nodes remain: manual repair must report capacity still missing.
            partial = json.loads(c.cli("repair", object_id, expected=2))
            assert partial["pendingShards"] > 0
            c.start("node-7"); c.wait_nodes(7)
            complete = json.loads(c.cli("repair", object_id, label="repair-capacity-restored" if args.measure else None, data_bytes=size))
            assert complete["pendingShards"] == 0
            download()
            print("PASS manual repair reports insufficient spares, then restores five holders with seventh node", flush=True)
            for name in holders[:2]:
                c.start(name)
            c.wait_nodes(7)
            chunk = c.request(f"/api/v1/objects/{object_id}/chunks/0")
            bad_node = mapping[chunk["locations"][0]["nodeId"]]
            shard_id = chunk["chunk"]["shards"][0]["id"]
            shard_path = c.work / bad_node / "shards" / f"{shard_id}.shard"
            with shard_path.open("r+b") as shard:
                b = shard.read(1); shard.seek(0); shard.write(bytes([b[0] ^ 1]))
            download()
            scrub = c.request("/api/v1/scrubs", "POST", origin=f"https://localhost:{c.node_ports[int(bad_node[5:])]}" )
            assert scrub["damagedShards"] >= 1
            repair = json.loads(c.cli("repair", object_id, label="repair-one" if args.measure else None, data_bytes=size))
            assert repair["repairedShards"] >= 1 and repair["pendingShards"] == 0
            download()
            print("PASS corrupt-shard rejection, scrub report and verified repair", flush=True)
            for mib in args.benchmark_mib:
                for repeat in range(args.repeats):
                    bench_source = c.work / f"benchmark-{mib}-{repeat}"
                    # Generate the source in bounded blocks too; only the CLI is measured.
                    with bench_source.open("wb") as generated:
                        for block in range(mib):
                            generated.write(os.urandom(1024 * 1024))
                    bench_bytes = mib * 1024 * 1024
                    subprocess.run(["python3", str(ROOT / "scripts/measure.py"), "--label", "plain-copy", "--bytes", str(bench_bytes),
                                    "--", "cp", str(bench_source), str(c.work / "plain-benchmark")], check=True, capture_output=True)
                    bench_id = c.cli("upload", bench_source, label="upload", data_bytes=bench_bytes)
                    bench_copy = c.work / f"benchmark-copy-{mib}-{repeat}"
                    c.cli("download", bench_id, bench_copy, label="download", data_bytes=bench_bytes)
                    assert digest(bench_source) == digest(bench_copy)
                    bench_chunk = c.request(f"/api/v1/objects/{bench_id}/chunks/0")
                    stopped = mapping[bench_chunk["locations"][0]["nodeId"]]
                    c.stop(stopped)
                    fixed = json.loads(c.cli("repair", bench_id, label="repair-node", data_bytes=bench_bytes))
                    assert fixed["pendingShards"] == 0
                    verified = c.work / f"benchmark-repaired-{mib}-{repeat}"
                    c.cli("download", bench_id, verified)
                    assert digest(bench_source) == digest(verified)
                    c.start(stopped); c.wait_nodes(7)
                    print(f"PASS measured {mib} MiB trial {repeat+1}: copy, encrypted upload/download, complete node repair", flush=True)
            c.stop("coordinator"); c.start("coordinator", automatic=True); c.wait_nodes(7)
            download()
            chunk = c.request(f"/api/v1/objects/{object_id}/chunks/0")
            lost = mapping[chunk["locations"][0]["nodeId"]]
            c.stop(lost)
            deadline = time.monotonic() + 60
            while True:
                current = c.request(f"/api/v1/objects/{object_id}/chunks/0")
                if all(mapping[loc["nodeId"]] != lost for loc in current["locations"]):
                    break
                if time.monotonic() > deadline:
                    raise AssertionError("Automatic repair did not replace the dead holder")
                time.sleep(.5)
            download()
            print("PASS metadata survives coordinator restart; heartbeat delay triggers automatic repair", flush=True)
            print("All process-cluster checks passed. Docker and Toxiproxy checks are separate.", flush=True)
        except Exception:
            for path in c.work.glob("*.log"):
                print(f"--- {path.name} ---\n" + path.read_text()[-6000:], flush=True)
            raise
        finally:
            c.close()


if __name__ == "__main__":
    main()
