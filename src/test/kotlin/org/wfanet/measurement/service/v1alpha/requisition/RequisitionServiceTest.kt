package org.wfanet.measurement.service.v1alpha.requisition

import com.google.common.truth.extensions.proto.ProtoTruth.assertThat
import com.google.protobuf.Timestamp
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.testing.GrpcCleanupRule
import java.time.Instant
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.wfanet.measurement.api.v1alpha.FulfillMetricsRequisitionRequest
import org.wfanet.measurement.api.v1alpha.ListMetricRequisitionsRequest
import org.wfanet.measurement.api.v1alpha.ListMetricRequisitionsResponse
import org.wfanet.measurement.api.v1alpha.MetricRequisition
import org.wfanet.measurement.api.v1alpha.RequisitionGrpcKt
import org.wfanet.measurement.common.ExternalId
import org.wfanet.measurement.common.base64UrlEncode
import org.wfanet.measurement.common.toJson
import org.wfanet.measurement.common.toProtoTime
import org.wfanet.measurement.internal.kingdom.FulfillRequisitionRequest
import org.wfanet.measurement.internal.kingdom.Requisition
import org.wfanet.measurement.internal.kingdom.RequisitionDetails
import org.wfanet.measurement.internal.kingdom.RequisitionServiceGrpcKt
import org.wfanet.measurement.internal.kingdom.RequisitionState
import org.wfanet.measurement.internal.kingdom.StreamRequisitionsRequest

@RunWith(JUnit4::class)
class RequisitionServiceTest {
  @get:Rule
  val grpcCleanup = GrpcCleanupRule()

  private lateinit var stub: RequisitionGrpcKt.RequisitionCoroutineStub

  companion object {
    var CREATE_TIME: Timestamp = Instant.ofEpochSecond(123).toProtoTime()
    var WINDOW_START_TIME: Timestamp = Instant.ofEpochSecond(456).toProtoTime()
    var WINDOW_END_TIME: Timestamp = Instant.ofEpochSecond(789).toProtoTime()

    var IRRELEVANT_DETAILS: RequisitionDetails = RequisitionDetails.getDefaultInstance()

    var REQUISITION: Requisition = Requisition.newBuilder().apply {
      externalDataProviderId = 1
      externalCampaignId = 2
      externalRequisitionId = 3
      createTime = CREATE_TIME
      state = RequisitionState.FULFILLED
      windowStartTime = WINDOW_START_TIME
      windowEndTime = WINDOW_END_TIME
      requisitionDetails = IRRELEVANT_DETAILS
      requisitionDetailsJson = IRRELEVANT_DETAILS.toJson()
    }.build()

    val REQUISITION_API_KEY: MetricRequisition.Key =
      MetricRequisition.Key.newBuilder().apply {
        dataProviderId = ExternalId(REQUISITION.externalDataProviderId).apiId.value
        campaignId = ExternalId(REQUISITION.externalCampaignId).apiId.value
        metricRequisitionId = ExternalId(REQUISITION.externalRequisitionId).apiId.value
      }.build()

    val logger = Logger.getLogger(this::class.java.name)
  }

  object FakeRequisitionService : RequisitionServiceGrpcKt.RequisitionServiceCoroutineImplBase() {
    var fulfillRequisitionFn: ((FulfillRequisitionRequest) -> Requisition)? = null
    var streamRequisitionsFn: ((StreamRequisitionsRequest) -> Flow<Requisition>)? = null

    override suspend fun fulfillRequisition(request: FulfillRequisitionRequest): Requisition =
      loggingExceptions { fulfillRequisitionFn!!.invoke(request) }

    override fun streamRequisitions(request: StreamRequisitionsRequest): Flow<Requisition> =
      loggingExceptions { streamRequisitionsFn!!.invoke(request) }

    private fun <T> loggingExceptions(block: () -> T): T {
      try {
        return block()
      } catch (e: Exception) {
        logger.log(Level.SEVERE, "Exception in FakeRequisitionService:", e)
        throw e
      }
    }
  }

  @Before
  fun setup() {
    val serverName = InProcessServerBuilder.generateName()

    val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
    grpcCleanup.register(channel)

    stub = RequisitionGrpcKt.RequisitionCoroutineStub(channel)
    val internalStub = RequisitionServiceGrpcKt.RequisitionServiceCoroutineStub(channel)

    grpcCleanup.register(
      InProcessServerBuilder.forName(serverName)
        .directExecutor()
        .addService(FakeRequisitionService)
        .addService(RequisitionService(internalStub))
        .build()
        .start()
    )
  }

