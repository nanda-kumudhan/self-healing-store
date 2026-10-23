#!/usr/bin/env bash
set -euo pipefail
umask 077
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DESTINATION="${1:-$PROJECT_ROOT/infra/certs/generated}"
command -v openssl >/dev/null
command -v keytool >/dev/null
if [[ -e "$DESTINATION" ]]; then
  echo "Refusing to overwrite $DESTINATION. Choose a new output directory to rotate certificates." >&2
  exit 1
fi
mkdir -p "$(dirname "$DESTINATION")"
WORK_DIRECTORY="$(mktemp -d "$(dirname "$DESTINATION")/.certs-XXXXXXXX")"
trap 'rm -rf -- "$WORK_DIRECTORY"' EXIT
export TLS_STORE_PASSWORD="${TLS_STORE_PASSWORD:-$(openssl rand -hex 24)}"
mkdir "$WORK_DIRECTORY/ca"
openssl req -x509 -newkey rsa:3072 -sha256 -days 30 \
  -keyout "$WORK_DIRECTORY/ca/ca.key" -out "$WORK_DIRECTORY/ca/ca.crt" \
  -passout env:TLS_STORE_PASSWORD -subj '/CN=Healstore development CA' \
  -addext 'basicConstraints=critical,CA:TRUE' -addext 'keyUsage=critical,keyCertSign,cRLSign' 2>/dev/null

for IDENTITY in coordinator client node-{1..6}; do
  mkdir "$WORK_DIRECTORY/$IDENTITY"
  openssl req -new -newkey rsa:2048 -noenc -sha256 \
    -keyout "$WORK_DIRECTORY/$IDENTITY/private.key" -out "$WORK_DIRECTORY/$IDENTITY/request.csr" \
    -subj "/CN=$IDENTITY" 2>/dev/null
  if [[ "$IDENTITY" == client ]]; then
    USAGE=clientAuth
  else
    USAGE=serverAuth,clientAuth
  fi
  cat > "$WORK_DIRECTORY/$IDENTITY/extensions.cnf" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=$USAGE
subjectAltName=DNS:$IDENTITY,DNS:localhost,DNS:toxiproxy,IP:127.0.0.1
EOF
  openssl x509 -req -sha256 -days 30 -in "$WORK_DIRECTORY/$IDENTITY/request.csr" \
    -CA "$WORK_DIRECTORY/ca/ca.crt" -CAkey "$WORK_DIRECTORY/ca/ca.key" \
    -passin env:TLS_STORE_PASSWORD -set_serial "0x$(openssl rand -hex 16)" \
    -extfile "$WORK_DIRECTORY/$IDENTITY/extensions.cnf" -out "$WORK_DIRECTORY/$IDENTITY/certificate.crt" 2>/dev/null
  openssl pkcs12 -export -name "$IDENTITY" -in "$WORK_DIRECTORY/$IDENTITY/certificate.crt" \
    -inkey "$WORK_DIRECTORY/$IDENTITY/private.key" -certfile "$WORK_DIRECTORY/ca/ca.crt" \
    -out "$WORK_DIRECTORY/$IDENTITY/$IDENTITY.p12" -passout env:TLS_STORE_PASSWORD
  keytool -importcert -noprompt -alias dev-ca -file "$WORK_DIRECTORY/ca/ca.crt" \
    -keystore "$WORK_DIRECTORY/$IDENTITY/truststore.p12" -storetype PKCS12 \
    -storepass:env TLS_STORE_PASSWORD >/dev/null 2>&1
  cp "$WORK_DIRECTORY/ca/ca.crt" "$WORK_DIRECTORY/$IDENTITY/ca.crt"
  rm -- "$WORK_DIRECTORY/$IDENTITY/private.key" "$WORK_DIRECTORY/$IDENTITY/request.csr" "$WORK_DIRECTORY/$IDENTITY/extensions.cnf"
  # Encrypted PKCS12 files are readable by the unprivileged container user.
  chmod 755 "$WORK_DIRECTORY/$IDENTITY"
  chmod 644 "$WORK_DIRECTORY/$IDENTITY/"*
done
printf 'TLS_STORE_PASSWORD=%s\nDATABASE_PASSWORD=%s\n' "$TLS_STORE_PASSWORD" "$(openssl rand -hex 24)" > "$WORK_DIRECTORY/dev.env"
printf '%s' "$TLS_STORE_PASSWORD" > "$WORK_DIRECTORY/store-password"
chmod 711 "$WORK_DIRECTORY"
mv -- "$WORK_DIRECTORY" "$DESTINATION"
trap - EXIT
echo "Development certificates created in $DESTINATION (30-day validity)."
echo 'The CA private key stays on the host. Each service mounts only its own identity directory.'
