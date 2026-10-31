#!/usr/bin/env bash
# SC-11 – API baseline scan (OWASP API Top 10, ASVS V13) with the OWASP ZAP API scan in passive / baseline mode against the
# staging OpenAPI document (WZ_API_DOCS_ENABLED=true in staging). Never point this at production without a change ticket.
#   ./11_zap_api_baseline.sh --authorised --targets targets.json [--out <dir>]
# targets: zap.openapiUrl (e.g. https://staging/v3/api-docs), zap.image (default ghcr.io/zaproxy/zaproxy:stable),
#          zap.failOn (WARN | FAIL, default FAIL). Needs docker; otherwise NOT_RUN.
set -u
AUTH=0; TARGETS=""; OUT="${WZ_PACK_OUT:-$PWD/device-security-evidence}"
while [ $# -gt 0 ]; do
  case "$1" in
    --authorised) AUTH=1 ;;
    --targets) TARGETS="$2"; shift ;;
    --out) OUT="$2"; shift ;;
    -h|--help) sed -n '2,7p' "$0"; exit 0 ;;
  esac
  shift
done
if [ "$AUTH" != 1 ] || [ -z "$TARGETS" ] || [ ! -f "$TARGETS" ]; then
  echo "Refusing to run: --authorised and --targets <file> are required (defensive checks of your own systems only)." >&2
  exit 2
fi
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
SCRIPT=11_zap_api_baseline
cfg() { node -e "const c=JSON.parse(require('fs').readFileSync(process.argv[1],'utf8'));const v=process.argv[2].split('.').reduce((o,k)=>o==null?undefined:o[k],c);if(v!==undefined)process.stdout.write(String(v))" "$TARGETS" "$1" 2>/dev/null; }
ENVIRONMENT="$(cfg environment)"; ENVIRONMENT="${ENVIRONMENT:-unspecified}"
URL="$(cfg zap.openapiUrl)"; IMAGE="$(cfg zap.image)"; IMAGE="${IMAGE:-ghcr.io/zaproxy/zaproxy:stable}"; FAIL_ON="$(cfg zap.failOn)"; FAIL_ON="${FAIL_ON:-FAIL}"
STARTED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
ID=zap-api-baseline; DESC="OWASP ZAP API baseline scan without FAIL-level alerts"
if [ -z "$URL" ]; then OUTCOME=NOT_RUN; DETAIL="zap.openapiUrl not configured"
elif ! command -v docker >/dev/null 2>&1; then OUTCOME=NOT_RUN; DETAIL="docker not available"
else
  docker run --rm -v "$OUT:/zap/wrk:rw" "$IMAGE" zap-api-scan.py -t "$URL" -f openapi -J zap-report.json -r zap-report.html -I >/dev/null 2>&1
  if [ -f "$OUT/zap-report.json" ]; then
    COUNTS=$(node -e "const j=JSON.parse(require('fs').readFileSync(process.argv[1],'utf8'));let h=0,m=0;for(const s of j.site||[])for(const a of s.alerts||[]){const r=+a.riskcode;if(r>=3)h++;else if(r===2)m++;}process.stdout.write(h+' '+m)" "$OUT/zap-report.json")
    HIGH=${COUNTS% *}; MED=${COUNTS#* }
    if [ "$HIGH" -gt 0 ] || { [ "$FAIL_ON" = WARN ] && [ "$MED" -gt 0 ]; }; then OUTCOME=FAIL; else OUTCOME=PASS; fi
    DETAIL="high=$HIGH medium=$MED report=zap-report.json sha256=$(sha256sum "$OUT/zap-report.json" | awk '{print $1}')"
  else OUTCOME=FAIL; DETAIL="ZAP produced no report"; fi
fi
FINISHED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
FILE="$OUT/$SCRIPT.json"
printf '{"script":"%s","controls":["SC-11"],"packVersion":"1.0.0","environment":"%s","startedAt":"%s","finishedAt":"%s","outcome":"%s","checks":[{"id":"%s","description":"%s","outcome":"%s","detail":"%s"}]}\n' \
  "$SCRIPT" "$ENVIRONMENT" "$STARTED" "$FINISHED" "$OUTCOME" "$ID" "$DESC" "$OUTCOME" "$DETAIL" > "$FILE"
SHA=$(sha256sum "$FILE" | awk '{print $1}')
echo "$SHA  $SCRIPT.json" > "$FILE.sha256"
echo "$SCRIPT SC-11 $OUTCOME evidence=$FILE sha256=$SHA"
[ "$OUTCOME" = FAIL ] && exit 1 || exit 0
