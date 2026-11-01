#!/usr/bin/env bash
# SC-06 – SBOM present for the build (CycloneDX); optional vulnerability scan with grype / trivy when installed.
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"; OUT="${KIT_RESULTS:-$DIR/results}"; mkdir -p "$OUT"
SBOM="${INTIMED_SBOM:-$DIR/../intimed-platform/target/classes/META-INF/sbom/application.cdx.json}"
[ -f "$SBOM" ] || SBOM="$DIR/../sbom/application.cdx.json"
if [ ! -f "$SBOM" ] && [ -f "$DIR/../intimed-platform.jar" ]; then (cd "$OUT" && unzip -o -q "$DIR/../intimed-platform.jar" "META-INF/sbom/application.cdx.json" && SBOM="$OUT/META-INF/sbom/application.cdx.json"); SBOM="$OUT/META-INF/sbom/application.cdx.json"; fi
res=PASS; detail=""; d=""
if [ -f "$SBOM" ]; then
  n=$(python3 -c "import json,sys;print(len(json.load(open(sys.argv[1])).get('components',[])))" "$SBOM")
  detail="CycloneDX SBOM with $n components"
else res=FAIL; detail="SBOM not found (build with ./mvnw package)"; fi
scan="not run (install grype or trivy to scan)"
if command -v grype >/dev/null 2>&1; then scan=$(grype "sbom:$SBOM" --fail-on critical -q >/dev/null 2>&1 && echo "grype: no critical" || echo "grype: CRITICAL findings"); [[ "$scan" == *CRITICAL* ]] && res=FAIL
elif command -v trivy >/dev/null 2>&1; then scan=$(trivy sbom --severity CRITICAL --exit-code 1 -q "$SBOM" >/dev/null 2>&1 && echo "trivy: no critical" || echo "trivy: CRITICAL findings"); [[ "$scan" == *CRITICAL* ]] && res=FAIL; fi
echo "  $res SC-06 SBOM – $detail; $scan"
python3 -c 'import json,sys; json.dump({"script":sys.argv[1],"results":[{"script":sys.argv[1],"control":sys.argv[2],"check":sys.argv[3],"result":sys.argv[4],"detail":sys.argv[5]}]},open(sys.argv[6],"w"))' "07_sbom_and_vuln_scan" "SC-06" "SBOM and vulnerability scan" "$res" "${d:-}${detail:-}" "$OUT/07_sbom_and_vuln_scan.json"
[ "$res" = PASS ]
