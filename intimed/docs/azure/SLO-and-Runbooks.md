# IntiMed on Azure – SLO catalogue and runbooks (IM-E08-BL-007)

## SLOs (PROD)

| SLO | Target | Measured by |
|---|---|---|
| Console / API availability | 99.9 % monthly | App Gateway backend health + `up` (alert RB-01) |
| Device ingestion availability (MLLP-TLS, HTTPS mTLS) | 99.9 % monthly | Internal LB health probe, `IntiMedDown` |
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
| UAT | 1 × D4s_v5 app node, pod 1–2 vCPU / 3 GiB | `sdk soak` 2× projected peak before each release |
| PROD | 2–4 × D8s_v5, pod 2–4 vCPU / 6 GiB | Soak at 2× acute-care peak (e.g. 120 msg/s for 1 h) – record p95/p99 and error rate (≤ 0.1 %) |

## Runbooks – top-20 failure modes

| Id | Failure mode | Detection | First actions |
|---|---|---|---|
| RB-01 | Platform pod down | `IntiMedDown`, App Gateway unhealthy | `kubectl -n intimed describe pod intimed-0`; events (node, volume attach, image pull); if node/zone failed wait for reschedule (≤ 5 min); else roll back the Helm release |
| RB-02 | Restart loop | `IntiMedRestarting` | `kubectl logs --previous`; check master key / DB secrets (CSI), database reachability, OOM (raise memory limit) |
| RB-03 | 5xx surge | `IntiMedHttp5xx` | Workbook: recent deploy? database? Roll back via release pipeline (previous digest) |
| RB-04 | Slow API | `IntiMedLatencyP95` | CPU/memory in workbook; PostgreSQL CPU/IO; long queries (`log_min_duration_statement`) |
| RB-05 | Heap pressure | `IntiMedHeapPressure` | Heap dump via `jcmd` (contains PHI – handle as PHI); raise limit; open defect |
| RB-06 | DB pool saturated | `IntiMedDbPoolExhausted` | PostgreSQL connections/locks; failover in progress? restart pod after DB healthy |
| RB-07 | PostgreSQL failover | Azure Resource Health, D2 | Verify platform reconnected (`sdk smoke` in pod); check last minutes in DLQ |
| RB-08 | Key Vault unreachable | Pod events (CSI mount), secret store errors | Private endpoint / DNS; Key Vault firewall; managed-identity federation |
| RB-09 | Device certificate expired / refused | Sentinel rule, Security → Endpoints | Issue rotation token; re-enrol gateway |
| RB-10 | Anomaly isolation of an endpoint | Security → Alerts (AN-01…AN-08) | SOC triage; release requires re-test of bound configurations |
| RB-11 | Messages in DLQ | Queues & context, monthly report | Fix driver / mapping; replay from DLQ (idempotent) |
| RB-12 | Unmatched queue growing | Queues & context | ADT feed down? worklists missing? resolve with clinicians |
| RB-13 | Regression failed after a release | `regression.failed` event, Operations → Regression | Compare evidence packs; roll back driver / platform; re-certify |
| RB-14 | Drift alert (AN-08) | Intelligence → Drift | Biomedical check of calibration/probe; acknowledge with calibration record |
| RB-15 | Suspected breach / PHI exposure | Sentinel incident, Defender alert | Incident response plan; preserve evidence (immutable archive); NCSA notification per the DPIA |
| RB-16 | VPN tunnel down | VPN connection status, devices silent | Site network team; gateways buffer (store-and-forward) – confirm flush after recovery |
| RB-17 | Disk / volume full | Node/pod events, PVC metrics | Expand PVC (storage class allows expansion); archive confirms evidence uploaded before pruning |
| RB-18 | Archive upload failing | Pod log "blob archive run failed" | Storage private endpoint/DNS; role assignment Storage Blob Data Contributor; immutability policy locked? (uploads are new blobs only) |
| RB-19 | Budget exceeded | Budget alert | Cost analysis by service; Log Analytics ingestion spikes (debug logging?) |
| RB-20 | Certificate on console expiring | Key Vault near-expiry event | Renew certificate in Key Vault; Application Gateway picks up new version |

## On-call

The action group `ag-intimed-<env>-qc` mails the configured addresses. For PROD, connect it to the on-call tool (webhook
receiver) and staff the rota (Manual guide M-11).
