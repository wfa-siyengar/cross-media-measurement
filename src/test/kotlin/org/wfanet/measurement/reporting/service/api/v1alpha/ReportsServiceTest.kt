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

package org.wfanet.measurement.reporting.service.api.v1alpha

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.extensions.proto.ProtoTruth.assertThat
import com.google.protobuf.ByteString
import com.google.protobuf.duration
import com.google.protobuf.kotlin.toByteStringUtf8
import com.google.protobuf.timestamp
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.nio.file.Paths
import java.security.SecureRandom
import java.time.Instant
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.KArgumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.wfanet.measurement.api.v2.alpha.ListReportsPageTokenKt.previousPageEnd
import org.wfanet.measurement.api.v2.alpha.listReportsPageToken
import org.wfanet.measurement.api.v2alpha.CertificatesGrpcKt.CertificatesCoroutineImplBase
import org.wfanet.measurement.api.v2alpha.CertificatesGrpcKt.CertificatesCoroutineStub
import org.wfanet.measurement.api.v2alpha.CreateMeasurementRequest
import org.wfanet.measurement.api.v2alpha.DataProviderKey
import org.wfanet.measurement.api.v2alpha.DataProvidersGrpcKt.DataProvidersCoroutineImplBase
import org.wfanet.measurement.api.v2alpha.DataProvidersGrpcKt.DataProvidersCoroutineStub
import org.wfanet.measurement.api.v2alpha.EncryptionPublicKey
import org.wfanet.measurement.api.v2alpha.GetDataProviderRequest
import org.wfanet.measurement.api.v2alpha.Measurement
import org.wfanet.measurement.api.v2alpha.Measurement.DataProviderEntry.Value.ENCRYPTED_REQUISITION_SPEC_FIELD_NUMBER
import org.wfanet.measurement.api.v2alpha.MeasurementConsumerCertificateKey
import org.wfanet.measurement.api.v2alpha.MeasurementConsumerKey
import org.wfanet.measurement.api.v2alpha.MeasurementConsumersGrpcKt.MeasurementConsumersCoroutineImplBase
import org.wfanet.measurement.api.v2alpha.MeasurementConsumersGrpcKt.MeasurementConsumersCoroutineStub
import org.wfanet.measurement.api.v2alpha.MeasurementKey
import org.wfanet.measurement.api.v2alpha.MeasurementKt
import org.wfanet.measurement.api.v2alpha.MeasurementKt.DataProviderEntryKt.value as dataProviderEntryValue
import org.wfanet.measurement.api.v2alpha.MeasurementKt.dataProviderEntry
import org.wfanet.measurement.api.v2alpha.MeasurementKt.failure
import org.wfanet.measurement.api.v2alpha.MeasurementKt.result
import org.wfanet.measurement.api.v2alpha.MeasurementKt.resultPair
import org.wfanet.measurement.api.v2alpha.MeasurementSpec
import org.wfanet.measurement.api.v2alpha.MeasurementSpecKt.duration as measurementSpecDuration
import org.wfanet.measurement.api.v2alpha.MeasurementSpecKt.impression as measurementSpecImpression
import org.wfanet.measurement.api.v2alpha.MeasurementSpecKt.reachAndFrequency as measurementSpecReachAndFrequency
import org.wfanet.measurement.api.v2alpha.MeasurementSpecKt.vidSamplingInterval
import org.wfanet.measurement.api.v2alpha.MeasurementsGrpcKt.MeasurementsCoroutineImplBase
import org.wfanet.measurement.api.v2alpha.MeasurementsGrpcKt.MeasurementsCoroutineStub
import org.wfanet.measurement.api.v2alpha.RequisitionSpec
import org.wfanet.measurement.api.v2alpha.RequisitionSpecKt.EventGroupEntryKt.value as eventGroupEntryValue
import org.wfanet.measurement.api.v2alpha.RequisitionSpecKt.eventFilter as requisitionSpecEventFilter
import org.wfanet.measurement.api.v2alpha.RequisitionSpecKt.eventGroupEntry
import org.wfanet.measurement.api.v2alpha.certificate
import org.wfanet.measurement.api.v2alpha.copy
import org.wfanet.measurement.api.v2alpha.dataProvider
import org.wfanet.measurement.api.v2alpha.differentialPrivacyParams
import org.wfanet.measurement.api.v2alpha.encryptionPublicKey
import org.wfanet.measurement.api.v2alpha.getCertificateRequest
import org.wfanet.measurement.api.v2alpha.getDataProviderRequest
import org.wfanet.measurement.api.v2alpha.getMeasurementConsumerRequest
import org.wfanet.measurement.api.v2alpha.getMeasurementRequest
import org.wfanet.measurement.api.v2alpha.makeDataProviderCertificateName
import org.wfanet.measurement.api.v2alpha.measurement
import org.wfanet.measurement.api.v2alpha.measurementConsumer
import org.wfanet.measurement.api.v2alpha.measurementSpec
import org.wfanet.measurement.api.v2alpha.requisitionSpec
import org.wfanet.measurement.api.v2alpha.signedData
import org.wfanet.measurement.api.v2alpha.timeInterval as measurementTimeInterval
import org.wfanet.measurement.api.v2alpha.withDataProviderPrincipal
import org.wfanet.measurement.common.base64UrlEncode
import org.wfanet.measurement.common.crypto.PrivateKeyHandle
import org.wfanet.measurement.common.crypto.SigningKeyHandle
import org.wfanet.measurement.common.crypto.hashSha256
import org.wfanet.measurement.common.crypto.readCertificate
import org.wfanet.measurement.common.crypto.readPrivateKey
import org.wfanet.measurement.common.crypto.tink.loadPrivateKey
import org.wfanet.measurement.common.crypto.tink.loadPublicKey
import org.wfanet.measurement.common.getRuntimePath
import org.wfanet.measurement.common.grpc.testing.GrpcTestServerRule
import org.wfanet.measurement.common.grpc.testing.mockService
import org.wfanet.measurement.common.identity.externalIdToApiId
import org.wfanet.measurement.common.readByteString
import org.wfanet.measurement.common.testing.captureFirst
import org.wfanet.measurement.common.testing.verifyProtoArgument
import org.wfanet.measurement.config.reporting.measurementConsumerConfig
import org.wfanet.measurement.consent.client.common.toEncryptionPublicKey
import org.wfanet.measurement.consent.client.dataprovider.decryptRequisitionSpec
import org.wfanet.measurement.consent.client.dataprovider.verifyMeasurementSpec
import org.wfanet.measurement.consent.client.dataprovider.verifyRequisitionSpec
import org.wfanet.measurement.consent.client.duchy.encryptResult
import org.wfanet.measurement.consent.client.duchy.signResult
import org.wfanet.measurement.consent.client.measurementconsumer.encryptRequisitionSpec
import org.wfanet.measurement.consent.client.measurementconsumer.signMeasurementSpec
import org.wfanet.measurement.consent.client.measurementconsumer.signRequisitionSpec
import org.wfanet.measurement.internal.reporting.GetReportRequest as GetInternalReportRequest
import org.wfanet.measurement.internal.reporting.GetReportingSetRequest
import org.wfanet.measurement.internal.reporting.Measurement as InternalMeasurement
import org.wfanet.measurement.internal.reporting.MeasurementKt.ResultKt.frequency as internalFrequency
import org.wfanet.measurement.internal.reporting.MeasurementKt.ResultKt.impression as internalImpression
import org.wfanet.measurement.internal.reporting.MeasurementKt.ResultKt.reach as internalReach
import org.wfanet.measurement.internal.reporting.MeasurementKt.ResultKt.watchDuration as internalWatchDuration
import org.wfanet.measurement.internal.reporting.MeasurementKt.failure as internalFailure
import org.wfanet.measurement.internal.reporting.MeasurementKt.result as internalMeasurementResult
import org.wfanet.measurement.internal.reporting.MeasurementsGrpcKt.MeasurementsCoroutineImplBase as InternalMeasurementsCoroutineImplBase
import org.wfanet.measurement.internal.reporting.MeasurementsGrpcKt.MeasurementsCoroutineStub as InternalMeasurementsCoroutineStub
import org.wfanet.measurement.internal.reporting.Metric as InternalMetric
import org.wfanet.measurement.internal.reporting.MetricKt.MeasurementCalculationKt.weightedMeasurement
import org.wfanet.measurement.internal.reporting.MetricKt.SetOperationKt.operand as internalSetOperationOperand
import org.wfanet.measurement.internal.reporting.MetricKt.SetOperationKt.reportingSetKey
import org.wfanet.measurement.internal.reporting.MetricKt.details as internalMetricDetails
import org.wfanet.measurement.internal.reporting.MetricKt.frequencyHistogramParams as internalFrequencyHistogramParams
import org.wfanet.measurement.internal.reporting.MetricKt.impressionCountParams as internalImpressionCountParams
import org.wfanet.measurement.internal.reporting.MetricKt.measurementCalculation
import org.wfanet.measurement.internal.reporting.MetricKt.namedSetOperation as internalNamedSetOperation
import org.wfanet.measurement.internal.reporting.MetricKt.reachParams as internalReachParams
import org.wfanet.measurement.internal.reporting.MetricKt.setOperation as internalSetOperation
import org.wfanet.measurement.internal.reporting.MetricKt.watchDurationParams as internalWatchDurationParams
import org.wfanet.measurement.internal.reporting.Report as InternalReport
import org.wfanet.measurement.internal.reporting.ReportKt.DetailsKt.ResultKt.HistogramTableKt.row as internalRow
import org.wfanet.measurement.internal.reporting.ReportKt.DetailsKt.ResultKt.column as internalColumn
import org.wfanet.measurement.internal.reporting.ReportKt.DetailsKt.ResultKt.histogramTable as internalHistogramTable
import org.wfanet.measurement.internal.reporting.ReportKt.DetailsKt.ResultKt.scalarTable as internalScalarTable
import org.wfanet.measurement.internal.reporting.ReportKt.DetailsKt.result as internalReportResult
import org.wfanet.measurement.internal.reporting.ReportKt.details as internalReportDetails
import org.wfanet.measurement.internal.reporting.ReportingSetKt.eventGroupKey as internalReportingSetEventGroupKey
import org.wfanet.measurement.internal.reporting.ReportingSetsGrpcKt.ReportingSetsCoroutineImplBase as InternalReportingSetsCoroutineImplBase
import org.wfanet.measurement.internal.reporting.ReportingSetsGrpcKt.ReportingSetsCoroutineStub as InternalReportingSetsCoroutineStub
import org.wfanet.measurement.internal.reporting.ReportsGrpcKt.ReportsCoroutineImplBase
import org.wfanet.measurement.internal.reporting.ReportsGrpcKt.ReportsCoroutineStub as InternalReportsCoroutineStub
import org.wfanet.measurement.internal.reporting.StreamReportsRequestKt.filter
import org.wfanet.measurement.internal.reporting.copy
import org.wfanet.measurement.internal.reporting.getMeasurementRequest as getInternalMeasurementRequest
import org.wfanet.measurement.internal.reporting.getReportByIdempotencyKeyRequest
import org.wfanet.measurement.internal.reporting.getReportRequest as getInternalReportRequest
import org.wfanet.measurement.internal.reporting.getReportingSetRequest
import org.wfanet.measurement.internal.reporting.measurement as internalMeasurement
import org.wfanet.measurement.internal.reporting.metric as internalMetric
import org.wfanet.measurement.internal.reporting.periodicTimeInterval as internalPeriodicTimeInterval
import org.wfanet.measurement.internal.reporting.report as internalReport
import org.wfanet.measurement.internal.reporting.reportingSet as internalReportingSet
import org.wfanet.measurement.internal.reporting.setMeasurementFailureRequest
import org.wfanet.measurement.internal.reporting.setMeasurementResultRequest
import org.wfanet.measurement.internal.reporting.streamReportsRequest
import org.wfanet.measurement.internal.reporting.timeInterval as internalTimeInterval
import org.wfanet.measurement.reporting.v1alpha.ListReportsRequest
import org.wfanet.measurement.reporting.v1alpha.Metric
import org.wfanet.measurement.reporting.v1alpha.MetricKt.SetOperationKt.operand as setOperationOperand
import org.wfanet.measurement.reporting.v1alpha.MetricKt.frequencyHistogramParams
import org.wfanet.measurement.reporting.v1alpha.MetricKt.impressionCountParams
import org.wfanet.measurement.reporting.v1alpha.MetricKt.namedSetOperation
import org.wfanet.measurement.reporting.v1alpha.MetricKt.reachParams
import org.wfanet.measurement.reporting.v1alpha.MetricKt.setOperation
import org.wfanet.measurement.reporting.v1alpha.MetricKt.watchDurationParams
import org.wfanet.measurement.reporting.v1alpha.Report
import org.wfanet.measurement.reporting.v1alpha.ReportKt.EventGroupUniverseKt.eventGroupEntry as eventGroupUniverseEntry
import org.wfanet.measurement.reporting.v1alpha.ReportKt.ResultKt.HistogramTableKt.row
import org.wfanet.measurement.reporting.v1alpha.ReportKt.ResultKt.column
import org.wfanet.measurement.reporting.v1alpha.ReportKt.ResultKt.histogramTable
import org.wfanet.measurement.reporting.v1alpha.ReportKt.ResultKt.scalarTable
import org.wfanet.measurement.reporting.v1alpha.ReportKt.eventGroupUniverse
import org.wfanet.measurement.reporting.v1alpha.ReportKt.result as reportResult
import org.wfanet.measurement.reporting.v1alpha.copy
import org.wfanet.measurement.reporting.v1alpha.createReportRequest
import org.wfanet.measurement.reporting.v1alpha.getReportRequest
import org.wfanet.measurement.reporting.v1alpha.listReportsRequest
import org.wfanet.measurement.reporting.v1alpha.listReportsResponse
import org.wfanet.measurement.reporting.v1alpha.metric
import org.wfanet.measurement.reporting.v1alpha.periodicTimeInterval
import org.wfanet.measurement.reporting.v1alpha.report

private const val DEFAULT_PAGE_SIZE = 50
private const val MAX_PAGE_SIZE = 1000
private const val PAGE_SIZE = 3

private const val NUMBER_VID_BUCKETS = 300
private const val REACH_ONLY_VID_SAMPLING_WIDTH = 3.0f / NUMBER_VID_BUCKETS
private const val NUMBER_REACH_ONLY_BUCKETS = 16
private val REACH_ONLY_VID_SAMPLING_START_LIST =
  (0 until NUMBER_REACH_ONLY_BUCKETS).map { it * REACH_ONLY_VID_SAMPLING_WIDTH }
private const val REACH_ONLY_REACH_EPSILON = 0.0041
private const val REACH_ONLY_FREQUENCY_EPSILON = 0.0001
private const val REACH_ONLY_MAXIMUM_FREQUENCY_PER_USER = 1

private const val REACH_FREQUENCY_VID_SAMPLING_WIDTH = 5.0f / NUMBER_VID_BUCKETS
private const val NUMBER_REACH_FREQUENCY_BUCKETS = 19
private val REACH_FREQUENCY_VID_SAMPLING_START_LIST =
  (0 until NUMBER_REACH_FREQUENCY_BUCKETS).map {
    REACH_ONLY_VID_SAMPLING_START_LIST.last() +
      REACH_ONLY_VID_SAMPLING_WIDTH +
      it * REACH_FREQUENCY_VID_SAMPLING_WIDTH
  }
private const val REACH_FREQUENCY_REACH_EPSILON = 0.0033
private const val REACH_FREQUENCY_FREQUENCY_EPSILON = 0.115

private const val IMPRESSION_VID_SAMPLING_WIDTH = 62.0f / NUMBER_VID_BUCKETS
private const val NUMBER_IMPRESSION_BUCKETS = 1
private val IMPRESSION_VID_SAMPLING_START_LIST =
  (0 until NUMBER_IMPRESSION_BUCKETS).map {
    REACH_FREQUENCY_VID_SAMPLING_START_LIST.last() +
      REACH_FREQUENCY_VID_SAMPLING_WIDTH +
      it * IMPRESSION_VID_SAMPLING_WIDTH
  }
