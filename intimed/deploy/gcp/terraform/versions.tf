terraform {
  required_version = ">= 1.9"
  required_providers {
    google = { source = "hashicorp/google", version = ">= 6.0, < 8.0" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
  # Remote state in a Cloud Storage bucket in me-central1 (envs/backend-*.hcl, IntiMed_GCP Setup Guide §4).
  backend "gcs" {}
}

provider "google" {
  project = var.project_id
  region  = var.region
}
