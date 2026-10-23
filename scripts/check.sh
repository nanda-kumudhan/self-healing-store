#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"
./mvnw -B -ntp verify
python3 -m unittest discover -s tests/tooling -p '*_test.py'
bash -n infra/certs/dev-certs.sh scripts/store scripts/check.sh
# Check the Compose config without printing the secrets it uses.
TLS_STORE_PASSWORD=validation-only DATABASE_PASSWORD=validation-only docker compose --profile chaos config --quiet
python3 scripts/smoke-tls.py
