# ---------------------------------------------------------------------------
# Secret Manager - CMEK, regional replication, per-secret IAM.
# Generated DB passwords are written with write-only arguments; key material
# placeholders MUST be replaced out-of-band (see deploy/README.md):
#   openssl rand -base64 32 | gcloud secrets versions add wz-master-key --data-file=-
# ---------------------------------------------------------------------------
locals {
  secrets = {
    "wz-db-password"          = ["core"]
    "wz-master-key"           = ["core"]
    "wz-pseudonym-key"        = ["core"]
    "wz-migrator-db-password" = var.migrations_mode == "app" ? ["migrations", "core", "bff"] : ["migrations"]
    "wz-bff-client-secret"    = ["bff"]
    "wz-bff-db-password"      = ["bff"]
    "wz-bff-session-key"      = ["bff"]
    "wz-graph-client-secret"  = ["core"]
    "wz-cloudsql-server-ca"   = ["core", "bff", "migrations"]
  }
  generated_secret_values = {
    "wz-db-password"          = ephemeral.random_password.wz_app.result
    "wz-migrator-db-password" = ephemeral.random_password.wz_migrator.result
    "wz-bff-db-password"      = ephemeral.random_password.wz_bff.result
  }
  placeholder_secrets = toset(["wz-master-key", "wz-pseudonym-key", "wz-bff-client-secret", "wz-bff-session-key", "wz-graph-client-secret"])

  secret_access = merge([
    for s, owners in local.secrets : { for o in owners : "${s}/${o}" => { secret = s, owner = o } }
  ]...)
}

resource "google_secret_manager_secret" "app" {
  for_each  = local.secrets
  secret_id = each.key

  replication {
    user_managed {
      replicas {
        location = var.region
        customer_managed_encryption {
          kms_key_name = google_kms_crypto_key.cmek["secret-manager"].id
        }
      }
    }
  }

  depends_on = [google_kms_crypto_key_iam_member.cmek_agents]
}

resource "google_secret_manager_secret_version" "generated" {
  for_each               = local.generated_secret_values
  secret                 = google_secret_manager_secret.app[each.key].id
  secret_data_wo         = each.value
  secret_data_wo_version = var.db_password_version
}

resource "google_secret_manager_secret_version" "placeholder" {
  for_each               = local.placeholder_secrets
  secret                 = google_secret_manager_secret.app[each.key].id
  secret_data_wo         = "REPLACE-ME-out-of-band"
  secret_data_wo_version = 1

  lifecycle {
    # Real values are added as newer versions by operators
    ignore_changes = all
  }
}

# Cloud SQL server CA (public certificate) for sslmode=verify-ca
resource "google_secret_manager_secret_version" "sql_ca" {
  secret      = google_secret_manager_secret.app["wz-cloudsql-server-ca"].id
  secret_data = google_sql_database_instance.main.server_ca_cert[0].cert
}

resource "google_secret_manager_secret_iam_member" "access" {
  for_each  = local.secret_access
  secret_id = google_secret_manager_secret.app[each.value.secret].id
  role      = "roles/secretmanager.secretAccessor"
  member    = google_service_account.workload[each.value.owner].member
}
