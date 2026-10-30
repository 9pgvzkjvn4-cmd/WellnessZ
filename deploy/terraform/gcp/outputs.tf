output "gke_cluster_name" {
  value = google_container_cluster.main.name
}

output "gke_location" {
  value = google_container_cluster.main.location
}

output "artifact_registry" {
  description = "global.imageRegistry"
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.main.repository_id}"
}

output "cloudsql_private_ip" {
  description = "Use in core.env.WZ_DB_URL (sslmode=verify-ca)"
  value       = google_sql_database_instance.main.private_ip_address
}

output "cloudsql_connection_name" {
  value = google_sql_database_instance.main.connection_name
}

output "workload_service_accounts" {
  description = "workloadIdentity.gcp.serviceAccounts"
  value       = { for k, v in google_service_account.workload : k => v.email }
}

output "cloud_armor_policy_name" {
  value = google_compute_security_policy.waf.name
}

output "ssl_policy_name" {
  value = google_compute_ssl_policy.modern.name
}

output "ingress_static_ip" {
  value = google_compute_global_address.ingress.address
}

output "psa_cidr" {
  description = "values-gcp.yaml networkPolicy.database.cidrs"
  value       = "${var.psa_range.address}/${var.psa_range.prefix_length}"
}

output "binauthz_attestor" {
  value = google_binary_authorization_attestor.ci.id
}

output "binauthz_kms_key_version" {
  value = data.google_kms_crypto_key_version.binauthz.id
}

output "github_workload_identity_provider" {
  description = "GitHub variable GCP_WORKLOAD_IDENTITY_PROVIDER"
  value       = var.github_repository == "" ? null : google_iam_workload_identity_pool_provider.github[0].name
}

output "github_deployer_service_account" {
  description = "GitHub variable GCP_SERVICE_ACCOUNT"
  value       = var.github_repository == "" ? null : google_service_account.github_deployer[0].email
}

output "environment" {
  value = var.environment
}

output "github_environments" {
  description = "GitHub Environments allowed to impersonate the deployer SA"
  value       = var.github_repository == "" ? [] : local.github_envs
}
