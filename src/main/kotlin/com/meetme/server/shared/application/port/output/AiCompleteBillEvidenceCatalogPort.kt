package com.meetme.server.shared.application.port.output

import java.math.BigInteger
import java.util.UUID

/** Reviewed complete charge evidence; usage and a provider success are not this authority. */
data class AiReviewedCompleteBillEvidence(
    val reservationId: UUID,
    val quoteFingerprint: String,
    val evidenceReference: String,
    val evidenceFingerprint: String,
    val amount: BigInteger,
    val unit: AiBudgetUnit,
    val reviewReference: String,
)

fun interface AiCompleteBillEvidenceCatalogPort {
    fun entries(): List<AiReviewedCompleteBillEvidence>
}
