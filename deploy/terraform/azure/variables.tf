# ----------------------------------------------------------------------------
# General
# ----------------------------------------------------------------------------
variable "subscription_id" {
  description = "Azure subscription ID to deploy into."
  type        = string
}

variable "location" {
  description = "Primary Azure region (must support availability zones)."
  type        = string
  default     = "westeurope"
}

variable "environment" {
  description = "Environment name (dev|test|prod)."
  type        = string
  default     = "prod"
  validation {
    condition     = contains(["dev", "test", "prod"], var.environment)
    error_message = "environment must be dev, test or prod."
  }
}

variable "name_prefix" {
  description = "Short prefix for resource names (lowercase letters/digits, max 10)."
  type        = string
  default     = "wz"
  validation {
    condition     = can(regex("^[a-z0-9]{2,10}$", var.name_prefix))
    error_message = "name_prefix must be 2-10 lowercase letters or digits."
  }
}

variable "tags" {
  description = "Additional tags applied to every resource."
  type        = map(string)
  default     = {}
}

# ----------------------------------------------------------------------------
# Network
# ----------------------------------------------------------------------------
variable "vnet_address_space" {
  type    = list(string)
  default = ["10.40.0.0/16"]
}

variable "subnet_cidrs" {
  description = "Subnet CIDRs. Keep in sync with networkPolicy.* in values-azure.yaml."
  type = object({
    aks               = string
    appgw             = string
    postgres          = string
    private_endpoints = string
  })
  default = {
    aks               = "10.40.0.0/23"
    appgw             = "10.40.2.0/24"
    postgres          = "10.40.3.0/24"
    private_endpoints = "10.40.4.0/24"
  }
}

variable "aks_pod_cidr" {
  description = "Azure CNI Overlay pod CIDR (must not overlap the VNet or on-prem ranges)."
  type        = string
  default     = "10.244.0.0/16"
}

variable "aks_service_cidr" {
  type    = string
  default = "10.41.0.0/16"
}

variable "aks_dns_service_ip" {
  type    = string
  default = "10.41.0.10"
}

# ----------------------------------------------------------------------------
# AKS
# ----------------------------------------------------------------------------
variable "kubernetes_version" {
  description = "AKS Kubernetes version (null = latest GA default)."
  type        = string
  default     = null
}

variable "aks_private_cluster" {
  description = "Private API server (requires self-hosted CI runners / az aks command invoke)."
  type        = bool
  default     = true
}

variable "aks_authorized_ip_ranges" {
  description = "Allowed CIDRs for a public API server (ignored when private)."
  type        = list(string)
  default     = []
}

variable "aks_admin_group_object_ids" {
  description = "Entra ID group object IDs granted cluster-admin via Azure RBAC."
  type        = list(string)
}

variable "aks_system_node_vm_size" {
  type    = string
  default = "Standard_D4ds_v5"
}

variable "aks_user_node_vm_size" {
  type    = string
  default = "Standard_D4ds_v5"
}

variable "aks_user_node_min" {
  type    = number
  default = 3
}

variable "aks_user_node_max" {
  type    = number
  default = 9
}

variable "aks_host_encryption" {
  description = "Encryption at host for node disks/temp (requires the EncryptionAtHost feature on the subscription)."
  type        = bool
  default     = true
}

variable "aks_disk_encryption_set_id" {
  description = "Disk Encryption Set (CMK) for node OS/data disks; null = platform-managed keys (+ host encryption)."
  type        = string
  default     = null
}

