#!/usr/bin/env bash
# SC-10 – encryption at rest. LOCAL: the H2 store is AES-encrypted and contains no clear-text subject identifiers.
# PostgreSQL (Azure Database for PostgreSQL / postgres profile, INTIMED_DB_URL set): every PHI-bearing document is stored
# application-encrypted (enc:v1:) and no subject identifier appears in clear text; the server-side storage encryption of
# Azure is verified by 16_azure_posture_check.py. Key material is owner-only and wrapped.
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"; OUT="${KIT_RESULTS:-$DIR/results}"; mkdir -p "$OUT"
HOME_DIR="${INTIMED_HOME:-$HOME/IntiMed-Demo-Data}"
res=PASS; d=""
DB=$(ls "$HOME_DIR"/db/*.mv.db 2>/dev/null | head -1)
if [ -n "${INTIMED_KUBE_POD:-}" ]; then
  # AKS: run the same check inside the IntiMed pod with its own credentials (sdk phi-check), counts only.
  out=$(kubectl exec -n "${INTIMED_KUBE_NS:-intimed}" "$INTIMED_KUBE_POD" -c platform -- java -Xmx128m -jar /opt/intimed/intimed-platform.jar sdk phi-check 2>/dev/null | grep '^phi-check')
  case "$out" in *result=PASS*) d="AKS/PostgreSQL: ${out#phi-check }";; *) res=FAIL; d="AKS/PostgreSQL: ${out:-phi-check did not run}";; esac
elif [ -z "$DB" ] && [ -n "${INTIMED_DB_URL:-}" ]; then
  # jdbc:postgresql://host:port/db?params  →  psql connection (password via PGPASSWORD / INTIMED_DB_PASSWORD)
  hp=$(echo "$INTIMED_DB_URL" | sed -E 's#jdbc:postgresql://([^/?]+)/([^?]+).*#\1 \2#'); host=${hp%%:*}; rest=${hp#*:}; port=${rest%% *}; db=${hp##* }
  [ "$port" = "$hp" ] && port=5432
  export PGPASSWORD="${PGPASSWORD:-${INTIMED_DB_PASSWORD:-}}"
  q() { psql "host=${host%% *} port=$port dbname=$db user=${INTIMED_DB_USER:-intimed} sslmode=${PGSSLMODE:-prefer}" -tAc "$1" 2>&1; }
  sens="'observation','sandbox-instance','template-instance','unmatched','dlq','raw','capture','worklist','shadow','subject','bed','enrolment'"
  total=$(q "select count(*) from documents where kind in ($sens)")
  plain=$(q "select count(*) from documents where kind in ($sens) and data not like 'enc:v1:%'")
  leak=$(q "select count(*) from documents where data ~ '(MRN-1002|EMP-7001)'")
  if ! [[ "$total" =~ ^[0-9]+$ ]]; then res=FAIL; d="PostgreSQL not reachable: $(echo "$total" | head -1)";
  elif [ "$plain" != "0" ] || [ "$leak" != "0" ]; then res=FAIL; d="PostgreSQL: $plain PHI documents not application-encrypted, $leak documents with clear-text identifiers";
  else d="PostgreSQL: $total PHI-bearing documents all application-encrypted (enc:v1, tenant DEK), no clear-text identifiers"; fi
elif [ -z "$DB" ]; then res=FAIL; d="database file not found"; else
  if LC_ALL=C grep -a -q -E "MRN-1002|EMP-7001|CREATE TABLE|documents" "$DB"; then res=FAIL; d="clear text found in $DB"; else d="no clear text identifiers or schema in $(basename "$DB")"; fi
fi
[ -n "${INTIMED_KUBE_POD:-}" ] || for f in "$HOME_DIR"/keys/master.key "$HOME_DIR"/pki/root-ca.key.enc; do
  [ -f "$f" ] || continue
  perm=$(stat -c %a "$f" 2>/dev/null || stat -f %Lp "$f")
  [ "$perm" = "600" ] || { res=FAIL; d="$d; $f permissions $perm"; }
done
grep -q "BEGIN" "$HOME_DIR"/pki/root-ca.key.enc 2>/dev/null && { res=FAIL; d="$d; CA key stored as clear PEM"; }
echo "  $res SC-10 encryption at rest – $d"
python3 -c 'import json,sys; json.dump({"script":sys.argv[1],"results":[{"script":sys.argv[1],"control":sys.argv[2],"check":sys.argv[3],"result":sys.argv[4],"detail":sys.argv[5]}]},open(sys.argv[6],"w"))' "10_encryption_at_rest_check" "SC-10" "encryption at rest" "$res" "${d:-}${detail:-}" "$OUT/10_encryption_at_rest_check.json"
[ "$res" = PASS ]
