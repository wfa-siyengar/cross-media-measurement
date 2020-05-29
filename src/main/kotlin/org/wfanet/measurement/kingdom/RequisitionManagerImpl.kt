package org.wfanet.measurement.kingdom

import org.wfanet.measurement.common.Pagination
import org.wfanet.measurement.db.kingdom.KingdomRelationalDatabase
import org.wfanet.measurement.internal.kingdom.Requisition
import org.wfanet.measurement.internal.kingdom.RequisitionState

class RequisitionManagerImpl(
  private val database: KingdomRelationalDatabase
) : RequisitionManager {
  override suspend fun createRequisition(requisition: Requisition): Requisition {
    require(requisition.externalRequisitionId == 0L) {
      "Cannot create a Requisition with a set externalRequisitionId: $requisition"
    }
    require(requisition.state == RequisitionState.UNFULFILLED) {
      "Initial requisitions must be unfulfilled: $requisition"
    }

    return database.writeNewRequisition(requisition)
  }

  override suspend fun fulfillRequisition(
    requisitionExternalKey: RequisitionExternalKey
  ): Requisition =
    database.fulfillRequisition(requisitionExternalKey.externalId)

  override suspend fun listRequisitions(
    campaignExternalKey: CampaignExternalKey,
    states: Set<RequisitionState>,
    pagination: Pagination
  ): RequisitionManager.ListResult {
    val result = database.listRequisitions(campaignExternalKey.externalId, states, pagination)
    return RequisitionManager.ListResult(result.requisitions, result.nextPageToken)
  }
}
