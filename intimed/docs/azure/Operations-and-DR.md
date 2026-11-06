# IntiMed on Azure Qatar Central – operations, high availability and disaster recovery

## 1. Availability design

| Layer | Mechanism | Failure covered | Recovery |
|---|---|---|---|
| Platform pod | Single-active StatefulSet (`intimed-0`) on a **Premium ZRS** volume; AKS nodes in zones 1–3 | Node or zone loss | Pod is rescheduled to another zone with its volume (drill D1, target RTO ≤ 5 min) |
| Why single-active | Rate limits, event bus and the installation PKI live in-process; two active pods would split them | – | Vertical scaling (D8s_v5 nodes); horizontal scale-out is a future architecture item |
| Database | PostgreSQL Flexible 16, **zone-redundant HA** (PROD), synchronous standby | Primary / zone failure | Automatic failover ≈ 60–120 s; platform reconnects (drill D2) |
| Data loss | Point-in-time restore, 35 days (PROD) / 14 days (UAT), in-region | Corruption, operator error | Restore to a new server (drill D3), repoint `database.host`, redeploy |
| Evidence & audit | Immutable Blob containers (ZRS, WORM, 7 years PROD) written every 5 min by `BlobArchiver` | Volume loss, tampering | Read-only archive; audit chain re-verifiable from segments |
| Keys | Key Vault Premium, soft delete 90 days, purge protection | Deletion, misuse | Recover soft-deleted objects; break-glass master-key copy (§4) |
| Ingress | Application Gateway WAF v2 across zones; internal Standard LBs (zone-redundant) | Zone loss | Automatic |
| Sites | VPN Gateway VpnGw1AZ (zone-redundant); gateways buffer encrypted (store-and-forward) | Link loss | Gateways flush in order on reconnection |

**Targets (PROD):** RTO 5 minutes for zone loss and 30 minutes for database restore; RPO 0 for zone loss (synchronous HA)
and ≤ 5 minutes for PITR. Measure them with the drills; the SLOs are in SLO-and-Runbooks.md.

**Cross-region DR:** Qatar Central has no paired region. Replication to another country would move health data out of
Qatar and needs an explicit regulator / customer decision (Manual guide M-01 step 4). Without that decision, DR stays
in-region:

- three availability zones;
- PITR backups;
- the immutable archive;
- infrastructure as code, so a full rebuild takes about 45 minutes plus restore time.

## 2. DR drills (`deploy/azure/scripts/dr-drill.sh`)

| Drill | What it does | Pass criteria |
|---|---|---|
| D1 zone/node loss | Drains the node running `intimed-0`; measures time to Ready on another node/zone | Ready ≤ 300 s; no data loss; devices reconnect (gateways flush) |
| D2 database failover | `az postgres flexible-server restart --failover Forced` | Platform healthy (`sdk smoke`) ≤ 180 s after failover |
| D3 restore | PITR restore 15 minutes back to `<server>-drill-<ts>` | Server restored; `sdk phi-check` against it PASS; row counts plausible; drill server deleted afterwards |
| D4 archive | Counts blobs in `evidence` / `audit` | Latest evidence packs present; audit segments contiguous (offsets) |

Cadence: quarterly in UAT and twice a year in PROD (change window). The JSON result is kept as the operations record.

## 3. Backup verification

- **PostgreSQL:** automated daily backups plus WAL. D3 is the verification.
- **Platform volume:** contains the PKI (wrapped keys), evidence packs (also archived) and the SIEM file (also archived).
  Take an AKS volume snapshot before upgrades:

  ```bash
  kubectl -n intimed get pvc data-intimed-0
  ```

  ```bash
  az snapshot create --source <disk-id> --sku Standard_ZRS
  ```

## 4. Master key break-glass

The master key (Key Vault secret `intimed-master-key`) is the root key-encryption key. Losing it makes every tenant data
key, and so all PHI, unrecoverable.

1. After `bootstrap-secrets.sh`, a Key Vault Administrator exports the value **once** into two sealed envelopes (or two
   HSM-backed offline stores) held by two custodians:

   ```bash
   az keyvault secret show --vault-name <kv> -n intimed-master-key --query value -o tsv
   ```

2. Never store it in a ticket, chat or e-mail. Record the custody in the key register.
3. Keep purge protection on. A deleted secret is recoverable for 90 days:

   ```bash
   az keyvault secret recover
   ```

4. Rotation: the master key wraps tenant data keys, so rotating it means re-wrapping them. That is a planned maintenance
   operation and is not automated in this release.

## 5. Upgrades and maintenance

- **AKS.** Automatic patch-version upgrades and weekly node-image upgrades in the maintenance window (Friday 01:00–05:00
  Asia/Qatar).
- **IntiMed.** Release pipeline: signed image, UAT gates, approval, PROD. A Helm rolling update of the single pod causes
  about 1–2 minutes of console unavailability; devices retry (MLLP) or buffer (gateways). Schedule it in the window.
- **Database.** PostgreSQL maintenance window Friday 00:00 UTC; minor versions are automatic.
- **Certificates.**
  - The platform's internal server certificate renews 30 days before expiry, on restart.
  - Device certificates renew via rotation tokens.
  - The console's public certificate is renewed in Key Vault; Application Gateway picks up the new version.

## 6. Access model

| Role (Entra group) | Azure | Kubernetes | IntiMed |
|---|---|---|---|
| Platform SRE | Contributor on RG (PIM, time-bound) | AKS RBAC Cluster Admin (PIM) | PLATFORM_SRE |
| SOC | Reader + Sentinel Responder | AKS RBAC Reader | SECURITY_ANALYST |
| Device administrators | – | – | DEVICE_ADMIN |
| Auditors | Reader | AKS RBAC Reader | AUDITOR |

The cluster has no local accounts and no SSH to nodes. Operators use Entra RBAC; changes go through the pipelines.