private const val IMPRESSION_EPSILON = 0.0011

private const val WATCH_DURATION_VID_SAMPLING_WIDTH = 95.0f / NUMBER_VID_BUCKETS
private const val NUMBER_WATCH_DURATION_BUCKETS = 1
private val WATCH_DURATION_VID_SAMPLING_START_LIST =
  (0 until NUMBER_WATCH_DURATION_BUCKETS).map {
    IMPRESSION_VID_SAMPLING_START_LIST.last() +
      IMPRESSION_VID_SAMPLING_WIDTH +
      it * WATCH_DURATION_VID_SAMPLING_WIDTH
  }
private const val WATCH_DURATION_EPSILON = 0.001

private const val DIFFERENTIAL_PRIVACY_DELTA = 1e-12

private const val SECURE_RANDOM_OUTPUT_INT = 0
private const val SECURE_RANDOM_OUTPUT_LONG = 0L

private val SECRETS_DIR =
  getRuntimePath(
      Paths.get(
        "wfa_measurement_system",
        "src",
        "main",
        "k8s",
        "testing",
        "secretfiles",
      )
    )!!
    .toFile()

// Authentication key
private const val API_AUTHENTICATION_KEY = "nR5QPN7ptx"

// Aggregator certificate
private val AGGREGATOR_CERTIFICATE_DER =
  SECRETS_DIR.resolve("aggregator_cs_cert.der").readByteString()
private val AGGREGATOR_PRIVATE_KEY_DER =
  SECRETS_DIR.resolve("aggregator_cs_private.der").readByteString()
private val AGGREGATOR_SIGNING_KEY: SigningKeyHandle by lazy {
  val consentSignal509Cert = readCertificate(AGGREGATOR_CERTIFICATE_DER)
  SigningKeyHandle(
    consentSignal509Cert,
    readPrivateKey(AGGREGATOR_PRIVATE_KEY_DER, consentSignal509Cert.publicKey.algorithm)
  )
}
private val AGGREGATOR_CERTIFICATE = certificate { x509Der = AGGREGATOR_CERTIFICATE_DER }

// Public keys of measurement consumers
private val MEASUREMENT_PUBLIC_KEY_DATA = SECRETS_DIR.resolve("mc_enc_public.tink").readByteString()
private val MEASUREMENT_PUBLIC_KEY = encryptionPublicKey {
  format = EncryptionPublicKey.Format.TINK_KEYSET
  data = MEASUREMENT_PUBLIC_KEY_DATA
}
private val INVALID_MEASUREMENT_PUBLIC_KEY_DATA = "Invalid public key".toByteStringUtf8()

// Private keys of measurement consumers
private val MEASUREMENT_CONSUMER_CERTIFICATE_DER =
  SECRETS_DIR.resolve("mc_cs_cert.der").readByteString()
private val MEASUREMENT_CONSUMER_PRIVATE_KEY_DER =
  SECRETS_DIR.resolve("mc_cs_private.der").readByteString()
private val MEASUREMENT_CONSUMER_CERTIFICATE = readCertificate(MEASUREMENT_CONSUMER_CERTIFICATE_DER)
private val MEASUREMENT_CONSUMER_SIGNING_PRIVATE_KEY =
  readPrivateKey(
    MEASUREMENT_CONSUMER_PRIVATE_KEY_DER,
    MEASUREMENT_CONSUMER_CERTIFICATE.publicKey.algorithm
  )
private val MEASUREMENT_CONSUMER_SIGNING_KEY_HANDLE =
  SigningKeyHandle(MEASUREMENT_CONSUMER_CERTIFICATE, MEASUREMENT_CONSUMER_SIGNING_PRIVATE_KEY)

// Private key handles of measurement consumers
private val MEASUREMENT_CONSUMER_PRIVATE_KEY_DATA = SECRETS_DIR.resolve("mc_enc_private.tink")
private val MEASUREMENT_CONSUMER_PRIVATE_KEY_HANDLE: PrivateKeyHandle =
  loadPrivateKey(MEASUREMENT_CONSUMER_PRIVATE_KEY_DATA)

// InMemoryEncryptionKeyPairStore
private val ENCRYPTION_KEY_PAIR_STORE =
  InMemoryEncryptionKeyPairStore(
    mapOf(MEASUREMENT_PUBLIC_KEY_DATA to MEASUREMENT_CONSUMER_PRIVATE_KEY_HANDLE)
  )

// Measurement consumer IDs and names
private val MEASUREMENT_CONSUMER_EXTERNAL_IDS = listOf(111L, 112L)
private val MEASUREMENT_CONSUMER_REFERENCE_IDS =
  MEASUREMENT_CONSUMER_EXTERNAL_IDS.map { externalIdToApiId(it) }
private val MEASUREMENT_CONSUMER_NAMES =
  MEASUREMENT_CONSUMER_REFERENCE_IDS.map { MeasurementConsumerKey(it).toName() }

// Measurement consumer certificate IDs
private const val MEASUREMENT_CONSUMER_CERTIFICATE_EXTERNAL_ID = 121L
private val MEASUREMENT_CONSUMER_CERTIFICATE_REFERENCE_ID =
  externalIdToApiId(MEASUREMENT_CONSUMER_CERTIFICATE_EXTERNAL_ID)
private val MEASUREMENT_CONSUMER_CERTIFICATE_NAME =
  MeasurementConsumerCertificateKey(
      MEASUREMENT_CONSUMER_REFERENCE_IDS[0],
      MEASUREMENT_CONSUMER_CERTIFICATE_REFERENCE_ID
    )
    .toName()

private val CONFIG = measurementConsumerConfig {
  apiKey = API_AUTHENTICATION_KEY
  signingCertificateName = MEASUREMENT_CONSUMER_CERTIFICATE_NAME
  signingPrivateKeyPath = "mc_cs_private.der"
}

// Measurement consumers
private val MEASUREMENT_CONSUMER = measurementConsumer {
  name = MEASUREMENT_CONSUMER_NAMES[0]
  certificateDer = MEASUREMENT_CONSUMER_CERTIFICATE_DER
  certificate = MEASUREMENT_CONSUMER_CERTIFICATE_NAME
  publicKey = signedData { data = MEASUREMENT_PUBLIC_KEY_DATA }
}

// Reporting set IDs and names
private val REPORTING_SET_EXTERNAL_IDS = listOf(221L, 222L, 223L, 224L)
private const val REPORTING_SET_EXTERNAL_ID_FOR_MC_2 = 241L

private val REPORTING_SET_NAMES =
  REPORTING_SET_EXTERNAL_IDS.map {
    ReportingSetKey(MEASUREMENT_CONSUMER_REFERENCE_IDS[0], externalIdToApiId(it)).toName()
  }
private const val INVALID_REPORTING_SET_NAME = "INVALID_REPORTING_SET_NAME"
private val REPORTING_SET_NAME_FOR_MC_2 =
  ReportingSetKey(
      MEASUREMENT_CONSUMER_REFERENCE_IDS[1],
      externalIdToApiId(REPORTING_SET_EXTERNAL_ID_FOR_MC_2)
    )
    .toName()

// Report IDs and names
private val REPORT_EXTERNAL_IDS = listOf(331L, 332L, 333L, 334L)
private val REPORT_NAMES =
  REPORT_EXTERNAL_IDS.map {
    ReportKey(MEASUREMENT_CONSUMER_REFERENCE_IDS[0], externalIdToApiId(it)).toName()
  }
// Typo causes invalid name
private const val INVALID_REPORT_NAME = "measurementConsumer/AAAAAAAAAG8/report/AAAAAAAAAU0"

// Data provider IDs and names
private val DATA_PROVIDER_EXTERNAL_IDS = listOf(551L, 552L, 553L)
private val DATA_PROVIDER_REFERENCE_IDS = DATA_PROVIDER_EXTERNAL_IDS.map { externalIdToApiId(it) }

private val DATA_PROVIDER_PUBLIC_KEY =
  loadPublicKey(SECRETS_DIR.resolve("edp1_enc_public.tink")).toEncryptionPublicKey()
private val DATA_PROVIDER_PRIVATE_KEY_HANDLE =
  loadPrivateKey(SECRETS_DIR.resolve("edp1_enc_private.tink"))

// Data provider certificates
private val DATA_PROVIDER_CERTIFICATE_EXTERNAL_IDS = listOf(561L, 562L, 563L)
private val DATA_PROVIDER_CERTIFICATE_REFERENCE_IDS =
  DATA_PROVIDER_CERTIFICATE_EXTERNAL_IDS.map { externalIdToApiId(it) }
private val DATA_PROVIDER_CERTIFICATE_NAMES =
  DATA_PROVIDER_CERTIFICATE_REFERENCE_IDS.mapIndexed { index, referenceId ->
    makeDataProviderCertificateName(DATA_PROVIDER_REFERENCE_IDS[index], referenceId)
  }

// Data providers
private val DATA_PROVIDERS =
  DATA_PROVIDER_REFERENCE_IDS.mapIndexed { index, dataProviderReferenceId ->
    dataProvider {
      name = DataProviderKey(dataProviderReferenceId).toName()
      certificate = DATA_PROVIDER_CERTIFICATE_NAMES[index]
      publicKey = signedData { data = DATA_PROVIDER_PUBLIC_KEY.toByteString() }
    }
  }

// Event group IDs and names
private const val NUMBER_COVERED_EVENT_GROUPS = 3
private val EVENT_GROUP_EXTERNAL_IDS = listOf(661L, 662L, 663L, 664L)
private val EVENT_GROUP_REFERENCE_IDS = EVENT_GROUP_EXTERNAL_IDS.map { externalIdToApiId(it) }

private val COVERED_EVENT_GROUP_NAMES =
  (0 until NUMBER_COVERED_EVENT_GROUPS).map { index ->
    EventGroupKey(
        MEASUREMENT_CONSUMER_REFERENCE_IDS[0],
        DATA_PROVIDER_REFERENCE_IDS[index],
        EVENT_GROUP_REFERENCE_IDS[index]
      )
      .toName()
  }

private val UNCOVERED_EVENT_GROUP_NAME =
  EventGroupKey(
      MEASUREMENT_CONSUMER_REFERENCE_IDS[0],
      DATA_PROVIDER_REFERENCE_IDS[2],
      EVENT_GROUP_REFERENCE_IDS[3]
    )
    .toName()

// Event group keys
private val COVERED_INTERNAL_EVENT_GROUP_KEYS =
  (0 until NUMBER_COVERED_EVENT_GROUPS).map { index ->
    internalReportingSetEventGroupKey {
      measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
      dataProviderReferenceId = DATA_PROVIDER_REFERENCE_IDS[index]
      eventGroupReferenceId = EVENT_GROUP_REFERENCE_IDS[index]
    }
  }
private val UNCOVERED_INTERNAL_EVENT_GROUP_KEY = internalReportingSetEventGroupKey {
  measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
  dataProviderReferenceId = DATA_PROVIDER_REFERENCE_IDS[2]
  eventGroupReferenceId = EVENT_GROUP_REFERENCE_IDS[3]
}

// Reporting sets
private const val REPORTING_SET_FILTER = "AGE>18"

private val REPORTING_SET_DISPLAY_NAMES = REPORTING_SET_NAMES.map { it + REPORTING_SET_FILTER }

private val INTERNAL_REPORTING_SETS =
  (0 until NUMBER_COVERED_EVENT_GROUPS).map { index ->
    internalReportingSet {
      measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
      externalReportingSetId = REPORTING_SET_EXTERNAL_IDS[index]
      eventGroupKeys.add(COVERED_INTERNAL_EVENT_GROUP_KEYS[index])
      filter = REPORTING_SET_FILTER
      displayName = REPORTING_SET_DISPLAY_NAMES[index]
    }
  }
private val UNCOVERED_INTERNAL_REPORTING_SET = internalReportingSet {
  measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
  externalReportingSetId = REPORTING_SET_EXTERNAL_IDS[3]
  eventGroupKeys.add(UNCOVERED_INTERNAL_EVENT_GROUP_KEY)
  filter = REPORTING_SET_FILTER
  displayName = REPORTING_SET_DISPLAY_NAMES[3]
}

// Time intervals
private val START_INSTANT = Instant.now()
private const val DAY_SECONDS = 86400L
private val END_INSTANT =
  Instant.ofEpochSecond(START_INSTANT.epochSecond + DAY_SECONDS, START_INSTANT.nano.toLong())

private val START_TIME = timestamp {
  seconds = START_INSTANT.epochSecond
  nanos = START_INSTANT.nano
}
private val TIME_INTERVAL_INCREMENT = duration { seconds = DAY_SECONDS }
private const val INTERVAL_COUNT = 1
private val END_TIME = timestamp {
  seconds = END_INSTANT.epochSecond
  nanos = END_INSTANT.nano
}
private val MEASUREMENT_TIME_INTERVAL = measurementTimeInterval {
  startTime = START_TIME
  endTime = END_TIME
}
private val INTERNAL_TIME_INTERVAL = internalTimeInterval {
  startTime = START_TIME
  endTime = END_TIME
}
private val INTERNAL_PERIODIC_TIME_INTERVAL = internalPeriodicTimeInterval {
  startTime = START_TIME
  increment = TIME_INTERVAL_INCREMENT
  intervalCount = INTERVAL_COUNT
}
private val PERIODIC_TIME_INTERVAL = periodicTimeInterval {
  startTime = START_TIME
  increment = TIME_INTERVAL_INCREMENT
  intervalCount = INTERVAL_COUNT
}

// Report idempotency keys
private const val REACH_REPORT_IDEMPOTENCY_KEY = "TEST_REACH_REPORT"
private const val IMPRESSION_REPORT_IDEMPOTENCY_KEY = "TEST_IMPRESSION_REPORT"
private const val WATCH_DURATION_REPORT_IDEMPOTENCY_KEY = "TEST_WATCH_DURATION_REPORT"
private const val FREQUENCY_HISTOGRAM_REPORT_IDEMPOTENCY_KEY = "TEST_FREQUENCY_HISTOGRAM_REPORT"

// Set operation unique names
private const val REACH_SET_OPERATION_UNIQUE_NAME = "Reach Set Operation"
private const val FREQUENCY_HISTOGRAM_SET_OPERATION_UNIQUE_NAME =
  "Frequency Histogram Set Operation"
private const val IMPRESSION_SET_OPERATION_UNIQUE_NAME = "Impression Set Operation"
private const val WATCH_DURATION_SET_OPERATION_UNIQUE_NAME = "Watch Duration Set Operation"

// Measurement IDs and names
private val REACH_MEASUREMENT_REFERENCE_ID =
  "$REACH_REPORT_IDEMPOTENCY_KEY-Reach-$REACH_SET_OPERATION_UNIQUE_NAME-$START_INSTANT-" +
    "$END_INSTANT-measurement-0"
private val FREQUENCY_HISTOGRAM_MEASUREMENT_REFERENCE_ID =
  "$FREQUENCY_HISTOGRAM_REPORT_IDEMPOTENCY_KEY-FrequencyHistogram-" +
    "$FREQUENCY_HISTOGRAM_SET_OPERATION_UNIQUE_NAME-$START_INSTANT-$END_INSTANT-measurement-0"
private val IMPRESSION_MEASUREMENT_REFERENCE_ID =
  "$IMPRESSION_REPORT_IDEMPOTENCY_KEY-ImpressionCount-$IMPRESSION_SET_OPERATION_UNIQUE_NAME" +
    "-$START_INSTANT-$END_INSTANT-measurement-0"
private val WATCH_DURATION_MEASUREMENT_REFERENCE_ID =
  "$WATCH_DURATION_REPORT_IDEMPOTENCY_KEY-WatchDuration-$WATCH_DURATION_SET_OPERATION_UNIQUE_NAME" +
    "-$START_INSTANT-$END_INSTANT-measurement-0"

private val REACH_MEASUREMENT_NAME =
  MeasurementKey(MEASUREMENT_CONSUMER_REFERENCE_IDS[0], REACH_MEASUREMENT_REFERENCE_ID).toName()
