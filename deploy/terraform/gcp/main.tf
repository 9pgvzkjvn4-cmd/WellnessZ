data "google_project" "this" {
  project_id = var.project_id
}

locals {
  name           = "${var.name_prefix}-${var.environment}"
  is_prod        = var.environment == "prod"
  protect        = coalesce(var.deletion_protection, var.environment != "dev")
  sql_ha         = coalesce(var.sql_availability_type, var.environment == "dev" ? "ZONAL" : "REGIONAL")
  github_envs    = coalesce(var.github_environments, ["gcp-${var.environment}"])
  project_number = data.google_project.this.number
  wi_pool        = "${var.project_id}.svc.id.goog"
  # Mirrors the chart's "wellnessz.fullname" helper (release "wellnessz" -> KSA "wellnessz-core")
  ksa_prefix = strcontains(var.helm_release_name, "wellnessz") ? var.helm_release_name : "${var.helm_release_name}-wellnessz"
}

resource "google_project_service" "apis" {
  for_each = toset([
    "artifactregistry.googleapis.com",
    "binaryauthorization.googleapis.com",
    "cloudkms.googleapis.com",
    "compute.googleapis.com",
    "container.googleapis.com",
    "containeranalysis.googleapis.com",
    "containerscanning.googleapis.com",
    "containersecurity.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "logging.googleapis.com",
    "monitoring.googleapis.com",
    "secretmanager.googleapis.com",
    "servicenetworking.googleapis.com",
    "sqladmin.googleapis.com",
    "sts.googleapis.com",
  ])
  service            = each.value
  disable_on_destroy = false
}

# Service agents (needed for CMEK grants before first use)
resource "google_project_service_identity" "agents" {
  provider = google-beta
  for_each = toset([
    "sqladmin.googleapis.com",
    "secretmanager.googleapis.com",
    "artifactregistry.googleapis.com",
    "container.googleapis.com",
  ])
  project    = var.project_id
  service    = each.value
  depends_on = [google_project_service.apis]
}

# ---------------------------------------------------------------------------
# Audit logging: Data Access logs for services that touch PHI or secrets
# ---------------------------------------------------------------------------
resource "google_project_iam_audit_config" "data_access" {
  for_each = toset([
    "secretmanager.googleapis.com",
    "cloudkms.googleapis.com",
    "cloudsql.googleapis.com",
    "container.googleapis.com",
    "iam.googleapis.com",
  ])
  project = var.project_id
  service = each.value
  audit_log_config {
    log_type = "ADMIN_READ"
  }
  audit_log_config {
    log_type = "DATA_READ"
  }
  audit_log_config {
    log_type = "DATA_WRITE"
  }
}

resource "google_logging_project_bucket_config" "default" {
  project        = var.project_id
  location       = "global"
  bucket_id      = "_Default"
  retention_days = var.log_retention_days
}
