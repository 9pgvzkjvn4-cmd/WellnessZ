#!/usr/bin/env bash
# IM-E08-BL-005 – HA/DR drill on GCP (UAT each quarter, PROD twice a year in a change window). Measures:
#  D1 pod/zone loss: drain the node of intimed-0; regional PD re-attaches in the other zone (target RTO ≤ 5 min).
#  D2 database failover: `gcloud sql instances failover` (REGIONAL HA only), time until the platform is healthy.
#  D3 restore: point-in-time clone of Cloud SQL to a new instance (backup verification) – delete it afterwards.
#  D4 archive: evidence packs and audit segments present in the retention-locked GCS buckets.
# Results go to dr-drill-gcp-<env>-<date>.json. Usage: ENV=uat deploy/gcp/scripts/dr-drill.sh [D1 D2 D3 D4]
source "$(dirname "$0")/common.sh"
need gcloud kubectl jq
P=$(project); SQL=$(tfo sql_instance); OUT="$ROOT/dr-drill-gcp-$ENV-$(date -u +%Y%m%dT%H%M%SZ).json"
DRILLS="${*:-D1 D2 D3 D4}"; echo '{}' > "$OUT"; creds
rec() { jq --arg k "$1" --arg v "$2" '. + {($k): $v}' "$OUT" > "$OUT.t" && mv "$OUT.t" "$OUT"; }
wait_ready() { local t0=$1; until kubectl get pod intimed-0 -n "$NS" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' | grep -q True; do sleep 5; done; echo $(( $(date +%s) - t0 )); }
for d in $DRILLS; do
  case $d in
    D1) log "D1 – node/zone loss"; node=$(kubectl get pod intimed-0 -n "$NS" -o jsonpath='{.spec.nodeName}')
        zone=$(kubectl get node "$node" -o jsonpath='{.metadata.labels.topology\.kubernetes\.io/zone}'); t0=$(date +%s)
        kubectl drain "$node" --ignore-daemonsets --delete-emptydir-data --timeout=10m >/dev/null
        s=$(wait_ready "$t0"); kubectl uncordon "$node" >/dev/null
        rec D1 "drained $node ($zone); ready after ${s}s (target 300s)"; echo "RTO ${s}s" ;;
    D2) log "D2 – Cloud SQL failover"
        if [ "$(gcloud sql instances describe "$SQL" --project "$P" --format='value(settings.availabilityType)')" != "REGIONAL" ]; then rec D2 "skipped – instance is ZONAL (UAT)"; continue; fi
        t0=$(date +%s); gcloud sql instances failover "$SQL" --project "$P" --quiet
        until kubectl exec -n "$NS" intimed-0 -c platform -- java -Xmx64m -jar /opt/intimed/intimed-platform.jar sdk smoke 127.0.0.1 >/dev/null 2>&1; do sleep 5; done
        s=$(( $(date +%s) - t0 )); rec D2 "failover; platform healthy after ${s}s"; echo "${s}s" ;;
    D3) log "D3 – point-in-time clone"; tgt="${SQL}-drill-$(date +%m%d%H%M)"; ts=$(date -u -d '-15 min' +%Y-%m-%dT%H:%M:%SZ)
        gcloud sql instances clone "$SQL" "$tgt" --point-in-time "$ts" --project "$P" --quiet
        rec D3 "cloned $ts to $tgt – verify with sdk phi-check / row counts, then: gcloud sql instances delete $tgt"; echo "cloned to $tgt" ;;
    D4) log "D4 – retention-locked archive"
        n=$(gcloud storage ls "gs://$(tfo evidence_bucket)/**" 2>/dev/null | wc -l); a=$(gcloud storage ls "gs://$(tfo audit_bucket)/**" 2>/dev/null | wc -l)
        r=$(gcloud storage buckets describe "gs://$(tfo audit_bucket)" --format='value(retention_policy.isLocked)')
        rec D4 "evidence objects=$n audit segments=$a retentionLocked=${r:-false}"; echo "evidence=$n audit=$a locked=${r:-false}" ;;
  esac
done
echo "results: $OUT"; cat "$OUT"
