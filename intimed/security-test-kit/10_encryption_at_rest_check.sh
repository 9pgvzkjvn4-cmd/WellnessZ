#!/usr/bin/env bash
# SC-10 – encryption at rest (LOCAL): the H2 store is AES-encrypted and contains no clear-text subject identifiers;
# key material is owner-only and wrapped.
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"; OUT="${KIT_RESULTS:-$DIR/results}"; mkdir -p "$OUT"
HOME_DIR="${INTIMED_HOME:-$HOME/IntiMed-Demo-Data}"
res=PASS; d=""
DB=$(ls "$HOME_DIR"/db/*.mv.db 2>/dev/null | head -1)
if [ -z "$DB" ]; then res=FAIL; d="database file not found"; else
  if LC_ALL=C grep -a -q -E "MRN-1002|EMP-7001|CREATE TABLE|documents" "$DB"; then res=FAIL; d="clear text found in $DB"; else d="no clear text identifiers or schema in $(basename "$DB")"; fi
fi
for f in "$HOME_DIR"/keys/master.key "$HOME_DIR"/pki/root-ca.key.enc; do
  [ -f "$f" ] || continue
  perm=$(stat -c %a "$f" 2>/dev/null || stat -f %Lp "$f")
  [ "$perm" = "600" ] || { res=FAIL; d="$d; $f permissions $perm"; }
done
grep -q "BEGIN" "$HOME_DIR"/pki/root-ca.key.enc 2>/dev/null && { res=FAIL; d="$d; CA key stored as clear PEM"; }
echo "  $res SC-10 encryption at rest – $d"
python3 -c 'import json,sys; json.dump({"script":sys.argv[1],"results":[{"script":sys.argv[1],"control":sys.argv[2],"check":sys.argv[3],"result":sys.argv[4],"detail":sys.argv[5]}]},open(sys.argv[6],"w"))' "10_encryption_at_rest_check" "SC-10" "encryption at rest" "$res" "${d:-}${detail:-}" "$OUT/10_encryption_at_rest_check.json"
[ "$res" = PASS ]
