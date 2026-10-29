variable "project_id" {
  type = string
}

variable "region" {
  type    = string
  default = "europe-west4"
}

variable "environment" {
  type    = string
  default = "prod"
  validation {
    condition     = contains(["dev", "test", "prod"], var.environment)
    error_message = "environment must be dev, test or prod."
  }
}

variable "name_prefix" {
  type    = string
  default = "wellnessz"
}

# ----------------------------------------------------------------------------
# Network
# ----------------------------------------------------------------------------
variable "subnet_cidr" {
  type    = string
  default = "10.60.0.0/20"
}

variable "pods_cidr" {
  type    = string
  default = "10.64.0.0/14"
}

variable "services_cidr" {
  type    = string
  default = "10.60.16.0/20"
}

variable "master_cidr" {
  description = "Control-plane CIDR (/28) for the private cluster."
  type        = string
  default     = "172.16.0.32/28"
}

variable "psa_range" {
  description = "Private Service Access range for Cloud SQL (keep in sync with values-gcp.yaml networkPolicy.database.cidrs)."
  type = object({
    address       = string
    prefix_length = number
  })
  default = {
    address       = "10.60.64.0"
    prefix_length = 20
  }
}

# ----------------------------------------------------------------------------
# GKE
# ----------------------------------------------------------------------------
variable "gke_autopilot" {
  description = "true = GKE Autopilot, false = Standard with a hardened node pool."
  type        = bool
  default     = true
}

variable "gke_private_endpoint" {
  description = "Disable the public control-plane endpoint (CI must run inside the VPC or use Connect Gateway)."
  type        = bool
  default     = false
}

variable "gke_master_authorized_networks" {
  description = "CIDRs allowed to reach the control plane."
  type = list(object({
    cidr_block   = string
    display_name = string
  }))
  default = []
}

variable "gke_release_channel" {
  type    = string
  default = "REGULAR"
}

variable "gke_node_machine_type" {
  description = "Standard mode only."
  type        = string
  default     = "e2-standard-4"
}

variable "gke_node_min" {
  type    = number
  default = 1
}

variable "gke_node_max" {
  type    = number
  default = 3
}

variable "k8s_namespace" {
  type    = string
  default = "wellnessz"
}

variable "helm_release_name" {
  type    = string
  default = "wellnessz"
}

variable "binauthz_enforcement_mode" {
  description = "ENFORCED_BLOCK_AND_AUDIT_LOG or DRYRUN_AUDIT_LOG_ONLY (start in dry-run, then enforce)."
  type        = string
  default     = "DRYRUN_AUDIT_LOG_ONLY"
}

# ----------------------------------------------------------------------------
# Cloud SQL
# ----------------------------------------------------------------------------
variable "sql_tier" {
  type    = string
  default = "db-custom-4-16384"
}

variable "sql_edition" {
  type    = string
  default = "ENTERPRISE"
}

variable "sql_disk_size_gb" {
  type    = number
  default = 100
}

variable "sql_backup_retention" {
  description = "Number of automated backups retained."
  type        = number
  default     = 30
}

variable "sql_iam_admin_group" {
  description = "Google group (email) granted IAM database authentication as DBA."
  type        = string
}

variable "db_password_version" {
  description = "Increment to regenerate wz_app / wz_migrator passwords (write-only; follow rotation runbook)."
  type        = number
  default     = 1
}

# ----------------------------------------------------------------------------
# Logging / CI
# ----------------------------------------------------------------------------
variable "log_retention_days" {
  type    = number
  default = 365
}

variable "github_repository" {
  description = "owner/repo allowed to use the GitHub Actions workload identity provider (empty = skip)."
  type        = string
  default     = ""
}
