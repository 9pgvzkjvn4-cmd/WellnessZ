resource "google_sql_database_instance" "main" {
  name                = "sql-${local.name}"
  region              = var.region
  database_version    = "POSTGRES_16"
  encryption_key_name = google_kms_crypto_key.cmek["cloudsql"].id
  deletion_protection = true

  settings {
    tier                        = var.sql_tier
    edition                     = var.sql_edition
    availability_type           = "REGIONAL" # HA: synchronous standby in another zone
    disk_type                   = "PD_SSD"
    disk_size                   = var.sql_disk_size_gb
    disk_autoresize             = true
    deletion_protection_enabled = true

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      start_time                     = "01:00"
      transaction_log_retention_days = 7
      backup_retention_settings {
        retained_backups = var.sql_backup_retention
        retention_unit   = "COUNT"
      }
    }

    ip_configuration {
      ipv4_enabled                                  = false
      private_network                               = google_compute_network.main.id
      ssl_mode                                      = "ENCRYPTED_ONLY"
      enable_private_path_for_google_cloud_services = true
    }

    database_flags {
      name  = "cloudsql.iam_authentication"
      value = "on"
    }
    database_flags {
      name  = "cloudsql.enable_pgaudit"
      value = "on"
    }
    database_flags {
      name  = "pgaudit.log"
      value = "ddl,role"
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
      name  = "log_checkpoints"
      value = "on"
    }
    database_flags {
      name  = "log_lock_waits"
      value = "on"
    }
    database_flags {
      name  = "log_min_duration_statement"
      value = "2000"
    }
    database_flags {
      name  = "log_temp_files"
      value = "0"
    }
    database_flags {
      name  = "log_min_messages"
      value = "warning"
    }

    password_validation_policy {
      enable_password_policy      = true
      min_length                  = 16
      complexity                  = "COMPLEXITY_DEFAULT"
      disallow_username_substring = true
    }

    insights_config {
      query_insights_enabled  = true
      record_application_tags = false
      record_client_address   = false
    }

    maintenance_window {
      day          = 7
      hour         = 2
      update_track = "stable"
    }

    user_labels = {
      application = "wellnessz"
    }
  }

  depends_on = [
    google_service_networking_connection.psa,
    google_kms_crypto_key_iam_member.cmek_agents,
  ]

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_sql_database" "wellnessz" {
  name     = "wellnessz"
  instance = google_sql_database_instance.main.name
}

# DBA access via IAM database authentication (no shared passwords)
resource "google_sql_user" "dba_group" {
  name     = var.sql_iam_admin_group
  instance = google_sql_database_instance.main.name
  type     = "CLOUD_IAM_GROUP"
}

# Application roles: passwords generated ephemerally, written with write-only
# arguments, never stored in state. Schema ownership / grants are applied by
# the bootstrap SQL (deploy/README.md "Database bootstrap").
ephemeral "random_password" "wz_app" {
  length  = 32
  special = false
}

ephemeral "random_password" "wz_migrator" {
  length  = 32
  special = false
}

resource "google_sql_user" "wz_app" {
  name                = "wz_app"
  instance            = google_sql_database_instance.main.name
  password_wo         = ephemeral.random_password.wz_app.result
  password_wo_version = var.db_password_version
}

resource "google_sql_user" "wz_migrator" {
  name                = "wz_migrator"
  instance            = google_sql_database_instance.main.name
  password_wo         = ephemeral.random_password.wz_migrator.result
  password_wo_version = var.db_password_version
}
