# ---- Cloud SQL for PostgreSQL 16 (Enterprise): private IP only, TLS required, CMEK, PITR backups stored in Doha,
# regional HA (synchronous standby in a second zone) in production.
resource "google_sql_database_instance" "pg" {
  name                = "psql-${local.base}-${random_string.suffix.result}"
  region              = var.region
  database_version    = "POSTGRES_16"
  encryption_key_name = google_kms_crypto_key.cmek.id
  root_password       = random_password.sql_admin.result
  deletion_protection = var.environment == "prod"

  settings {
    edition                     = "ENTERPRISE"
    tier                        = var.sql_tier
    availability_type           = var.sql_availability_type
    disk_type                   = "PD_SSD"
    disk_size                   = var.sql_disk_gb
    disk_autoresize             = true
    user_labels                 = local.labels
    deletion_protection_enabled = var.environment == "prod"

    ip_configuration {
      ipv4_enabled                                  = false
      private_network                               = google_compute_network.vpc.id
      ssl_mode                                      = "ENCRYPTED_ONLY"
      enable_private_path_for_google_cloud_services = false
    }

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      location                       = var.region
      start_time                     = "21:00" # 00:00 Asia/Qatar
      transaction_log_retention_days = 7
      backup_retention_settings {
        retained_backups = var.sql_backup_retention
        retention_unit   = "COUNT"
      }
    }

    maintenance_window {
      day          = 5 # Friday
      hour         = 22
      update_track = "stable"
    }

    insights_config {
      query_insights_enabled  = true
      record_application_tags = false
      record_client_address   = false
    }

    database_flags {
      name  = "log_connections"
      value = "on"
    }
    database_flags {
      name  = "log_disconnections"
      value = "on"
    }
    database_flags {
      name  = "log_min_duration_statement"
      value = "2000"
    }
    database_flags {
      name  = "cloudsql.enable_pgaudit"
      value = "on"
    }
  }

  depends_on = [google_service_networking_connection.psa, google_kms_crypto_key_iam_member.cmek_users]
}

resource "google_sql_database" "intimed" {
  name     = "intimed"
  instance = google_sql_database_instance.pg.name
}

# ---- Artifact Registry (Doha), CMEK, immutable tags, vulnerability scanning.
resource "google_artifact_registry_repository" "images" {
  location      = var.region
  repository_id = "intimed-${var.environment}"
  format        = "DOCKER"
  kms_key_name  = google_kms_crypto_key.cmek.id
  labels        = local.labels
  docker_config {
    immutable_tags = true
  }
  depends_on = [google_kms_crypto_key_iam_member.cmek_users]
}

# ---- Archive buckets (Doha): sealed evidence packs and the audit stream, WORM via retention policy, CMEK.
resource "google_storage_bucket" "archive" {
  for_each                    = toset(["evidence", "audit"])
  name                        = "${var.project_id}-intimed-${each.value}"
  location                    = upper(var.region)
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false
  labels                      = local.labels
  retention_policy {
    retention_period = var.archive_retention_days * 86400
    is_locked        = var.lock_archive_retention
  }
  encryption {
    default_kms_key_name = google_kms_crypto_key.cmek.id
  }
  soft_delete_policy {
    retention_duration_seconds = 604800
  }
  depends_on = [google_kms_crypto_key_iam_member.cmek_users]
}

# ---- Logs stay in Doha: regional log bucket + project sink for everything; the _Default sink is disabled by
# scripts/bootstrap-secrets.sh (organisation default storage location: manual guide M-01).
resource "google_logging_project_bucket_config" "logs" {
  project        = var.project_id
  location       = var.region
  bucket_id      = "intimed-${var.environment}"
  retention_days = var.log_retention_days
  description    = "IntiMed ${var.environment} – all project logs incl. the audit-chain stream (logger intimed.siem), Doha"
}

resource "google_logging_project_sink" "all_to_doha" {
  name                   = "intimed-all-logs-doha"
  destination            = "logging.googleapis.com/${google_logging_project_bucket_config.logs.id}"
  filter                 = ""
  unique_writer_identity = true
}

# Optional SIEM export (e.g. to Google SecOps or a customer SIEM): Pub/Sub topic in Doha with the audit stream only.
resource "google_pubsub_topic" "siem" {
  name                       = "intimed-siem-${var.environment}"
  kms_key_name               = google_kms_crypto_key.cmek.id
  message_retention_duration = "604800s"
  message_storage_policy {
    allowed_persistence_regions = [var.region]
  }
  depends_on = [google_kms_crypto_key_iam_member.cmek_users]
}


resource "google_logging_project_sink" "siem" {
  name                   = "intimed-siem-pubsub"
  destination            = "pubsub.googleapis.com/${google_pubsub_topic.siem.id}"
  filter                 = "resource.type=\"k8s_container\" AND resource.labels.namespace_name=\"intimed\" AND textPayload:\"intimed.siem\""
  unique_writer_identity = true
}

resource "google_pubsub_topic_iam_member" "siem_writer" {
  topic  = google_pubsub_topic.siem.id
  role   = "roles/pubsub.publisher"
  member = google_logging_project_sink.siem.writer_identity
}
