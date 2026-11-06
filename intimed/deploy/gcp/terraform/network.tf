resource "google_compute_network" "vpc" {
  name                    = "vpc-${local.base}"
  auto_create_subnetworks = false
  routing_mode            = "REGIONAL"
  depends_on              = [google_project_service.apis]
}

resource "google_compute_subnetwork" "gke" {
  name                     = "snet-gke-${local.base}"
  region                   = var.region
  network                  = google_compute_network.vpc.id
  ip_cidr_range            = var.subnet_cidrs.gke
  private_ip_google_access = true
  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = var.subnet_cidrs.pods
  }
  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = var.subnet_cidrs.services
  }
  log_config {
    aggregation_interval = "INTERVAL_5_MIN"
    flow_sampling        = 0.5
    metadata             = "INCLUDE_ALL_METADATA"
  }
}

# Envoy-based regional external Application Load Balancer (console) – TLS terminates in Doha, not at a global edge.
resource "google_compute_subnetwork" "proxy_only" {
  name          = "snet-proxy-${local.base}"
  region        = var.region
  network       = google_compute_network.vpc.id
  ip_cidr_range = var.subnet_cidrs.proxy_only
  purpose       = "REGIONAL_MANAGED_PROXY"
  role          = "ACTIVE"
}

# Private services access for Cloud SQL (private IP only).
resource "google_compute_global_address" "psa" {
  name          = "psa-${local.base}"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  address       = split("/", var.subnet_cidrs.psa)[0]
  prefix_length = tonumber(split("/", var.subnet_cidrs.psa)[1])
  network       = google_compute_network.vpc.id
}

resource "google_service_networking_connection" "psa" {
  network                 = google_compute_network.vpc.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.psa.name]
}

# Egress: Cloud NAT (nodes have no public IPs).
resource "google_compute_router" "nat" {
  name    = "cr-nat-${local.base}"
  region  = var.region
  network = google_compute_network.vpc.id
}

resource "google_compute_router_nat" "nat" {
  name                               = "nat-${local.base}"
  router                             = google_compute_router.nat.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"
  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}

# ---- firewall: device ports only from hospital networks; health checks; deny everything else inbound.
resource "google_compute_firewall" "devices" {
  name          = "fw-devices-${local.base}"
  network       = google_compute_network.vpc.id
  direction     = "INGRESS"
  priority      = 1000
  source_ranges = var.device_source_cidrs
  allow {
    protocol = "tcp"
    ports    = ["2575", "8443"]
  }
  target_tags = ["intimed-node"]
  log_config {
    metadata = "INCLUDE_ALL_METADATA"
  }
}

resource "google_compute_firewall" "health_checks" {
  name          = "fw-hc-${local.base}"
  network       = google_compute_network.vpc.id
  direction     = "INGRESS"
  priority      = 1000
  source_ranges = ["35.191.0.0/16", "130.211.0.0/22"]
  allow {
    protocol = "tcp"
    ports    = ["2575", "8443", "9443"]
  }
  target_tags = ["intimed-node"]
}

resource "google_compute_firewall" "proxy_to_console" {
  name          = "fw-proxy-${local.base}"
  network       = google_compute_network.vpc.id
  direction     = "INGRESS"
  priority      = 1000
  source_ranges = [var.subnet_cidrs.proxy_only]
  allow {
    protocol = "tcp"
    ports    = ["9443"]
  }
  target_tags = ["intimed-node"]
}

resource "google_compute_firewall" "deny_ingress" {
  name          = "fw-deny-ingress-${local.base}"
  network       = google_compute_network.vpc.id
  direction     = "INGRESS"
  priority      = 65000
  source_ranges = ["0.0.0.0/0"]
  deny {
    protocol = "all"
  }
  log_config {
    metadata = "EXCLUDE_ALL_METADATA"
  }
}

# ---- production: Cloud NGFW FQDN egress allow-list (Google APIs via Private Google Access stay allowed).
resource "google_compute_network_firewall_policy" "egress" {
  count = var.enable_fqdn_egress_policy ? 1 : 0
  name  = "fwp-egress-${local.base}"
}

