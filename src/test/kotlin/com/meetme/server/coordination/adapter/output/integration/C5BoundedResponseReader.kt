package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import okhttp3.ResponseBody

/** Observation capacity is separate from admission; oversized provider envelopes still fail closed. */
internal object C5BoundedResponseReader {
    const val OBSERVATION_LIMIT = 2 * 1024 * 1024

    fun read(
        body: ResponseBody,
        admittedLimit: Int,
        responseObserver: ((ByteArray) -> Unit)? = null,
        diagnostics: ((Map<String, Any>) -> Unit)? = null,
    ): ByteArray {
        require(admittedLimit in 1..OBSERVATION_LIMIT)
        val declared = body.contentLength()
        val readLimit = if (responseObserver != null) OBSERVATION_LIMIT else admittedLimit
        val bytes = body.byteStream().use { it.readNBytes(readLimit + 1) }
        val completeCapture = responseObserver != null && bytes.size <= OBSERVATION_LIMIT
        if (completeCapture) runCatching { responseObserver.invoke(bytes.copyOf()) }
        if (declared > admittedLimit || bytes.size > admittedLimit) {
            runCatching {
                diagnostics?.invoke(
                    mapOf(
                        "kind" to "LOCAL_RESPONSE_BYTE_LIMIT",
                        "admittedLimit" to admittedLimit,
                        "declaredBytes" to declared,
                        "observedBytes" to bytes.size,
                        "captureComplete" to completeCapture,
                    ),
                )
            }
            throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
        }
        return bytes
    }
}
