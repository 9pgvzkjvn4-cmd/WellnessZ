#!/usr/bin/env bash
# Builds the comprehensive universal macOS demo pack (Apple silicon + Intel in one zip):
#   dist/IntiMed-2.0-Demo-Pack-macOS-Universal.zip
# Usage: tools/demo-pack/build-universal.sh <jre-dir-with-jre-mac-aarch64.tar.gz-and-jre-mac-x64.tar.gz> [videos-dir]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; JRE="$1"; VIDEOS="${2:-}"
DIST="$ROOT/dist"; P="$DIST/pack-universal/IntiMed-2.0-Demo-Pack"
JAR="$ROOT/intimed-platform/target/intimed-platform.jar"; [ -f "$JAR" ] || { echo "build the jar first"; exit 1; }
rm -rf "$DIST/pack-universal"; mkdir -p "$P/app" "$P/runtime/aarch64" "$P/runtime/x64" "$P/docs" "$P/security-test-kit" "$P/sbom"
cp "$JAR" "$P/app/"
tar xzf "$JRE/jre-mac-aarch64.tar.gz" -C "$P/runtime/aarch64"
tar xzf "$JRE/jre-mac-x64.tar.gz" -C "$P/runtime/x64"
cp "$ROOT"/tools/demo-pack/launchers/*.command "$ROOT"/tools/demo-pack/launchers/start-demo.* "$P/"
chmod +x "$P"/*.command "$P"/start-demo.sh
cp "$ROOT"/security-test-kit/*.py "$ROOT"/security-test-kit/*.sh "$ROOT"/security-test-kit/README.md "$P/security-test-kit/"
unzip -o -q "$JAR" "META-INF/sbom/application.cdx.json" -d "$P/sbom-tmp" && mv "$P/sbom-tmp/META-INF/sbom/application.cdx.json" "$P/sbom/" && rm -rf "$P/sbom-tmp"
pandoc "$ROOT/docs/demo/Demo-Guide.md" -s --metadata title="IntiMed 2.0 demo guide" -o "$P/docs/IntiMed-Demo-Guide.html"
python3 "$ROOT/tools/demo-pack/build_guide_xlsx.py" "$ROOT/docs/demo/Demo-Guide.md" "$P/docs/IntiMed-Demo-Guide.xlsx" >/dev/null
for d in Release-Notes-2.0.0-local traceability/Deferred-and-Partial-Backlog traceability/Traceability-Matrix security/Security-Architecture integration/Integration-Guide; do
  pandoc "$ROOT/docs/$d.md" -s --metadata title="$(basename "$d" | tr '-' ' ')" -o "$P/docs/$(basename "$d").html"
done
pandoc "$ROOT/docs/enhancements/IntiMed-2.0-Enhancement-Epics-and-Backlog.md" -s --metadata title="IntiMed 2.0 enhancement epics and backlog" -o "$P/docs/IntiMed-2.0-Enhancement-Epics-and-Backlog.html"
if [ -n "$VIDEOS" ] && [ -d "$VIDEOS" ]; then mkdir -p "$P/videos" && cp "$VIDEOS"/*.mp4 "$VIDEOS"/*.srt "$P/videos/" 2>/dev/null || true; fi
cat > "$P/README.txt" <<TXT
IntiMed 2.0 – Intelligent Medical Device Integration Platform · comprehensive demo pack (macOS, universal)

Works on Apple silicon and Intel Macs: the launcher picks the bundled Java runtime for your Mac automatically.

1. Right-click "Start IntiMed Demo.command" → Open (first time only), then follow docs/IntiMed-Demo-Guide.html
   (Excel version: docs/IntiMed-Demo-Guide.xlsx). Section 5 covers the IntiMed 2.0 intelligence scenarios.
2. Browser: https://localhost:9443 · personas and password in the guide (synthetic data only).
3. "Run Security Test Kit.command" verifies the security controls (69 checks).
4. "Stop IntiMed Demo.command" stops the platform; "Reset Demo Data.command" deletes ~/IntiMed-Demo-Data and the Keychain item.

Contents: app/intimed-platform.jar (platform, simulators, cohort simulator, SDK), runtime/aarch64 and runtime/x64
(Eclipse Temurin JRE 21, Eclipse-signed), security-test-kit/, sbom/ (CycloneDX), docs/ (demo guide, release notes,
enhancement epics and backlog, traceability, security, integration)$( [ -n "$VIDEOS" ] && echo ", videos/ (reference videos)" ).
Windows/Linux: start-demo.bat / start-demo.sh with Java 21 installed.

Reference ranges shown by the platform are defaults for integration validation and demonstration only.
TXT
(cd "$DIST/pack-universal" && rm -f "$DIST/IntiMed-2.0-Demo-Pack-macOS-Universal.zip" && zip -q -r -y "$DIST/IntiMed-2.0-Demo-Pack-macOS-Universal.zip" IntiMed-2.0-Demo-Pack)
echo "built $DIST/IntiMed-2.0-Demo-Pack-macOS-Universal.zip ($(du -h "$DIST/IntiMed-2.0-Demo-Pack-macOS-Universal.zip" | cut -f1))"
