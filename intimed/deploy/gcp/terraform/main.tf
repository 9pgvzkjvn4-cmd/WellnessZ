locals {
  base = "${var.prefix}-${var.environment}"
  labels = merge({
    product             = "intimed"
    environment         = var.environment
    data-residency      = "qatar"
    data-classification = "phi"
    managed-by          = "terraform"
  }, var.labels)
  services = [
    "compute.googleapis.com", "container.googleapis.com", "sqladmin.googleapis.com", "servicenetworking.googleapis.com",
    "secretmanager.googleapis.com", "cloudkms.googleapis.com", "artifactregistry.googleapis.com", "containeranalysis.googleapis.com",
    "containerscanning.googleapis.com", "binaryauthorization.googleapis.com", "logging.googleapis.com", "monitoring.googleapis.com",
    "networksecurity.googleapis.com", "certificatemanager.googleapis.com", "iam.googleapis.com", "iamcredentials.googleapis.com",
    "cloudresourcemanager.googleapis.com", "orgpolicy.googleapis.com", "dns.googleapis.com", "billingbudgets.googleapis.com",
    "securitycenter.googleapis.com", "storage.googleapis.com",
  ]
}

data "google_project" "this" {
  project_id = var.project_id
}

resource "google_project_service" "apis" {
  for_each           = toset(local.services)
  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

# Data residency guard (PDPPL Law No. 13 of 2016 / MoPH): resources may only be created in Doha (and global).
resource "google_org_policy_policy" "locations" {
  count  = var.enforce_location_org_policy ? 1 : 0
  name   = "projects/${var.project_id}/policies/gcp.resourceLocations"
  parent = "projects/${var.project_id}"
  spec {
    rules {
      values {
        allowed_values = ["in:me-central1-locations"]
      }
    }
  }
  depends_on = [google_project_service.apis]
}

resource "random_string" "suffix" {
  length  = 4
  upper   = false
  special = false
}