private val FREQUENCY_HISTOGRAM_MEASUREMENT_NAME =
  MeasurementKey(
      MEASUREMENT_CONSUMER_REFERENCE_IDS[0],
      FREQUENCY_HISTOGRAM_MEASUREMENT_REFERENCE_ID
    )
    .toName()
private val IMPRESSION_MEASUREMENT_NAME =
  MeasurementKey(MEASUREMENT_CONSUMER_REFERENCE_IDS[0], IMPRESSION_MEASUREMENT_REFERENCE_ID)
    .toName()
private val WATCH_DURATION_MEASUREMENT_NAME =
  MeasurementKey(MEASUREMENT_CONSUMER_REFERENCE_IDS[0], WATCH_DURATION_MEASUREMENT_REFERENCE_ID)
    .toName()

// Set operations
private val INTERNAL_SET_OPERATION = internalSetOperation {
  type = InternalMetric.SetOperation.Type.UNION
  lhs = internalSetOperationOperand {
    reportingSetId = reportingSetKey {
      measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
      externalReportingSetId = REPORTING_SET_EXTERNAL_IDS[0]
    }
  }
  rhs = internalSetOperationOperand {
    reportingSetId = reportingSetKey {
      measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
      externalReportingSetId = REPORTING_SET_EXTERNAL_IDS[1]
    }
  }
}

private val SET_OPERATION = setOperation {
  type = Metric.SetOperation.Type.UNION
  lhs = setOperationOperand { reportingSet = REPORTING_SET_NAMES[0] }
  rhs = setOperationOperand { reportingSet = REPORTING_SET_NAMES[1] }
}
private val DATA_PROVIDER_INDICES_IN_SET_OPERATION = listOf(0, 1)

private val SET_OPERATION_WITH_INVALID_REPORTING_SET = setOperation {
  type = Metric.SetOperation.Type.UNION
  lhs = setOperationOperand { reportingSet = INVALID_REPORTING_SET_NAME }
  rhs = setOperationOperand { reportingSet = REPORTING_SET_NAMES[1] }
}

private val SET_OPERATION_WITH_INACCESSIBLE_REPORTING_SET = setOperation {
  type = Metric.SetOperation.Type.UNION
  lhs = setOperationOperand { reportingSet = REPORTING_SET_NAME_FOR_MC_2 }
  rhs = setOperationOperand { reportingSet = REPORTING_SET_NAMES[1] }
}

// Event group filters
private const val EVENT_GROUP_FILTER = "AGE>20"
private val EVENT_GROUP_FILTERS_MAP = COVERED_EVENT_GROUP_NAMES.associateWith { EVENT_GROUP_FILTER }

// Event group entries
private val EVENT_GROUP_ENTRIES =
  COVERED_EVENT_GROUP_NAMES.map {
    eventGroupEntry {
      key = it
      value = eventGroupEntryValue {
        collectionInterval = MEASUREMENT_TIME_INTERVAL
        filter = requisitionSpecEventFilter {
          expression = "($REPORTING_SET_FILTER) AND ($EVENT_GROUP_FILTER)"
        }
      }
    }
  }

// Requisition specs
private val REQUISITION_SPECS =
  EVENT_GROUP_ENTRIES.map {
    requisitionSpec {
      eventGroups.add(it)
      measurementPublicKey = MEASUREMENT_CONSUMER.publicKey.data
      nonce = SECURE_RANDOM_OUTPUT_LONG
    }
  }

// Data provider entries
private val DATA_PROVIDER_ENTRIES =
  (REQUISITION_SPECS.indices).map { index ->
    dataProviderEntry {
      key = DATA_PROVIDERS[index].name
      value = dataProviderEntryValue {
        dataProviderCertificate = DATA_PROVIDERS[index].certificate
        dataProviderPublicKey = DATA_PROVIDERS[index].publicKey
        encryptedRequisitionSpec =
          encryptRequisitionSpec(
            signRequisitionSpec(REQUISITION_SPECS[index], MEASUREMENT_CONSUMER_SIGNING_KEY_HANDLE),
            EncryptionPublicKey.parseFrom(DATA_PROVIDERS[index].publicKey.data)
          )
        nonceHash = hashSha256(REQUISITION_SPECS[index].nonce)
      }
    }
  }

// Measurements
private val BASE_MEASUREMENT = measurement {
  measurementConsumerCertificate = MEASUREMENT_CONSUMER_CERTIFICATE_NAME
}

// Measurement values
private const val REACH_VALUE = 100_000L
private val FREQUENCY_DISTRIBUTION = mapOf(1L to 1.0 / 6, 2L to 2.0 / 6, 3L to 3.0 / 6)
private val IMPRESSION_VALUES = listOf(100L, 150L)
private val TOTAL_IMPRESSION_VALUE = IMPRESSION_VALUES.sum()
private val WATCH_DURATION_SECOND_LIST = listOf(100L, 200L)
private val WATCH_DURATION_LIST = WATCH_DURATION_SECOND_LIST.map { duration { seconds = it } }
private val TOTAL_WATCH_DURATION = duration { seconds = WATCH_DURATION_SECOND_LIST.sum() }

// Reach measurement
private val BASE_REACH_MEASUREMENT =
  BASE_MEASUREMENT.copy {
    name = REACH_MEASUREMENT_NAME
    measurementReferenceId = REACH_MEASUREMENT_REFERENCE_ID
  }

private val PENDING_REACH_MEASUREMENT =
  BASE_REACH_MEASUREMENT.copy { state = Measurement.State.COMPUTING }

private val REACH_ONLY_MEASUREMENT_SPEC = measurementSpec {
  measurementPublicKey = MEASUREMENT_PUBLIC_KEY_DATA

  nonceHashes.addAll(
    listOf(hashSha256(SECURE_RANDOM_OUTPUT_LONG), hashSha256(SECURE_RANDOM_OUTPUT_LONG))
  )

  reachAndFrequency = measurementSpecReachAndFrequency {
    reachPrivacyParams = differentialPrivacyParams {
      epsilon = REACH_ONLY_REACH_EPSILON
      delta = DIFFERENTIAL_PRIVACY_DELTA
    }
    frequencyPrivacyParams = differentialPrivacyParams {
      epsilon = REACH_ONLY_FREQUENCY_EPSILON
      delta = DIFFERENTIAL_PRIVACY_DELTA
    }
    maximumFrequencyPerUser = REACH_ONLY_MAXIMUM_FREQUENCY_PER_USER
  }
  vidSamplingInterval = vidSamplingInterval {
    start = REACH_ONLY_VID_SAMPLING_START_LIST[SECURE_RANDOM_OUTPUT_INT]
    width = REACH_ONLY_VID_SAMPLING_WIDTH
  }
}

private val SUCCEEDED_REACH_MEASUREMENT =
  BASE_REACH_MEASUREMENT.copy {
    dataProviders +=
      DATA_PROVIDER_INDICES_IN_SET_OPERATION.map { index -> DATA_PROVIDER_ENTRIES[index] }

    measurementSpec =
      signMeasurementSpec(REACH_ONLY_MEASUREMENT_SPEC, MEASUREMENT_CONSUMER_SIGNING_KEY_HANDLE)

    state = Measurement.State.SUCCEEDED

    results += resultPair {
      val result = result {
        reach = MeasurementKt.ResultKt.reach { value = REACH_VALUE }
        frequency =
          MeasurementKt.ResultKt.frequency {
            relativeFrequencyDistribution.putAll(FREQUENCY_DISTRIBUTION)
          }
      }
      encryptedResult = getEncryptedResult(result)
      certificate = DATA_PROVIDER_CERTIFICATE_NAMES[0]
    }
  }

private val INTERNAL_PENDING_REACH_MEASUREMENT = internalMeasurement {
  measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
  measurementReferenceId = REACH_MEASUREMENT_REFERENCE_ID
  state = InternalMeasurement.State.PENDING
}
private val INTERNAL_SUCCEEDED_REACH_MEASUREMENT =
  INTERNAL_PENDING_REACH_MEASUREMENT.copy {
    state = InternalMeasurement.State.SUCCEEDED
    result = internalMeasurementResult {
      reach = internalReach { value = REACH_VALUE }
      frequency = internalFrequency { relativeFrequencyDistribution.putAll(FREQUENCY_DISTRIBUTION) }
    }
  }

// Frequency histogram measurement
private val BASE_REACH_FREQUENCY_HISTOGRAM_MEASUREMENT =
  BASE_MEASUREMENT.copy {
    name = FREQUENCY_HISTOGRAM_MEASUREMENT_NAME
    measurementReferenceId = FREQUENCY_HISTOGRAM_MEASUREMENT_REFERENCE_ID
  }

private val PENDING_FREQUENCY_HISTOGRAM_MEASUREMENT =
  BASE_REACH_FREQUENCY_HISTOGRAM_MEASUREMENT.copy { state = Measurement.State.COMPUTING }

private val REACH_FREQUENCY_MEASUREMENT_SPEC = measurementSpec {
  measurementPublicKey = MEASUREMENT_PUBLIC_KEY_DATA

  nonceHashes.addAll(
    listOf(hashSha256(SECURE_RANDOM_OUTPUT_LONG), hashSha256(SECURE_RANDOM_OUTPUT_LONG))
  )

  reachAndFrequency = measurementSpecReachAndFrequency {
    reachPrivacyParams = differentialPrivacyParams {
      epsilon = REACH_FREQUENCY_REACH_EPSILON
      delta = DIFFERENTIAL_PRIVACY_DELTA
    }
    frequencyPrivacyParams = differentialPrivacyParams {
      epsilon = REACH_FREQUENCY_FREQUENCY_EPSILON
      delta = DIFFERENTIAL_PRIVACY_DELTA
    }
    maximumFrequencyPerUser = MAXIMUM_FREQUENCY_PER_USER
  }
  vidSamplingInterval = vidSamplingInterval {
    start = REACH_FREQUENCY_VID_SAMPLING_START_LIST[SECURE_RANDOM_OUTPUT_INT]
    width = REACH_FREQUENCY_VID_SAMPLING_WIDTH
  }
}

private val SUCCEEDED_FREQUENCY_HISTOGRAM_MEASUREMENT =
  BASE_REACH_FREQUENCY_HISTOGRAM_MEASUREMENT.copy {
    dataProviders +=
      DATA_PROVIDER_INDICES_IN_SET_OPERATION.map { index -> DATA_PROVIDER_ENTRIES[index] }

    measurementSpec =
      signMeasurementSpec(REACH_FREQUENCY_MEASUREMENT_SPEC, MEASUREMENT_CONSUMER_SIGNING_KEY_HANDLE)

    state = Measurement.State.SUCCEEDED
    results += resultPair {
      val result = result {
        reach = MeasurementKt.ResultKt.reach { value = REACH_VALUE }
        frequency =
          MeasurementKt.ResultKt.frequency {
            relativeFrequencyDistribution.putAll(FREQUENCY_DISTRIBUTION)
          }
      }
      encryptedResult = getEncryptedResult(result)
      certificate = DATA_PROVIDER_CERTIFICATE_NAMES[0]
    }
  }

private val INTERNAL_PENDING_FREQUENCY_HISTOGRAM_MEASUREMENT = internalMeasurement {
  measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
  measurementReferenceId = FREQUENCY_HISTOGRAM_MEASUREMENT_REFERENCE_ID
  state = InternalMeasurement.State.PENDING
}

private val INTERNAL_SUCCEEDED_FREQUENCY_HISTOGRAM_MEASUREMENT =
  INTERNAL_PENDING_FREQUENCY_HISTOGRAM_MEASUREMENT.copy {
    state = InternalMeasurement.State.SUCCEEDED
    result = internalMeasurementResult {
      reach = internalReach { value = REACH_VALUE }
      frequency = internalFrequency { relativeFrequencyDistribution.putAll(FREQUENCY_DISTRIBUTION) }
    }
  }

// Impression measurement
private val BASE_IMPRESSION_MEASUREMENT =
  BASE_MEASUREMENT.copy {
    name = IMPRESSION_MEASUREMENT_NAME
    measurementReferenceId = IMPRESSION_MEASUREMENT_REFERENCE_ID
  }

private val PENDING_IMPRESSION_MEASUREMENT =
  BASE_IMPRESSION_MEASUREMENT.copy { state = Measurement.State.COMPUTING }

private val IMPRESSION_MEASUREMENT_SPEC = measurementSpec {
  measurementPublicKey = MEASUREMENT_PUBLIC_KEY_DATA

  nonceHashes.addAll(
    listOf(hashSha256(SECURE_RANDOM_OUTPUT_LONG), hashSha256(SECURE_RANDOM_OUTPUT_LONG))
  )

  impression = measurementSpecImpression {
    privacyParams = differentialPrivacyParams {
      epsilon = IMPRESSION_EPSILON
      delta = DIFFERENTIAL_PRIVACY_DELTA
    }
    maximumFrequencyPerUser = MAXIMUM_FREQUENCY_PER_USER
  }
  vidSamplingInterval = vidSamplingInterval {
    start = IMPRESSION_VID_SAMPLING_START_LIST[SECURE_RANDOM_OUTPUT_INT]
    width = IMPRESSION_VID_SAMPLING_WIDTH
  }
}

private val SUCCEEDED_IMPRESSION_MEASUREMENT =
  BASE_IMPRESSION_MEASUREMENT.copy {
    dataProviders +=
      DATA_PROVIDER_INDICES_IN_SET_OPERATION.map { index -> DATA_PROVIDER_ENTRIES[index] }

    measurementSpec =
      signMeasurementSpec(IMPRESSION_MEASUREMENT_SPEC, MEASUREMENT_CONSUMER_SIGNING_KEY_HANDLE)

    state = Measurement.State.SUCCEEDED

    results += resultPair {
      val result = result {
        impression = MeasurementKt.ResultKt.impression { value = IMPRESSION_VALUES[0] }
      }
      encryptedResult = getEncryptedResult(result)
      certificate = DATA_PROVIDER_CERTIFICATE_NAMES[0]
    }
    results += resultPair {
      val result = result {
        impression = MeasurementKt.ResultKt.impression { value = IMPRESSION_VALUES[1] }
      }
      encryptedResult = getEncryptedResult(result)
      certificate = DATA_PROVIDER_CERTIFICATE_NAMES[1]
    }
  }

private val INTERNAL_PENDING_IMPRESSION_MEASUREMENT = internalMeasurement {
  measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
  measurementReferenceId = IMPRESSION_MEASUREMENT_REFERENCE_ID
  state = InternalMeasurement.State.PENDING
}

private val INTERNAL_SUCCEEDED_IMPRESSION_MEASUREMENT =
  INTERNAL_PENDING_IMPRESSION_MEASUREMENT.copy {
    state = InternalMeasurement.State.SUCCEEDED
    result = internalMeasurementResult {
      impression = internalImpression { value = TOTAL_IMPRESSION_VALUE }
    }
  }

// Watch Duration measurement
private val BASE_WATCH_DURATION_MEASUREMENT =
  BASE_MEASUREMENT.copy {
    name = WATCH_DURATION_MEASUREMENT_NAME
    measurementReferenceId = WATCH_DURATION_MEASUREMENT_REFERENCE_ID
  }

private val PENDING_WATCH_DURATION_MEASUREMENT =
  BASE_WATCH_DURATION_MEASUREMENT.copy { state = Measurement.State.COMPUTING }

private val WATCH_DURATION_MEASUREMENT_SPEC = measurementSpec {
  measurementPublicKey = MEASUREMENT_PUBLIC_KEY_DATA

  nonceHashes.addAll(
    listOf(hashSha256(SECURE_RANDOM_OUTPUT_LONG), hashSha256(SECURE_RANDOM_OUTPUT_LONG))
  )

  duration = measurementSpecDuration {
    privacyParams = differentialPrivacyParams {
      epsilon = WATCH_DURATION_EPSILON
      delta = DIFFERENTIAL_PRIVACY_DELTA
    }
    maximumWatchDurationPerUser = MAXIMUM_WATCH_DURATION_PER_USER
    maximumFrequencyPerUser = MAXIMUM_FREQUENCY_PER_USER
  }
  vidSamplingInterval = vidSamplingInterval {
    start = WATCH_DURATION_VID_SAMPLING_START_LIST[SECURE_RANDOM_OUTPUT_INT]
    width = WATCH_DURATION_VID_SAMPLING_WIDTH
  }
}

