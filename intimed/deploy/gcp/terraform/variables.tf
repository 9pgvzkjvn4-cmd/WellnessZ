variable "project_id" {
  type        = string
  description = "Google Cloud project for this environment (one project per environment)."
}

variable "environment" {
  type = string
  validation {
    condition     = contains(["dev", "uat", "prod"], var.environment)
    error_message = "environment must be dev, uat or prod."
  }
}

variable "region" {
  type        = string
  default     = "me-central1"
  description = "Doha. Health data stays in Qatar: only me-central1 is accepted (changing this needs a reviewed commit and a regulator decision)."
  validation {
    condition     = var.region == "me-central1"
    error_message = "IntiMed_GCP deploys to me-central1 (Doha) only – health data stays in Qatar."
  }
}

variable "zones" {
  type    = list(string)
  default = ["me-central1-a", "me-central1-b", "me-central1-c"]
}

variable "prefix" {
  type    = string
  default = "intimed"
}

variable "labels" {
  type    = map(string)
  default = {}
}

variable "billing_account" {
  type        = string
  default     = ""
  description = "Billing account id (XXXXXX-XXXXXX-XXXXXX) for the budget; empty = no budget resource."
}

variable "enforce_location_org_policy" {
  type        = bool
  default     = true
  description = "Project-level organisation policy gcp.resourceLocations = in:me-central1-locations (needs roles/orgpolicy.policyAdmin)."
}

# ---------------------------------------------------------------- network
variable "subnet_cidrs" {
  type = map(string)
  default = {
    gke        = "10.50.0.0/20" # nodes + internal load balancers (console backend, devices)
    pods       = "10.52.0.0/14"
    services   = "10.56.0.0/20"
    proxy_only = "10.50.32.0/23" # regional external Application Load Balancer (Envoy proxies)
    psa        = "10.50.48.0/20" # private services access (Cloud SQL)
  }
}

variable "master_cidr" {
  type    = string
  default = "172.16.0.32/28"
}

variable "devices_lb_ip" {
  type        = string
  default     = "10.50.15.250"
  description = "Static internal IP of the device load balancer (MLLP-TLS 2575, HTTPS mTLS 8443) in the gke subnet."
}

variable "device_source_cidrs" {
  type        = list(string)
  description = "Hospital / clinic device networks (over HA VPN / Interconnect) allowed to reach 2575 and 8443."
}

variable "admin_source_cidrs" {
  type        = list(string)
  default     = []
  description = "Operator egress IPs for the GKE DNS endpoint is IAM-authenticated; these restrict the IP endpoint (if enabled)."
}

variable "console_allowed_cidrs" {
  type        = list(string)
  default     = []
  description = "Optional: restrict the public console (Cloud Armor allow-list). Empty = internet-reachable behind WAF."
}

# ---------------------------------------------------------------- GKE
variable "system_machine_type" {
  type    = string
  default = "n2-standard-2"
}

variable "app_machine_type" {
  type    = string
  default = "n2-standard-4"
}

variable "app_nodes_min_per_zone" {
  type    = number
  default = 0
}

variable "app_nodes_max_per_zone" {
  type    = number
  default = 1
}

variable "app_node_locations" {
  type        = list(string)
  default     = ["me-central1-a", "me-central1-b"]
  description = "Zones of the app pool (regional persistent disks replicate across two zones – keep the pool in the same two)."
}

# ---------------------------------------------------------------- data
variable "sql_tier" {
  type    = string
  default = "db-custom-2-7680"
}

variable "sql_availability_type" {
  type    = string
  default = "ZONAL"
  validation {
    condition     = contains(["ZONAL", "REGIONAL"], var.sql_availability_type)
    error_message = "ZONAL or REGIONAL."
  }
}

variable "sql_disk_gb" {
  type    = number
  default = 50
}

variable "sql_backup_retention" {
  type    = number
  default = 14
}

variable "kms_protection_level" {
  type        = string
  default     = "HSM"
  description = "HSM (Cloud HSM) or SOFTWARE. preflight.sh checks Cloud HSM availability in me-central1."
}

variable "archive_retention_days" {
  type    = number
  default = 2555
}

variable "lock_archive_retention" {
  type        = bool
  default     = false
  description = "Lock the bucket retention policies (irreversible) after acceptance – manual guide M-11."
}

# ---------------------------------------------------------------- edge
variable "enable_console_gateway" {
  type        = bool
  default     = true
  description = "Static regional IP + Cloud Armor policy for the regional external Application Load Balancer created by the GKE Gateway."
}

variable "enable_vpn" {
  type    = bool
  default = false
}

variable "vpn_sites" {
  type = map(object({
    peer_ip    = string
    peer_asn   = number
    bgp_ranges = list(string) # two /30 link-local ranges, e.g. ["169.254.10.0/30","169.254.10.4/30"]
  }))
  default = {}
}

variable "vpn_shared_secrets" {
  type      = map(string)
  default   = {}
  sensitive = true
}

variable "enable_fqdn_egress_policy" {
  type        = bool
  default     = false
  description = "Cloud NGFW FQDN egress allow-list (network firewall policy with destination FQDNs) – production."
}

variable "egress_allowed_fqdns" {
  type    = list(string)
  default = ["api.fda.gov", "accessgudid.nlm.nih.gov", "ec.europa.eu"]
}

# ---------------------------------------------------------------- operations
variable "log_retention_days" {
  type    = number
  default = 90
}

variable "alert_emails" {
  type    = list(string)
  default = []
}

variable "monthly_budget_usd" {
  type    = number
  default = 3000
}

variable "binauthz_enforce" {
  type        = bool
  default     = false
  description = "Binary Authorization: require the IntiMed release attestation (production)."
}

variable "github_repository" {
  type        = string
  default     = ""
  description = "owner/repo allowed to deploy through Workload Identity Federation (empty = no CI/CD identity created)."
}
