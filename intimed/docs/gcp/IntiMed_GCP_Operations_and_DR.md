# IntiMed_GCP – operations, high availability and disaster recovery (Google Cloud me-central1, Doha)

## 1. Availability design

| Layer | Mechanism | Failure covered | Recovery |
|---|---|---|---|
| Platform pod | Single-active StatefulSet (`intimed-0`) on a **regional PD-SSD** (synchronously replicated across zones a and b); regional GKE control plane across a/b/c | Node or zone loss | The pod is rescheduled to the other zone and the regional disk is force-attached there (drill D1, target RTO ≤ 5 min) |
| Why single-active | Rate limits, the event bus and the installation PKI live in-process; two active pods would split them | – | Vertical scaling (n2-standard-8 nodes); horizontal scale-out is a future architecture item |
| Database | Cloud SQL for PostgreSQL 16, **REGIONAL HA** (PROD), synchronous replication to a standby in another zone | Primary or zone failure | Automatic failover ≈ 60 s; the platform reconnects (drill D2) |
| Data loss | Automated backups + PITR (35 backups PROD / 14 UAT), **backup location me-central1** | Corruption, operator error | Clone to a new instance at a point in time (drill D3), repoint `database.host`, redeploy |
| Evidence & audit | GCS buckets with a retention policy (7 years in PROD, **locked** after acceptance), CMEK, create-only uploads (`ifGenerationMatch=0`) every 5 min by `BlobArchiver` | Volume loss, tampering | Read-only archive; the audit chain can be re-verified from the segments |
| Keys | Cloud KMS HSM key ring (keys cannot be deleted immediately – destruction is scheduled, 30 days by default) and Secret Manager with versioning | Deletion, misuse | Restore a destroyed-scheduled key version; re-enable a disabled secret version; break-glass copy of the master key (§4) |
| Ingress | Regional external Application Load Balancer (Envoy proxies spread across zones); internal passthrough NLB for devices | Zone loss | Automatic |
| Sites | HA VPN (2 tunnels per site on two gateway interfaces, BGP, 99.99 % SLA); gateways buffer encrypted (store-and-forward) | Link or tunnel loss | BGP fails over; gateways flush in order on reconnection |

**Targets (PROD):**

- RTO: 5 minutes for zone loss, 30 minutes for a database restore.
- RPO: 0 for zone loss (regional PD and Cloud SQL HA are synchronous), ≤ 5 minutes for PITR.

Measure them with the drills; the SLOs are in [IntiMed_GCP_SLO_and_Runbooks.md](IntiMed_GCP_SLO_and_Runbooks.md).

**Cross-region DR:** the only other GCC Google region is me-central2 (Dammam, Saudi Arabia). Replicating there would
move health data out of Qatar and needs an explicit regulator / customer decision (Manual guide M-01 step 4). Without
that decision, DR stays inside Doha:

- three zones;
- PITR backups located in `me-central1`;
- the retention-locked archive;
- infrastructure as code, so a full rebuild takes about 45 minutes plus restore time.

## 2. DR drills (`deploy/gcp/scripts/dr-drill.sh`)

| Drill | What it does | Pass criteria |
|---|---|---|
| D1 zone/node loss | Drains the node running `intimed-0`; measures the time to Ready on the other zone | Ready ≤ 300 s; no data loss; devices reconnect (gateways flush) |
| D2 database failover | `gcloud sql instances failover` (PROD / REGIONAL only; skipped on ZONAL UAT) | Platform healthy (`sdk smoke`) ≤ 180 s after failover |
| D3 restore | `gcloud sql instances clone --point-in-time` 15 minutes back, to `<instance>-drill-<ts>` | Clone created; `sdk phi-check` against it PASS; row counts plausible; clone deleted afterwards |
| D4 archive | Counts objects in the evidence / audit buckets and reads `retention_policy.isLocked` | Latest evidence packs present; audit segments contiguous; PROD: locked = true |

Cadence: quarterly in UAT and twice a year in PROD (change window). The JSON result (`dr-drill-gcp-<env>-<ts>.json`) is
kept as the operations record.

## 3. Backup verification

