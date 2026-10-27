package com.meetme.server.shared.application.port.output

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Exact ordered immutable input used for both final-provider and persisted-source binding. */
data class AiDispatchInputSnapshot(
    val inputRef: String,
    val rawText: String,
    val locale: String,
    val referenceDate: LocalDate,
)

/** Canonical SHA-256 of length-prefixed UTF-8 components; no source text is retained. */
object AiDispatchFingerprints {
    fun personalSource(rawText: String): String = framed(listOf(rawText))

    fun personalContext(
        referenceAt: Instant,
        timeZone: ZoneId,
        locale: String,
        searchStartDate: LocalDate,
        searchEndDate: LocalDate,
    ): String = framed(listOf(referenceAt.toString(), timeZone.id, locale, searchStartDate.toString(), searchEndDate.toString()))

    fun batchSource(inputs: List<AiDispatchInputSnapshot>): String =
        framed(
            listOf("final-source-v1") + inputs.flatMap { listOf(it.inputRef, it.rawText) },
        )

    fun batchContext(
        timeZone: ZoneId,
        searchStartDate: LocalDate,
        searchEndDate: LocalDate,
        inputs: List<AiDispatchInputSnapshot>,
    ): String =
        framed(
            listOf("final-context-v1", timeZone.id, searchStartDate.toString(), searchEndDate.toString()) +
                inputs.flatMap { listOf(it.inputRef, it.locale, it.referenceDate.toString()) },
        )

    private fun framed(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
