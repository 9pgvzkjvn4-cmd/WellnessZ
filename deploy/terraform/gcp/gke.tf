# Least-privilege node service account (Standard mode / Autopilot defaults)
resource "google_service_account" "nodes" {
  account_id   = "gke-nodes-${var.environment}"
  display_name = "WellnessZ GKE nodes (${var.environment})"
}

resource "google_project_iam_member" "nodes" {
  for_each = toset([
    "roles/logging.logWriter",
    "roles/monitoring.metricWriter",
    "roles/monitoring.viewer",
    "roles/stackdriver.resourceMetadata.writer",
    "roles/autoscaling.metricsWriter",
  ])
  project = var.project_id
  role    = each.value
  member  = google_service_account.nodes.member
}

resource "google_artifact_registry_repository_iam_member" "nodes_pull" {
  location   = google_artifact_registry_repository.main.location
  repository = google_artifact_registry_repository.main.name
  role       = "roles/artifactregistry.reader"
  member     = google_service_account.nodes.member
}

resource "google_container_cluster" "main" {
  provider = google-beta
  name     = "gke-${local.name}"
  location = var.region

  enable_autopilot    = var.gke_autopilot ? true : null
  deletion_protection = true

  resource_labels = {
    application         = "wellnessz"
    environment         = var.environment
    data-classification = "phi-special-category"
  }

  network         = google_compute_network.main.id
  subnetwork      = google_compute_subnetwork.gke.id
  networking_mode = "VPC_NATIVE"
  ip_allocation_policy {
    cluster_secondary_range_name  = "pods"
    services_secondary_range_name = "services"
  }

  # Dataplane V2 (Cilium) enforces Kubernetes NetworkPolicies
  datapath_provider = "ADVANCED_DATAPATH"

  private_cluster_config {
    enable_private_nodes    = true
    enable_private_endpoint = var.gke_private_endpoint
    master_ipv4_cidr_block  = var.master_cidr
    master_global_access_config {
      enabled = false
    }
  }

  master_authorized_networks_config {
    gcp_public_cidrs_access_enabled = false
    dynamic "cidr_blocks" {
      for_each = var.gke_master_authorized_networks
      content {
        cidr_block   = cidr_blocks.value.cidr_block
        display_name = cidr_blocks.value.display_name
      }
    }
  }

  release_channel {
    channel = var.gke_release_channel
  }

  workload_identity_config {
    workload_pool = local.wi_pool
  }

  # Kubernetes Secrets encrypted with CMEK (application-layer encryption)
  database_encryption {
    state    = "ENCRYPTED"
    key_name = google_kms_crypto_key.cmek["gke-secrets"].id
  }

  binary_authorization {
    evaluation_mode = "PROJECT_SINGLETON_POLICY_ENFORCE"
  }

  # Managed Secret Manager CSI add-on (provider "gke", driver secrets-store-gke.csi.k8s.io)
  secret_manager_config {
    enabled = true
  }

  security_posture_config {
    mode               = "BASIC"
    vulnerability_mode = "VULNERABILITY_ENTERPRISE"
  }

  # Standard-mode only settings (Autopilot enforces these itself)
  enable_shielded_nodes    = true # always on in Autopilot; explicit for Standard
  remove_default_node_pool = var.gke_autopilot ? null : true
  initial_node_count       = var.gke_autopilot ? null : 1

  dynamic "node_config" {
    for_each = var.gke_autopilot ? [] : [1]
    content {
      service_account = google_service_account.nodes.email
      oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]
    }
  }

  dynamic "cluster_autoscaling" {
    for_each = var.gke_autopilot ? [1] : []
    content {
      auto_provisioning_defaults {
        service_account = google_service_account.nodes.email
        oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]
      }
    }
  }

  logging_config {
    enable_components = var.gke_autopilot ? ["SYSTEM_COMPONENTS", "WORKLOADS", "APISERVER", "CONTROLLER_MANAGER", "SCHEDULER"] : ["SYSTEM_COMPONENTS", "WORKLOADS", "APISERVER"]
  }

  monitoring_config {
    enable_components = ["SYSTEM_COMPONENTS"]
    managed_prometheus {
      enabled = true
    }
  }

  maintenance_policy {
    recurring_window {
      start_time = "2025-01-05T02:00:00Z"
      end_time   = "2025-01-05T06:00:00Z"
      recurrence = "FREQ=WEEKLY;BYDAY=SU"
    }
  }

  depends_on = [
    google_project_service.apis,
    google_kms_crypto_key_iam_member.cmek_agents,
  ]

  lifecycle {
    ignore_changes = [node_config, initial_node_count]
  }
}

# Standard mode: hardened, autoscaled node pool across zones
resource "google_container_node_pool" "apps" {
  count    = var.gke_autopilot ? 0 : 1
  name     = "apps"
  cluster  = google_container_cluster.main.id
  location = var.region

  autoscaling {
    min_node_count  = var.gke_node_min
    max_node_count  = var.gke_node_max
    location_policy = "BALANCED"
  }

  management {
    auto_repair  = true
    auto_upgrade = true
  }

  upgrade_settings {
    max_surge       = 1
    max_unavailable = 0
  }

  node_config {
    machine_type    = var.gke_node_machine_type
    image_type      = "COS_CONTAINERD"
    disk_type       = "pd-balanced"
    disk_size_gb    = 100
    service_account = google_service_account.nodes.email
    oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]

    shielded_instance_config {
      enable_secure_boot          = true
      enable_integrity_monitoring = true
    }

    workload_metadata_config {
      mode = "GKE_METADATA"
    }

    metadata = {
      disable-legacy-endpoints = "true"
    }
  }
}
