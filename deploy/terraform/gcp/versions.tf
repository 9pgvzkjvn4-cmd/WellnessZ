terraform {
  required_version = ">= 1.11.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.9"
    }
    google-beta = {
      source  = "hashicorp/google-beta"
      version = "~> 8.9"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }

  # Remote state in a GCS bucket (uniform access, versioning, CMEK).
  # terraform init -backend-config=backend.hcl   (see backend.hcl.example)
  backend "gcs" {}
}

provider "google" {
  project = var.project_id
  region  = var.region
  default_labels = merge({
    application         = "wellnessz"
    environment         = var.environment
    environment-tier    = var.environment == "prod" ? "production" : "non-production"
    data-classification = "phi-special-category"
    managed-by          = "terraform"
  }, var.labels)
}

provider "google-beta" {
  project = var.project_id
  region  = var.region
}
