#!/usr/bin/env bash
# Step 1 – checks and one-time project preparation before the first `terraform apply` in me-central1 (Doha):
# tools, login, APIs, Google service agents (needed by the CMEK grants), machine types per zone, CPU quota,
# Cloud HSM availability, Cloud SQL tier, GKE versions; disables the _Default log sink (logs go to the Doha bucket).
# Usage: ENV=uat PROJECT=<project-id> deploy/gcp/scripts/preflight.sh
source "$(dirname "$0")/common.sh"
need gcloud terraform helm kubectl jq openssl
P=$(project); [ -n "$P" ] || { echo "set PROJECT=<project-id>" >&2; exit 2; }
VARS="$TF_DIR/envs/$ENV.tfvars"
log "Account / project"
gcloud config list --format='table(core.account,core.project)'
gcloud projects describe "$P" --format='value(projectId,projectNumber,lifecycleState)'
log "Enable APIs"
gcloud services enable --project "$P" compute.googleapis.com container.googleapis.com sqladmin.googleapis.com \
  servicenetworking.googleapis.com secretmanager.googleapis.com cloudkms.googleapis.com artifactregistry.googleapis.com \
  storage.googleapis.com logging.googleapis.com monitoring.googleapis.com pubsub.googleapis.com dns.googleapis.com \
  binaryauthorization.googleapis.com containeranalysis.googleapis.com networksecurity.googleapis.com \
  orgpolicy.googleapis.com billingbudgets.googleapis.com cloudresourcemanager.googleapis.com iam.googleapis.com \
  iamcredentials.googleapis.com sts.googleapis.com gkehub.googleapis.com
log "Google service agents (CMEK grants in security.tf reference them)"
for s in sqladmin.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com pubsub.googleapis.com container.googleapis.com; do
  gcloud beta services identity create --service "$s" --project "$P" --format='value(email)' || true
done
gcloud storage service-agent --project "$P" >/dev/null && echo "ok  storage service agent"
log "Machine types in $REGION zones"
for mt in $(grep -E '^(system_machine_type|app_machine_type)' "$VARS" | sed -E 's/.*"(.*)"/\1/'); do
  for z in a b c; do
    if gcloud compute machine-types describe "$mt" --zone "$REGION-$z" --project "$P" --format='value(name)' >/dev/null 2>&1; then echo "ok  $mt $REGION-$z"; else echo "MISSING $mt $REGION-$z"; fi
  done
done
log "Regional quotas ($REGION)"
gcloud compute regions describe "$REGION" --project "$P" --format=json | jq -r '.quotas[] | select(.metric|test("^(CPUS|N2_CPUS|E2_CPUS|SSD_TOTAL_GB|IN_USE_ADDRESSES|STATIC_ADDRESSES|INTERNAL_ADDRESSES)$")) | "\(.metric)\t\(.usage)/\(.limit)"'
log "Cloud KMS HSM protection level in $REGION"
if gcloud kms locations describe "$REGION" --project "$P" --format=json | jq -e '.metadata.hsmAvailable == true' >/dev/null; then echo "ok  HSM available"
else echo "HSM NOT available – set kms_protection_level = \"SOFTWARE\" in envs/$ENV.tfvars and record the deviation"; fi
log "Cloud SQL tiers (PostgreSQL 16)"
TIER=$(grep -E '^sql_tier' "$VARS" | sed -E 's/.*"(.*)"/\1/'); echo "configured: $TIER (custom tiers are valid when vCPU/RAM fit the Enterprise edition rules)"
gcloud sql flags list --database-version=POSTGRES_16 --project "$P" --format='value(name)' | grep -q '^cloudsql.enable_pgaudit$' && echo "ok  pgaudit flag supported"
log "GKE versions (REGULAR channel)"
gcloud container get-server-config --region "$REGION" --project "$P" --format=json | jq -r '.channels[] | select(.channel=="REGULAR") | "default: \(.defaultVersion)"'
log "_Default log sink"
gcloud logging sinks update _Default --disabled --project "$P" >/dev/null 2>&1 && echo "disabled _Default (all logs go to the Doha log bucket via the Terraform sink)" || echo "could not disable _Default – do it after terraform apply"
echo; echo "Preflight finished – review the output above before terraform apply."