private val SUCCEEDED_WATCH_DURATION_MEASUREMENT =
  BASE_WATCH_DURATION_MEASUREMENT.copy {
    dataProviders +=
      DATA_PROVIDER_INDICES_IN_SET_OPERATION.map { index -> DATA_PROVIDER_ENTRIES[index] }

    measurementSpec =
      signMeasurementSpec(WATCH_DURATION_MEASUREMENT_SPEC, MEASUREMENT_CONSUMER_SIGNING_KEY_HANDLE)

    state = Measurement.State.SUCCEEDED

    results += resultPair {
      val result = result {
        watchDuration = MeasurementKt.ResultKt.watchDuration { value = WATCH_DURATION_LIST[0] }
      }
      encryptedResult = getEncryptedResult(result)
      certificate = DATA_PROVIDER_CERTIFICATE_NAMES[0]
    }
    results += resultPair {
      val result = result {
        watchDuration = MeasurementKt.ResultKt.watchDuration { value = WATCH_DURATION_LIST[1] }
      }
      encryptedResult = getEncryptedResult(result)
      certificate = DATA_PROVIDER_CERTIFICATE_NAMES[1]
    }
  }

private val INTERNAL_PENDING_WATCH_DURATION_MEASUREMENT = internalMeasurement {
  measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
  measurementReferenceId = WATCH_DURATION_MEASUREMENT_REFERENCE_ID
  state = InternalMeasurement.State.PENDING
}
private val INTERNAL_SUCCEEDED_WATCH_DURATION_MEASUREMENT =
  INTERNAL_PENDING_WATCH_DURATION_MEASUREMENT.copy {
    state = InternalMeasurement.State.SUCCEEDED
    result = internalMeasurementResult {
      watchDuration = internalWatchDuration { value = TOTAL_WATCH_DURATION }
    }
  }

/** Verify and decrypt a measurement result. */
private fun getEncryptedResult(
  result: Measurement.Result,
): ByteString {
  val signedResult = signResult(result, AGGREGATOR_SIGNING_KEY)
  return encryptResult(signedResult, MEASUREMENT_PUBLIC_KEY)
}

// Weighted measurements
private val WEIGHTED_REACH_MEASUREMENT = weightedMeasurement {
  measurementReferenceId = REACH_MEASUREMENT_REFERENCE_ID
  coefficient = 1
}

private val WEIGHTED_FREQUENCY_HISTOGRAM_MEASUREMENT = weightedMeasurement {
  measurementReferenceId = FREQUENCY_HISTOGRAM_MEASUREMENT_REFERENCE_ID
  coefficient = 1
}

private val WEIGHTED_IMPRESSION_MEASUREMENT = weightedMeasurement {
  measurementReferenceId = IMPRESSION_MEASUREMENT_REFERENCE_ID
  coefficient = 1
}

private val WEIGHTED_WATCH_DURATION_MEASUREMENT = weightedMeasurement {
  measurementReferenceId = WATCH_DURATION_MEASUREMENT_REFERENCE_ID
  coefficient = 1
}

// Measurement Calculations
private val REACH_MEASUREMENT_CALCULATION = measurementCalculation {
  timeInterval = INTERNAL_TIME_INTERVAL
  weightedMeasurements.add(WEIGHTED_REACH_MEASUREMENT)
}

private val FREQUENCY_HISTOGRAM_MEASUREMENT_CALCULATION = measurementCalculation {
  timeInterval = INTERNAL_TIME_INTERVAL
  weightedMeasurements.add(WEIGHTED_FREQUENCY_HISTOGRAM_MEASUREMENT)
}

private val IMPRESSION_MEASUREMENT_CALCULATION = measurementCalculation {
  timeInterval = INTERNAL_TIME_INTERVAL
  weightedMeasurements.add(WEIGHTED_IMPRESSION_MEASUREMENT)
}

private val WATCH_DURATION_MEASUREMENT_CALCULATION = measurementCalculation {
  timeInterval = INTERNAL_TIME_INTERVAL
  weightedMeasurements.add(WEIGHTED_WATCH_DURATION_MEASUREMENT)
}

// Named set operations
// Reach set operation
private val INTERNAL_NAMED_REACH_SET_OPERATION = internalNamedSetOperation {
  displayName = REACH_SET_OPERATION_UNIQUE_NAME
  setOperation = INTERNAL_SET_OPERATION
  measurementCalculations += REACH_MEASUREMENT_CALCULATION
}
private val NAMED_REACH_SET_OPERATION = namedSetOperation {
  uniqueName = REACH_SET_OPERATION_UNIQUE_NAME
  setOperation = SET_OPERATION
}
// Frequency histogram set operation
private val INTERNAL_NAMED_FREQUENCY_HISTOGRAM_SET_OPERATION = internalNamedSetOperation {
  displayName = FREQUENCY_HISTOGRAM_SET_OPERATION_UNIQUE_NAME
  setOperation = INTERNAL_SET_OPERATION
  measurementCalculations += FREQUENCY_HISTOGRAM_MEASUREMENT_CALCULATION
}
private val NAMED_FREQUENCY_HISTOGRAM_SET_OPERATION = namedSetOperation {
  uniqueName = FREQUENCY_HISTOGRAM_SET_OPERATION_UNIQUE_NAME
  setOperation = SET_OPERATION
}
// Impression set operation
private val INTERNAL_NAMED_IMPRESSION_SET_OPERATION = internalNamedSetOperation {
  displayName = IMPRESSION_SET_OPERATION_UNIQUE_NAME
  setOperation = INTERNAL_SET_OPERATION
  measurementCalculations += IMPRESSION_MEASUREMENT_CALCULATION
}
private val NAMED_IMPRESSION_SET_OPERATION = namedSetOperation {
  uniqueName = IMPRESSION_SET_OPERATION_UNIQUE_NAME
  setOperation = SET_OPERATION
}
// Watch duration set operation
private val INTERNAL_NAMED_WATCH_DURATION_SET_OPERATION = internalNamedSetOperation {
  displayName = WATCH_DURATION_SET_OPERATION_UNIQUE_NAME
  setOperation = INTERNAL_SET_OPERATION
  measurementCalculations += WATCH_DURATION_MEASUREMENT_CALCULATION
}
private val NAMED_WATCH_DURATION_SET_OPERATION = namedSetOperation {
  uniqueName = WATCH_DURATION_SET_OPERATION_UNIQUE_NAME
  setOperation = SET_OPERATION
}

// Internal metrics
private const val MAXIMUM_FREQUENCY_PER_USER = 10
private const val MAXIMUM_WATCH_DURATION_PER_USER = 300

// Reach metric
private val REACH_METRIC = metric {
  reach = reachParams {}
  cumulative = false
  setOperations.add(NAMED_REACH_SET_OPERATION)
}
private val INTERNAL_REACH_METRIC = internalMetric {
  details = internalMetricDetails {
    reach = internalReachParams {}
    cumulative = false
  }
  namedSetOperations.add(INTERNAL_NAMED_REACH_SET_OPERATION)
}
// Frequency histogram metric
private val FREQUENCY_HISTOGRAM_METRIC = metric {
  frequencyHistogram = frequencyHistogramParams {
    maximumFrequencyPerUser = MAXIMUM_FREQUENCY_PER_USER
  }
  cumulative = false
  setOperations.add(NAMED_FREQUENCY_HISTOGRAM_SET_OPERATION)
}
private val INTERNAL_FREQUENCY_HISTOGRAM_METRIC = internalMetric {
  details = internalMetricDetails {
    frequencyHistogram = internalFrequencyHistogramParams {
      maximumFrequencyPerUser = MAXIMUM_FREQUENCY_PER_USER
    }
    cumulative = false
  }
  namedSetOperations.add(INTERNAL_NAMED_FREQUENCY_HISTOGRAM_SET_OPERATION)
}
// Impression metric
private val IMPRESSION_METRIC = metric {
  impressionCount = impressionCountParams { maximumFrequencyPerUser = MAXIMUM_FREQUENCY_PER_USER }
  cumulative = false
  setOperations.add(NAMED_IMPRESSION_SET_OPERATION)
}
private val INTERNAL_IMPRESSION_METRIC = internalMetric {
  details = internalMetricDetails {
    impressionCount = internalImpressionCountParams {
      maximumFrequencyPerUser = MAXIMUM_FREQUENCY_PER_USER
    }
    cumulative = false
  }
  namedSetOperations.add(INTERNAL_NAMED_IMPRESSION_SET_OPERATION)
}
// Watch duration metric
private val WATCH_DURATION_METRIC = metric {
  watchDuration = watchDurationParams {
    maximumFrequencyPerUser = MAXIMUM_FREQUENCY_PER_USER
    maximumWatchDurationPerUser = MAXIMUM_WATCH_DURATION_PER_USER
  }
  cumulative = false
  setOperations.add(NAMED_WATCH_DURATION_SET_OPERATION)
}
private val INTERNAL_WATCH_DURATION_METRIC = internalMetric {
  details = internalMetricDetails {
    watchDuration = internalWatchDurationParams {
      maximumFrequencyPerUser = MAXIMUM_FREQUENCY_PER_USER
      maximumWatchDurationPerUser = MAXIMUM_WATCH_DURATION_PER_USER
    }
    cumulative = false
  }
  namedSetOperations.add(INTERNAL_NAMED_WATCH_DURATION_SET_OPERATION)
}

// Internal reports with running states
// Internal reports of reach
private val INTERNAL_PENDING_REACH_REPORT = internalReport {
  measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
  externalReportId = REPORT_EXTERNAL_IDS[0]
  periodicTimeInterval = INTERNAL_PERIODIC_TIME_INTERVAL
  metrics.add(INTERNAL_REACH_METRIC)
  state = InternalReport.State.RUNNING
  measurements.put(REACH_MEASUREMENT_REFERENCE_ID, INTERNAL_PENDING_REACH_MEASUREMENT)
  details = internalReportDetails { eventGroupFilters.putAll(EVENT_GROUP_FILTERS_MAP) }
  createTime = timestamp { seconds = 1000 }
  reportIdempotencyKey = REACH_REPORT_IDEMPOTENCY_KEY
}
private val INTERNAL_SUCCEEDED_REACH_REPORT =
  INTERNAL_PENDING_REACH_REPORT.copy {
    state = InternalReport.State.SUCCEEDED
    measurements.put(REACH_MEASUREMENT_REFERENCE_ID, INTERNAL_SUCCEEDED_REACH_MEASUREMENT)
  }
// Internal reports of impression
private val INTERNAL_PENDING_IMPRESSION_REPORT = internalReport {
  measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
  externalReportId = REPORT_EXTERNAL_IDS[1]
  periodicTimeInterval = INTERNAL_PERIODIC_TIME_INTERVAL
  metrics.add(INTERNAL_IMPRESSION_METRIC)
  state = InternalReport.State.RUNNING
  measurements.put(IMPRESSION_MEASUREMENT_REFERENCE_ID, INTERNAL_PENDING_IMPRESSION_MEASUREMENT)
  details = internalReportDetails { eventGroupFilters.putAll(EVENT_GROUP_FILTERS_MAP) }
  createTime = timestamp { seconds = 2000 }
  reportIdempotencyKey = IMPRESSION_REPORT_IDEMPOTENCY_KEY
}
private val INTERNAL_SUCCEEDED_IMPRESSION_REPORT =
  INTERNAL_PENDING_IMPRESSION_REPORT.copy {
    state = InternalReport.State.SUCCEEDED
    measurements.put(IMPRESSION_MEASUREMENT_REFERENCE_ID, INTERNAL_SUCCEEDED_IMPRESSION_MEASUREMENT)
  }
// Internal reports of watch duration
private val INTERNAL_PENDING_WATCH_DURATION_REPORT = internalReport {
  measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
  externalReportId = REPORT_EXTERNAL_IDS[2]
  periodicTimeInterval = INTERNAL_PERIODIC_TIME_INTERVAL
  metrics.add(INTERNAL_WATCH_DURATION_METRIC)
  state = InternalReport.State.RUNNING
  measurements.put(
    WATCH_DURATION_MEASUREMENT_REFERENCE_ID,
    INTERNAL_PENDING_WATCH_DURATION_MEASUREMENT
  )
  details = internalReportDetails { eventGroupFilters.putAll(EVENT_GROUP_FILTERS_MAP) }
  createTime = timestamp { seconds = 3000 }
  reportIdempotencyKey = WATCH_DURATION_REPORT_IDEMPOTENCY_KEY
}
private val INTERNAL_SUCCEEDED_WATCH_DURATION_REPORT =
  INTERNAL_PENDING_WATCH_DURATION_REPORT.copy {
    state = InternalReport.State.SUCCEEDED
    measurements.put(
      WATCH_DURATION_MEASUREMENT_REFERENCE_ID,
      INTERNAL_SUCCEEDED_WATCH_DURATION_MEASUREMENT
    )
  }
// Internal reports of frequency histogram
private val INTERNAL_PENDING_FREQUENCY_HISTOGRAM_REPORT = internalReport {
  measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
  externalReportId = REPORT_EXTERNAL_IDS[3]
  periodicTimeInterval = INTERNAL_PERIODIC_TIME_INTERVAL
  metrics.add(INTERNAL_FREQUENCY_HISTOGRAM_METRIC)
  state = InternalReport.State.RUNNING
  measurements.put(
    FREQUENCY_HISTOGRAM_MEASUREMENT_REFERENCE_ID,
    INTERNAL_PENDING_FREQUENCY_HISTOGRAM_MEASUREMENT
  )
  details = internalReportDetails { eventGroupFilters.putAll(EVENT_GROUP_FILTERS_MAP) }
  createTime = timestamp { seconds = 4000 }
  reportIdempotencyKey = FREQUENCY_HISTOGRAM_REPORT_IDEMPOTENCY_KEY
}
private val INTERNAL_SUCCEEDED_FREQUENCY_HISTOGRAM_REPORT =
  INTERNAL_PENDING_FREQUENCY_HISTOGRAM_REPORT.copy {
    state = InternalReport.State.SUCCEEDED
    measurements.put(
      FREQUENCY_HISTOGRAM_MEASUREMENT_REFERENCE_ID,
      INTERNAL_SUCCEEDED_FREQUENCY_HISTOGRAM_MEASUREMENT
    )
  }

// Event Group Universe
private val EVENT_GROUP_UNIVERSE_ENTRIES =
  COVERED_EVENT_GROUP_NAMES.map {
    eventGroupUniverseEntry {
      key = it
      value = EVENT_GROUP_FILTER
    }
  }

private val EVENT_GROUP_UNIVERSE = eventGroupUniverse {
  eventGroupEntries += EVENT_GROUP_UNIVERSE_ENTRIES
}

// Public reports with running states
// Reports of reach
private val PENDING_REACH_REPORT = report {
  name = REPORT_NAMES[0]
  reportIdempotencyKey = REACH_REPORT_IDEMPOTENCY_KEY
  measurementConsumer = MEASUREMENT_CONSUMER_NAMES[0]
  eventGroupUniverse = EVENT_GROUP_UNIVERSE
  periodicTimeInterval = PERIODIC_TIME_INTERVAL
  metrics.add(REACH_METRIC)
  state = Report.State.RUNNING
}
private val SUCCEEDED_REACH_REPORT = PENDING_REACH_REPORT.copy { state = Report.State.SUCCEEDED }
// Reports of impression
private val PENDING_IMPRESSION_REPORT = report {
  name = REPORT_NAMES[1]
  reportIdempotencyKey = IMPRESSION_REPORT_IDEMPOTENCY_KEY
  measurementConsumer = MEASUREMENT_CONSUMER_NAMES[0]
  eventGroupUniverse = EVENT_GROUP_UNIVERSE
  periodicTimeInterval = PERIODIC_TIME_INTERVAL
  metrics.add(IMPRESSION_METRIC)
  state = Report.State.RUNNING
}
private val SUCCEEDED_IMPRESSION_REPORT =
  PENDING_IMPRESSION_REPORT.copy { state = Report.State.SUCCEEDED }
