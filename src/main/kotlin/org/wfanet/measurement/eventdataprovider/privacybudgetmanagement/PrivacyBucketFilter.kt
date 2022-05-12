/**
 * Copyright 2022 The Cross-Media Measurement Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * ```
 *      http://www.apache.org/licenses/LICENSE-2.0
 * ```
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.wfanet.measurement.eventdataprovider.privacybudgetmanagement

import com.google.protobuf.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.wfanet.measurement.eventdataprovider.eventfiltration.EventFilters

class PrivacyBucketFilter(val privacyBucketMapper: PrivacyBucketMapper) {
  /**
   * Returns a list of privacy bucket groups that might be affected by a query.
   *
   * @param requisitionSpec The requisitionSpec protobuf that is associated with the query. The date
   * range and demo groups are obtained from this.
   * @param measurementSpec The measurementSpec protobuf that is associated with the query. The VID
   * sampling interval is obtained from from this.
   * @return A list of potentially affected PrivacyBucketGroups. It is guaranteed that the items in
   * this list are disjoint. In the current implementation, each privacy bucket group represents a
   * single privacy bucket.
   */
  fun getPrivacyBucketGroups(
    measurementConsumerId: String,
    privacyQuery: PrivacyQuery
  ): List<PrivacyBucketGroup> {

    return privacyQuery.privacyEventGroupSpecs
      .flatMap {
        getPrivacyBucketGroups(
          measurementConsumerId,
          it.eventFilter,
          it.startDate,
          it.endDate,
          privacyQuery.vidSampleStart,
          privacyQuery.vidSampleStart + privacyQuery.vidSampleWidth
        )
      }
      .toList()
  }

  private fun getPrivacyBucketGroups(
    measurementConsumerId: String,
    eventFilter: String,
    startDate: LocalDate,
    endDate: LocalDate,
    vidSamplingIntervalStart: Float,
    vidSamplingIntervalEnd: Float
  ): Sequence<PrivacyBucketGroup> {

    val program = privacyBucketMapper.toPrivacyFilterProgram(eventFilter)

    val vidsIntervalStartPoints =
      PrivacyLandscape.vidsIntervalStartPoints.filter {
        it >= vidSamplingIntervalStart && it <= vidSamplingIntervalEnd
      }
    val dates =
      PrivacyLandscape.dates.filter {
        (it.isAfter(startDate) || it.isEqual(startDate)) &&
          (it.isBefore(endDate) || it.isEqual(endDate))
      }

    return sequence {
      for (vidsIntervalStartPoint in vidsIntervalStartPoints) {
        for (date in dates) {
          for (ageGroup in PrivacyLandscape.ageGroups) {
            for (gender in PrivacyLandscape.genders) {
              val privacyBucketGroup =
                PrivacyBucketGroup(
                  measurementConsumerId,
                  date,
                  date,
                  ageGroup,
                  gender,
                  vidsIntervalStartPoint,
                  PrivacyLandscape.PRIVACY_BUCKET_VID_SAMPLE_WIDTH
                )
              if (EventFilters.matches(
                  privacyBucketMapper.toEventMessage(privacyBucketGroup),
                  program
                )
              ) {
                yield(privacyBucketGroup)
              }
            }
          }
        }
      }
    }
  }
}
