resource "google_compute_network" "main" {
  name                            = "vpc-${local.name}"
  auto_create_subnetworks         = false
  routing_mode                    = "REGIONAL"
  delete_default_routes_on_create = false
  depends_on                      = [google_project_service.apis]
}

resource "google_compute_subnetwork" "gke" {
  name                     = "snet-${local.name}-gke"
  region                   = var.region
  network                  = google_compute_network.main.id
  ip_cidr_range            = var.subnet_cidr
  private_ip_google_access = true

  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = var.pods_cidr
  }
  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = var.services_cidr
  }

  log_config {
    aggregation_interval = "INTERVAL_5_SEC"
    flow_sampling        = 0.5
    metadata             = "INCLUDE_ALL_METADATA"
  }
}

# Egress for private nodes (image pulls from public registries, IdP JWKS)
resource "google_compute_router" "main" {
  name    = "cr-${local.name}"
  region  = var.region
  network = google_compute_network.main.id
}

resource "google_compute_router_nat" "main" {
  name                               = "nat-${local.name}"
  router                             = google_compute_router.main.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"

  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}

# Private Service Access for Cloud SQL private IP
resource "google_compute_global_address" "psa" {
  name          = "psa-${local.name}"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  address       = var.psa_range.address
  prefix_length = var.psa_range.prefix_length
  network       = google_compute_network.main.id
}

resource "google_service_networking_connection" "psa" {
  network                 = google_compute_network.main.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.psa.name]
  deletion_policy         = "ABANDON"
}

# Explicit, logged deny for SSH/RDP from the internet (defence in depth)
resource "google_compute_firewall" "deny_admin_ports" {
  name      = "fw-${local.name}-deny-admin"
  network   = google_compute_network.main.name
  direction = "INGRESS"
  priority  = 100

  deny {
    protocol = "tcp"
    ports    = ["22", "3389"]
  }
  source_ranges = ["0.0.0.0/0"]

  log_config {
    metadata = "INCLUDE_ALL_METADATA"
  }
}

# Global static IP for the GKE Ingress (values-gcp.yaml)
resource "google_compute_global_address" "ingress" {
  name = "wellnessz-ingress-ip"
}
