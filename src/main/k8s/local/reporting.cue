// Copyright 2022 The Cross-Media Measurement Authors
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

_reportingSecretName:         string @tag("secret_name")
_reportingDbSecretName:       string @tag("db_secret_name")
_reportingMcConfigSecretName: string @tag("mc_config_secret_name")

#ReportingServerResourceConfig: #DefaultResourceConfig & {
}

objectSets: [ for objectSet in reporting {objectSet}]

reporting: #Reporting & {
	_secretName:         _reportingSecretName
	_mcConfigSecretName: _reportingMcConfigSecretName

	_postgresConfig: {
	  host:     (#Target & {name: "postgres"}).host,
	  port:     (#Target & {name: "postgres"}).port,
	  password: "$(POSTGRES_PASSWORD)"
	  user:     "$(POSTGRES_USER)"
	}
	_images: {
		"update-reporting-schema":         "bazel/src/main/kotlin/org/wfanet/measurement/reporting/deploy/postgres/tools:update_schema_image"
		"postgres-reporting-data-server":  "bazel/src/main/kotlin/org/wfanet/measurement/reporting/deploy/postgres/server:postgres_reporting_data_server_image"
		"v1alpha-public-api-server":       "bazel/src/main/kotlin/org/wfanet/measurement/reporting/deploy/common/server:v1alpha_public_api_server_image"
	}
	_resourceConfigs: {
		"postgres-reporting-data-server":   #ReportingServerResourceConfig
		"v1alpha-public-api-server":        #ReportingServerResourceConfig
	}
	_imagePullPolicy: "Never"
	_verboseGrpcServerLogging:  true
	_verboseGrpcClientLogging:  true

	deployments: {
    "postgres-reporting-data-server": {
      _envVars: "POSTGRES_USER": {
        valueFrom:
          secretKeyRef: {
            name: _reportingDbSecretName
            key:  "username"
          }
      }
      _envVars: "POSTGRES_PASSWORD": {
        valueFrom:
          secretKeyRef: {
            name: _reportingDbSecretName
            key:  "password"
          }
      }
    }
  }
}
