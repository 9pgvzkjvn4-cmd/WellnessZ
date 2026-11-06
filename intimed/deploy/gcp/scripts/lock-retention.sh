#!/usr/bin/env bash
# Manual step M-06 (irreversible) – locks the retention policy of the evidence and audit buckets (WORM). Run only after
# the retention period was confirmed by the DPO; a locked policy can never be shortened or removed.
source "$(dirname "$0")/common.sh"
need gcloud
for b in "$(tfo evidence_bucket)" "$(tfo audit_bucket)"; do
  gcloud storage buckets describe "gs://$b" --format='value(retention_policy.retentionPeriod,retention_policy.isLocked)'
  read -r -p "Type LOCK to lock the retention policy of gs://$b permanently: " a; [ "$a" = "LOCK" ] || { echo "skipped $b"; continue; }
  gcloud storage buckets update "gs://$b" --lock-retention-period
done