// Reports of watch duration
private val PENDING_WATCH_DURATION_REPORT = report {
  name = REPORT_NAMES[2]
  reportIdempotencyKey = WATCH_DURATION_REPORT_IDEMPOTENCY_KEY
  measurementConsumer = MEASUREMENT_CONSUMER_NAMES[0]
  eventGroupUniverse = EVENT_GROUP_UNIVERSE
  periodicTimeInterval = PERIODIC_TIME_INTERVAL
  metrics.add(WATCH_DURATION_METRIC)
  state = Report.State.RUNNING
}
private val SUCCEEDED_WATCH_DURATION_REPORT =
  PENDING_WATCH_DURATION_REPORT.copy { state = Report.State.SUCCEEDED }
// Reports of frequency histogram
private val PENDING_FREQUENCY_HISTOGRAM_REPORT = report {
  name = REPORT_NAMES[3]
  reportIdempotencyKey = FREQUENCY_HISTOGRAM_REPORT_IDEMPOTENCY_KEY
  measurementConsumer = MEASUREMENT_CONSUMER_NAMES[0]
  eventGroupUniverse = EVENT_GROUP_UNIVERSE
  periodicTimeInterval = PERIODIC_TIME_INTERVAL
  metrics.add(FREQUENCY_HISTOGRAM_METRIC)
  state = Report.State.RUNNING
}
private val SUCCEEDED_FREQUENCY_HISTOGRAM_REPORT =
  PENDING_FREQUENCY_HISTOGRAM_REPORT.copy { state = Report.State.SUCCEEDED }

@RunWith(JUnit4::class)
class ReportsServiceTest {

  private val internalReportsMock: ReportsCoroutineImplBase = mockService {
    onBlocking { createReport(any()) }
      .thenReturn(
        INTERNAL_PENDING_REACH_REPORT,
        INTERNAL_PENDING_IMPRESSION_REPORT,
        INTERNAL_PENDING_WATCH_DURATION_REPORT,
        INTERNAL_PENDING_FREQUENCY_HISTOGRAM_REPORT,
      )
    onBlocking { streamReports(any()) }
      .thenReturn(
        flowOf(
          INTERNAL_PENDING_REACH_REPORT,
          INTERNAL_PENDING_IMPRESSION_REPORT,
          INTERNAL_PENDING_WATCH_DURATION_REPORT,
          INTERNAL_PENDING_FREQUENCY_HISTOGRAM_REPORT,
        )
      )
    onBlocking { getReport(any()) }
      .thenReturn(
        INTERNAL_SUCCEEDED_REACH_REPORT,
        INTERNAL_SUCCEEDED_IMPRESSION_REPORT,
        INTERNAL_SUCCEEDED_WATCH_DURATION_REPORT,
        INTERNAL_SUCCEEDED_FREQUENCY_HISTOGRAM_REPORT,
      )
    onBlocking { getReportByIdempotencyKey(any()) }
      .thenThrow(StatusRuntimeException(Status.NOT_FOUND))
  }

  private val internalReportingSetsMock: InternalReportingSetsCoroutineImplBase = mockService {
    onBlocking { getReportingSet(any()) }
      .thenReturn(
        INTERNAL_REPORTING_SETS[0],
        INTERNAL_REPORTING_SETS[1],
        INTERNAL_REPORTING_SETS[0],
        INTERNAL_REPORTING_SETS[1]
      )
  }

  private val internalMeasurementsMock: InternalMeasurementsCoroutineImplBase = mockService {
    onBlocking { getMeasurement(any()) }.thenThrow(StatusRuntimeException(Status.NOT_FOUND))
  }

  private val measurementsMock: MeasurementsCoroutineImplBase = mockService {
    onBlocking { getMeasurement(any()) }
      .thenReturn(
        SUCCEEDED_REACH_MEASUREMENT,
        SUCCEEDED_IMPRESSION_MEASUREMENT,
        SUCCEEDED_WATCH_DURATION_MEASUREMENT,
        SUCCEEDED_FREQUENCY_HISTOGRAM_MEASUREMENT,
      )
  }

  private val measurementConsumersMock: MeasurementConsumersCoroutineImplBase = mockService {
    onBlocking { getMeasurementConsumer(any()) }.thenReturn(MEASUREMENT_CONSUMER)
  }

  private val dataProvidersMock: DataProvidersCoroutineImplBase = mockService {
    onBlocking { getDataProvider(any()) }.thenReturn(DATA_PROVIDERS[0], DATA_PROVIDERS[1])
  }

  private val certificateMock: CertificatesCoroutineImplBase = mockService {
    onBlocking {
        getCertificate(eq(getCertificateRequest { name = DATA_PROVIDER_CERTIFICATE_NAMES[0] }))
      }
      .thenReturn(AGGREGATOR_CERTIFICATE)
    onBlocking {
        getCertificate(eq(getCertificateRequest { name = DATA_PROVIDER_CERTIFICATE_NAMES[1] }))
      }
      .thenReturn(AGGREGATOR_CERTIFICATE)
    onBlocking {
        getCertificate(eq(getCertificateRequest { name = MEASUREMENT_CONSUMER_CERTIFICATE_NAME }))
      }
      .thenReturn(certificate { x509Der = MEASUREMENT_CONSUMER_CERTIFICATE_DER })
  }

  private val secureRandomMock: SecureRandom = mock()

  @get:Rule
  val grpcTestServerRule = GrpcTestServerRule {
    addService(internalReportsMock)
    addService(internalReportingSetsMock)
    addService(internalMeasurementsMock)
    addService(measurementsMock)
    addService(measurementConsumersMock)
    addService(dataProvidersMock)
    addService(certificateMock)
  }

  private lateinit var service: ReportsService

  @Before
  fun initService() {
    secureRandomMock.stub {
      on { nextInt(any()) } doReturn SECURE_RANDOM_OUTPUT_INT
      on { nextLong() } doReturn SECURE_RANDOM_OUTPUT_LONG
    }

    service =
      ReportsService(
        InternalReportsCoroutineStub(grpcTestServerRule.channel),
        InternalReportingSetsCoroutineStub(grpcTestServerRule.channel),
        InternalMeasurementsCoroutineStub(grpcTestServerRule.channel),
        DataProvidersCoroutineStub(grpcTestServerRule.channel),
        MeasurementConsumersCoroutineStub(grpcTestServerRule.channel),
        MeasurementsCoroutineStub(grpcTestServerRule.channel),
        CertificatesCoroutineStub(grpcTestServerRule.channel),
        ENCRYPTION_KEY_PAIR_STORE,
        secureRandomMock,
        SECRETS_DIR
      )
  }

  @Test
  fun `createReport returns a report of reach with RUNNING state`() {
    val request = createReportRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      report = PENDING_REACH_REPORT.copy { clearState() }
    }