  @Test
  fun fulfillMetricRequisition() = runBlocking {
    FakeRequisitionService.fulfillRequisitionFn = { request: FulfillRequisitionRequest ->
      assertThat(request).isEqualTo(
        FulfillRequisitionRequest.newBuilder()
          .setExternalRequisitionId(REQUISITION.externalRequisitionId)
          .build()
      )
      REQUISITION
    }

    val request = FulfillMetricsRequisitionRequest.newBuilder().apply {
      keyBuilder.apply {
        dataProviderId = ExternalId(REQUISITION.externalDataProviderId).apiId.value
        campaignId = ExternalId(REQUISITION.externalCampaignId).apiId.value
        metricRequisitionId = ExternalId(REQUISITION.externalRequisitionId).apiId.value
      }
    }.build()

    val result = stub.fulfillMetricRequisition(request)

    val expected = MetricRequisition.newBuilder().apply {
      key = REQUISITION_API_KEY
      state = MetricRequisition.State.FULFILLED
    }.build()

    assertThat(result).isEqualTo(expected)
  }

  @Test
  fun `listMetricRequisitions without page token`() = runBlocking {
    FakeRequisitionService.streamRequisitionsFn = { streamRequisitionsRequest ->
      assertThat(streamRequisitionsRequest).ignoringRepeatedFieldOrder().isEqualTo(
        StreamRequisitionsRequest.newBuilder().apply {
          limit = 2
          filterBuilder.apply {
            addAllStates(listOf(RequisitionState.UNFULFILLED, RequisitionState.FULFILLED))
            addExternalDataProviderIds(REQUISITION.externalDataProviderId)
            addExternalCampaignIds(REQUISITION.externalCampaignId)
          }
        }.build()
      )
      flowOf(REQUISITION, REQUISITION)
    }

    val request = ListMetricRequisitionsRequest.newBuilder().apply {
      parentBuilder.apply {
        dataProviderId = ExternalId(REQUISITION.externalDataProviderId).apiId.value
        campaignId = ExternalId(REQUISITION.externalCampaignId).apiId.value
      }
      filterBuilder.addAllStates(
        listOf(
          MetricRequisition.State.UNFULFILLED,
          MetricRequisition.State.FULFILLED
        )
      )
      pageSize = 2
      pageToken = ""
    }.build()

    val result = stub.listMetricRequisitions(request)

    val expected = ListMetricRequisitionsResponse.newBuilder().apply {
      addMetricRequisitionsBuilder().apply {
        key = REQUISITION_API_KEY
        state = MetricRequisition.State.FULFILLED
      }
      addMetricRequisitionsBuilder().apply {
        key = REQUISITION_API_KEY
        state = MetricRequisition.State.FULFILLED
      }
      nextPageToken = CREATE_TIME.toByteArray().base64UrlEncode()
    }.build()

    assertThat(result)
      .ignoringRepeatedFieldOrder()
      .isEqualTo(expected)
  }

  @Test
  fun `listMetricRequisitions with page token`() = runBlocking {
    FakeRequisitionService.streamRequisitionsFn = { streamRequisitionsRequest ->
      assertThat(streamRequisitionsRequest).ignoringRepeatedFieldOrder().isEqualTo(
        StreamRequisitionsRequest.newBuilder().apply {
          limit = 1
          filterBuilder.apply {
            addStates(RequisitionState.UNFULFILLED)
            addExternalDataProviderIds(REQUISITION.externalDataProviderId)
            addExternalCampaignIds(REQUISITION.externalCampaignId)
            createdAfter = CREATE_TIME
          }
        }.build()
      )
      emptyFlow()
    }

    val request = ListMetricRequisitionsRequest.newBuilder().apply {
      parentBuilder.apply {
        dataProviderId = ExternalId(REQUISITION.externalDataProviderId).apiId.value
        campaignId = ExternalId(REQUISITION.externalCampaignId).apiId.value
      }
      filterBuilder.addStates(MetricRequisition.State.UNFULFILLED)
      pageSize = 1
      pageToken = CREATE_TIME.toByteArray().base64UrlEncode()
    }.build()

    val result = stub.listMetricRequisitions(request)
    val expected = ListMetricRequisitionsResponse.getDefaultInstance()

    assertThat(result).isEqualTo(expected)
  }
}
