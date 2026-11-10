package com.meetme.server.support

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.ProposedLiteralQuoteLocator
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Nonpersonal manual semantics; repeated words are evidence-location data, not a live-language oracle. */
object ScopedTermEvidenceTestFixture {
    const val RAW = "🙂 안내 오후. 2027-01-08 오후 가능. 끝 오후."
    const val CONTEXT = "2027-01-08 오후 가능."
    const val NARROW_CONTEXT = "2027-01-08 오후"
    const val OTHER_CONTEXT = "끝 오후."
    val first = F.ir.first
    val second = F.ir.second
    val policy = N.policy

    fun input(raw: String = RAW): ScheduleDslOfflineInput = F.input(raw).copy(policy = policy, meetingMode = MeetingMode.REMOTE)

    fun snapshot(raw: String = RAW) = F.snapshot(input(raw))

    fun locator(
        quote: String,
        source: String = "original",
        revision: Int = 3,
    ) = ProposedLiteralQuoteLocator(source, revision, quote)

    fun span(
        quote: String,
        start: Int,
        end: Int,
    ) = IrEvidenceSpan("original", 3, start, end, IrEvidenceBindings.sha256(quote))

    fun document(
        input: ScheduleDslOfflineInput = input(),
        contexts: List<String> = listOf(CONTEXT),
    ): MutableMap<String, Any?> {
        val claim = F.claim(input).copy(evidence = contexts.map { F.evidence(input, it) })
        val original = F.document(F.proposal(input, claims = listOf(claim)))

        @Suppress("UNCHECKED_CAST")
        val result = locators(original) as MutableMap<String, Any?>
        result["semantic_version"] = "schedule-ir-product-6"
        results(result).forEach {
            it["mode_restrictions"] = emptyList<Any>()
            it["directional_times"] = emptyList<Any>()
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    fun results(document: MutableMap<String, Any?>) = document["results"] as MutableList<MutableMap<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    fun firstTerm(document: MutableMap<String, Any?>) =
        (results(document).first()["approved_terms"] as MutableList<MutableMap<String, Any?>>).single()

    @Suppress("UNCHECKED_CAST")
    fun fields(value: Any?) = value as MutableMap<String, Any?>

    private fun locators(value: Any?): Any? =
        when (value) {
            is Map<*, *> ->
                if ("start_code_point" in value) {
                    value.entries
                        .filter { it.key in setOf("source_id", "revision", "quote") }
                        .associate { it.key.toString() to it.value }
                        .toMutableMap()
                } else {
                    value.entries.associate { it.key.toString() to locators(it.value) }.toMutableMap()
                }
            is List<*> -> value.map(::locators).toMutableList()
            else -> value
        }
}
