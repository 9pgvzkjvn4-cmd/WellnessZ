output "project_id" { value = var.project_id }
output "region" { value = var.region }
output "gke_name" { value = google_container_cluster.gke.name }
output "artifact_registry" { value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.images.repository_id}" }
output "sql_instance" { value = google_sql_database_instance.pg.name }
output "sql_connection_name" { value = google_sql_database_instance.pg.connection_name }
output "sql_private_ip" { value = google_sql_database_instance.pg.private_ip_address }
# Public CA certificate of the instance (not a secret) – used by the platform for sslmode=verify-ca.
output "sql_server_ca_pem" {
  value = nonsensitive(google_sql_database_instance.pg.server_ca_cert[0].cert)
}
output "app_gsa_email" { value = google_service_account.app.email }
output "evidence_bucket" { value = google_storage_bucket.archive["evidence"].name }
output "audit_bucket" { value = google_storage_bucket.archive["audit"].name }
output "console_ip_name" { value = var.enable_console_gateway ? google_compute_address.console[0].name : null }
output "console_ip" { value = var.enable_console_gateway ? google_compute_address.console[0].address : null }
output "armor_policy" { value = var.enable_console_gateway ? google_compute_region_security_policy.console[0].name : null }
output "devices_lb_ip" { value = var.devices_lb_ip }
output "subnet_cidrs" { value = var.subnet_cidrs }
output "device_source_cidrs" { value = var.device_source_cidrs }
output "cosign_key" { value = "gcpkms://projects/${var.project_id}/locations/${var.region}/keyRings/${google_kms_key_ring.ring.name}/cryptoKeys/${google_kms_crypto_key.cosign.name}/versions/1" }
output "attestor" { value = google_binary_authorization_attestor.release.name }
output "vpn_gateway_ips" { value = var.enable_vpn ? [for i in google_compute_ha_vpn_gateway.vpn[0].vpn_interfaces : i.ip_address] : [] }
output "log_bucket" { value = google_logging_project_bucket_config.logs.id }
