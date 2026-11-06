# IntiMed_GCP – SLO catalogue and runbooks (IM-E08-BL-007, Google Cloud me-central1)

## SLOs (PROD)

| SLO | Target | Measured by |
|---|---|---|
| Console / API availability | 99.9 % monthly | Load balancer backend health (HealthCheckPolicy `/api/public/info`) + `up` (alert IntiMedDown, RB-01) |
| Device ingestion availability (MLLP-TLS, HTTPS mTLS) | 99.9 % monthly | Internal passthrough NLB health check, `IntiMedDown` |
| Spot result end-to-end latency | p95 ≤ 3 s | XT-10 per exchange test; `sdk soak` p95 |
| Streaming interval latency | p95 ≤ 1 s | `sdk soak` against continuous drivers |
| API latency | p95 ≤ 1 s | `IntiMedLatencyP95` (RB-04) |
| Error budget | 5xx ≤ 2 % per 10 min | `IntiMedHttp5xx` (RB-03) |
| RTO zone loss / restore | 5 min / 30 min | DR drills D1 / D3 |
| RPO | 0 (zone) / ≤ 5 min (PITR) | DR drills D2 / D3 |

## Capacity per environment

| Environment | Sizing | Tested by |
|---|---|---|
| LOCAL (MacBook) | 1 JVM | 613 msg/s idle, 430 msg/s under load (1.0 measurement) |
| UAT | 1 × n2-standard-4 app node, pod 1–2 vCPU / 3 GiB | `sdk soak` 2× projected peak before each release |
| PROD | 2–4 × n2-standard-8, pod 2–4 vCPU / 6 GiB | Soak at 2× acute-care peak (e.g. 120 msg/s for 1 h) – record p95/p99 and error rate (≤ 0.1 %) |

## Runbooks – top-20 failure modes

| Id | Failure mode | Detection | First actions |
|---|---|---|---|
| RB-01 | Platform pod down | `IntiMedDown`, Gateway backend unhealthy | `kubectl -n intimed describe pod intimed-0`; events (node, volume attach, image pull); if node/zone failed wait for reschedule (≤ 5 min); else roll back the Helm release |
| RB-02 | Restart loop | `IntiMedRestarting` | `kubectl logs --previous`; check master key / DB secret files (Secret Manager add-on mount), Cloud SQL reachability, OOM (raise memory limit) |
| RB-03 | 5xx surge | `IntiMedHttp5xx` | Dashboard: recent deploy? database? Roll back via release pipeline (previous digest) |
| RB-04 | Slow API | `IntiMedLatencyP95` | CPU/memory in the dashboard; Cloud SQL CPU/IO and Query Insights; long queries |
| RB-05 | Heap pressure | `IntiMedHeapPressure` | Heap dump via `jcmd` (contains PHI – handle as PHI); raise limit; open defect |
| RB-06 | DB pool saturated | `IntiMedDbPoolExhausted` | Cloud SQL connections/locks; failover in progress? restart pod after DB healthy |
| RB-07 | Cloud SQL failover | Cloud SQL operations log (`failover`), D2 | Verify platform reconnected (`sdk smoke` in pod); check last minutes in DLQ |
| RB-08 | Secret Manager unreachable / denied | Pod events (secrets-store-gke mount), secret store errors | Workload Identity binding (KSA annotation ↔ GSA), `secretAccessor` IAM, restricted.googleapis.com DNS zone, Cloud NGFW policy |
| RB-09 | Device certificate expired / refused | Log-based alert "security events", Security → Endpoints | Issue rotation token; re-enrol gateway |
| RB-10 | Anomaly isolation of an endpoint | Security → Alerts (AN-01…AN-08) | SOC triage; release requires re-test of bound configurations |
| RB-11 | Messages in DLQ | Queues & context, monthly report | Fix driver / mapping; replay from DLQ (idempotent) |
| RB-12 | Unmatched queue growing | Queues & context | ADT feed down? worklists missing? resolve with clinicians |
| RB-13 | Regression failed after a release | `regression.failed` event, Operations → Regression | Compare evidence packs; roll back driver / platform; re-certify |
| RB-14 | Drift alert (AN-08) | Intelligence → Drift | Biomedical check of calibration/probe; acknowledge with calibration record |
| RB-15 | Suspected breach / PHI exposure | SIEM incident (Pub/Sub stream), Security Command Center finding | Incident response plan; preserve evidence (retention-locked archive); NCSA notification per the DPIA |
| RB-16 | VPN tunnel down | HA VPN tunnel status / BGP session down, devices silent | Site network team; gateways buffer (store-and-forward) – confirm flush after recovery |
| RB-17 | Disk / volume full | Node/pod events, PVC metrics | Expand PVC (regional PD class allows expansion); archive confirms evidence uploaded before pruning |
| RB-18 | Archive upload failing | Pod log "blob archive run failed" | `storage.objectCreator` on the bucket for the app GSA; restricted.googleapis.com DNS; 412 responses are normal (already archived – uploads are create-only) |
| RB-19 | Budget exceeded | Budget alert | Billing reports by service/SKU; Cloud Logging ingestion spikes (debug logging?), Cloud Armor request volume (attack?) |
| RB-20 | Certificate on console expiring | Security kit 13 / certificate monitoring | Replace the `intimed-console-tls` secret (Setup Guide §8 step 5); the Gateway picks it up |

## On-call

The Cloud Monitoring notification channels (`alert_emails`) receive every alert policy. For PROD, add the on-call tool
(PagerDuty / webhook channel) and staff the rota (Manual guide M-11).

## Additional GCP failure modes

| Id | Failure mode | Detection | First actions |
|---|---|---|---|
| RB-21 | Pod rejected by Binary Authorization | `kubectl get events` (FailedCreate, "denied by attestor"), audit log | Deploy the pipeline-attested digest; never add a break-glass annotation without CISO approval (it is audit-logged) |
| RB-22 | Cloud Armor blocks legitimate users | 403 + `enforcedSecurityPolicy` in LB logs | Identify the rule id; tune sensitivity / exclude the signature for that path; review with the SOC |
| RB-23 | Regional PD cannot attach after zone loss | Pod Pending, `FailedAttachVolume` | GKE force-attaches the regional disk in the healthy zone; if stuck > 10 min: `gcloud compute disks describe` (replica status), cordon the failed zone |