- **Cloud SQL:** daily automated backups plus write-ahead logs for PITR. D3 is the verification.
- **Platform volume:** contains the PKI (wrapped keys), evidence packs (also archived) and the SIEM file (also archived).
  Snapshot it before upgrades:

  ```bash
  kubectl -n intimed get pvc data-intimed-0 -o jsonpath='{.spec.volumeName}'
  ```

  ```bash
  gcloud compute disks snapshot <pv-disk-name> --region me-central1 --storage-location me-central1 --snapshot-names intimed-pre-upgrade-$(date +%Y%m%d)
  ```

  Always pass `--storage-location me-central1`. The default is a multi-region location, which would move data out of Qatar.
  The organisation location policy blocks this, but be explicit anyway.

## 4. Master key break-glass

The master key (Secret Manager secret `intimed-master-key`) is the root key-encryption key. Losing it makes every tenant
data key, and so all PHI, unrecoverable.

1. After `bootstrap-secrets.sh`, a Secret Manager Admin exports the value **once** into two sealed envelopes (or two
   HSM-backed offline stores) held by two custodians:

   ```bash
   gcloud secrets versions access latest --secret intimed-master-key
   ```

2. Never store it in a ticket, chat or e-mail. Record the custody in the key register.
3. Never *destroy* a version of `intimed-master-key`. Disabling a version is reversible:

   ```bash
   gcloud secrets versions enable <version> --secret intimed-master-key
   ```

4. The CMEK key that encrypts Secret Manager, Cloud SQL, GCS, Artifact Registry and GKE secrets is protected by
   `prevent_destroy` in Terraform. Destroying a key version is scheduled (30 days) and can be cancelled with
   `gcloud kms keys versions restore`.
5. Rotation: the master key wraps tenant data keys, so rotating it means re-wrapping them. That is a planned maintenance
   operation and is not automated in this release. The CMEK key rotates automatically every 90 days; older versions stay
   enabled for decryption.

## 5. Upgrades and maintenance

- **GKE.** REGULAR release channel. Automatic upgrades only in the maintenance window (Friday 01:00–05:00 Asia/Qatar).
  Use maintenance exclusions around go-lives.
- **IntiMed.** Release pipeline: signed and attested image, UAT gates, approval, PROD. A Helm rolling update of the single
  pod causes about 1–2 minutes of console unavailability; devices retry (MLLP) or buffer (gateways). Schedule it in the
  window.
- **Database.** Cloud SQL maintenance window Saturday 01:00 Asia/Qatar (Friday 22:00 UTC), stable update track.
  Minor versions are applied automatically.
- **Certificates.**
  - The platform's internal server certificate renews 30 days before expiry, on restart.
  - Device certificates renew via rotation tokens.
  - The console's public certificate is replaced in the `intimed-console-tls` secret (Setup Guide §8 step 5); the Gateway
    picks it up.
  - The Cloud SQL server CA is valid for 10 years. Re-run `render-values.sh` + `deploy.sh` after a CA rotation.

## 6. Access model

| Role (Google group) | Google Cloud IAM | Kubernetes | IntiMed |
|---|---|---|---|
| Platform SRE | Editor-like custom role on the project; `container.admin`; time-bound elevation via Privileged Access Manager | cluster-admin via IAM | PLATFORM_SRE |
| SOC | `logging.viewer`, `securitycenter.findingsViewer`, Pub/Sub subscriber on the SIEM topic | `container.viewer` | SECURITY_ANALYST |
| Device administrators | – | – | DEVICE_ADMIN |
| Auditors | `viewer`, `iam.securityReviewer` | `container.viewer` | AUDITOR |
| Release pipeline | `sa-intimed-deploy-<env>` via Workload Identity Federation (no keys) | `container.developer` | – |

The cluster has no client certificates or basic auth, no public IP endpoint (IAM-authenticated DNS endpoint only) and no
SSH to nodes (private nodes, no external IPs, OS Login). Changes go through the pipelines.

## 7. Logging and data residency of operational data

- All project logs go to the log bucket `intimed-<env>` in `me-central1` (90 days in PROD). The `_Default` sink is
  disabled; the `_Required` bucket (admin activity audit logs, 400 days) is Google-managed and global.
- The SIEM stream (`intimed.siem`, masked, hash-chained) is routed to a Pub/Sub topic whose message storage policy allows
  only `me-central1`. Connect your SIEM with a pull subscription.
- Managed Prometheus stores metrics in Google Cloud Monitoring (Monarch). Metric labels never carry PHI: the platform
  exposes counters and timings only.
