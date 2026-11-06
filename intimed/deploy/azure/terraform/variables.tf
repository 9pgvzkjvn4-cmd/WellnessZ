variable "subscription_id" {
  type        = string
  description = "Azure subscription that hosts this environment."
}

variable "environment" {
  type        = string
  description = "Environment short name: uat or prod."
  validation {
    condition     = contains(["dev", "uat", "prod"], var.environment)
    error_message = "environment must be dev, uat or prod."
  }
}

variable "location" {
  type        = string
  default     = "qatarcentral"
  description = "Azure region. Health data stays in Qatar: only qatarcentral is accepted unless allow_non_qatar_region is true (requires a documented regulatory approval)."
}

variable "allow_non_qatar_region" {
  type    = bool
  default = false
  validation {
    condition     = var.allow_non_qatar_region == false
    error_message = "Deploying outside Qatar Central needs a recorded MoPH/NCSA approval: change this validation deliberately in a reviewed commit."
  }
}

variable "prefix" {
  type    = string
  default = "intimed"
}

variable "tags" {
  type    = map(string)
  default = {}
}

# ---------------------------------------------------------------- network
variable "address_space" {
  type    = string
  default = "10.40.0.0/16"
}

variable "subnets" {
  type = map(string)
  default = {
    aks       = "10.40.0.0/22" # nodes + console internal LB
    appgw     = "10.40.4.0/24" # Application Gateway WAF v2
    devices   = "10.40.5.0/24" # device internal LB (MLLP-TLS 2575, HTTPS mTLS 8443)
    postgres  = "10.40.6.0/24" # delegated to PostgreSQL Flexible Server
    endpoints = "10.40.7.0/24" # private endpoints (Key Vault, Storage, ACR)
    firewall  = "10.40.8.0/26" # AzureFirewallSubnet
    gateway   = "10.40.9.0/27" # GatewaySubnet (site-to-site VPN)
  }
}

variable "console_lb_ip" {
  type        = string
  default     = "10.40.3.250"
  description = "Static private IP of the console internal load balancer (in the aks subnet); Application Gateway backend."
}

variable "devices_lb_ip" {
  type    = string
  default = "10.40.5.250"
}

variable "device_source_cidrs" {
  type        = list(string)
  description = "Hospital / clinic networks (over VPN or ExpressRoute) allowed to reach the device ports 2575 and 8443."
}

variable "admin_source_cidrs" {
  type        = list(string)
  default     = []
  description = "Operator egress IPs allowed to reach the AKS API server, Key Vault and ACR data planes (non-private clusters)."
}

# ---------------------------------------------------------------- AKS
variable "kubernetes_version" {
  type    = string
  default = null # latest default version in the region
}

variable "aks_private_cluster" {
  type    = bool
  default = false
}

variable "zones" {
  type    = list(string)
  default = ["1", "2", "3"]
}

variable "system_node_vm_size" {
  type    = string
  default = "Standard_D2s_v5"
}

variable "system_node_count" {
  type    = number
  default = 2
}

variable "app_node_vm_size" {
  type    = string
  default = "Standard_D4s_v5"
}

variable "app_node_min" {
  type    = number
  default = 1
}

variable "app_node_max" {
  type    = number
  default = 3
}

# ---------------------------------------------------------------- data
variable "postgres_sku" {
  type    = string
  default = "GP_Standard_D2ds_v5"
}

variable "postgres_storage_mb" {
  type    = number
  default = 131072
  validation {
    condition     = contains([32768, 65536, 131072, 262144, 524288, 1048576], var.postgres_storage_mb)
    error_message = "postgres_storage_mb must be one of the Premium SSD sizes 32768…1048576."
  }
}

variable "postgres_ha_mode" {
  type        = string
  default     = "Disabled"
  description = "Disabled | ZoneRedundant | SameZone. Check `az postgres flexible-server list-skus -l qatarcentral` (deploy/azure/scripts/preflight.sh) – fall back to SameZone if zone-redundant HA is not offered."
  validation {
    condition     = contains(["Disabled", "ZoneRedundant", "SameZone"], var.postgres_ha_mode)
    error_message = "postgres_ha_mode must be Disabled, ZoneRedundant or SameZone."
  }
}

variable "postgres_backup_retention_days" {
  type    = number
  default = 35
}

variable "archive_retention_days" {
  type        = number
  default     = 2555
  description = "WORM retention of sealed evidence packs and the SIEM audit stream (7 years by default)."
}

# ---------------------------------------------------------------- edge, ingress, egress
variable "enable_app_gateway" {
  type        = bool
  default     = false
  description = "Phase 2 of the setup: needs the platform root CA (console_root_ca_pem) and a TLS certificate in Key Vault."
}

variable "console_hostname" {
  type    = string
  default = ""
}

variable "console_root_ca_pem" {
  type        = string
  default     = ""
  description = "PEM of the IntiMed installation root CA (exported after first start, scripts/export-root-ca.sh) – Application Gateway trusts the backend with it."
}

variable "appgw_certificate_secret_id" {
  type        = string
  default     = ""
  description = "Key Vault versionless secret id of the public TLS certificate for console_hostname."
}

variable "enable_firewall" {
  type    = bool
  default = false
}

variable "firewall_allowed_fqdns" {
  type = list(string)
  default = [
    "login.microsoftonline.com",
    "api.fda.gov",
    "accessgudid.nlm.nih.gov",
    "ec.europa.eu",
  ]
}

variable "enable_vpn" {
  type    = bool
  default = false
}

variable "vpn_sites" {
  type = map(object({
    gateway_ip       = string
    address_prefixes = list(string)
  }))
  default = {}
}

variable "vpn_shared_keys" {
  type      = map(string)
  default   = {}
  sensitive = true
}

# ---------------------------------------------------------------- security operations & cost
variable "log_retention_days" {
  type    = number
  default = 90
}

variable "enable_sentinel" {
  type    = bool
  default = true
}

variable "enable_defender_plans" {
  type        = bool
  default     = false
  description = "Subscription-scope Microsoft Defender plans (Containers, Key Vault, Storage, open-source DBs). Enable once per subscription."
}

variable "alert_emails" {
  type    = list(string)
  default = []
}

variable "monthly_budget_usd" {
  type    = number
  default = 3000
}

variable "acr_public_network" {
  type        = bool
  default     = true
  description = "GitHub-hosted runners push over the public endpoint (Entra auth, no admin user). Set false with self-hosted runners in the VNet."
}

variable "budget_start_date" {
  type        = string
  default     = "2026-12-01T00:00:00Z"
  description = "First day of the budget period (first of a month, UTC)."
}