    val result =
      withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
        runBlocking { service.createReport(request) }
      }

    val expected = PENDING_REACH_REPORT

    // Verify proto argument of ReportsCoroutineImplBase::getReportByIdempotencyKey
    verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::getReportByIdempotencyKey)
      .isEqualTo(
        getReportByIdempotencyKeyRequest {
          measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
          reportIdempotencyKey = REACH_REPORT_IDEMPOTENCY_KEY
        }
      )

    // Verify proto argument of InternalReportingSetsCoroutineImplBase::getReportingSet
    val internalReportingSetCaptor: KArgumentCaptor<GetReportingSetRequest> = argumentCaptor()
    verifyBlocking(internalReportingSetsMock, times(4)) {
      getReportingSet(internalReportingSetCaptor.capture())
    }
    val capturedInternalReportingSetRequests = internalReportingSetCaptor.allValues
    val expectedInternalReportingSetRequest = getReportingSetRequest {
      measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
    }
    assertThat(capturedInternalReportingSetRequests)
      .containsExactly(
        expectedInternalReportingSetRequest.copy {
          externalReportingSetId = REPORTING_SET_EXTERNAL_IDS[0]
        },
        expectedInternalReportingSetRequest.copy {
          externalReportingSetId = REPORTING_SET_EXTERNAL_IDS[0]
        },
        expectedInternalReportingSetRequest.copy {
          externalReportingSetId = REPORTING_SET_EXTERNAL_IDS[1]
        },
        expectedInternalReportingSetRequest.copy {
          externalReportingSetId = REPORTING_SET_EXTERNAL_IDS[1]
        }
      )

    // Verify proto argument of InternalMeasurementsCoroutineImplBase::getMeasurement
    verifyProtoArgument(
        internalMeasurementsMock,
        InternalMeasurementsCoroutineImplBase::getMeasurement
      )
      .isEqualTo(
        getInternalMeasurementRequest {
          measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
          measurementReferenceId = REACH_MEASUREMENT_REFERENCE_ID
        }
      )
    // Verify proto argument of MeasurementConsumersCoroutineImplBase::getMeasurementConsumer
    verifyProtoArgument(
        measurementConsumersMock,
        MeasurementConsumersCoroutineImplBase::getMeasurementConsumer
      )
      .isEqualTo(getMeasurementConsumerRequest { name = MEASUREMENT_CONSUMER_NAMES[0] })

    // Verify proto argument of DataProvidersCoroutineImplBase::getDataProvider
    val dataProvidersCaptor: KArgumentCaptor<GetDataProviderRequest> = argumentCaptor()
    verifyBlocking(dataProvidersMock, times(2)) { getDataProvider(dataProvidersCaptor.capture()) }
    val capturedDataProviderRequests = dataProvidersCaptor.allValues
    assertThat(capturedDataProviderRequests)
      .containsExactly(
        getDataProviderRequest { name = DATA_PROVIDERS[0].name },
        getDataProviderRequest { name = DATA_PROVIDERS[1].name }
      )

    // Verify proto argument of MeasurementsCoroutineImplBase::createMeasurement
    val capturedMeasurementRequest =
      captureFirst<CreateMeasurementRequest> {
        runBlocking { verify(measurementsMock).createMeasurement(capture()) }
      }
    val capturedMeasurement = capturedMeasurementRequest.measurement
    val expectedMeasurement =
      BASE_REACH_MEASUREMENT.copy {
        dataProviders +=
          DATA_PROVIDER_INDICES_IN_SET_OPERATION.map { index -> DATA_PROVIDER_ENTRIES[index] }
        measurementSpec =
          signMeasurementSpec(REACH_ONLY_MEASUREMENT_SPEC, MEASUREMENT_CONSUMER_SIGNING_KEY_HANDLE)
      }

    assertThat(capturedMeasurement)
      .ignoringRepeatedFieldOrder()
      .ignoringFieldDescriptors(
        Measurement.getDescriptor().findFieldByNumber(Measurement.MEASUREMENT_SPEC_FIELD_NUMBER),
        Measurement.DataProviderEntry.Value.getDescriptor()
          .findFieldByNumber(ENCRYPTED_REQUISITION_SPEC_FIELD_NUMBER),
      )
      .isEqualTo(expectedMeasurement)

    val measurementSpec = MeasurementSpec.parseFrom(capturedMeasurement.measurementSpec.data)
    val expectedMeasurementSpec = REACH_ONLY_MEASUREMENT_SPEC
    assertThat(measurementSpec).isEqualTo(expectedMeasurementSpec)
    assertThat(
        verifyMeasurementSpec(
          capturedMeasurement.measurementSpec.signature,
          measurementSpec,
          MEASUREMENT_CONSUMER_CERTIFICATE
        )
      )
      .isTrue()

    val dataProvidersList = capturedMeasurement.dataProvidersList.sortedBy { it.key }

    dataProvidersList.map { dataProviderEntry ->
      val signedRequisitionSpec =
        decryptRequisitionSpec(
          dataProviderEntry.value.encryptedRequisitionSpec,
          DATA_PROVIDER_PRIVATE_KEY_HANDLE
        )
      val requisitionSpec = RequisitionSpec.parseFrom(signedRequisitionSpec.data)
      assertThat(
          verifyRequisitionSpec(
            signedRequisitionSpec.signature,
            requisitionSpec,
            measurementSpec,
            MEASUREMENT_CONSUMER_CERTIFICATE
          )
        )
        .isTrue()
    }

    // Verify proto argument of InternalMeasurementsCoroutineImplBase::createMeasurement
    verifyProtoArgument(
        internalMeasurementsMock,
        InternalMeasurementsCoroutineImplBase::createMeasurement
      )
      .isEqualTo(
        internalMeasurement {
          measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
          measurementReferenceId = REACH_MEASUREMENT_REFERENCE_ID
          state = InternalMeasurement.State.PENDING
        }
      )

    assertThat(result).isEqualTo(expected)
  }

  @Test
  fun `createReport throws UNAUTHENTICATED when no principal is found`() {
    val request = createReportRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      report = PENDING_REACH_REPORT.copy { clearState() }
    }
    val exception =
      assertFailsWith<StatusRuntimeException> { runBlocking { service.createReport(request) } }
    assertThat(exception.status.code).isEqualTo(Status.Code.UNAUTHENTICATED)
  }

  @Test
  fun `createReport throws PERMISSION_DENIED when MeasurementConsumer caller doesn't match`() {
    val request = createReportRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      report = PENDING_REACH_REPORT.copy { clearState() }
    }
    val exception =
      assertFailsWith<StatusRuntimeException> {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[1], CONFIG) {
          runBlocking { service.createReport(request) }
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.PERMISSION_DENIED)
    assertThat(exception.status.description)
      .isEqualTo("Cannot create a Report for another MeasurementConsumer.")
  }

  @Test
  fun `createReport throws PERMISSION_DENIED when report doesn't belong to caller`() {
    val request = createReportRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[1]
      report = PENDING_REACH_REPORT.copy { clearState() }
    }
    val exception =
      assertFailsWith<StatusRuntimeException> {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.createReport(request) }
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.PERMISSION_DENIED)
    assertThat(exception.status.description)
      .isEqualTo("Cannot create a Report for another MeasurementConsumer.")
  }

  @Test
  fun `createReport throws UNAUTHENTICATED when the caller is not MeasurementConsumer`() {
    val request = createReportRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      report = PENDING_REACH_REPORT.copy { clearState() }
    }
    val exception =
      assertFailsWith<StatusRuntimeException> {
        withDataProviderPrincipal(DATA_PROVIDERS[0].name) {
          runBlocking { service.createReport(request) }
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.UNAUTHENTICATED)
    assertThat(exception.status.description).isEqualTo("No ReportingPrincipal found")
  }

  @Test
  fun `createReport throws INVALID_ARGUMENT when parent is unspecified`() {
    val request = createReportRequest { report = PENDING_REACH_REPORT.copy { clearState() } }

    val exception =
      assertFailsWith<StatusRuntimeException> {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.createReport(request) }
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description).isEqualTo("Parent is either unspecified or invalid.")
  }

  @Test
  fun `createReport throws INVALID_ARGUMENT when report is unspecified`() {
    val request = createReportRequest { parent = MEASUREMENT_CONSUMER_NAMES[0] }

    val exception =
      assertFailsWith<StatusRuntimeException> {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.createReport(request) }
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description).isEqualTo("Report is not specified.")
  }

  @Test
  fun `createReport throws INVALID_ARGUMENT when reportIdempotencyKey is unspecified`() {
    val request = createReportRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      report =
        PENDING_REACH_REPORT.copy {
          clearState()
          clearReportIdempotencyKey()
        }
    }

    val exception =
      assertFailsWith<StatusRuntimeException> {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.createReport(request) }
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description).isEqualTo("ReportIdempotencyKey is not specified.")
  }

  @Test
  fun `createReport throws INVALID_ARGUMENT when eventGroupUniverse in Report is unspecified`() {
    val request = createReportRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      report =
        PENDING_REACH_REPORT.copy {
          clearState()
          clearEventGroupUniverse()
        }
    }

    val exception =
      assertFailsWith<StatusRuntimeException> {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.createReport(request) }
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description).isEqualTo("EventGroupUniverse is not specified.")
  }

  @Test
  fun `createReport throws INVALID_ARGUMENT when setOperationName duplicate for same metricType`() {
    val request = createReportRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      report =
        PENDING_REACH_REPORT.copy {
          clearState()
          metrics.add(REACH_METRIC)
        }
    }

    val exception =
      assertFailsWith<StatusRuntimeException> {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.createReport(request) }
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description)
      .isEqualTo("The names of the set operations within the same metric type should be unique.")
  }

  @Test
  fun `createReport throws INVALID_ARGUMENT when time in Report is unspecified`() {
    val request = createReportRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      report =
        PENDING_REACH_REPORT.copy {
          clearState()
          clearTime()
        }
    }

    val exception =
      assertFailsWith<StatusRuntimeException> {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.createReport(request) }
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description).isEqualTo("The time in Report is not specified.")
  }

  @Test
  fun `createReport throws INVALID_ARGUMENT when any metric type in Report is unspecified`() {
    val request = createReportRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      report =
        PENDING_REACH_REPORT.copy {
          clearState()
          metrics.clear()
          metrics.add(REACH_METRIC.copy { clearReach() })
        }
    }

    val exception =
      assertFailsWith<StatusRuntimeException> {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.createReport(request) }
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description)
      .isEqualTo("The metric type in Report is not specified.")
  }

  @Test
  fun `createReport throws INVALID_ARGUMENT when provided reporting set name is invalid`() {
    val invalidMetric = metric {
      reach = reachParams {}
      cumulative = false
      setOperations.add(
        NAMED_REACH_SET_OPERATION.copy { setOperation = SET_OPERATION_WITH_INVALID_REPORTING_SET }
      )
    }

    val request = createReportRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      report =
        PENDING_REACH_REPORT.copy {
          clearState()
          metrics.clear()
          metrics.add(invalidMetric)
        }
    }

    val exception =
      assertFailsWith<StatusRuntimeException> {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.createReport(request) }
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description)
      .isEqualTo("Invalid reporting set name $INVALID_REPORTING_SET_NAME.")
  }

  @Test
  fun `createReport throws INVALID_ARGUMENT when any reporting set is not accessible to caller`() {
    val invalidMetric = metric {
      reach = reachParams {}
      cumulative = false
      setOperations.add(
        NAMED_REACH_SET_OPERATION.copy {
          setOperation = SET_OPERATION_WITH_INACCESSIBLE_REPORTING_SET
        }
      )
    }

    val request = createReportRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      report =
        PENDING_REACH_REPORT.copy {
          clearState()
          metrics.clear()
          metrics.add(invalidMetric)
        }
    }

    val exception =
      assertFailsWith<StatusRuntimeException> {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.createReport(request) }
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description)
      .isEqualTo("No access to the reporting set [$REPORTING_SET_NAME_FOR_MC_2].")
  }

  @Test
  fun `createReport throws INVALID_ARGUMENT when eventGroup isn't covered by eventGroupUniverse`() =
    runBlocking {
      whenever(internalReportingSetsMock.getReportingSet(any()))
        .thenReturn(
          INTERNAL_REPORTING_SETS[0],
          UNCOVERED_INTERNAL_REPORTING_SET,
        )
      val request = createReportRequest {
        parent = MEASUREMENT_CONSUMER_NAMES[0]
        report = PENDING_REACH_REPORT.copy { clearState() }
      }

      val exception =
        assertFailsWith<StatusRuntimeException> {
          withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
            runBlocking { service.createReport(request) }
          }
        }
      val expectedExceptionDescription =
        "The event group [$UNCOVERED_EVENT_GROUP_NAME] in the reporting set" +
          " [${UNCOVERED_INTERNAL_REPORTING_SET.displayName}] is not included in the event group " +
          "universe."
      assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
      assertThat(exception.status.description).isEqualTo(expectedExceptionDescription)
    }

  @Test
  fun `createReport throws exception from getReportByIdempotencyKey when status isn't NOT_FOUND`() =
    runBlocking {
      whenever(internalReportsMock.getReportByIdempotencyKey(any()))
        .thenThrow(StatusRuntimeException(Status.INVALID_ARGUMENT))

      val request = createReportRequest {
        parent = MEASUREMENT_CONSUMER_NAMES[0]
        report = PENDING_REACH_REPORT.copy { clearState() }
      }

      val exception =
        assertFailsWith(Exception::class) {
          withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
            runBlocking { service.createReport(request) }
          }
        }
      val expectedExceptionDescription =
        "Unable to retrieve a report from the reporting database using the provided " +
          "reportIdempotencyKey [${PENDING_REACH_REPORT.reportIdempotencyKey}]."
      assertThat(exception.message).isEqualTo(expectedExceptionDescription)
    }

  @Test
  fun `createReport throws exception from internal getMeasurement when status isn't NOT_FOUND`() =
    runBlocking {
      whenever(internalMeasurementsMock.getMeasurement(any()))
        .thenThrow(StatusRuntimeException(Status.INVALID_ARGUMENT))

      val request = createReportRequest {
        parent = MEASUREMENT_CONSUMER_NAMES[0]
        report = PENDING_REACH_REPORT.copy { clearState() }
      }

      val exception =
        assertFailsWith(Exception::class) {
          withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
            runBlocking { service.createReport(request) }
          }
        }
      val expectedExceptionDescription =
        "Unable to retrieve the measurement [$REACH_MEASUREMENT_REFERENCE_ID] from the reporting " +
          "database."
      assertThat(exception.message).isEqualTo(expectedExceptionDescription)
    }

  @Test
  fun `createReport throws exception when internal createReport throws exception`() = runBlocking {
    whenever(internalReportsMock.createReport(any()))
      .thenThrow(StatusRuntimeException(Status.INVALID_ARGUMENT))

    val request = createReportRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      report = PENDING_REACH_REPORT.copy { clearState() }
    }

    val exception =
      assertFailsWith(Exception::class) {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.createReport(request) }
        }
      }
    val expectedExceptionDescription = "Unable to create a report in the reporting database."
    assertThat(exception.message).isEqualTo(expectedExceptionDescription)
  }

  @Test
  fun `createReport throws exception when the CMM createMeasurement throws exception`() =
    runBlocking {
      whenever(measurementsMock.createMeasurement(any()))
        .thenThrow(StatusRuntimeException(Status.INVALID_ARGUMENT))

      val request = createReportRequest {
        parent = MEASUREMENT_CONSUMER_NAMES[0]
        report = PENDING_REACH_REPORT.copy { clearState() }
      }

      val exception =
        assertFailsWith(Exception::class) {
          withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
            runBlocking { service.createReport(request) }
          }
        }
      val expectedExceptionDescription =
        "Unable to create the measurement [$REACH_MEASUREMENT_NAME]."
      assertThat(exception.message).isEqualTo(expectedExceptionDescription)
    }

  @Test
  fun `createReport throws exception when the internal createMeasurement throws exception`() =
    runBlocking {
      whenever(internalMeasurementsMock.createMeasurement(any()))
        .thenThrow(StatusRuntimeException(Status.INVALID_ARGUMENT))

      val request = createReportRequest {
        parent = MEASUREMENT_CONSUMER_NAMES[0]
        report = PENDING_REACH_REPORT.copy { clearState() }
      }

      val exception =
        assertFailsWith(Exception::class) {
          withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
            runBlocking { service.createReport(request) }
          }
        }
      val expectedExceptionDescription =
        "Unable to create the measurement [$REACH_MEASUREMENT_NAME] in the reporting database."
      assertThat(exception.message).isEqualTo(expectedExceptionDescription)
    }

  @Test
  fun `createReport throws exception when getMeasurementConsumer throws exception`() = runBlocking {
    whenever(measurementConsumersMock.getMeasurementConsumer(any()))
      .thenThrow(StatusRuntimeException(Status.INVALID_ARGUMENT))

    val request = createReportRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      report = PENDING_REACH_REPORT.copy { clearState() }
    }

    val exception =
      assertFailsWith(Exception::class) {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.createReport(request) }
        }
      }
    val expectedExceptionDescription =
      "Unable to retrieve the measurement consumer [${MEASUREMENT_CONSUMER_NAMES[0]}]."
    assertThat(exception.message).isEqualTo(expectedExceptionDescription)
  }

  @Test
  fun `createReport throws exception when the internal getReportingSet throws exception`(): Unit =
    runBlocking {
      whenever(internalReportingSetsMock.getReportingSet(any()))
        .thenReturn(
          INTERNAL_REPORTING_SETS[0],
          INTERNAL_REPORTING_SETS[1],
        )
        .thenThrow(StatusRuntimeException(Status.INVALID_ARGUMENT))

      val request = createReportRequest {
        parent = MEASUREMENT_CONSUMER_NAMES[0]
        report = PENDING_REACH_REPORT.copy { clearState() }
      }

      assertFails {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.createReport(request) }
        }
      }
    }

  @Test
  fun `createReport throws exception when getDataProvider throws exception`() = runBlocking {
    whenever(dataProvidersMock.getDataProvider(any()))
      .thenThrow(StatusRuntimeException(Status.INVALID_ARGUMENT))

    val request = createReportRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      report = PENDING_REACH_REPORT.copy { clearState() }
    }

    val exception =
      assertFailsWith(Exception::class) {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.createReport(request) }
        }
      }
    val expectedExceptionDescription = "Unable to retrieve the data provider"
    assertThat(exception.message).contains(expectedExceptionDescription)
  }

  @Test
  fun `createReport throws exception when checkReportingSet got exception from getReportingSet`() =
    runBlocking {
      whenever(internalReportingSetsMock.getReportingSet(any()))
        .thenThrow(StatusRuntimeException(Status.INVALID_ARGUMENT))

      val request = createReportRequest {
        parent = MEASUREMENT_CONSUMER_NAMES[0]
        report = PENDING_REACH_REPORT.copy { clearState() }
      }

      val exception =
        assertFailsWith(Exception::class) {
          withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
            runBlocking { service.createReport(request) }
          }
        }
      val expectedExceptionDescription =
        "Unable to retrieve the reporting set [${REPORTING_SET_NAMES[0]}] from the reporting " +
          "database."
      assertThat(exception.message).isEqualTo(expectedExceptionDescription)
    }

  @Test
  fun `listReports returns without a next page token when there is no previous page token`() {
    val request = listReportsRequest { parent = MEASUREMENT_CONSUMER_NAMES[0] }

    val result =
      withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
        runBlocking { service.listReports(request) }
      }

    val expected = listReportsResponse {
      reports.add(SUCCEEDED_REACH_REPORT)
      reports.add(SUCCEEDED_IMPRESSION_REPORT)
      reports.add(SUCCEEDED_WATCH_DURATION_REPORT)
      reports.add(SUCCEEDED_FREQUENCY_HISTOGRAM_REPORT)
    }

    verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::streamReports)
      .isEqualTo(
        streamReportsRequest {
          limit = DEFAULT_PAGE_SIZE + 1
          this.filter = filter {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
          }
        }
      )

    assertThat(result).ignoringRepeatedFieldOrder().isEqualTo(expected)
  }

  @Test
  fun `listReports returns with a next page token when there is no previous page token`() {
    val request = listReportsRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      pageSize = PAGE_SIZE
    }

    val result =
      withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
        runBlocking { service.listReports(request) }
      }

    val expected = listReportsResponse {
      reports.add(SUCCEEDED_REACH_REPORT)
      reports.add(SUCCEEDED_IMPRESSION_REPORT)
      reports.add(SUCCEEDED_WATCH_DURATION_REPORT)

      nextPageToken =
        listReportsPageToken {
            pageSize = PAGE_SIZE
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            lastReport = previousPageEnd {
              measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
              externalReportId = REPORT_EXTERNAL_IDS[2]
            }
          }
          .toByteString()
          .base64UrlEncode()
    }

    verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::streamReports)
      .isEqualTo(
        streamReportsRequest {
          limit = PAGE_SIZE + 1
          this.filter = filter {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
          }
        }
      )

    assertThat(result).ignoringRepeatedFieldOrder().isEqualTo(expected)
  }

  @Test
  fun `listReports returns with a next page token when there is a previous page token`() {
    val request = listReportsRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      pageSize = PAGE_SIZE
      pageToken =
        listReportsPageToken {
            pageSize = PAGE_SIZE
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            lastReport = previousPageEnd {
              measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
              externalReportId = REPORT_EXTERNAL_IDS[0]
            }
          }
          .toByteString()
          .base64UrlEncode()
    }

    val result =
      withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
        runBlocking { service.listReports(request) }
      }

    val expected = listReportsResponse {
      reports.add(SUCCEEDED_REACH_REPORT)
      reports.add(SUCCEEDED_IMPRESSION_REPORT)
      reports.add(SUCCEEDED_WATCH_DURATION_REPORT)

      nextPageToken =
        listReportsPageToken {
            pageSize = PAGE_SIZE
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            lastReport = previousPageEnd {
              measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
              externalReportId = REPORT_EXTERNAL_IDS[2]
            }
          }
          .toByteString()
          .base64UrlEncode()
    }

    verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::streamReports)
      .isEqualTo(
        streamReportsRequest {
          limit = PAGE_SIZE + 1
          this.filter = filter {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            externalReportIdAfter = REPORT_EXTERNAL_IDS[0]
          }
        }
      )

    assertThat(result).ignoringRepeatedFieldOrder().isEqualTo(expected)
  }

  @Test
  fun `listReports with page size replaced with a valid value and no previous page token`() {
    val invalidPageSize = MAX_PAGE_SIZE * 2
    val request = listReportsRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      pageSize = invalidPageSize
    }

    val result =
      withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
        runBlocking { service.listReports(request) }
      }

    val expected = listReportsResponse {
      reports.add(SUCCEEDED_REACH_REPORT)
      reports.add(SUCCEEDED_IMPRESSION_REPORT)
      reports.add(SUCCEEDED_WATCH_DURATION_REPORT)
      reports.add(SUCCEEDED_FREQUENCY_HISTOGRAM_REPORT)
    }

    verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::streamReports)
      .isEqualTo(
        streamReportsRequest {
          limit = MAX_PAGE_SIZE + 1
          this.filter = filter {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
          }
        }
      )

    assertThat(result).ignoringRepeatedFieldOrder().isEqualTo(expected)
  }

  @Test
  fun `listReports with invalid page size replaced with the one in previous page token`() {
    val invalidPageSize = MAX_PAGE_SIZE * 2
    val previousPageSize = PAGE_SIZE
    val request = listReportsRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      pageSize = invalidPageSize
      pageToken =
        listReportsPageToken {
            pageSize = previousPageSize
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            lastReport = previousPageEnd {
              measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
              externalReportId = REPORT_EXTERNAL_IDS[0]
            }
          }
          .toByteString()
          .base64UrlEncode()
    }

    val result =
      withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
        runBlocking { service.listReports(request) }
      }

    val expected = listReportsResponse {
      reports.add(SUCCEEDED_REACH_REPORT)
      reports.add(SUCCEEDED_IMPRESSION_REPORT)
      reports.add(SUCCEEDED_WATCH_DURATION_REPORT)

      nextPageToken =
        listReportsPageToken {
            pageSize = previousPageSize
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            lastReport = previousPageEnd {
              measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
              externalReportId = REPORT_EXTERNAL_IDS[2]
            }
          }
          .toByteString()
          .base64UrlEncode()
    }

    verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::streamReports)
      .isEqualTo(
        streamReportsRequest {
          limit = previousPageSize + 1
          this.filter = filter {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            externalReportIdAfter = REPORT_EXTERNAL_IDS[0]
          }
        }
      )

    assertThat(result).ignoringRepeatedFieldOrder().isEqualTo(expected)
  }

  @Test
  fun `listReports with page size replacing the one in previous page token`() {
    val newPageSize = PAGE_SIZE
    val previousPageSize = 1
    val request = listReportsRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      pageSize = newPageSize
      pageToken =
        listReportsPageToken {
            pageSize = previousPageSize
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            lastReport = previousPageEnd {
              measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
              externalReportId = REPORT_EXTERNAL_IDS[0]
            }
          }
          .toByteString()
          .base64UrlEncode()
    }

    val result =
      withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
        runBlocking { service.listReports(request) }
      }

    val expected = listReportsResponse {
      reports.add(SUCCEEDED_REACH_REPORT)
      reports.add(SUCCEEDED_IMPRESSION_REPORT)
      reports.add(SUCCEEDED_WATCH_DURATION_REPORT)

      nextPageToken =
        listReportsPageToken {
            pageSize = newPageSize
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            lastReport = previousPageEnd {
              measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
              externalReportId = REPORT_EXTERNAL_IDS[2]
            }
          }
          .toByteString()
          .base64UrlEncode()
    }

    verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::streamReports)
      .isEqualTo(
        streamReportsRequest {
          limit = newPageSize + 1
          this.filter = filter {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            externalReportIdAfter = REPORT_EXTERNAL_IDS[0]
          }
        }
      )

    assertThat(result).ignoringRepeatedFieldOrder().isEqualTo(expected)
  }

  @Test
  fun `listReports throws UNAUTHENTICATED when no principal is found`() {
    val request = listReportsRequest { parent = MEASUREMENT_CONSUMER_NAMES[0] }
    val exception =
      assertFailsWith<StatusRuntimeException> { runBlocking { service.listReports(request) } }
    assertThat(exception.status.code).isEqualTo(Status.Code.UNAUTHENTICATED)
  }

  @Test
  fun `listReports throws PERMISSION_DENIED when MeasurementConsumer caller doesn't match`() {
    val request = listReportsRequest { parent = MEASUREMENT_CONSUMER_NAMES[0] }
    val exception =
      assertFailsWith<StatusRuntimeException> {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[1], CONFIG) {
          runBlocking { service.listReports(request) }
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.PERMISSION_DENIED)
    assertThat(exception.status.description)
      .isEqualTo("Cannot list Reports belonging to other MeasurementConsumers.")
  }

  @Test
  fun `listReports throws UNAUTHENTICATED when the caller is not MeasurementConsumer`() {
    val request = listReportsRequest { parent = MEASUREMENT_CONSUMER_NAMES[0] }
    val exception =
      assertFailsWith<StatusRuntimeException> {
        withDataProviderPrincipal(DATA_PROVIDERS[0].name) {
          runBlocking { service.listReports(request) }
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.UNAUTHENTICATED)
    assertThat(exception.status.description).isEqualTo("No ReportingPrincipal found")
  }

  @Test
  fun `listReports throws INVALID_ARGUMENT when page size is less than 0`() {
    val request = listReportsRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      pageSize = -1
    }
    val exception =
      assertFailsWith<StatusRuntimeException> {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.listReports(request) }
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    assertThat(exception.status.description).isEqualTo("Page size cannot be less than 0")
  }

  @Test
  fun `listReports throws INVALID_ARGUMENT when parent is unspecified`() {
    val exception =
      assertFailsWith<StatusRuntimeException> {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.listReports(ListReportsRequest.getDefaultInstance()) }
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
  }

  @Test
  fun `listReports throws INVALID_ARGUMENT when mc id doesn't match one in page token`() {
    val request = listReportsRequest {
      parent = MEASUREMENT_CONSUMER_NAMES[0]
      pageToken =
        listReportsPageToken {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[1]
            lastReport = previousPageEnd {
              measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[1]
              externalReportId = REPORT_EXTERNAL_IDS[0]
            }
          }
          .toByteString()
          .base64UrlEncode()
    }

    val exception =
      assertFailsWith<StatusRuntimeException> {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.listReports(request) }
        }
      }
    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
  }

  @Test
  fun `listReports throws Exception when the internal streamReports throws Exception`() =
    runBlocking {
      whenever(internalReportsMock.streamReports(any()))
        .thenThrow(StatusRuntimeException(Status.INVALID_ARGUMENT))

      val request = listReportsRequest { parent = MEASUREMENT_CONSUMER_NAMES[0] }

      val exception =
        assertFailsWith(Exception::class) {
          withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
            runBlocking { service.listReports(request) }
          }
        }
      val expectedExceptionDescription = "Unable to list reports from the reporting database."
      assertThat(exception.message).isEqualTo(expectedExceptionDescription)
    }

  @Test
  fun `listReports throws Exception when the internal getReport throws Exception`() = runBlocking {
    whenever(internalReportsMock.getReport(any()))
      .thenThrow(StatusRuntimeException(Status.INVALID_ARGUMENT))

    val request = listReportsRequest { parent = MEASUREMENT_CONSUMER_NAMES[0] }

    val exception =
      assertFailsWith(Exception::class) {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.listReports(request) }
        }
      }
    val expectedExceptionDescription =
      "Unable to get the report [${REPORT_NAMES[0]}] from the reporting database."
    assertThat(exception.message).isEqualTo(expectedExceptionDescription)
  }

  @Test
  fun `listReports throws Exception when the CMM getMeasurement throws Exception`() = runBlocking {
    whenever(measurementsMock.getMeasurement(any()))
      .thenThrow(StatusRuntimeException(Status.INVALID_ARGUMENT))

    val request = listReportsRequest { parent = MEASUREMENT_CONSUMER_NAMES[0] }

    val exception =
      assertFailsWith(Exception::class) {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.listReports(request) }
        }
      }
    val expectedExceptionDescription =
      "Unable to retrieve the measurement [$REACH_MEASUREMENT_NAME]."
    assertThat(exception.message).isEqualTo(expectedExceptionDescription)
  }

  @Test
  fun `listReports throws Exception when the internal setMeasurementResult throws Exception`() =
    runBlocking {
      whenever(internalMeasurementsMock.setMeasurementResult(any()))
        .thenThrow(StatusRuntimeException(Status.INVALID_ARGUMENT))

      val request = listReportsRequest { parent = MEASUREMENT_CONSUMER_NAMES[0] }

      val exception =
        assertFailsWith(Exception::class) {
          withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
            runBlocking { service.listReports(request) }
          }
        }
      val expectedExceptionDescription =
        "Unable to update the measurement [$REACH_MEASUREMENT_NAME] in the reporting database."
      assertThat(exception.message).isEqualTo(expectedExceptionDescription)
    }

  @Test
  fun `listReports throws Exception when the internal setMeasurementFailure throws Exception`() =
    runBlocking {
      whenever(internalMeasurementsMock.setMeasurementFailure(any()))
        .thenThrow(StatusRuntimeException(Status.INVALID_ARGUMENT))

      whenever(internalReportsMock.streamReports(any()))
        .thenReturn(flowOf(INTERNAL_PENDING_REACH_REPORT))
      whenever(measurementsMock.getMeasurement(any()))
        .thenReturn(
          PENDING_REACH_MEASUREMENT.copy {
            state = Measurement.State.FAILED
            failure = failure {
              reason = Measurement.Failure.Reason.REQUISITION_REFUSED
              message = "Privacy budget exceeded."
            }
          }
        )
      whenever(internalReportsMock.getReport(any()))
        .thenReturn(
          INTERNAL_PENDING_REACH_REPORT.copy { state = InternalReport.State.FAILED },
        )

      val request = listReportsRequest { parent = MEASUREMENT_CONSUMER_NAMES[0] }

      val exception =
        assertFailsWith(Exception::class) {
          withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
            runBlocking { service.listReports(request) }
          }
        }
      val expectedExceptionDescription =
        "Unable to update the measurement [$REACH_MEASUREMENT_NAME] in the reporting database."
      assertThat(exception.message).isEqualTo(expectedExceptionDescription)
    }

  @Test
  fun `listReports throws Exception when the getCertificate throws Exception`() = runBlocking {
    whenever(certificateMock.getCertificate(any()))
      .thenThrow(StatusRuntimeException(Status.INVALID_ARGUMENT))

    val request = listReportsRequest { parent = MEASUREMENT_CONSUMER_NAMES[0] }

    val exception =
      assertFailsWith(Exception::class) {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.listReports(request) }
        }
      }
    val expectedExceptionDescription =
      "Unable to retrieve the certificate [${DATA_PROVIDER_CERTIFICATE_NAMES[0]}]."
    assertThat(exception.message).isEqualTo(expectedExceptionDescription)
  }

  @Test
  fun `listReports returns reports with SUCCEEDED states when reports are already succeeded`() {
    whenever(internalReportsMock.streamReports(any()))
      .thenReturn(
        flowOf(
          INTERNAL_SUCCEEDED_REACH_REPORT,
          INTERNAL_SUCCEEDED_IMPRESSION_REPORT,
          INTERNAL_SUCCEEDED_WATCH_DURATION_REPORT,
          INTERNAL_SUCCEEDED_FREQUENCY_HISTOGRAM_REPORT,
        )
      )

    val request = listReportsRequest { parent = MEASUREMENT_CONSUMER_NAMES[0] }

    val result =
      withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
        runBlocking { service.listReports(request) }
      }

    val expected = listReportsResponse {
      reports.add(SUCCEEDED_REACH_REPORT)
      reports.add(SUCCEEDED_IMPRESSION_REPORT)
      reports.add(SUCCEEDED_WATCH_DURATION_REPORT)
      reports.add(SUCCEEDED_FREQUENCY_HISTOGRAM_REPORT)
    }

    verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::streamReports)
      .isEqualTo(
        streamReportsRequest {
          limit = DEFAULT_PAGE_SIZE + 1
          this.filter = filter {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
          }
        }
      )

    assertThat(result).ignoringRepeatedFieldOrder().isEqualTo(expected)
  }

  @Test
  fun `listReports returns reports with FAILED states when reports are already failed`() {
    whenever(internalReportsMock.streamReports(any()))
      .thenReturn(
        flowOf(
          INTERNAL_PENDING_REACH_REPORT.copy { state = InternalReport.State.FAILED },
          INTERNAL_PENDING_IMPRESSION_REPORT.copy { state = InternalReport.State.FAILED },
          INTERNAL_PENDING_WATCH_DURATION_REPORT.copy { state = InternalReport.State.FAILED },
          INTERNAL_PENDING_FREQUENCY_HISTOGRAM_REPORT.copy { state = InternalReport.State.FAILED },
        )
      )

    val request = listReportsRequest { parent = MEASUREMENT_CONSUMER_NAMES[0] }

    val result =
      withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
        runBlocking { service.listReports(request) }
      }

    val expected = listReportsResponse {
      reports.add(PENDING_REACH_REPORT.copy { state = Report.State.FAILED })
      reports.add(PENDING_IMPRESSION_REPORT.copy { state = Report.State.FAILED })
      reports.add(PENDING_WATCH_DURATION_REPORT.copy { state = Report.State.FAILED })
      reports.add(PENDING_FREQUENCY_HISTOGRAM_REPORT.copy { state = Report.State.FAILED })
    }

    verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::streamReports)
      .isEqualTo(
        streamReportsRequest {
          limit = DEFAULT_PAGE_SIZE + 1
          this.filter = filter {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
          }
        }
      )

    assertThat(result).ignoringRepeatedFieldOrder().isEqualTo(expected)
  }

  @Test
  fun `listReports returns reports with RUNNING states when measurements are PENDING`() =
    runBlocking {
      whenever(internalReportsMock.streamReports(any()))
        .thenReturn(flowOf(INTERNAL_PENDING_REACH_REPORT))
      whenever(measurementsMock.getMeasurement(any()))
        .thenReturn(
          PENDING_REACH_MEASUREMENT.copy {
            state = Measurement.State.COMPUTING
            results.clear()
          }
        )
      whenever(internalReportsMock.getReport(any())).thenReturn(INTERNAL_PENDING_REACH_REPORT)

      val request = listReportsRequest { parent = MEASUREMENT_CONSUMER_NAMES[0] }

      val result =
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.listReports(request) }
        }

      val expected = listReportsResponse { reports.add(PENDING_REACH_REPORT) }

      verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::streamReports)
        .isEqualTo(
          streamReportsRequest {
            limit = DEFAULT_PAGE_SIZE + 1
            this.filter = filter {
              measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            }
          }
        )
      verifyProtoArgument(measurementsMock, MeasurementsCoroutineImplBase::getMeasurement)
        .isEqualTo(getMeasurementRequest { name = REACH_MEASUREMENT_NAME })
      verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::getReport)
        .isEqualTo(
          getInternalReportRequest {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            externalReportId = REPORT_EXTERNAL_IDS[0]
          }
        )

      assertThat(result).ignoringRepeatedFieldOrder().isEqualTo(expected)
    }

  @Test
  fun `listReports returns reports with FAILED states when measurements are FAILED`() =
    runBlocking {
      whenever(internalReportsMock.streamReports(any()))
        .thenReturn(flowOf(INTERNAL_PENDING_REACH_REPORT))
      whenever(measurementsMock.getMeasurement(any()))
        .thenReturn(
          PENDING_REACH_MEASUREMENT.copy {
            state = Measurement.State.FAILED
            failure = failure {
              reason = Measurement.Failure.Reason.REQUISITION_REFUSED
              message = "Privacy budget exceeded."
            }
          }
        )
      whenever(internalReportsMock.getReport(any()))
        .thenReturn(
          INTERNAL_PENDING_REACH_REPORT.copy { state = InternalReport.State.FAILED },
        )

      val request = listReportsRequest { parent = MEASUREMENT_CONSUMER_NAMES[0] }

      val result =
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.listReports(request) }
        }

      val expected = listReportsResponse {
        reports.add(PENDING_REACH_REPORT.copy { state = Report.State.FAILED })
      }

      verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::streamReports)
        .isEqualTo(
          streamReportsRequest {
            limit = DEFAULT_PAGE_SIZE + 1
            this.filter = filter {
              measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            }
          }
        )
      verifyProtoArgument(measurementsMock, MeasurementsCoroutineImplBase::getMeasurement)
        .isEqualTo(getMeasurementRequest { name = REACH_MEASUREMENT_NAME })
      verifyProtoArgument(
          internalMeasurementsMock,
          InternalMeasurementsCoroutineImplBase::setMeasurementFailure
        )
        .isEqualTo(
          setMeasurementFailureRequest {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            measurementReferenceId = REACH_MEASUREMENT_REFERENCE_ID
            failure = internalFailure {
              reason = InternalMeasurement.Failure.Reason.REQUISITION_REFUSED
              message = "Privacy budget exceeded."
            }
          }
        )
      verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::getReport)
        .isEqualTo(
          getInternalReportRequest {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            externalReportId = REPORT_EXTERNAL_IDS[0]
          }
        )

      assertThat(result).ignoringRepeatedFieldOrder().isEqualTo(expected)
    }

  @Test
  fun `listReports returns reports with SUCCEEDED states when measurements are SUCCEEDED`() =
    runBlocking {
      whenever(internalReportsMock.streamReports(any()))
        .thenReturn(flowOf(INTERNAL_PENDING_REACH_REPORT))
      whenever(measurementsMock.getMeasurement(any())).thenReturn(SUCCEEDED_REACH_MEASUREMENT)
      whenever(internalReportsMock.getReport(any())).thenReturn(INTERNAL_SUCCEEDED_REACH_REPORT)

      val request = listReportsRequest { parent = MEASUREMENT_CONSUMER_NAMES[0] }

      val result =
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.listReports(request) }
        }

      val expected = listReportsResponse { reports.add(SUCCEEDED_REACH_REPORT) }

      verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::streamReports)
        .isEqualTo(
          streamReportsRequest {
            limit = DEFAULT_PAGE_SIZE + 1
            this.filter = filter {
              measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            }
          }
        )
      verifyProtoArgument(measurementsMock, MeasurementsCoroutineImplBase::getMeasurement)
        .isEqualTo(getMeasurementRequest { name = REACH_MEASUREMENT_NAME })
      verifyProtoArgument(
          internalMeasurementsMock,
          InternalMeasurementsCoroutineImplBase::setMeasurementResult
        )
        .usingDoubleTolerance(1e-12)
        .isEqualTo(
          setMeasurementResultRequest {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            measurementReferenceId = REACH_MEASUREMENT_REFERENCE_ID
            this.result = internalMeasurementResult {
              reach = internalReach { value = REACH_VALUE }
              frequency = internalFrequency {
                relativeFrequencyDistribution.putAll(FREQUENCY_DISTRIBUTION)
              }
            }
          }
        )
      verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::getReport)
        .isEqualTo(
          getInternalReportRequest {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            externalReportId = REPORT_EXTERNAL_IDS[0]
          }
        )

      assertThat(result).ignoringRepeatedFieldOrder().isEqualTo(expected)
    }

  @Test
  fun `listReports returns an impression report with aggregated results`() = runBlocking {
    whenever(internalReportsMock.streamReports(any()))
      .thenReturn(flowOf(INTERNAL_PENDING_IMPRESSION_REPORT))
    whenever(measurementsMock.getMeasurement(any())).thenReturn(SUCCEEDED_IMPRESSION_MEASUREMENT)
    whenever(internalReportsMock.getReport(any())).thenReturn(INTERNAL_SUCCEEDED_IMPRESSION_REPORT)

    val request = listReportsRequest { parent = MEASUREMENT_CONSUMER_NAMES[0] }

    val result =
      withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
        runBlocking { service.listReports(request) }
      }

    val expected = listReportsResponse { reports.add(SUCCEEDED_IMPRESSION_REPORT) }

    verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::streamReports)
      .isEqualTo(
        streamReportsRequest {
          limit = DEFAULT_PAGE_SIZE + 1
          this.filter = filter {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
          }
        }
      )
    verifyProtoArgument(measurementsMock, MeasurementsCoroutineImplBase::getMeasurement)
      .isEqualTo(getMeasurementRequest { name = IMPRESSION_MEASUREMENT_NAME })
    verifyProtoArgument(
        internalMeasurementsMock,
        InternalMeasurementsCoroutineImplBase::setMeasurementResult
      )
      .isEqualTo(
        setMeasurementResultRequest {
          measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
          measurementReferenceId = IMPRESSION_MEASUREMENT_REFERENCE_ID
          this.result = internalMeasurementResult {
            impression = internalImpression { value = TOTAL_IMPRESSION_VALUE }
          }
        }
      )
    verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::getReport)
      .isEqualTo(
        getInternalReportRequest {
          measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
          externalReportId = REPORT_EXTERNAL_IDS[1]
        }
      )

    assertThat(result).ignoringRepeatedFieldOrder().isEqualTo(expected)
  }

  @Test
  fun `listReports returns a watch duration report with aggregated results`() = runBlocking {
    whenever(internalReportsMock.streamReports(any()))
      .thenReturn(flowOf(INTERNAL_PENDING_WATCH_DURATION_REPORT))
    whenever(measurementsMock.getMeasurement(any()))
      .thenReturn(SUCCEEDED_WATCH_DURATION_MEASUREMENT)
    whenever(internalReportsMock.getReport(any()))
      .thenReturn(INTERNAL_SUCCEEDED_WATCH_DURATION_REPORT)

    val request = listReportsRequest { parent = MEASUREMENT_CONSUMER_NAMES[0] }

    val result =
      withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
        runBlocking { service.listReports(request) }
      }

    val expected = listReportsResponse { reports.add(SUCCEEDED_WATCH_DURATION_REPORT) }

    verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::streamReports)
      .isEqualTo(
        streamReportsRequest {
          limit = DEFAULT_PAGE_SIZE + 1
          this.filter = filter {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
          }
        }
      )
    verifyProtoArgument(measurementsMock, MeasurementsCoroutineImplBase::getMeasurement)
      .isEqualTo(getMeasurementRequest { name = WATCH_DURATION_MEASUREMENT_NAME })
    verifyProtoArgument(
        internalMeasurementsMock,
        InternalMeasurementsCoroutineImplBase::setMeasurementResult
      )
      .isEqualTo(
        setMeasurementResultRequest {
          measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
          measurementReferenceId = WATCH_DURATION_MEASUREMENT_REFERENCE_ID
          this.result = internalMeasurementResult {
            watchDuration = internalWatchDuration { value = TOTAL_WATCH_DURATION }
          }
        }
      )
    verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::getReport)
      .isEqualTo(
        getInternalReportRequest {
          measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
          externalReportId = REPORT_EXTERNAL_IDS[2]
        }
      )

    assertThat(result).ignoringRepeatedFieldOrder().isEqualTo(expected)
  }

  @Test
  fun `getReport returns the report with SUCCEEDED when the report is already succeeded`() =
    runBlocking {
      whenever(internalReportsMock.getReport(any()))
        .thenReturn(INTERNAL_SUCCEEDED_WATCH_DURATION_REPORT)

      val request = getReportRequest { name = REPORT_NAMES[2] }

      val report =
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.getReport(request) }
        }

      assertThat(report).isEqualTo(SUCCEEDED_WATCH_DURATION_REPORT)

      verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::getReport)
        .isEqualTo(
          getInternalReportRequest {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            externalReportId = REPORT_EXTERNAL_IDS[2]
          }
        )
    }

  @Test
  fun `getReport returns the report with FAILED when the report is already failed`() = runBlocking {
    whenever(internalReportsMock.getReport(any()))
      .thenReturn(
        INTERNAL_PENDING_WATCH_DURATION_REPORT.copy { state = InternalReport.State.FAILED }
      )

    val request = getReportRequest { name = REPORT_NAMES[2] }

    val report =
      withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
        runBlocking { service.getReport(request) }
      }

    assertThat(report).isEqualTo(PENDING_WATCH_DURATION_REPORT.copy { state = Report.State.FAILED })

    verifyProtoArgument(internalReportsMock, ReportsCoroutineImplBase::getReport)
      .isEqualTo(
        getInternalReportRequest {
          measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
          externalReportId = REPORT_EXTERNAL_IDS[2]
        }
      )
  }

  @Test
  fun `getReport returns the report with RUNNING when measurements are pending`(): Unit =
    runBlocking {
      whenever(internalReportsMock.getReport(any()))
        .thenReturn(INTERNAL_PENDING_WATCH_DURATION_REPORT)
      whenever(measurementsMock.getMeasurement(any()))
        .thenReturn(PENDING_WATCH_DURATION_MEASUREMENT)

      val request = getReportRequest { name = REPORT_NAMES[2] }

      val report =
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.getReport(request) }
        }

      assertThat(report).isEqualTo(PENDING_WATCH_DURATION_REPORT)

      verifyProtoArgument(measurementsMock, MeasurementsCoroutineImplBase::getMeasurement)
        .comparingExpectedFieldsOnly()
        .isEqualTo(getMeasurementRequest { name = WATCH_DURATION_MEASUREMENT_NAME })

      val internalReportCaptor: KArgumentCaptor<GetInternalReportRequest> = argumentCaptor()
      verifyBlocking(internalReportsMock, times(2)) { getReport(internalReportCaptor.capture()) }
      assertThat(internalReportCaptor.allValues)
        .containsExactly(
          getInternalReportRequest {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            externalReportId = REPORT_EXTERNAL_IDS[2]
          },
          getInternalReportRequest {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            externalReportId = REPORT_EXTERNAL_IDS[2]
          }
        )
    }

  @Test
  fun `getReport syncs and returns an SUCCEEDED report with aggregated results`(): Unit =
    runBlocking {
      whenever(measurementsMock.getMeasurement(any())).thenReturn(SUCCEEDED_IMPRESSION_MEASUREMENT)
      whenever(internalReportsMock.getReport(any()))
        .thenReturn(INTERNAL_PENDING_IMPRESSION_REPORT, INTERNAL_SUCCEEDED_IMPRESSION_REPORT)

      val request = getReportRequest { name = REPORT_NAMES[1] }

      val report =
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.getReport(request) }
        }

      assertThat(report).isEqualTo(SUCCEEDED_IMPRESSION_REPORT)

      verifyProtoArgument(measurementsMock, MeasurementsCoroutineImplBase::getMeasurement)
        .isEqualTo(getMeasurementRequest { name = IMPRESSION_MEASUREMENT_NAME })
      verifyProtoArgument(
          internalMeasurementsMock,
          InternalMeasurementsCoroutineImplBase::setMeasurementResult
        )
        .isEqualTo(
          setMeasurementResultRequest {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            measurementReferenceId = IMPRESSION_MEASUREMENT_REFERENCE_ID
            this.result = internalMeasurementResult {
              impression = internalImpression { value = TOTAL_IMPRESSION_VALUE }
            }
          }
        )

      val internalReportCaptor: KArgumentCaptor<GetInternalReportRequest> = argumentCaptor()
      verifyBlocking(internalReportsMock, times(2)) { getReport(internalReportCaptor.capture()) }
      assertThat(internalReportCaptor.allValues)
        .containsExactly(
          getInternalReportRequest {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            externalReportId = REPORT_EXTERNAL_IDS[1]
          },
          getInternalReportRequest {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            externalReportId = REPORT_EXTERNAL_IDS[1]
          }
        )
    }

  @Test
  fun `getReport syncs and returns an FAILED report when measurements failed`(): Unit =
    runBlocking {
      whenever(measurementsMock.getMeasurement(any()))
        .thenReturn(
          BASE_IMPRESSION_MEASUREMENT.copy {
            state = Measurement.State.FAILED
            failure = failure {
              reason = Measurement.Failure.Reason.REQUISITION_REFUSED
              message = "Privacy budget exceeded."
            }
          }
        )
      whenever(internalReportsMock.getReport(any()))
        .thenReturn(
          INTERNAL_PENDING_IMPRESSION_REPORT,
          INTERNAL_PENDING_IMPRESSION_REPORT.copy { state = InternalReport.State.FAILED }
        )

      val request = getReportRequest { name = REPORT_NAMES[1] }

      val report =
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.getReport(request) }
        }

      assertThat(report).isEqualTo(PENDING_IMPRESSION_REPORT.copy { state = Report.State.FAILED })

      verifyProtoArgument(measurementsMock, MeasurementsCoroutineImplBase::getMeasurement)
        .isEqualTo(getMeasurementRequest { name = IMPRESSION_MEASUREMENT_NAME })
      verifyProtoArgument(
          internalMeasurementsMock,
          InternalMeasurementsCoroutineImplBase::setMeasurementFailure
        )
        .isEqualTo(
          setMeasurementFailureRequest {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            measurementReferenceId = IMPRESSION_MEASUREMENT_REFERENCE_ID
            failure = internalFailure {
              reason = InternalMeasurement.Failure.Reason.REQUISITION_REFUSED
              message = "Privacy budget exceeded."
            }
          }
        )

      val internalReportCaptor: KArgumentCaptor<GetInternalReportRequest> = argumentCaptor()
      verifyBlocking(internalReportsMock, times(2)) { getReport(internalReportCaptor.capture()) }
      assertThat(internalReportCaptor.allValues)
        .containsExactly(
          getInternalReportRequest {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            externalReportId = REPORT_EXTERNAL_IDS[1]
          },
          getInternalReportRequest {
            measurementConsumerReferenceId = MEASUREMENT_CONSUMER_REFERENCE_IDS[0]
            externalReportId = REPORT_EXTERNAL_IDS[1]
          }
        )
    }

  @Test
  fun `getReport throws INVALID_ARGUMENT when Report name is invalid`() {
    val request = getReportRequest { name = INVALID_REPORT_NAME }

    val exception =
      assertFailsWith<StatusRuntimeException> {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.getReport(request) }
        }
      }

    assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
  }

  @Test
  fun `getReport throws PERMISSION_DENIED when MeasurementConsumer's identity does not match`() {
    val request = getReportRequest { name = REPORT_NAMES[0] }

    val exception =
      assertFailsWith<StatusRuntimeException> {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[1], CONFIG) {
          runBlocking { service.getReport(request) }
        }
      }

    assertThat(exception.status.code).isEqualTo(Status.Code.PERMISSION_DENIED)
  }

  @Test
  fun `getReport throws UNAUTHENTICATED when the caller is not a MeasurementConsumer`() {
    val request = getReportRequest { name = REPORT_NAMES[0] }

    val exception =
      assertFailsWith<StatusRuntimeException> {
        withDataProviderPrincipal(DATA_PROVIDERS[0].name) {
          runBlocking { service.getReport(request) }
        }
      }

    assertThat(exception.status.code).isEqualTo(Status.Code.UNAUTHENTICATED)
  }

  @Test
  fun `getReport throws PERMISSION_DENIED when encryption private key not found`() = runBlocking {
    whenever(internalReportsMock.getReport(any()))
      .thenReturn(INTERNAL_PENDING_WATCH_DURATION_REPORT)

    whenever(measurementsMock.getMeasurement(any()))
      .thenReturn(
        SUCCEEDED_WATCH_DURATION_MEASUREMENT.copy {
          val measurementSpec = measurementSpec {
            measurementPublicKey = INVALID_MEASUREMENT_PUBLIC_KEY_DATA
          }
          this.measurementSpec =
            signMeasurementSpec(measurementSpec, MEASUREMENT_CONSUMER_SIGNING_KEY_HANDLE)
        }
      )

    val request = getReportRequest { name = REPORT_NAMES[2] }

    val exception =
      assertFailsWith<StatusRuntimeException> {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.getReport(request) }
        }
      }

    assertThat(exception.status.code).isEqualTo(Status.Code.PERMISSION_DENIED)
    assertThat(exception.status.description).contains("private key")
  }

  @Test
  fun `getReport throws Exception when the internal GetReport throws Exception`() = runBlocking {
    whenever(internalReportsMock.getReport(any()))
      .thenThrow(StatusRuntimeException(Status.INVALID_ARGUMENT))

    val request = getReportRequest { name = REPORT_NAMES[2] }

    val exception =
      assertFailsWith(Exception::class) {
        withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
          runBlocking { service.getReport(request) }
        }
      }
    val expectedExceptionDescription = "Unable to get the report from the reporting database."
    assertThat(exception.message).isEqualTo(expectedExceptionDescription)
  }

  @Test
  fun `toResult converts internal result to external result with the same content`() = runBlocking {
    val internalResult = internalReportResult {
      scalarTable = internalScalarTable {
        rowHeaders += listOf("row1", "row2", "row3")
        columns += internalColumn {
          columnHeader = "column1"
          setOperations += listOf(1.0, 2.0, 3.0)
        }
      }
      histogramTables += internalHistogramTable {
        rows += internalRow {
          rowHeader = "row4"
          frequency = 100
        }
        rows += internalRow {
          rowHeader = "row5"
          frequency = 101
        }
        columns += internalColumn {
          columnHeader = "column1"
          setOperations += listOf(10.0, 11.0, 12.0)
        }
        columns += internalColumn {
          columnHeader = "column2"
          setOperations += listOf(20.0, 21.0, 22.0)
        }
      }
    }

    whenever(internalReportsMock.getReport(any()))
      .thenReturn(
        INTERNAL_SUCCEEDED_WATCH_DURATION_REPORT.copy {
          details = internalReportDetails { result = internalResult }
        }
      )

    val request = getReportRequest { name = REPORT_NAMES[2] }

    val report =
      withMeasurementConsumerPrincipal(MEASUREMENT_CONSUMER_NAMES[0], CONFIG) {
        runBlocking { service.getReport(request) }
      }

    assertThat(report.result)
      .isEqualTo(
        reportResult {
          scalarTable = scalarTable {
            rowHeaders += listOf("row1", "row2", "row3")
            columns += column {
              columnHeader = "column1"
              setOperations += listOf(1.0, 2.0, 3.0)
            }
          }
          histogramTables += histogramTable {
            rows += row {
              rowHeader = "row4"
              frequency = 100
            }
            rows += row {
              rowHeader = "row5"
              frequency = 101
            }
            columns += column {
              columnHeader = "column1"
              setOperations += listOf(10.0, 11.0, 12.0)
            }
            columns += column {
              columnHeader = "column2"
              setOperations += listOf(20.0, 21.0, 22.0)
            }
          }
        }
      )
  }
}
