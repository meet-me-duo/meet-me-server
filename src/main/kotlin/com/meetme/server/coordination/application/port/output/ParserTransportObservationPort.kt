package com.meetme.server.coordination.application.port.output

import java.util.UUID

/** Explicit local identity; this context must never be included in a provider payload. */
data class ParserObservationContext(
    val invocationId: UUID,
    val attemptId: UUID,
)

enum class ParserClientCallOutcome {
    COMPLETED,
    FAILED,
}

/** Local OkHttp lifecycle only, without request, response or exception content. */
data class ParserClientCallObservation(
    val callNumber: Int,
    val elapsedNanos: Long,
    val outcome: ParserClientCallOutcome,
    val httpStatus: Int?,
)

/** Present only once every locally started call settled. An absent snapshot means unknown. */
data class ParserTransportSnapshot(
    val context: ParserObservationContext,
    val provider: AnalysisProvider,
    val parserDispatched: Boolean,
    val calls: List<ParserClientCallObservation>,
) {
    companion object {
        const val MAX_CLIENT_CALLS = 256
    }
}

fun interface ParserTransportObservationPort {
    fun record(snapshot: ParserTransportSnapshot)
}
