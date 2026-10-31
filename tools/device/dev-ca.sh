#!/usr/bin/env bash
# Local development only: a THROW-AWAY device CA and client certificates for gateway / agent / tablet / kiosk enrolment
# (contract §15.2 step 2 – production certificates come from the group PKI via EST / SCEP / manual CSR).
#
#   tools/device/dev-ca.sh init                       creates tools/device/dev-ca/ca.{key,pem}  (EC P-256, 30 days)
#   tools/device/dev-ca.sh client <name> [eku]        creates tools/device/dev-ca/<name>.{key,pem} signed by the dev CA
#                                                     (eku default clientAuth; use serverAuth to test certificate_key_usage)
#   tools/device/dev-ca.sh proof <name> <message>     prints the base64 proof-of-possession signature over <message>
#   tools/device/dev-ca.sh thumbprint <name>          prints the SHA-256 thumbprint (hex, DER) the ingress forwards
#
# Point the core at the CA:  WZ_DEVICE_TRUST_ANCHORS=$PWD/tools/device/dev-ca/ca.pem  (wellnessz.device.pki.trust-anchors).
# The directory is git-ignored; never use these keys outside a developer machine.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)/dev-ca"
mkdir -p "$DIR"
chmod 700 "$DIR"
cmd="${1:-help}"

case "$cmd" in
  init)
    openssl ecparam -name prime256v1 -genkey -noout -out "$DIR/ca.key"
    openssl req -x509 -new -key "$DIR/ca.key" -sha256 -days 30 -subj "/O=WellnessZ Dev/CN=WellnessZ Dev Device CA" \
      -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" -out "$DIR/ca.pem"
    chmod 600 "$DIR/ca.key"
    echo "dev CA: $DIR/ca.pem"
    ;;
  client)
    name="${2:?client name required}"; eku="${3:-clientAuth}"
    [[ "$name" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || { echo "invalid name" >&2; exit 2; }
    [ -f "$DIR/ca.key" ] || { echo "run '$0 init' first" >&2; exit 2; }
    openssl ecparam -name prime256v1 -genkey -noout -out "$DIR/$name.key.sec1"
    openssl pkcs8 -topk8 -nocrypt -in "$DIR/$name.key.sec1" -out "$DIR/$name.key" && rm -f "$DIR/$name.key.sec1"
    openssl req -new -key "$DIR/$name.key" -subj "/O=WellnessZ Dev/OU=Devices/CN=$name" -out "$DIR/$name.csr"
    printf 'basicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=%s\n' "$eku" > "$DIR/$name.ext"
    openssl x509 -req -in "$DIR/$name.csr" -CA "$DIR/ca.pem" -CAkey "$DIR/ca.key" -CAcreateserial -days 30 -sha256 \
      -extfile "$DIR/$name.ext" -out "$DIR/$name.pem" 2>/dev/null
    rm -f "$DIR/$name.csr" "$DIR/$name.ext"
    chmod 600 "$DIR/$name.key"
    echo "client certificate: $DIR/$name.pem  thumbprint: $("$0" thumbprint "$name")"
    ;;
  proof)
    name="${2:?client name required}"; message="${3:?message required}"
    printf '%s' "$message" | openssl dgst -sha256 -sign "$DIR/$name.key" | openssl base64 -A
    echo
    ;;
  thumbprint)
    name="${2:?client name required}"
    openssl x509 -in "$DIR/$name.pem" -outform DER | openssl dgst -sha256 -r | awk '{print $1}'
    ;;
  *)
    sed -n '2,15p' "$0"
    ;;
esac
