# ---- Cloud KMS (Doha): CMEK for Cloud SQL, buckets, Artifact Registry, GKE secrets; HSM image-signing key.
resource "google_kms_key_ring" "ring" {
  name       = "kr-${local.base}-${random_string.suffix.result}"
  location   = var.region
  depends_on = [google_project_service.apis]
}

resource "google_kms_crypto_key" "cmek" {
  name            = "cmek-data"
  key_ring        = google_kms_key_ring.ring.id
  purpose         = "ENCRYPT_DECRYPT"
  rotation_period = "7776000s" # 90 days
  labels          = local.labels
  version_template {
    algorithm        = "GOOGLE_SYMMETRIC_ENCRYPTION"
    protection_level = var.kms_protection_level
  }
  lifecycle {
    prevent_destroy = true
  }
}

resource "google_kms_crypto_key" "cosign" {
  name     = "cosign-signing"
  key_ring = google_kms_key_ring.ring.id
  purpose  = "ASYMMETRIC_SIGN"
  labels   = local.labels
  version_template {
    algorithm        = "EC_SIGN_P256_SHA256"
    protection_level = var.kms_protection_level
  }
  lifecycle {
    prevent_destroy = true
  }
}

# Google-managed service agents that use the CMEK key (created by scripts/preflight.sh with
# `gcloud beta services identity create`; their addresses are deterministic).

data "google_storage_project_service_account" "gcs" {}

locals {
  cmek_users = {
    sql = "serviceAccount:service-${data.google_project.this.number}@gcp-sa-cloud-sql.iam.gserviceaccount.com"
    ar  = "serviceAccount:service-${data.google_project.this.number}@gcp-sa-artifactregistry.iam.gserviceaccount.com"
    sm  = "serviceAccount:service-${data.google_project.this.number}@gcp-sa-secretmanager.iam.gserviceaccount.com"
    ps  = "serviceAccount:service-${data.google_project.this.number}@gcp-sa-pubsub.iam.gserviceaccount.com"
    gcs = "serviceAccount:${data.google_storage_project_service_account.gcs.email_address}"
    gke = "serviceAccount:service-${data.google_project.this.number}@container-engine-robot.iam.gserviceaccount.com"
  }
}

resource "google_kms_crypto_key_iam_member" "cmek_users" {
  for_each      = local.cmek_users
  crypto_key_id = google_kms_crypto_key.cmek.id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = each.value
}

# ---- Secret Manager: runtime secrets (values are added by scripts/bootstrap-secrets.sh, never by Terraform),
# replicated to Doha only and encrypted with the CMEK key.
resource "google_secret_manager_secret" "runtime" {
  for_each  = toset(["intimed-master-key", "intimed-db-password", "intimed-db-admin-password"])
  secret_id = each.value
  labels    = local.labels
  replication {
    user_managed {
      replicas {
        location = var.region
        customer_managed_encryption {
          kms_key_name = google_kms_crypto_key.cmek.id
        }
      }
    }
  }
  depends_on = [google_kms_crypto_key_iam_member.cmek_users]
}


# Cloud SQL admin password: generated once, stored as a Secret Manager version (bootstrap job only).
resource "random_password" "sql_admin" {
  length  = 32
  special = false
}

resource "google_secret_manager_secret_version" "sql_admin" {
  secret      = google_secret_manager_secret.runtime["intimed-db-admin-password"].id
  secret_data = random_password.sql_admin.result
}

# ---- workload identities (no keys): app (Secret Manager, archive buckets), CI/CD (push, sign, deploy), nodes.
resource "google_service_account" "app" {
  account_id   = "sa-intimed-app-${var.environment}"
  display_name = "IntiMed platform (${var.environment}) – GKE Workload Identity"
}

resource "google_service_account" "nodes" {
  account_id   = "sa-intimed-nodes-${var.environment}"
  display_name = "IntiMed GKE nodes (${var.environment}) – minimal"
}

resource "google_project_iam_member" "nodes" {
  for_each = toset(["roles/logging.logWriter", "roles/monitoring.metricWriter", "roles/monitoring.viewer", "roles/stackdriver.resourceMetadata.writer", "roles/autoscaling.metricsWriter"])
  project  = var.project_id
  role     = each.value
  member   = "serviceAccount:${google_service_account.nodes.email}"
}

resource "google_artifact_registry_repository_iam_member" "nodes_pull" {
  location   = var.region
  repository = google_artifact_registry_repository.images.name
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.nodes.email}"
}

resource "google_service_account_iam_member" "app_wi" {
  for_each           = toset(["intimed", "intimed-dbadmin"])
  service_account_id = google_service_account.app.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[intimed/${each.value}]"
  depends_on         = [google_container_cluster.gke]
}

# Runtime secrets: read-only for the app.
resource "google_secret_manager_secret_iam_member" "app_runtime" {
  for_each  = toset(["intimed-master-key", "intimed-db-password", "intimed-db-admin-password"])
  secret_id = google_secret_manager_secret.runtime[each.value].id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.app.email}"
}

# Tenant integration secrets written by the platform: only secrets whose id starts with "im-" (IAM condition).
resource "google_project_iam_member" "app_tenant_secrets" {
  project = var.project_id
  role    = "roles/secretmanager.admin"
  member  = "serviceAccount:${google_service_account.app.email}"
  condition {
    title       = "intimed-tenant-secrets-only"
    description = "Platform-managed tenant secrets (im-<hash>) only"
    expression  = "resource.name.startsWith(\"projects/${data.google_project.this.number}/secrets/im-\")"
  }
}

# Archive buckets: create objects only (no delete, no overwrite – retention policy + ifGenerationMatch=0).
resource "google_storage_bucket_iam_member" "app_archive" {
  for_each = google_storage_bucket.archive
  bucket   = each.value.name
  role     = "roles/storage.objectCreator"
  member   = "serviceAccount:${google_service_account.app.email}"
}

# ---- Binary Authorization: only signed IntiMed images (attestor backed by the HSM cosign key) + GKE system images.
resource "google_container_analysis_note" "release" {
  name = "intimed-release-${var.environment}"
  attestation_authority {
    hint {
      human_readable_name = "IntiMed release pipeline attestation"
    }
  }
  depends_on = [google_project_service.apis]
}

resource "google_binary_authorization_attestor" "release" {
  name = "intimed-release-${var.environment}"
  attestation_authority_note {
    note_reference = google_container_analysis_note.release.name
    public_keys {
      id = "//cloudkms.googleapis.com/v1/${google_kms_crypto_key.cosign.id}/cryptoKeyVersions/1"
      pkix_public_key {
        public_key_pem      = data.google_kms_crypto_key_version.cosign.public_key[0].pem
        signature_algorithm = "ECDSA_P256_SHA256"
      }
    }
  }
}

data "google_kms_crypto_key_version" "cosign" {
  crypto_key = google_kms_crypto_key.cosign.id
}

resource "google_binary_authorization_policy" "policy" {
  global_policy_evaluation_mode = "ENABLE"
  admission_whitelist_patterns {
    name_pattern = "gke.gcr.io/*"
  }
  default_admission_rule {
    # UAT: same rule in dry-run (violations only audit-logged) · PROD: enforced.
    evaluation_mode         = "REQUIRE_ATTESTATION"
    enforcement_mode        = var.binauthz_enforce ? "ENFORCED_BLOCK_AND_AUDIT_LOG" : "DRYRUN_AUDIT_LOG_ONLY"
    require_attestations_by = [google_binary_authorization_attestor.release.name]
  }
}
