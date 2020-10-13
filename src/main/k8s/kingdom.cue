// Copyright 2020 The Measurement System Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package k8s

import ("strings")

#Kingdom: {
	_duchy_ids: [...string]
	_duchy_id_flags: [ for d in _duchy_ids {"--duchy-ids=\(d)"}]

	_spanner_schema_push_flags: [...string]
	_spanner_flags: [...string]

	_images: [Name=_]: string
	_kingdom_image_pull_policy: string

	kingdom_service: [Name=_]: #GrpcService & {
		_name:   Name
		_system: "kingdom"
	}

	kingdom_service: {
		"gcp-kingdom-data-server": {}
		"global-computation-server": {}
		"requisition-server": {}
	}

	kingdom_job: "kingdom-push-spanner-schema-job": {
		apiVersion: "batch/v1"
		kind:       "Job"
		metadata: name: "kingdom-push-spanner-schema-job"
		spec: template: spec: {
			containers: [{
				name:            "push-spanner-schema-container"
				image:           _images[name]
				imagePullPolicy: _kingdom_image_pull_policy
				args:            [
							"--databases=kingdom=/app/wfa_measurement_system/src/main/db/gcp/kingdom.sdl",
				] + _spanner_schema_push_flags
			}]
			restartPolicy: "OnFailure"
		}
	}

	kingdom_pod: [Name=_]: #Pod & {
		_name:            strings.TrimSuffix(Name, "-pod")
		_system:          "kingdom"
		_image:           _images[_name]
		_imagePullPolicy: _kingdom_image_pull_policy
	}

	kingdom_pod: {
		"report-maker-daemon-pod": #Pod & {
			_args: [
				"--debug-verbose-grpc-client-logging=true",
				"--internal-services-target=" + (#Target & {name: "gcp-kingdom-data-server"}).target,
				"--max-concurrency=32",
				"--throttler-overload-factor=1.2",
				"--throttler-poll-delay=1ms",
				"--throttler-time-horizon=2m",
				"--combined-public-key-id=combined-public-key-1",
			]
		}

		"report-starter-daemon-pod": #Pod & {
			_args: [
				"--debug-verbose-grpc-client-logging=true",
				"--internal-services-target=" + (#Target & {name: "gcp-kingdom-data-server"}).target,
				"--max-concurrency=32",
				"--throttler-overload-factor=1.2",
				"--throttler-poll-delay=1ms",
				"--throttler-time-horizon=2m",
			]
		}

		"requisition-linker-daemon-pod": #Pod & {
			_args: [
				"--debug-verbose-grpc-client-logging=true",
				"--internal-services-target=" + (#Target & {name: "gcp-kingdom-data-server"}).target,
				"--max-concurrency=32",
				"--throttler-overload-factor=1.2",
				"--throttler-poll-delay=1ms",
				"--throttler-time-horizon=2m",
			]
		}

		"gcp-kingdom-data-server-pod": #ServerPod & {
			_args: [
				"--debug-verbose-grpc-server-logging=true",
				"--port=8080",
			] + _duchy_id_flags + _spanner_flags
		}

		"global-computation-server-pod": #ServerPod & {
			_args: [
				"--debug-verbose-grpc-client-logging=true",
				"--debug-verbose-grpc-server-logging=true",
				"--internal-api-target=" + (#Target & {name: "gcp-kingdom-data-server"}).target,
				"--port=8080",
			] + _duchy_id_flags
		}

		"requisition-server-pod": #ServerPod & {
			_args: [
				"--debug-verbose-grpc-client-logging=true",
				"--debug-verbose-grpc-server-logging=true",
				"--internal-api-target=" + (#Target & {name: "gcp-kingdom-data-server"}).target,
				"--port=8080",
			] + _duchy_id_flags
		}
	}
}
