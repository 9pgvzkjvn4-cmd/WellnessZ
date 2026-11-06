#!/usr/bin/env bash
# Step 4 – adds the first version of the runtime secrets (idempotent): platform master key (root KEK, 32 random bytes)
# and application DB password. Terraform created the empty secrets (Doha-only replication, CMEK); the values are
# generated locally and piped to Secret Manager – never printed, written to disk, in Terraform state or in Git.
# (intimed-db-admin-password already has a version created by Terraform from the Cloud SQL admin user.)
source "$(dirname "$0")/common.sh"
need gcloud openssl
P=$(project)
put_if_absent() {
  local name="$1" gen="$2"
  if gcloud secrets versions list "$name" --project "$P" --filter='state=ENABLED' --limit 1 --format='value(name)' | grep -q .; then echo "exists  $name"; return; fi
  eval "$gen" | gcloud secrets versions add "$name" --project "$P" --data-file=- --format='value(name)' >/dev/null
  echo "created $name"
}
put_if_absent intimed-master-key "openssl rand -base64 32 | tr -d '\n'"
put_if_absent intimed-db-password "openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 40"
echo "Secret Manager ($P, $REGION) ready. Back up the master key with the break-glass procedure (IntiMed_GCP_Operations_and_DR.md §4): losing it makes all PHI unrecoverable."
