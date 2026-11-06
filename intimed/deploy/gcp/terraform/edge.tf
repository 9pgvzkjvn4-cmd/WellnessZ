# ---- Console: regional external Application Load Balancer created by the GKE Gateway (class
# gke-l7-regional-external-managed) on this static Doha IP, protected by this regional Cloud Armor policy.
resource "google_compute_address" "console" {
  count        = var.enable_console_gateway ? 1 : 0
  name         = "ip-console-${local.base}"
  region       = var.region
  address_type = "EXTERNAL"
  network_tier = "STANDARD"
}

resource "google_compute_region_security_policy" "console" {
  count       = var.enable_console_gateway ? 1 : 0
  name        = "armor-console-${local.base}"
  region      = var.region
  type        = "CLOUD_ARMOR"
  description = "IntiMed console WAF: OWASP CRS 3.3 preconfigured rules, rate limiting, optional source allow-list"
}

locals {
  waf_rules = {
    1000 = "sqli-v33-stable", 1010 = "xss-v33-stable", 1020 = "lfi-v33-stable", 1030 = "rfi-v33-stable",
    1040 = "rce-v33-stable", 1050 = "methodenforcement-v33-stable", 1060 = "scannerdetection-v33-stable",
    1070 = "protocolattack-v33-stable", 1080 = "sessionfixation-v33-stable", 1090 = "java-v33-stable", 1100 = "cve-canary",
  }
}

resource "google_compute_region_security_policy_rule" "waf" {
  for_each        = var.enable_console_gateway ? local.waf_rules : {}
  region          = var.region
  security_policy = google_compute_region_security_policy.console[0].name
  priority        = tonumber(each.key)
  action          = "deny(403)"
  description     = "Preconfigured WAF ${each.value}"
  match {
    expr {
      expression = "evaluatePreconfiguredWaf('${each.value}', {'sensitivity': 1})"
    }
  }
}

resource "google_compute_region_security_policy_rule" "rate_limit" {
  count           = var.enable_console_gateway ? 1 : 0
  region          = var.region
  security_policy = google_compute_region_security_policy.console[0].name
  priority        = 2000
  action          = "rate_based_ban"
  description     = "Per-client rate limit (login brute force is also throttled by the platform)"
  match {
    versioned_expr = "SRC_IPS_V1"
    config {
      src_ip_ranges = ["*"]
    }
  }
  rate_limit_options {
    conform_action   = "allow"
    exceed_action    = "deny(429)"
    enforce_on_key   = "IP"
    ban_duration_sec = 300
    rate_limit_threshold {
      count        = 600
      interval_sec = 60
    }
  }
}

resource "google_compute_region_security_policy_rule" "allow_list" {
  count           = var.enable_console_gateway && length(var.console_allowed_cidrs) > 0 ? 1 : 0
  region          = var.region
  security_policy = google_compute_region_security_policy.console[0].name
  priority        = 3000
  action          = "allow"
  description     = "Console reachable from the organisation's networks only"
  match {
    versioned_expr = "SRC_IPS_V1"
    config {
      src_ip_ranges = var.console_allowed_cidrs
    }
  }
}

resource "google_compute_region_security_policy_rule" "default" {
  count           = var.enable_console_gateway ? 1 : 0
  region          = var.region
  security_policy = google_compute_region_security_policy.console[0].name
  priority        = 2147483647
  action          = length(var.console_allowed_cidrs) > 0 ? "deny(403)" : "allow"
  description     = "Default rule"
  match {
    versioned_expr = "SRC_IPS_V1"
    config {
      src_ip_ranges = ["*"]
    }
  }
}

# ---- HA VPN to hospitals / clinics (99.99 % SLA with two tunnels per site, BGP).
resource "google_compute_ha_vpn_gateway" "vpn" {
  count   = var.enable_vpn ? 1 : 0
  name    = "havpn-${local.base}"
  region  = var.region
  network = google_compute_network.vpc.id
}

resource "google_compute_router" "vpn" {
  count   = var.enable_vpn ? 1 : 0
  name    = "cr-vpn-${local.base}"
  region  = var.region
  network = google_compute_network.vpc.id
  bgp {
    asn               = 64514
    advertise_mode    = "CUSTOM"
    advertised_groups = []
    advertised_ip_ranges {
      range = var.subnet_cidrs.gke
    }
  }
}

resource "google_compute_external_vpn_gateway" "site" {
  for_each        = var.enable_vpn ? var.vpn_sites : {}
  name            = "peer-${each.key}-${local.base}"
  redundancy_type = "SINGLE_IP_INTERNALLY_REDUNDANT"
  interface {
    id         = 0
    ip_address = each.value.peer_ip
  }
}

locals {
  tunnels = var.enable_vpn ? merge([for site, cfg in var.vpn_sites : {
    for i in [0, 1] : "${site}-${i}" => { site = site, iface = i, cfg = cfg }
  }]...) : {}
}

resource "google_compute_vpn_tunnel" "site" {
  for_each                        = local.tunnels
  name                            = "tun-${each.key}-${local.base}"
  region                          = var.region
  vpn_gateway                     = google_compute_ha_vpn_gateway.vpn[0].id
  vpn_gateway_interface           = each.value.iface
  peer_external_gateway           = google_compute_external_vpn_gateway.site[each.value.site].id
  peer_external_gateway_interface = 0
  shared_secret                   = var.vpn_shared_secrets[each.value.site]
  router                          = google_compute_router.vpn[0].id
  ike_version                     = 2
}

resource "google_compute_router_interface" "site" {
  for_each   = local.tunnels
  name       = "if-${each.key}"
  router     = google_compute_router.vpn[0].name
  region     = var.region
  ip_range   = "${cidrhost(each.value.cfg.bgp_ranges[each.value.iface], 1)}/30"
  vpn_tunnel = google_compute_vpn_tunnel.site[each.key].name
}

resource "google_compute_router_peer" "site" {
  for_each                  = local.tunnels
  name                      = "bgp-${each.key}"
  router                    = google_compute_router.vpn[0].name
  region                    = var.region
  peer_ip_address           = cidrhost(each.value.cfg.bgp_ranges[each.value.iface], 2)
  peer_asn                  = each.value.cfg.peer_asn
  advertised_route_priority = 100
  interface                 = google_compute_router_interface.site[each.key].name
}

# TLS policy for the console Gateway (referenced by the Helm GCPGatewayPolicy as "intimed-tls12-modern").
resource "google_compute_region_ssl_policy" "console" {
  name            = "intimed-tls12-modern"
  region          = var.region
  profile         = "MODERN"
  min_tls_version = "TLS_1_2"
}
