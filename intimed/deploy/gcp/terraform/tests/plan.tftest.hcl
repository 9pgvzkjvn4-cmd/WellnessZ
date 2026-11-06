# `terraform test` – plans the IntiMed_GCP configuration against mocked providers (nothing is created in Google Cloud)
# for UAT and production, and proves the Doha residency guard.
mock_provider "google" {
  mock_data "google_project" {
    defaults = { number = "123456789012", project_id = "intimed-test-qa" }
  }
  mock_data "google_storage_project_service_account" {
    defaults = { email_address = "service-123456789012@gs-project-accounts.iam.gserviceaccount.com" }
  }
  mock_data "google_kms_crypto_key_version" {
    defaults = { public_key = [{ pem = "-----BEGIN PUBLIC KEY-----\nMFkw\n-----END PUBLIC KEY-----", algorithm = "EC_SIGN_P256_SHA256" }] }
  }
  mock_resource "google_kms_crypto_key" {
    defaults = { id = "projects/intimed-test-qa/locations/me-central1/keyRings/kr/cryptoKeys/k" }
  }
  mock_resource "google_kms_key_ring" {
    defaults = { id = "projects/intimed-test-qa/locations/me-central1/keyRings/kr", name = "kr" }
  }
  mock_resource "google_compute_network" {
    defaults = { id = "projects/intimed-test-qa/global/networks/vpc" }
  }
  mock_resource "google_compute_subnetwork" {
    defaults = { id = "projects/intimed-test-qa/regions/me-central1/subnetworks/snet" }
  }
  mock_resource "google_container_cluster" {
    defaults = { id = "projects/intimed-test-qa/locations/me-central1/clusters/gke" }
  }
  mock_resource "google_secret_manager_secret" {
    defaults = { id = "projects/intimed-test-qa/secrets/s" }
  }
  mock_resource "google_service_account" {
    defaults = { email = "sa-intimed-app@intimed-test-qa.iam.gserviceaccount.com", name = "projects/intimed-test-qa/serviceAccounts/sa-intimed-app@intimed-test-qa.iam.gserviceaccount.com" }
  }
  mock_resource "google_sql_database_instance" {
    defaults = { server_ca_cert = [{ cert = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----", common_name = "ca", create_time = "", expiration_time = "", sha1_fingerprint = "" }] }
  }
  mock_resource "google_pubsub_topic" {
    defaults = { id = "projects/intimed-test-qa/topics/siem" }
  }
  mock_resource "google_logging_project_bucket_config" {
    defaults = { id = "projects/intimed-test-qa/locations/me-central1/buckets/intimed" }
  }
  mock_resource "google_logging_project_sink" {
    defaults = { writer_identity = "serviceAccount:sink@gcp-sa-logging.iam.gserviceaccount.com" }
  }
  mock_resource "google_compute_ha_vpn_gateway" {
    defaults = { id = "projects/intimed-test-qa/regions/me-central1/vpnGateways/vpn", vpn_interfaces = [{ id = 0, ip_address = "34.0.0.1", interconnect_attachment = "" }, { id = 1, ip_address = "34.0.0.2", interconnect_attachment = "" }] }
  }
}
mock_provider "random" {}

variables {
  project_id          = "intimed-test-qa"
  device_source_cidrs = ["10.60.0.0/16"]
  alert_emails        = ["ops@example.qa"]
  billing_account     = "000000-000000-000000"
}

run "uat_plan" {
  command = plan
  variables {
    environment = "uat"
  }
  assert {
    condition     = google_sql_database_instance.pg.settings[0].ip_configuration[0].ipv4_enabled == false && google_sql_database_instance.pg.settings[0].ip_configuration[0].ssl_mode == "ENCRYPTED_ONLY"
    error_message = "Cloud SQL must be private-IP only with TLS required"
  }
  assert {
    condition     = google_sql_database_instance.pg.settings[0].backup_configuration[0].location == "me-central1"
    error_message = "backups must stay in Doha"
  }
  assert {
    condition     = alltrue([for k, s in google_secret_manager_secret.runtime : s.replication[0].user_managed[0].replicas[0].location == "me-central1"])
    error_message = "secrets must replicate to Doha only"
  }
  assert {
    condition     = alltrue([for b in google_storage_bucket.archive : b.location == "ME-CENTRAL1" && b.public_access_prevention == "enforced"])
    error_message = "archive buckets must be in Doha and private"
  }
  assert {
    condition     = google_container_cluster.gke.private_cluster_config[0].enable_private_endpoint && google_container_cluster.gke.workload_identity_config[0].workload_pool == "intimed-test-qa.svc.id.goog"
    error_message = "GKE must be private with Workload Identity"
  }
  assert {
    condition     = google_binary_authorization_policy.policy.default_admission_rule[0].enforcement_mode == "DRYRUN_AUDIT_LOG_ONLY"
    error_message = "UAT runs Binary Authorization in dry-run"
  }
  assert {
    condition     = length(google_compute_network_firewall_policy.egress) == 0 && length(google_compute_vpn_tunnel.site) == 0
    error_message = "UAT has no FQDN egress policy and no VPN"
  }
}

run "prod_plan" {
  command = plan
  variables {
    environment               = "prod"
    sql_tier                  = "db-custom-4-15360"
    sql_availability_type     = "REGIONAL"
    sql_disk_gb               = 250
    sql_backup_retention      = 35
    enable_vpn                = true
    vpn_sites                 = { hmc = { peer_ip = "198.51.100.7", peer_asn = 65010, bgp_ranges = ["169.254.10.0/30", "169.254.10.4/30"] } }
    vpn_shared_secrets        = { hmc = "test-only-shared-secret-0123456789" }
    enable_fqdn_egress_policy = true
    binauthz_enforce          = true
    console_allowed_cidrs     = ["203.0.113.0/24"]
  }
  assert {
    condition     = google_sql_database_instance.pg.settings[0].availability_type == "REGIONAL"
    error_message = "prod Cloud SQL must be regional HA"
  }
  assert {
    condition     = length(google_compute_vpn_tunnel.site) == 2
    error_message = "two HA VPN tunnels per site"
  }
  assert {
    condition     = google_binary_authorization_policy.policy.default_admission_rule[0].evaluation_mode == "REQUIRE_ATTESTATION"
    error_message = "prod requires the release attestation"
  }
  assert {
    condition     = google_compute_region_security_policy_rule.default[0].action == "deny(403)"
    error_message = "with an allow-list the default WAF rule denies"
  }
  assert {
    condition     = length(google_compute_region_security_policy_rule.waf) == 11
    error_message = "all preconfigured WAF rules present"
  }
}

run "residency_guard" {
  command = plan
  variables {
    environment = "uat"
    region      = "europe-west1"
  }
  expect_failures = [var.region]
}
