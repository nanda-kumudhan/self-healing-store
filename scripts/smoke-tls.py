#!/usr/bin/env python3
"""Start both services locally, check TLS connections, then stop them."""
import contextlib
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

ROOT = Path(__file__).resolve().parents[1]


def port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def fetch(url, context, method="GET", data=None):
    request = urllib.request.Request(url, method=method, data=data)
    if data is not None:
        request.add_header("Content-Type", "application/octet-stream")
    with urllib.request.urlopen(request, context=context, timeout=3) as response:
        return json.load(response)


def main():
    generated = ROOT / "infra/certs/generated"
    if not generated.exists():
        subprocess.run([str(ROOT / "infra/certs/dev-certs.sh")], check=True)
    env = dict(os.environ, TLS_STORE_PASSWORD=(generated / "store-password").read_text())
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    with tempfile.TemporaryDirectory(prefix="healstore-tls-") as temporary, contextlib.ExitStack() as stack:
        work = Path(temporary)
        key = work / "client.key"
        subprocess.run(["openssl", "pkcs12", "-in", str(generated / "client/client.p12"),
            "-nocerts", "-noenc", "-out", str(key), "-passin", "env:TLS_STORE_PASSWORD"],
            env=env, check=True, capture_output=True)
        client = ssl.create_default_context(cafile=str(generated / "client/ca.crt"))
        client.load_cert_chain(str(generated / "client/certificate.crt"), str(key))
        anonymous = ssl.create_default_context(cafile=str(generated / "client/ca.crt"))
        rogue_key, rogue_cert = work / "rogue.key", work / "rogue.crt"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-noenc", "-days", "1",
            "-subj", "/CN=untrusted-test-client", "-keyout", str(rogue_key), "-out", str(rogue_cert),
            "-addext", "basicConstraints=critical,CA:FALSE", "-addext", "extendedKeyUsage=clientAuth"],
            check=True, capture_output=True)
        rogue = ssl.create_default_context(cafile=str(generated / "client/ca.crt"))
        rogue.load_cert_chain(str(rogue_cert), str(rogue_key))

        for module, identity in (("coordinator", "coordinator"), ("storage-node", "node-1")):
            jar = ROOT / module / "target" / f"{module}-0.1.0-SNAPSHOT.jar"
            if not jar.exists():
                raise SystemExit("Build first: mvn verify")
            service_port = port()
            service_env = dict(env, SERVER_ADDRESS="127.0.0.1", SERVER_PORT=str(service_port),
                TLS_KEY_STORE=f"file:{generated / identity / (identity + '.p12')}",
                TLS_TRUST_STORE=f"file:{generated / identity / 'truststore.p12'}",
                SPRING_DATASOURCE_URL="jdbc:h2:mem:tls;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                SPRING_DATASOURCE_USERNAME="sa", SPRING_DATASOURCE_PASSWORD="",
                STORE_DATA_DIRECTORY=str(work / module / "shards"))
            log_path = work / f"{module}.log"
            output = stack.enter_context(log_path.open("w"))
            process = subprocess.Popen([java, "-jar", str(jar)], cwd=ROOT, env=service_env, stdout=output, stderr=subprocess.STDOUT)
            try:
                origin = f"https://localhost:{service_port}"
                deadline = time.monotonic() + 60
                while True:
                    try:
                        capabilities = fetch(origin + "/api/v1/capabilities", client)
                        break
                    except (OSError, urllib.error.URLError):
                        if process.poll() is not None or time.monotonic() > deadline:
                            raise RuntimeError(f"{module} failed to start:\n{log_path.read_text()}")
                        time.sleep(0.25)
                if capabilities["fileOperationsReady"] is not True:
                    raise AssertionError("Implementation capabilities are missing")
                for name, context in (("anonymous", anonymous), ("untrusted", rogue)):
                    try:
                        fetch(origin + "/api/v1/capabilities", context)
                    except (urllib.error.HTTPError, json.JSONDecodeError) as error:
                        raise AssertionError(f"{name} peer reached HTTP instead of failing TLS") from error
                    except (OSError, urllib.error.URLError):
                        pass
                    else:
                        raise AssertionError(f"{name} peer was accepted")
                # A trusted client still cannot impersonate a storage node.
                if module == "coordinator":
                    try:
                        fetch(origin + "/api/v1/nodes/00000000-0000-0000-0000-000000000001/heartbeats", client, "POST")
                    except urllib.error.HTTPError as error:
                        if error.code != 403:
                            raise AssertionError(f"Wrong-role status: {error.code}")
                    else:
                        raise AssertionError("Client impersonated a node")
                if module == "coordinator":
                    cli_env = dict(env, STORE_TLS_PASSWORD=env["TLS_STORE_PASSWORD"])
                    subprocess.run([java, "-jar", str(ROOT / "client/target/client-0.1.0-SNAPSHOT.jar"),
                        "status", "--coordinator", origin], cwd=ROOT, env=cli_env, check=True, capture_output=True)
                if module == "storage-node":
                    # It is CA-trusted and has a matching localhost SAN, but is the wrong server role.
                    wrong_server = subprocess.run([java, "-jar", str(ROOT / "client/target/client-0.1.0-SNAPSHOT.jar"),
                        "nodes", "--coordinator", origin], cwd=ROOT,
                        env=dict(env, STORE_TLS_PASSWORD=env["TLS_STORE_PASSWORD"]), capture_output=True, text=True)
                    if wrong_server.returncode == 0 or "role" not in wrong_server.stderr.lower():
                        raise AssertionError(f"Client did not reject the wrong server identity: {wrong_server.stderr}")
                print(f"PASS {module}: trusted mTLS, anonymous/rogue rejection, capabilities, role authorization")
            finally:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


if __name__ == "__main__":
    main()
