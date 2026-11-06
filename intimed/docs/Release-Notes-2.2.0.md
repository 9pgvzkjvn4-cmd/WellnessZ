# IntiMed 2.2.0 – release notes (IntiMed_GCP: Google Cloud me-central1, Doha)

## Highlights

- **Deployable to Google Cloud in Doha (`me-central1`).** This adds a second cloud next to Azure Qatar Central (2.1).
  - **Terraform (`deploy/gcp/terraform`):**
    - VPC with Cloud NAT, a proxy-only subnet, private service access and private Google API DNS;
    - private regional GKE with Workload Identity, Dataplane V2, Shielded nodes, Binary Authorization, CMEK secrets encryption, the Secret Manager add-on and Managed Prometheus;
    - Cloud SQL for PostgreSQL 16: private IP only, TLS, CMEK, PITR with backups kept in Doha, REGIONAL HA in PROD;
    - Cloud KMS HSM keys: CMEK (90-day rotation) and a cosign signing key;
    - Secret Manager with Doha-only replicas, and Artifact Registry with immutable tags;
    - retention-policy GCS archive buckets;
    - Cloud Armor (OWASP CRS v3.3 + rate-based ban) behind a regional external Application Load Balancer, so TLS terminates in Doha;
    - HA VPN with BGP, and a Cloud NGFW FQDN egress allow-list;
    - the organisation location policy, a regional log bucket, a SIEM Pub/Sub topic, PromQL alert policies, a dashboard and a billing budget;
    - a GitHub Workload Identity Federation identity (no keys).
  - **Helm chart:** the `cloud: gcp` mode adds a Gateway API console with Cloud Armor, health-check and TLS policies; a Secret Manager CSI mount (files only); a regional PD-SSD StorageClass; an internal passthrough NLB for devices; GKE NetworkPolicies; and PodMonitoring. The Azure rendering is unchanged.
  - **Scripts (`deploy/gcp/scripts`):** `preflight`, `bootstrap-secrets`, `render-values`, `deploy`, `dr-drill` and `lock-retention`.
  - **Pipelines:**
    - `intimed-gcp-release.yml`: Trivy gate, cosign with the Cloud KMS HSM key, Binary Authorization attestations, UAT gates, four-eyes PROD promotion with digest copy and enforced attestation;
    - `intimed-gcp-infra.yml`.
- **Cloud adapters in the product.**
  - `GcpSecretManagerSecretStore`: tenant-bound, Doha-only replication, strict endpoint/project/region validation.
  - `GcpIdentity`: a metadata-server token with caching.
  - A create-only GCS archive in `BlobArchiver`.
  - File-based runtime secrets (`common/Secrets.java`, `INTIMED_SECRETS_DIR`, Spring `configtree`), so secrets never go through environment variables or Kubernetes Secrets on GKE.
- **Security kit.**
  - New: `16_gcp_posture_check.py` (31 checks), with compliant and violating fixtures, and `run-kit-gke.sh`.
  - `05_container_runtime_check.py` now recognises GKE Workload Identity.
- **Guides (pack name IntiMed_GCP, in `docs/gcp`):**
  - [Setup Guide](gcp/IntiMed_GCP_Setup_Guide.md);
  - [Operations, HA & DR](gcp/IntiMed_GCP_Operations_and_DR.md);
  - [SLOs & runbooks](gcp/IntiMed_GCP_SLO_and_Runbooks.md) (RB-01…RB-23);
  - [Manual Backlog Execution Guide](gcp/IntiMed_GCP_Manual_Backlog_Execution_Guide.md) (M-01…M-16, GCP-specific steps, every deferred item with its path).
- **Costs.** The [cost model](gcp/IntiMed_GCP_Cost_Model.xlsx) is built from a committed me-central1 price snapshot. Every line shows its source and a confidence label.
  - List prices: UAT ≈ USD 723 / month, PROD ≈ USD 2,211 / month (on-demand).
  - The workbook also has a 5-year TCO with 1- and 3-year committed-use discounts, and cost per connected device.
  - About USD 107 (UAT) and USD 394 (PROD) of the monthly figure comes from lines marked *estimate – verify*. Confirm them in the Google Cloud Pricing Calculator.

## Backlog status (1.0 product backlog, 117 items)

| Status | 2.1 release | 2.2 release |
|---|---:|---:|
| DELIVERED | 78 | 80 |
| PARTIAL (software complete, manual remainder) | 30 | 29 |
| DEFERRED (path in the manual guide) | 9 | 8 |

Changes:

- **IM-E08-BL-003 (GCP blueprint): DEFERRED → DELIVERED.**
- **IM-E06-BL-004 (secret stores): PARTIAL → DELIVERED.** Azure Key Vault, GCP Secret Manager and Vault now sit behind one contract.
- **IM-E06-BL-010 and IM-E08-BL-010** stay PARTIAL. Only the private-cloud parts remain (M-13).
- **IM-E08-BL-004 (private cloud)** stays DEFERRED (M-13).

The 2.0 enhancement backlog is unchanged.

## Verification

| Check | Result |
|---|---|
| Maven tests (unit, integration, architecture) | 138 / 138 pass (new: `GcpAdaptersTest` 4) |
| Terraform GCP | `fmt` and `validate` clean; `terraform test` 3 / 3 (UAT plan, PROD plan, residency guard) with mocked providers |
| Helm | `helm lint` clean for `cloud: azure` and `cloud: gcp` (console published or not); rendered manifests pass strict Kubernetes 1.30 schema validation (CRDs of the Gateway API / GKE policies are not in the schema set) |
| File-based secrets on PostgreSQL 16 | `sdk db-bootstrap`, Flyway migration, start-up and `sdk phi-check` with secrets read only from `INTIMED_SECRETS_DIR` |
| Security kit on the 2.2.0 jar | 69 / 69 local checks pass |
| New cloud checks | 16_gcp: compliant fixture 31 / 31 PASS, violating fixture 20 FAIL. 05 on the rendered GCP chart: 12 / 12 PASS |

## Known limitations

- **Not run against a real Google Cloud project in this build.** Terraform was validated with mocked providers and Helm
  with schema checks. The first UAT run of the Setup Guide is the end-to-end acceptance.
- **Prices.** Compute, disks, networking and storage come from a me-central1 dataset, and Cloud SQL from a published Doha
  price table. Other services use global list prices and are marked *estimate – verify*. No authenticated Cloud Billing
  Catalog API call was possible from the build environment.
- **Single active pod**, as in 2.1.
- **Cross-region DR** is not configured. The only other GCC region (Dammam) is outside Qatar and needs a regulator
  decision (M-01).
