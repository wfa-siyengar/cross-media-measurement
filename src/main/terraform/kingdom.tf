variable project_id {}
variable region {}
variable zone {}

# resource "google_service_account" "default" {
#  account_id   = "service-account-id"
#  display_name = "Service Account"
# }

# GKE cluster
resource "google_container_cluster" "primary" {
  provider  = google-beta
  # name     = "${var.project_id}-gke"
  name      = "tristan-terraform-test"
  location  = var.zone

  remove_default_node_pool = true
  initial_node_count       = 1

  database_encryption {
    state = "ENCRYPTED"
    key_name = ""
  }

  network_policy {
    enabled = true
  }

  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }

  cluster_autoscaling {
    enabled = false
  }

  monitoring_config {
    managed_prometheus {
      enabled = true
    }
  }
}

# Separately Managed Node Pool
resource "google_container_node_pool" "primary_nodes" {
  name       = google_container_cluster.primary.name
  location   = var.zone
  cluster    = google_container_cluster.primary.name
  version    = "1.24.5-gke.600"

  autoscaling {
    min_node_count = 1
    max_node_count = 3
  }

  node_config {
    service_account = "gke-cluster@${var.project_id}.iam.gserviceaccount.com"
    oauth_scopes = [
      "https://www.googleapis.com/auth/cloud-platform"
    ]

    labels = {
      env = var.project_id
    }

    machine_type = "e2-highcpu-2"
    tags         = ["gke-node", "${var.project_id}-gke"]
    metadata = {
      disable-legacy-endpoints = "true"
    }
  }
}