resource "google_compute_network_firewall_policy_rule" "allow_fqdns" {
  count           = var.enable_fqdn_egress_policy ? 1 : 0
  firewall_policy = google_compute_network_firewall_policy.egress[0].name
  priority        = 1000
  direction       = "EGRESS"
  action          = "allow"
  enable_logging  = true
  match {
    dest_fqdns = var.egress_allowed_fqdns
    layer4_configs {
      ip_protocol = "tcp"
      ports       = ["443"]
    }
  }
}

resource "google_compute_network_firewall_policy_rule" "allow_google_apis" {
  count           = var.enable_fqdn_egress_policy ? 1 : 0
  firewall_policy = google_compute_network_firewall_policy.egress[0].name
  priority        = 1100
  direction       = "EGRESS"
  action          = "allow"
  match {
    dest_ip_ranges = ["199.36.153.4/30", "199.36.153.8/30", var.subnet_cidrs.psa, var.subnet_cidrs.gke, var.subnet_cidrs.pods, var.subnet_cidrs.services, var.master_cidr]
    layer4_configs {
      ip_protocol = "all"
    }
  }
}

resource "google_compute_network_firewall_policy_rule" "deny_internet" {
  count           = var.enable_fqdn_egress_policy ? 1 : 0
  firewall_policy = google_compute_network_firewall_policy.egress[0].name
  priority        = 65000
  direction       = "EGRESS"
  action          = "deny"
  enable_logging  = true
  match {
    dest_ip_ranges = ["0.0.0.0/0"]
    layer4_configs {
      ip_protocol = "all"
    }
  }
}

resource "google_compute_network_firewall_policy_association" "egress" {
  count             = var.enable_fqdn_egress_policy ? 1 : 0
  name              = "assoc-egress-${local.base}"
  firewall_policy   = google_compute_network_firewall_policy.egress[0].name
  attachment_target = google_compute_network.vpc.id
}

# Private Google Access through restricted.googleapis.com (VPC-SC compatible): private DNS zone.
resource "google_dns_managed_zone" "googleapis" {
  name       = "dz-googleapis-${local.base}"
  dns_name   = "googleapis.com."
  visibility = "private"
  private_visibility_config {
    networks {
      network_url = google_compute_network.vpc.id
    }
  }
}

resource "google_dns_record_set" "restricted" {
  managed_zone = google_dns_managed_zone.googleapis.name
  name         = "restricted.googleapis.com."
  type         = "A"
  ttl          = 300
  rrdatas      = ["199.36.153.4", "199.36.153.5", "199.36.153.6", "199.36.153.7"]
}

resource "google_dns_record_set" "googleapis_cname" {
  managed_zone = google_dns_managed_zone.googleapis.name
  name         = "*.googleapis.com."
  type         = "CNAME"
  ttl          = 300
  rrdatas      = ["restricted.googleapis.com."]
}

# Artifact Registry / GKE system images also through the restricted VIP (production denies internet egress).
resource "google_dns_managed_zone" "registries" {
  for_each   = toset(["pkg.dev.", "gcr.io."])
  name       = "dz-${replace(trimsuffix(each.value, "."), ".", "-")}-${local.base}"
  dns_name   = each.value
  visibility = "private"
  private_visibility_config {
    networks {
      network_url = google_compute_network.vpc.id
    }
  }
}

resource "google_dns_record_set" "registries_a" {
  for_each     = google_dns_managed_zone.registries
  managed_zone = each.value.name
  name         = each.value.dns_name
  type         = "A"
  ttl          = 300
  rrdatas      = ["199.36.153.4", "199.36.153.5", "199.36.153.6", "199.36.153.7"]
}

resource "google_dns_record_set" "registries_cname" {
  for_each     = google_dns_managed_zone.registries
  managed_zone = each.value.name
  name         = "*.${each.value.dns_name}"
  type         = "CNAME"
  ttl          = 300
  rrdatas      = [each.value.dns_name]
}
