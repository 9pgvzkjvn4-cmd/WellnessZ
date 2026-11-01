# Capacity model (local environment)

Measured by `PlatformIntegrationTest.pipelineThroughputForTheCapacityModel` on the build machine (4 vCPU, single JVM,
encrypted H2): **2,000 HL7 PCD-01 messages in 3.26 s = 613 msg/s** on an idle machine, and **430 msg/s** while video
encoding and a second platform instance shared the CPUs – through the full pipeline (parse → normalise → associate →
validate → route into a template → encrypted persistence, two adapters rendered). The NFR target is 500 msg/s per tenant
with horizontal scaling; one local node reaches it when not contended, and the cloud releases scale engine replicas
horizontally. Re-run on your MacBook with `./mvnw verify` (the value is written to `$INTIMED_HOME/perf.txt` of the test
run and printed as `CAPACITY:`).

| Resource | Local sizing | Driver |
|---|---|---|
| CPU | 2–4 cores | parsing + AES-GCM + adapter rendering per message |
| Memory | 1–2 GB heap | caches (tenant DEKs, drivers, templates) are small |
| Storage | ~5–10 KB per observation (encrypted JSON + raw payload + template instance) | 1 M observations ≈ 10 GB |
| Network | loopback mTLS: 10–60 ms end-to-end per spot message (XT-10) | TLS session reuse on gateways |

Per-tenant quota defaults: 500 msg/s (burst 1,000), per endpoint 50 msg/s (burst 100). Scale-out in the cloud releases:
stateless engine replicas behind the ingress, partitioned bus per tenant, managed PostgreSQL with read replicas;
soak tests at 2× projected acute-care peak run in UAT (IM-E01-BL-030, deferred part).