variable "aks_outbound_type" {
  description = "loadBalancer or userDefinedRouting (egress via Azure Firewall / NVA)."
  type        = string
  default     = "loadBalancer"
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

variable "k8s_namespace" {
  description = "Namespace the Helm release is installed into."
  type        = string
  default     = "wellnessz"
}

variable "helm_release_name" {
  description = "Helm release name. Service accounts are <release>-<component> when the release name contains \"wellnessz\", otherwise <release>-wellnessz-<component> (chart fullname rule)."
  type        = string
  default     = "wellnessz"
}

# ----------------------------------------------------------------------------
# PostgreSQL Flexible Server
# ----------------------------------------------------------------------------
variable "postgres_sku" {
  type    = string
  default = "GP_Standard_D4ds_v5"
}

variable "postgres_storage_mb" {
  type    = number
  default = 131072
}

variable "postgres_ha_enabled" {
  description = "Zone-redundant high availability (standby in another zone)."
  type        = bool
  default     = true
}

variable "postgres_geo_redundant_backup" {
  description = "Geo-redundant backups (immutable once the server is created)."
  type        = bool
  default     = true
}

variable "postgres_backup_retention_days" {
  type    = number
  default = 35
}

variable "postgres_admin_login" {
  description = "Break-glass administrator login (password generated ephemerally, stored only in Key Vault)."
  type        = string
  default     = "wzadmin"
}

variable "db_password_version" {
  description = "Increment to regenerate the PostgreSQL admin / wz_app / wz_migrator passwords (write-only arguments). Follow the rotation runbook: ALTER ROLE must be applied for wz_app/wz_migrator."
  type        = number
  default     = 1
}

variable "postgres_entra_admin" {
  description = "Entra ID group that administers the server (object_id + display name)."
  type = object({
    object_id      = string
    principal_name = string
  })
}

variable "postgres_cmk_enabled" {
  description = "Encrypt PostgreSQL data with a customer-managed key in Key Vault."
  type        = bool
  default     = false
}

variable "postgres_geo_backup_cmk_key_id" {
  description = "Key Vault key ID in the paired region for geo-backups (required when CMK + geo backup)."
  type        = string
  default     = null
}

variable "postgres_geo_backup_identity_id" {
  description = "User-assigned identity with access to the geo-backup key (paired region)."
  type        = string
  default     = null
}

# ----------------------------------------------------------------------------
# Key Vault / ACR / logging
# ----------------------------------------------------------------------------
variable "key_vault_allowed_ips" {
  description = "Public IPs allowed through the Key Vault firewall (e.g. Terraform runner). Prefer private runners."
  type        = list(string)
  default     = []
}

variable "acr_public_network_access" {
  description = "Allow public access to ACR (true if GitHub-hosted runners push images)."
  type        = bool
  default     = true
}

variable "acr_allowed_ips" {
  type    = list(string)
  default = []
}

variable "log_retention_days" {
  description = "Log Analytics retention (SOC 2 evidence: >= 365 recommended)."
  type        = number
  default     = 365
}

variable "enable_defender_plans" {
  description = "Enable Microsoft Defender for Containers, Key Vault and open-source databases at subscription level."
  type        = bool
  default     = true
}

# ----------------------------------------------------------------------------
# Edge / WAF
# ----------------------------------------------------------------------------
variable "waf_rule_set" {
  description = "Managed rule set for the App Gateway WAF policy."
  type = object({
    type    = string
    version = string
  })
  default = {
    type    = "Microsoft_DefaultRuleSet" # DRS 2.1 (successor of OWASP CRS 3.2)
    version = "2.1"
  }
}

variable "waf_mode" {
  type    = string
  default = "Prevention"
}

variable "enable_front_door" {
  description = "Also deploy Azure Front Door Premium + WAF in front of App Gateway (global edge)."
  type        = bool
  default     = false
}

variable "front_door_origin_host" {
  description = "Host name Front Door uses to reach the App Gateway origin (DNS name of the AppGW public IP or custom domain)."
  type        = string
  default     = ""
}

# ----------------------------------------------------------------------------
# GitHub Actions OIDC (no client secrets in CI)
# ----------------------------------------------------------------------------
variable "github_repository" {
  description = "owner/repo allowed to federate into the deployer identity (empty = skip)."
  type        = string
  default     = ""
}

variable "github_environments" {
  description = "GitHub Environments whose jobs may deploy (subject repo:<owner/repo>:environment:<env>)."
  type        = list(string)
  default     = ["dev", "test", "prod"]
}
