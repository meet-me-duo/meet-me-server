package com.meetme.server.coordination.application.port.output

import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.coordination.domain.ir.IrExecutionTicket
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import java.time.Duration

/** One final-analysis dispatch; the calling experiment owns Jev/clarification and the single global ledger. */
data class ScheduleDslProviderRequest(
    val ticket: IrExecutionTicket,
    val snapshot: ScheduleEvidenceSnapshot,
    val timeout: Duration,
    val inputCapsule: ScheduleDslProviderInputCapsule? = null,
)

data class ScheduleDslProviderResponse(
    val responseJson: String,
    val usage: ParserUsage,
    val provider: AnalysisProvider,
    val model: String,
)

fun interface ScheduleDslProviderPort {
    fun generate(request: ScheduleDslProviderRequest): ScheduleDslProviderResponse
}

/**
 * Admission precedes the physical attempt. Implementations must atomically consume the global cap
 * and reject a previously admitted logicalKey, including concurrent callers and uncertain outcomes.
 * The worker alone does not provide exactly-once dispatch; no default permit implementation exists.
 */
fun interface ScheduleDslCallBudget {
    fun claim(
        logicalKey: String,
        provider: String,
        model: String,
    )
}
