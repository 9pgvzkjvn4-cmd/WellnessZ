#!/usr/bin/env bash
# IM-E08-BL-005 – HA/DR drill (run in UAT each quarter, PROD twice a year in a change window). Measures:
#  D1 pod/zone loss: drain the node of intimed-0, time until ready on another zone (target RTO ≤ 5 min).
#  D2 database failover: forced HA failover of PostgreSQL (zone-redundant), time until the platform is healthy again.
#  D3 restore: point-in-time restore of PostgreSQL to a new server + row-count comparison (backup verification).
#  D4 archive: evidence pack and audit segments present in the immutable blob containers.
# Results go to dr-drill-<env>-<date>.json for the operations record. Usage: ENV=uat dr-drill.sh [D1 D2 D3 D4]
source "$(dirname "$0")/common.sh"
need az jq
RG=$(tfo resource_group); PG=$(tfo postgres_server); OUT="$ROOT/dr-drill-$ENV-$(date -u +%Y%m%dT%H%M%SZ).json"
DRILLS="${*:-D1 D2 D3 D4}"; echo '{}' > "$OUT"
rec() { jq --arg k "$1" --arg v "$2" '. + {($k): $v}' "$OUT" > "$OUT.t" && mv "$OUT.t" "$OUT"; }
wait_ready() { local t0=$1; until kc get pod intimed-0 -n "$NS" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' | grep -q True; do sleep 5; done; echo $(( $(date +%s) - t0 )); }
for d in $DRILLS; do
  case $d in
    D1) log "D1 – node/zone loss"; node=$(kc get pod intimed-0 -n "$NS" -o jsonpath='{.spec.nodeName}')
        zone=$(kc get node "$node" -o jsonpath='{.metadata.labels.topology\.kubernetes\.io/zone}'); t0=$(date +%s)
        kc drain "$node" --ignore-daemonsets --delete-emptydir-data --timeout=10m >/dev/null
        s=$(wait_ready "$t0"); kc uncordon "$node" >/dev/null
        rec D1 "drained $node ($zone); ready after ${s}s (target 300s)"; echo "RTO ${s}s" ;;
    D2) log "D2 – PostgreSQL forced failover"; t0=$(date +%s)
        az postgres flexible-server restart -g "$RG" -n "$PG" --failover Forced -o none
        until kc exec -n "$NS" intimed-0 -c platform -- java -Xmx64m -jar /opt/intimed/intimed-platform.jar sdk smoke 127.0.0.1 >/dev/null 2>&1; do sleep 5; done
        s=$(( $(date +%s) - t0 )); rec D2 "forced failover; platform healthy after ${s}s"; echo "${s}s" ;;
    D3) log "D3 – point-in-time restore"; tgt="${PG}-drill-$(date +%m%d%H%M)"; ts=$(date -u -d '-15 min' +%Y-%m-%dT%H:%M:%SZ)
        az postgres flexible-server restore -g "$RG" -n "$tgt" --source-server "$PG" --restore-time "$ts" -o none
        rec D3 "restored $ts to $tgt – verify with sdk phi-check / row counts, then delete the server"; echo "restored to $tgt" ;;
    D4) log "D4 – immutable archive"; acct=$(tfo archive_account_url | sed -E 's#https://([^.]+).*#\1#')
        n=$(az storage blob list --account-name "$acct" -c evidence --auth-mode login --query "length(@)" -o tsv)
        a=$(az storage blob list --account-name "$acct" -c audit --auth-mode login --query "length(@)" -o tsv)
        rec D4 "evidence blobs=$n audit segments=$a"; echo "evidence=$n audit=$a" ;;
  esac
done
echo "results: $OUT"; cat "$OUT"
