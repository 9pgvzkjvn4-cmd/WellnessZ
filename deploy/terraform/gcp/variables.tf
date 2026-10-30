variable "project_id" {
  type = string
}

variable "region" {
  type    = string
  default = "europe-west4"
}

variable "environment" {
  description = "Environment name (dev|uat|prod). Use one GCP project per environment (several resource names are project-scoped singletons) and environments/<env>.tfvars."
  type        = string
  validation {
    condition     = contains(["dev", "uat", "prod"], var.environment)
    error_message = "environment must be dev, uat or prod (\"test\" was renamed to \"uat\")."
  }
}

variable "deletion_protection" {
  description = "Deletion protection on GKE and Cloud SQL (null = on in uat/prod, off in dev). The Cloud SQL instance and KMS keys additionally carry lifecycle.prevent_destroy."
  type        = bool
  default     = null
}

variable "labels" {
  description = "Additional labels applied to every resource (provider default_labels)."
  type        = map(string)
  default     = {}
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

variable "gke_rbac_security_group" {
  description = "Google Groups for RBAC: gke-security-groups@<your-domain> (must exist in Cloud Identity / Workspace and contain the groups used in RoleBindings). null = feature off; access is then granted to users / service accounts via IAM."
  type        = string
  default     = null
  validation {
    condition     = var.gke_rbac_security_group == null || can(regex("^gke-security-groups@", coalesce(var.gke_rbac_security_group, "x")))
    error_message = "gke_rbac_security_group must be gke-security-groups@<domain>."
  }
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

variable "gke_node_locations" {
  description = "Standard mode only: zones for the node pool (null = all zones of the region). One zone keeps dev small; Autopilot is always regional."
  type        = list(string)
  default     = null
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
  validation {
    condition     = contains(["ENFORCED_BLOCK_AND_AUDIT_LOG", "DRYRUN_AUDIT_LOG_ONLY"], var.binauthz_enforcement_mode)
    error_message = "binauthz_enforcement_mode must be ENFORCED_BLOCK_AND_AUDIT_LOG or DRYRUN_AUDIT_LOG_ONLY."
  }
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
  description = "Number of automated (daily) backups retained."
  type        = number
  default     = 30
}

variable "sql_availability_type" {
  description = "REGIONAL (HA standby in another zone) or ZONAL (null = ZONAL in dev, REGIONAL in uat/prod)."
  type        = string
  default     = null
  validation {
    condition     = var.sql_availability_type == null || contains(["REGIONAL", "ZONAL"], coalesce(var.sql_availability_type, "ZONAL"))
    error_message = "sql_availability_type must be REGIONAL or ZONAL."
  }
}

variable "sql_pitr_enabled" {
  description = "Point-in-time recovery (write-ahead log archiving)."
  type        = bool
  default     = true
}

variable "sql_transaction_log_retention_days" {
  description = "PITR window in days (1-7 for ENTERPRISE, up to 35 for ENTERPRISE_PLUS)."
  type        = number
  default     = 7
}

variable "sql_iam_admin_group" {
  description = "Google group (email) granted IAM database authentication as DBA."
  type        = string
}

variable "migrations_mode" {
  description = "Must match Helm migrations.mode (app|job). Controls which identities may read the migrator secret."
  type        = string
  default     = "job"
  validation {
    condition     = contains(["app", "job"], var.migrations_mode)
    error_message = "migrations_mode must be app or job."
  }
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

# ----------------------------------------------------------------------------
# Cloud Armor
# ----------------------------------------------------------------------------
variable "cloud_armor_preview" {
  description = "Run the preconfigured WAF rules in preview (log only, no blocking) - dev rule tuning. Must be false in uat/prod (prevention)."
  type        = bool
  default     = false
}

variable "cloud_armor_adaptive_protection" {
  description = "Cloud Armor Adaptive Protection (L7 DDoS defense)."
  type        = bool
  default     = true
}

variable "github_repository" {
  description = "owner/repo allowed to use the GitHub Actions workload identity provider (empty = skip)."
  type        = string
  default     = ""
}

variable "github_environments" {
  description = "GitHub Environments whose jobs may impersonate this environment's deployer SA (null = [\"gcp-<environment>\"])."
  type        = list(string)
  default     = null
}

variable "github_image_publish_branches" {
  description = "Branches whose workflow runs (container.yml) may impersonate the deployer SA to push images. Normally only dev; uat/prod receive images by digest-preserving promotion."
  type        = list(string)
  default     = ["main"]
}
