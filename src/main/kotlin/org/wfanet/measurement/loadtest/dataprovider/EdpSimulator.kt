// Copyright 2021 The Cross-Media Measurement Authors
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

package org.wfanet.measurement.loadtest.dataprovider

import java.io.File
import java.time.Clock
import java.time.Duration
import kotlin.properties.Delegates
import kotlinx.coroutines.runBlocking
import org.wfanet.measurement.api.v2alpha.CertificatesGrpcKt.CertificatesCoroutineStub
import org.wfanet.measurement.api.v2alpha.EventGroupsGrpcKt.EventGroupsCoroutineStub
import org.wfanet.measurement.api.v2alpha.RequisitionFulfillmentGrpcKt.RequisitionFulfillmentCoroutineStub
import org.wfanet.measurement.api.v2alpha.RequisitionsGrpcKt.RequisitionsCoroutineStub
import org.wfanet.measurement.api.v2alpha.createEventGroupRequest
import org.wfanet.measurement.api.v2alpha.eventGroup
import org.wfanet.measurement.common.crypto.SigningCerts
import org.wfanet.measurement.common.grpc.CommonServer
import org.wfanet.measurement.common.grpc.TlsFlags
import org.wfanet.measurement.common.grpc.buildMutualTlsChannel
import org.wfanet.measurement.common.grpc.withVerboseLogging
import org.wfanet.measurement.common.throttler.MinimumIntervalThrottler
import org.wfanet.measurement.common.toByteString
import org.wfanet.measurement.consent.crypto.keystore.testing.InMemoryKeyStore
import org.wfanet.measurement.loadtest.KingdomPublicApiFlags
import org.wfanet.measurement.loadtest.RequisitionFulfillmentServiceFlags
import org.wfanet.measurement.loadtest.storage.SketchStore
import picocli.CommandLine

data class SketchGenerationParams(
  val reach: Int,
  val universeSize: Int,
)

/** [EdpSimulator] runs the [RequisitionFulfillmentWorkflow] that does the actual work */
abstract class EdpSimulator : Runnable {
  @CommandLine.Mixin
  protected lateinit var flags: Flags
    private set

  abstract val sketchStore: SketchStore

  override fun run() {
    val throttler = MinimumIntervalThrottler(Clock.systemUTC(), flags.throttlerMinimumInterval)

    val clientCerts =
      SigningCerts.fromPemFiles(
        certificateFile = flags.tlsFlags.certFile,
        privateKeyFile = flags.tlsFlags.privateKeyFile,
        trustedCertCollectionFile = flags.tlsFlags.certCollectionFile
      )

    val requisitionsStub =
      RequisitionsCoroutineStub(
        buildMutualTlsChannel(
            flags.kingdomPublicApiFlags.target,
            clientCerts,
            flags.kingdomPublicApiFlags.certHost
          )
          .withVerboseLogging(flags.debugVerboseGrpcClientLogging)
      )

    val requisitionFulfillmentStub =
      RequisitionFulfillmentCoroutineStub(
        buildMutualTlsChannel(
            flags.requisitionFulfillmentServiceFlags.target,
            clientCerts,
            flags.requisitionFulfillmentServiceFlags.certHost,
          )
          .withVerboseLogging(flags.debugVerboseGrpcClientLogging)
      )

    val eventGroupStub =
      EventGroupsCoroutineStub(
        buildMutualTlsChannel(
            flags.requisitionFulfillmentServiceFlags.target,
            clientCerts,
            flags.requisitionFulfillmentServiceFlags.certHost,
          )
          .withVerboseLogging(flags.debugVerboseGrpcClientLogging)
      )

    val certificateServiceStub =
      CertificatesCoroutineStub(
        buildMutualTlsChannel(
          flags.kingdomPublicApiFlags.target,
          clientCerts,
          flags.kingdomPublicApiFlags.certHost
        )
      )

    val inMemoryKeyStore = InMemoryKeyStore()

    val workflow =
      RequisitionFulfillmentWorkflow(
        flags.dataProviderResourceName,
        requisitionsStub,
        requisitionFulfillmentStub,
        sketchStore,
        inMemoryKeyStore,
        certificateServiceStub,
        SketchGenerationParams(reach = flags.edpSketchReach, universeSize = flags.edpUniverseSize)
      )

    runBlocking {
      inMemoryKeyStore.storePrivateKeyDer(
        EDP_PRIVATE_KEY_HANDLE_KEY,
        flags.edpPrivateKeyDerFile.readBytes().toByteString()
      )

      eventGroupStub.createEventGroup(
        createEventGroupRequest {
          parent = flags.dataProviderResourceName
          eventGroup = eventGroup { measurementConsumer = flags.mcResourceName }
        }
      )

      throttler.loopOnReady { workflow.execute() }
    }
  }

  protected class Flags {

    @CommandLine.Mixin
    lateinit var tlsFlags: TlsFlags
      private set

    @CommandLine.Option(
      names = ["--data-provider-resource-name"],
      description = ["The public API resource name of this data provider."],
      required = true
    )
    lateinit var dataProviderResourceName: String
      private set

    @CommandLine.Option(
      names = ["--data-provider-consent-signaling-key-der-file"],
      description = ["The EDP's consent signaling private key (DER format) file."],
      required = true
    )
    lateinit var edpPrivateKeyDerFile: File
      private set

    @CommandLine.Option(
      names = ["--mc-resource-name"],
      description = ["The public API resource name of the Measurement Consumer."],
      required = true
    )
    lateinit var mcResourceName: String
      private set

    @CommandLine.Option(
      names = ["--throttler-minimum-interval"],
      description = ["Minimum throttle interval"],
      defaultValue = "1s"
    )
    lateinit var throttlerMinimumInterval: Duration
      private set

    @set:CommandLine.Option(
      names = ["--edp-sketch-reach"],
      description = ["The reach for sketches generated by this EDP Simulator"],
      defaultValue = "10000"
    )
    var edpSketchReach by Delegates.notNull<Int>()
      private set

    @set:CommandLine.Option(
      names = ["--edp-sketch-universe-size"],
      description = ["The size of the universe for sketches generated by this EDP Simulator"],
      defaultValue = "10000"
    )
    var edpUniverseSize by Delegates.notNull<Int>()
      private set

    @CommandLine.Mixin
    lateinit var server: CommonServer.Flags
      private set

    @CommandLine.Mixin
    lateinit var kingdomPublicApiFlags: KingdomPublicApiFlags
      private set

    @CommandLine.Mixin
    lateinit var requisitionFulfillmentServiceFlags: RequisitionFulfillmentServiceFlags
      private set

    var debugVerboseGrpcClientLogging by Delegates.notNull<Boolean>()
      private set
  }

  companion object {
    const val DAEMON_NAME = "EdpSimulator"
  }
}
