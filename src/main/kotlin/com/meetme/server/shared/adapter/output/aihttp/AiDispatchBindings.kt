package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiDispatchFingerprints
import com.meetme.server.shared.application.port.output.AiDispatchInputSnapshot
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal fun validatePersonalDispatch(
    context: AiProviderDispatchContext?,
    raw: String,
    referenceAt: Instant,
    zone: ZoneId,
    locale: String,
    start: LocalDate,
    end: LocalDate,
) {
    val execution = context?.execution ?: denyDispatch()
    if (execution.kind != AiBudgetExecutionKind.PRECHECK ||
        execution.sourceFingerprint != AiDispatchFingerprints.personalSource(raw) ||
        execution.contextFingerprint != AiDispatchFingerprints.personalContext(referenceAt, zone, locale, start, end)
    ) {
        denyDispatch()
    }
}

internal fun validateFinalDispatch(request: NaturalLanguageBatchRequest) {
    val execution = request.dispatchContext?.execution ?: denyDispatch()
    val inputs = request.inputs.map { AiDispatchInputSnapshot(it.inputRef, it.rawText, it.locale.toLanguageTag(), it.referenceDate) }
    if (execution.kind != AiBudgetExecutionKind.FINAL_ANALYSIS ||
        execution.sourceFingerprint != AiDispatchFingerprints.batchSource(inputs) ||
        execution.contextFingerprint !=
        AiDispatchFingerprints.batchContext(request.timeZone, request.searchStartDate, request.searchEndDate, inputs)
    ) {
        denyDispatch()
    }
}

internal fun physicalProfile(
    operation: AiPhysicalOperation,
    model: String,
    endpoint: URI,
    limit: Int,
): AiPhysicalWireProfile =
    AiPhysicalWireProfile(
        digest("${operation.name}\n$model\nPOST\n${endpoint.toASCIIString()}\n$limit\nv1".toByteArray(Charsets.UTF_8)),
        "POST",
        endpoint,
        limit,
    )
