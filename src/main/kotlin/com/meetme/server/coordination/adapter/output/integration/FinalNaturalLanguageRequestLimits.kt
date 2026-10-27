package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.submission.domain.SubmissionRules

/** Existing product and persisted metadata bounds, enforced before either final provider can perform I/O. */
internal fun validateFinalNaturalLanguageRequest(request: NaturalLanguageBatchRequest) {
    if (request.inputs.isEmpty() ||
        request.inputs.size > SubmissionRules.MAX_ROOM_PARTICIPANTS ||
        request.timeZone.id.length > 64 ||
        !validWireText(request.timeZone.id)
    ) {
        invalidFinalRequest()
    }
    val references = hashSetOf<String>()
    var total = 0
    request.inputs.forEach { input ->
        val raw = input.rawText
        if (input.inputRef.length > 36 ||
            !validWireText(input.inputRef) ||
            !references.add(input.inputRef) ||
            input.locale.toLanguageTag().length > 35 ||
            raw.length > SubmissionRules.MAX_RAW_TEXT_CODE_POINTS * 2 ||
            !validWireText(raw)
        ) {
            invalidFinalRequest()
        }
        val count = raw.codePointCount(0, raw.length)
        if (count > SubmissionRules.MAX_RAW_TEXT_CODE_POINTS) invalidFinalRequest()
        total += count
        if (total > SubmissionRules.MAX_BATCH_TEXT_CODE_POINTS) invalidFinalRequest()
    }
}

private fun validWireText(value: String): Boolean {
    if (value.isBlank()) return false
    var index = 0
    while (index < value.length) {
        val character = value[index++]
        if (character == '\u0000' || character.isLowSurrogate()) return false
        if (character.isHighSurrogate() && (index >= value.length || !value[index++].isLowSurrogate())) return false
    }
    return true
}

private fun invalidFinalRequest(): Nothing = throw NaturalLanguageParserException(ParserFailureKind.INVALID_REQUEST)
