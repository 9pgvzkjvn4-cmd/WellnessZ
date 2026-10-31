#!/usr/bin/env bash
# SC-10 – Encryption at rest for databases, object storage, queues and backups (NIST SC-28, ISO 27001 A.8.24, GDPR Art. 32).
#   ./10_encryption_at_rest_check.sh --authorised --targets targets.json [--out <dir>]
# With database.psqlUrl (read-only account is enough): connector secrets and signing keys are field-encrypted (v1: prefix), the
# outbox payloads are encrypted, the database enforces TLS. On a gateway host: the store-and-forward queue directory lives on an
# encrypted volume. Backup encryption is evidenced by the configured backup policy (backup.encrypted in the targets file).
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
SCRIPT=10_encryption_at_rest_check
cfg() { node -e "const r=v=>typeof v==='string'&&v.startsWith('env:')?process.env[v.slice(4)]:v;const c=JSON.parse(require('fs').readFileSync(process.argv[1],'utf8'));const v=r(process.argv[2].split('.').reduce((o,k)=>o==null?undefined:o[k],c));if(v!==undefined)process.stdout.write(String(v))" "$TARGETS" "$1" 2>/dev/null; }
ENVIRONMENT="$(cfg environment)"; ENVIRONMENT="${ENVIRONMENT:-unspecified}"
PSQL_URL="$(cfg database.psqlUrl)"
QUEUE_DIR="$(cfg gateway.queueDir)"
BACKUP_ENC="$(cfg backup.encrypted)"
CHECKS=""
add() { local d; d=$(printf '%s' "$4" | tr -d '"\\' | tr '\n' ' ' | cut -c1-300); CHECKS="${CHECKS}${CHECKS:+,}{\"id\":\"$1\",\"description\":\"$3\",\"outcome\":\"$2\",\"detail\":\"$d\"}"; }
q() { psql "$PSQL_URL" -At -c "$1" 2>/dev/null; }
STARTED=$(date -u +%Y-%m-%dT%H:%M:%SZ)

if [ -n "$PSQL_URL" ] && command -v psql >/dev/null 2>&1; then
  N=$(q "select count(*) from devicesec.secret where ciphertext is not null and ciphertext not like 'v_%:%'")
  T=$(q "select count(*) from devicesec.secret where ciphertext is not null")
  if [ -n "$N" ]; then [ "$N" = 0 ] && add secrets-encrypted PASS "connector secrets field-encrypted (AES-256-GCM, key version prefix)" "$T secret(s)" || add secrets-encrypted FAIL "connector secrets field-encrypted" "$N not encrypted"
  else add secrets-encrypted NOT_RUN "connector secrets field-encrypted" "query not permitted"; fi
  N=$(q "select count(*) from devicesec.signing_key where private_key not like 'v_%:%'")
  if [ -n "$N" ]; then [ "$N" = 0 ] && add signing-keys-encrypted PASS "package-signing private keys encrypted" "ok" || add signing-keys-encrypted FAIL "package-signing private keys encrypted" "$N plaintext"
  else add signing-keys-encrypted NOT_RUN "package-signing private keys encrypted" "query not permitted"; fi
  N=$(q "select count(*) from platform.outbox_event where payload not like 'v_%:%'")
  if [ -n "$N" ]; then [ "$N" = 0 ] && add outbox-encrypted PASS "outbox payloads encrypted at rest" "ok" || add outbox-encrypted FAIL "outbox payloads encrypted at rest" "$N plaintext"
  else add outbox-encrypted NOT_RUN "outbox payloads encrypted at rest" "query not permitted"; fi
  S=$(q "select ssl from pg_stat_ssl where pid = pg_backend_pid()")
  [ "$S" = "t" ] && add db-tls PASS "database connection encrypted (TLS)" "pg_stat_ssl.ssl=t" || add db-tls FAIL "database connection encrypted (TLS)" "ssl=${S:-unknown} (local dev stacks may run without TLS)"
else
  add database NOT_RUN "database encryption at rest" "database.psqlUrl not configured or psql missing"
fi

if [ -n "$QUEUE_DIR" ]; then
  if [ -d "$QUEUE_DIR" ] && command -v findmnt >/dev/null 2>&1; then
    SRC=$(findmnt -no SOURCE --target "$QUEUE_DIR" 2>/dev/null)
    if lsblk -rno TYPE "$SRC" 2>/dev/null | grep -q crypt || echo "$SRC" | grep -q mapper; then add queue-volume PASS "store-and-forward queue on an encrypted volume" "$SRC"
    else add queue-volume FAIL "store-and-forward queue on an encrypted volume" "$SRC"; fi
  else add queue-volume NOT_RUN "store-and-forward queue on an encrypted volume" "queue directory not present on this host"; fi
fi

case "$BACKUP_ENC" in
  true) add backups PASS "backups encrypted (policy evidence)" "backup.encrypted=true – attach the backup policy" ;;
  false) add backups FAIL "backups encrypted (policy evidence)" "backup.encrypted=false" ;;
  *) add backups NOT_RUN "backups encrypted (policy evidence)" "backup.encrypted not stated" ;;
esac

FINISHED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
OUTCOME=NOT_RUN
echo "$CHECKS" | grep -q '"outcome":"PASS"' && OUTCOME=PASS
echo "$CHECKS" | grep -q '"outcome":"FAIL"' && OUTCOME=FAIL
FILE="$OUT/$SCRIPT.json"
printf '{"script":"%s","controls":["SC-10"],"packVersion":"1.0.0","environment":"%s","startedAt":"%s","finishedAt":"%s","outcome":"%s","checks":[%s]}\n' \
  "$SCRIPT" "$ENVIRONMENT" "$STARTED" "$FINISHED" "$OUTCOME" "$CHECKS" > "$FILE"
SHA=$(sha256sum "$FILE" | awk '{print $1}')
echo "$SHA  $SCRIPT.json" > "$FILE.sha256"
echo "$SCRIPT SC-10 $OUTCOME evidence=$FILE sha256=$SHA"
[ "$OUTCOME" = FAIL ] && exit 1 || exit 0
