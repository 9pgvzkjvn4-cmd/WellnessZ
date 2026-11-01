#!/usr/bin/env bash
# Builds the self-executable demo packs: dist/IntiMed-Demo-Pack-macOS-{AppleSilicon,Intel}.zip
# Usage: tools/demo-pack/build.sh <jre-dir-with-jre-mac-aarch64.tar.gz-and-jre-mac-x64.tar.gz> [videos-dir]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; JRE="$1"; VIDEOS="${2:-}"
DIST="$ROOT/dist"; rm -rf "$DIST/pack" && mkdir -p "$DIST/pack"
JAR="$ROOT/intimed-platform/target/intimed-platform.jar"; [ -f "$JAR" ] || { echo "build the jar first"; exit 1; }
for arch in aarch64:AppleSilicon x64:Intel; do
  a="${arch%%:*}"; label="${arch##*:}"; P="$DIST/pack/IntiMed-Demo-Pack"
  rm -rf "$P"; mkdir -p "$P/app" "$P/runtime" "$P/docs" "$P/security-test-kit"
  cp "$JAR" "$P/app/"
  tar xzf "$JRE/jre-mac-$a.tar.gz" -C "$P/runtime"
  cp "$ROOT"/tools/demo-pack/launchers/*.command "$ROOT"/tools/demo-pack/launchers/start-demo.* "$P/"
  cp "$ROOT"/security-test-kit/*.py "$ROOT"/security-test-kit/*.sh "$ROOT"/security-test-kit/README.md "$P/security-test-kit/"
  unzip -o -q "$JAR" "META-INF/sbom/application.cdx.json" -d "$P/security-test-kit/sbom-tmp" && mkdir -p "$P/sbom" && mv "$P/security-test-kit/sbom-tmp/META-INF/sbom/application.cdx.json" "$P/sbom/" && rm -rf "$P/security-test-kit/sbom-tmp"
  pandoc "$ROOT/docs/demo/Demo-Guide.md" -s --metadata title="IntiMed demo guide" -o "$P/docs/IntiMed-Demo-Guide.html"
  python3 "$ROOT/tools/demo-pack/build_guide_xlsx.py" "$ROOT/docs/demo/Demo-Guide.md" "$P/docs/IntiMed-Demo-Guide.xlsx" >/dev/null
  for d in traceability/Deferred-and-Partial-Backlog traceability/Traceability-Matrix traceability/Increments-and-Capacity security/Security-Architecture integration/Integration-Guide; do
    pandoc "$ROOT/docs/$d.md" -s --metadata title="$(basename "$d" | tr '-' ' ')" -o "$P/docs/$(basename "$d").html"
  done
  [ -n "$VIDEOS" ] && [ -d "$VIDEOS" ] && mkdir -p "$P/videos" && cp "$VIDEOS"/*.mp4 "$VIDEOS"/*.srt "$P/videos/" 2>/dev/null || true
  cat > "$P/README.txt" <<TXT
IntiMed – Intelligent Medical Device Integration Platform · demo pack (macOS, $label)

1. Right-click "Start IntiMed Demo.command" → Open (first time only), then follow docs/IntiMed-Demo-Guide.html.
2. Browser: https://localhost:9443 · personas and password in the guide (synthetic data only).
3. "Run Security Test Kit.command" verifies the security controls (69 checks).
4. "Stop IntiMed Demo.command" stops the platform; "Reset Demo Data.command" deletes ~/IntiMed-Demo-Data and the Keychain item.

Contents: app/intimed-platform.jar (platform + simulators + SDK), runtime/ (Eclipse Temurin JRE 21, $label, Eclipse-signed),
security-test-kit/, sbom/ (CycloneDX), docs/ (demo guide HTML + Excel, traceability, deferred backlog, security, integration)$( [ -n "$VIDEOS" ] && echo ", videos/" ).
Windows/Linux: start-demo.bat / start-demo.sh with Java 21 installed.
TXT
  (cd "$DIST/pack" && rm -f "$DIST/IntiMed-Demo-Pack-macOS-$label.zip" && zip -q -r -y "$DIST/IntiMed-Demo-Pack-macOS-$label.zip" IntiMed-Demo-Pack)
  echo "built $DIST/IntiMed-Demo-Pack-macOS-$label.zip ($(du -h "$DIST/IntiMed-Demo-Pack-macOS-$label.zip" | cut -f1))"
done
