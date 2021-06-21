// Copyright 2020 The Cross-Media Measurement Authors
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

package org.wfanet.measurement.kingdom.deploy.gcloud.server

import java.time.Clock
import kotlinx.coroutines.runBlocking
import org.wfanet.measurement.common.commandLineMain
import org.wfanet.measurement.common.identity.RandomIdGenerator
import org.wfanet.measurement.gcloud.spanner.SpannerFlags
import org.wfanet.measurement.kingdom.deploy.common.server.LegacyKingdomDataServer
import org.wfanet.measurement.kingdom.deploy.gcloud.spanner.SpannerReportDatabase
import org.wfanet.measurement.kingdom.deploy.gcloud.spanner.SpannerRequisitionDatabase
import picocli.CommandLine

/** Implementation of [LegacyKingdomDataServer] using Google Cloud Spanner. */
@CommandLine.Command(
  name = "LegacySpannerKingdomDataServer",
  description = ["Start the internal Kingdom data-layer services in a single blocking server."],
  mixinStandardHelpOptions = true,
  showDefaultValues = true
)
class LegacySpannerKingdomDataServer : LegacyKingdomDataServer() {
  @CommandLine.Mixin private lateinit var spannerFlags: SpannerFlags

  override fun run() = runBlocking {
    spannerFlags.usingSpanner { spanner ->
      val clock = Clock.systemUTC()
      val idGenerator = RandomIdGenerator(clock)
      val client = spanner.databaseClient

      run(
        SpannerReportDatabase(clock, idGenerator, client),
        SpannerRequisitionDatabase(clock, idGenerator, client)
      )
    }
  }
}

fun main(args: Array<String>) = commandLineMain(LegacySpannerKingdomDataServer(), args)
