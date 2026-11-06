# ---- GKE Standard, regional (control plane across me-central1-a/b/c), private nodes, IAM-authenticated DNS endpoint
# (no public IP endpoint), Workload Identity, Dataplane V2 network policy, Shielded nodes, Binary Authorization,
# Secret Manager add-on, Managed Service for Prometheus, Gateway API, application-layer secrets encryption (CMEK).
resource "google_container_cluster" "gke" {
  name                     = "gke-${local.base}"
  location                 = var.region
  network                  = google_compute_network.vpc.id
  subnetwork               = google_compute_subnetwork.gke.id
  networking_mode          = "VPC_NATIVE"
  datapath_provider        = "ADVANCED_DATAPATH"
  remove_default_node_pool = true
  initial_node_count       = 1
  enable_shielded_nodes    = true
  deletion_protection      = var.environment == "prod"
  resource_labels          = local.labels

  ip_allocation_policy {
    cluster_secondary_range_name  = "pods"
    services_secondary_range_name = "services"
  }

  private_cluster_config {
    enable_private_nodes    = true
    enable_private_endpoint = true
    master_ipv4_cidr_block  = var.master_cidr
  }

  control_plane_endpoints_config {
    dns_endpoint_config {
      allow_external_traffic = true # IAM-authenticated (container.clusters.connect); no network exposure of an IP endpoint
    }
  }

  master_authorized_networks_config {
    gcp_public_cidrs_access_enabled = false
  }

  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }

  release_channel {
    channel = "REGULAR"
  }

  binary_authorization {
    evaluation_mode = "PROJECT_SINGLETON_POLICY_ENFORCE"
  }

  secret_manager_config {
    enabled = true
  }

  database_encryption {
    state    = "ENCRYPTED"
    key_name = google_kms_crypto_key.cmek.id
  }

  security_posture_config {
    mode               = "BASIC"
    vulnerability_mode = "VULNERABILITY_BASIC"
  }

  gateway_api_config {
    channel = "CHANNEL_STANDARD"
  }

  addons_config {
    gce_persistent_disk_csi_driver_config {
      enabled = true
    }
    http_load_balancing {
      disabled = false
    }
  }

  logging_config {
    enable_components = ["SYSTEM_COMPONENTS", "WORKLOADS", "APISERVER"]
  }

  monitoring_config {
    enable_components = ["SYSTEM_COMPONENTS", "POD", "DEPLOYMENT", "STATEFULSET"]
    managed_prometheus {
      enabled = true
    }
  }

  maintenance_policy {
    recurring_window {
      start_time = "2026-01-01T22:00:00Z" # Friday 01:00 Asia/Qatar
      end_time   = "2026-01-02T02:00:00Z"
      recurrence = "FREQ=WEEKLY;BYDAY=TH"
    }
  }

  depends_on = [google_kms_crypto_key_iam_member.cmek_users, google_project_service.apis]
}

resource "google_container_node_pool" "system" {
  name       = "system"
  cluster    = google_container_cluster.gke.id
  location   = var.region
  node_count = 1 # per zone → 3 nodes
  node_config {
    machine_type    = var.system_machine_type
    disk_type       = "pd-balanced"
    disk_size_gb    = 50
    service_account = google_service_account.nodes.email
    oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]
    tags            = ["intimed-node"]
    labels          = { pool = "system" }
    taint {
      key    = "CriticalAddonsOnly"
      value  = "true"
      effect = "PREFER_NO_SCHEDULE"
    }
    shielded_instance_config {
      enable_secure_boot          = true
      enable_integrity_monitoring = true
    }
    workload_metadata_config {
      mode = "GKE_METADATA"
    }
  }
  management {
    auto_repair  = true
    auto_upgrade = true
  }
}

resource "google_container_node_pool" "apps" {
  name           = "apps"
  cluster        = google_container_cluster.gke.id
  location       = var.region
  node_locations = var.app_node_locations
  autoscaling {
    min_node_count = var.app_nodes_min_per_zone
    max_node_count = var.app_nodes_max_per_zone
  }
  initial_node_count = 1
  node_config {
    machine_type    = var.app_machine_type
    disk_type       = "pd-balanced"
    disk_size_gb    = 50
    service_account = google_service_account.nodes.email
    oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]
    tags            = ["intimed-node"]
    labels          = { pool = "apps" }
    shielded_instance_config {
      enable_secure_boot          = true
      enable_integrity_monitoring = true
    }
    workload_metadata_config {
      mode = "GKE_METADATA"
    }
  }
  management {
    auto_repair  = true
    auto_upgrade = true
  }
  lifecycle {
    ignore_changes = [initial_node_count]
  }
}
