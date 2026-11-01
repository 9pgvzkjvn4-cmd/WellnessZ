#!/usr/bin/env bash
# IntiMed MacBook developer kit bootstrap (IM-E08-BL-001). Apple silicon or Intel, macOS 13+.
#   ./deploy/macos/bootstrap.sh            build, test, start the platform (local profile)
#   ./deploy/macos/bootstrap.sh --demo     start with synthetic demo tenants, personas and the 22 template scenarios
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; cd "$ROOT"
PROFILE=local; [ "${1:-}" = "--demo" ] && PROFILE=demo
export INTIMED_HOME="${INTIMED_HOME:-$HOME/IntiMed-Dev-Data}"
command -v java >/dev/null || { echo "JDK 21 required: brew install --cask temurin@21"; exit 1; }
v=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/'); [ "$v" -ge 21 ] || { echo "JDK 21+ required (found $v)"; exit 1; }
# Master key lives in the login Keychain, never in a file (SC-05)
if ! security find-generic-password -s intimed-dev -a master-key >/dev/null 2>&1; then
  security add-generic-password -s intimed-dev -a master-key -w "$(openssl rand -base64 32)" -T "" >/dev/null
  echo "Created IntiMed master key in the login Keychain (service intimed-dev)."
fi
export INTIMED_MASTER_KEY="$(security find-generic-password -s intimed-dev -a master-key -w)"
./mvnw -q -B verify
echo "Starting IntiMed ($PROFILE) – console https://localhost:9443 · device ingress mTLS :8443 · MLLP-TLS :2575"
if [ -f "$INTIMED_HOME/pki/root-ca.pem" ] && ! security find-certificate -c "IntiMed Local Root CA" >/dev/null 2>&1; then
  read -r -p "Trust the IntiMed local root CA in your login keychain (removes browser warnings)? [y/N] " a
  [ "$a" = y ] && security add-trusted-cert -r trustRoot -k "$HOME/Library/Keychains/login.keychain-db" "$INTIMED_HOME/pki/root-ca.pem"
fi
exec java -jar intimed-platform/target/intimed-platform.jar --spring.profiles.active="$PROFILE"
