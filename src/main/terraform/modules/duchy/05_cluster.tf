# Copyright 2020 The Cross-Media Measurement Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

resource "google_service_account" "cluster_service_account" {
  account_id  = var.cluster_info.service_account_name
  display_name = var.cluster_info.service_account_name
}

resource "google_spanner_database_iam_binding" "db_binding" {
  instance = "${google_spanner_instance.duchy_instance.name}"
  database = "${google_spanner_database.duchy_database.name}"
  role = "roles/spanner.databaseUser"

  members = [
    "serviceAccount:${google_service_account.cluster_service_account.email}"
  ]
}

resource "google_project_iam_binding" "log_permissions" {
  project = data.google_client_config.current.project
  role = "roles/logging.logWriter"
  members = [
    "serviceAccount:${google_service_account.cluster_service_account.email}"
  ]
}

resource "google_project_iam_binding" "monitor_write_permissions" {
  project = data.google_client_config.current.project
  role = "roles/monitoring.metricWriter"
  members = [
    "serviceAccount:${google_service_account.cluster_service_account.email}"
  ]
}

resource "google_project_iam_binding" "monitor_view_permissions" {
  project = data.google_client_config.current.project
  role = "roles/monitoring.viewer"
  members = [
    "serviceAccount:${google_service_account.cluster_service_account.email}"
  ]
}

resource "google_project_iam_binding" "stack_driver_permissions" {
  project = data.google_client_config.current.project
  role = "roles/stackdriver.resourceMetadata.writer"
  members = [
    "serviceAccount:${google_service_account.cluster_service_account.email}"
  ]
}

resource "google_kms_key_ring" "k8s_cluster_keyring" {
  count = var.kms_data.key_ring_exists ? 0 : 1
  name = var.kms_data.key_ring_name
  location = data.google_client_config.current.region
}

resource "google_kms_crypto_key" "k8s_cluster_key" {
  count = var.kms_data.key_exists ? 0 : 1
  name = var.kms_data.key_id
  key_ring = "projects/${data.google_client_config.current.project}/locations/${data.google_client_config.current.region}/keyRings/${var.kms_data.key_ring_name}"
  rotation_period = "100000s"

  lifecycle {
    prevent_destroy = false
  }
}

resource "google_kms_crypto_key_iam_binding" "crypto_key_binding" {
  crypto_key_id = "projects/${data.google_client_config.current.project}/locations/${data.google_client_config.current.region}/keyRings/${var.kms_data.key_ring_name}/cryptoKeys/${var.kms_data.key_id}"
  role = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  members = [
    "serviceAccount:service-${data.google_project.project.number}@container-engine-robot.iam.gserviceaccount.com"
  ]
}





resource "google_container_cluster" "primary_cluster" {
  depends_on = [
    null_resource.build-images
  ]

  name     = var.cluster_info.primary_name
  location = data.google_client_config.current.zone
  remove_default_node_pool = true
  initial_node_count       = 1

  network_policy {
    provider = "CALICO"
    enabled = true
  }

  database_encryption {
    state = "ENCRYPTED"
    key_name = "projects/${data.google_client_config.current.project}/locations/${data.google_client_config.current.region}/keyRings/${var.kms_data.key_ring_name}/cryptoKeys/${var.kms_data.key_id}"
  }

  workload_identity_config {
    workload_pool = "${data.google_client_config.current.project}.svc.id.goog"
  }
}

resource "google_container_node_pool" "primary_preemptible_nodes" {
  location   = data.google_client_config.current.zone
  cluster    = google_container_cluster.primary_cluster.name
  node_count = 3
  autoscaling {
    min_node_count = 1
    max_node_count = 5
  }

  node_config {
    machine_type = "e2-medium"

    # Google recommends custom service accounts that have cloud-platform scope and permissions granted via IAM Roles.
    service_account = "${google_service_account.cluster_service_account.email}"
    oauth_scopes    = [
      "https://www.googleapis.com/auth/cloud-platform"
    ]
  }
}
