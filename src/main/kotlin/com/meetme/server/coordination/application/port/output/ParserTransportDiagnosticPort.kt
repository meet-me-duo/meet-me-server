package com.meetme.server.coordination.application.port.output

/** Optional local diagnostics. No provider/request content or exception messages are permitted. */
fun interface ParserTransportDiagnosticPort {
    fun record(snapshot: ParserTransportDiagnosticSnapshot)
}

data class ParserTransportDiagnosticSnapshot(
    val context: ParserObservationContext,
    val provider: AnalysisProvider,
    val calls: List<ParserClientCallDiagnostic>,
    val clientCallsTruncated: Boolean,
)

data class ParserClientCallDiagnostic(
    val callNumber: Int,
    val requestedTimeoutNanos: Long?,
    val adapterBudgetNanos: Long?,
    val actualCallTimeoutNanos: Long?,
    val callStartDeadlineRemainingNanos: Long?,
    val requestHeadersDeadlineRemainingNanos: Long?,
    val dispatchRemainingAtCallStartNanos: Long?,
    val canceledAtFailure: Boolean?,
    val causes: List<ParserDiagnosticCause>,
    val causesTruncated: Boolean,
    val streamResetCode: ParserDiagnosticResetCode?,
    val events: List<ParserDiagnosticEvent>,
    val eventsTruncated: Boolean,
    val metadataUnavailable: Boolean,
    val settled: Boolean,
) {
    companion object {
        const val MAX_EVENTS = 16
        const val MAX_CAUSES = 8
    }
}

data class ParserDiagnosticEvent(
    val kind: ParserDiagnosticEventKind,
    val elapsedNanos: Long,
)

enum class ParserDiagnosticEventKind {
    CALL_START,
    REQUEST_HEADERS_START,
    REQUEST_HEADERS_END,
    RESPONSE_HEADERS_START,
    RESPONSE_HEADERS_END,
    CANCELED,
    CALL_FAILED,
    CALL_END,
}

enum class ParserDiagnosticCause {
    GENAI_IO,
    INTERRUPTED_IO,
    SOCKET_TIMEOUT,
    HTTP_TIMEOUT,
    STREAM_RESET,
    IO,
    DISPATCH_DENIED,
    OTHER,
}

/** A reset code is an observed value, never proof of which peer caused the failure. */
enum class ParserDiagnosticResetCode {
    NO_ERROR,
    PROTOCOL_ERROR,
    INTERNAL_ERROR,
    FLOW_CONTROL_ERROR,
    SETTINGS_TIMEOUT,
    STREAM_CLOSED,
    FRAME_SIZE_ERROR,
    REFUSED_STREAM,
    CANCEL,
    COMPRESSION_ERROR,
    CONNECT_ERROR,
    ENHANCE_YOUR_CALM,
    INADEQUATE_SECURITY,
    HTTP_1_1_REQUIRED,
    UNKNOWN,
}
