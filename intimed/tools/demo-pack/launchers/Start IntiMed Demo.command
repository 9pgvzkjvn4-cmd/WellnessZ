#!/bin/bash
# IntiMed demo launcher (macOS). Double-click (first time: right-click → Open). Ctrl-C or "Stop IntiMed Demo.command" stops it.
set -u
PACK="$(cd "$(dirname "$0")" && pwd)"; cd "$PACK"
export INTIMED_HOME="${INTIMED_HOME:-$HOME/IntiMed-Demo-Data}"
mkdir -p "$INTIMED_HOME" && chmod 700 "$INTIMED_HOME"
echo "IntiMed demo pack – data in $INTIMED_HOME"
# The pack was downloaded: clear the quarantine flag on the pack's own files so the bundled (Eclipse-signed) runtime starts.
xattr -dr com.apple.quarantine "$PACK" 2>/dev/null || true
# Universal pack: one bundled JRE per architecture – pick the one for this Mac (arm64 = Apple silicon, x86_64 = Intel).
case "$(uname -m)" in arm64|aarch64) ARCH=aarch64 ;; *) ARCH=x64 ;; esac
JAVA="$(ls -d "$PACK"/runtime/$ARCH/*/Contents/Home/bin/java 2>/dev/null | head -1)"
[ -z "$JAVA" ] && JAVA="$(ls -d "$PACK"/runtime/*/Contents/Home/bin/java 2>/dev/null | head -1)"
if [ -z "$JAVA" ]; then
  if command -v java >/dev/null && java -version 2>&1 | grep -qE 'version "(2[1-9]|[3-9][0-9])'; then JAVA=java; else
    echo "No bundled runtime for $(uname -m) and no Java 21 found. Use the pack for your Mac (Apple silicon / Intel) or install Temurin 21."; read -r -p "Press Enter to close"; exit 1; fi
fi
# Master key in the login Keychain – never written to a file (SC-05)
if ! security find-generic-password -s intimed-demo -a master-key >/dev/null 2>&1; then
  security add-generic-password -s intimed-demo -a master-key -w "$(openssl rand -base64 32)" >/dev/null && echo "Created a master key in your login Keychain (service intimed-demo)."
fi
export INTIMED_MASTER_KEY="$(security find-generic-password -s intimed-demo -a master-key -w)"
for p in 9443 8443 2575; do
  if lsof -nP -iTCP:$p -sTCP:LISTEN >/dev/null 2>&1; then echo "Port $p is in use – is the demo already running? (Stop IntiMed Demo.command)"; read -r -p "Press Enter to close"; exit 1; fi
done
LOG="$INTIMED_HOME/intimed-demo.log"
"$JAVA" -Xmx1g -jar "$PACK/app/intimed-platform.jar" --spring.profiles.active=demo > "$LOG" 2>&1 &
PID=$!; echo $PID > "$INTIMED_HOME/intimed-demo.pid"
trap 'echo; echo "Stopping IntiMed…"; kill $PID 2>/dev/null; wait $PID 2>/dev/null; rm -f "$INTIMED_HOME/intimed-demo.pid"; exit 0' INT TERM
printf "Starting"
for i in $(seq 1 120); do
  if curl -s --max-time 2 --cacert "$INTIMED_HOME/pki/root-ca.pem" https://localhost:9443/api/public/info >/dev/null 2>&1; then break; fi
  if ! kill -0 $PID 2>/dev/null; then echo; echo "IntiMed stopped unexpectedly – see $LOG"; tail -20 "$LOG"; read -r -p "Press Enter to close"; exit 1; fi
  printf "."; sleep 1
done
echo; echo "IntiMed is running: https://localhost:9443   (device ingress mTLS :8443 · MLLP-TLS :2575)"
if ! security find-certificate -c "IntiMed Local Root CA" >/dev/null 2>&1; then
  read -r -t 30 -p "Trust the IntiMed demo root CA in your login keychain to avoid browser warnings? [y/N] " a || a=n
  if [ "${a:-n}" = "y" ]; then security add-trusted-cert -r trustRoot -k "$HOME/Library/Keychains/login.keychain-db" "$INTIMED_HOME/pki/root-ca.pem" && echo "Trusted (remove later in Keychain Access → 'IntiMed Local Root CA')."; fi
fi
open "https://localhost:9443"
echo "Sign in as admin.device / IntiMed-Demo-2026!  (demo authenticator shows the TOTP code). Guide: docs/IntiMed-Demo-Guide.html"
echo "The first start auto-provisions 20 template scenarios in the background (Demo control shows progress)."
echo "Keep this window open. Ctrl-C to stop."
wait $PID
