package com.meetme.server.submission.domain.ir

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** Independent synthetic sources and expected IR; no production validator/factory supplies an oracle. */
object ScheduleIrTestFixture {
    val firstId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
    val secondId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000102")
    val reference: Instant = Instant.parse("2027-01-04T00:00:00Z")
    const val FIRST_TEXT = "🙂 목요일 18시부터 20시까지 가능. 금요일 7시는 미정. 장소는 역 근처."
    const val SECOND_TEXT = "목요일 18시부터 21시까지 가능."
    val window = IrScope(IrDimension.TIME, LocalDateTime.parse("2027-01-07T18:00:00"), LocalDateTime.parse("2027-01-07T20:00:00"))
    val fullSearch = IrScope(IrDimension.TIME, LocalDateTime.parse("2027-01-04T00:00:00"), LocalDateTime.parse("2027-01-11T00:00:00"))

    fun sha(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun context(): ScheduleIrContext =
        ScheduleIrContext(
            UUID.fromString("00000000-0000-0000-0000-000000000201"),
            UUID.fromString("00000000-0000-0000-0000-000000000202"),
            UUID.fromString("00000000-0000-0000-0000-000000000203"),
            "Asia/Seoul",
            LocalDate.parse("2027-01-04"),
            LocalDate.parse("2027-01-11"),
            "synthetic-explicit-policy-1",
            listOf(binding(firstId, FIRST_TEXT, 301), binding(secondId, SECOND_TEXT, 302)),
        )

    fun snapshot(context: ScheduleIrContext = context()): ScheduleEvidenceSnapshot =
        ScheduleEvidenceSnapshot(
            context,
            mapOf(firstId to mapOf("original" to FIRST_TEXT), secondId to mapOf("original" to SECOND_TEXT)),
        )

    fun evidence(quote: String = "목요일 18시부터 20시까지 가능"): ProposedIrEvidence {
        val start = FIRST_TEXT.codePointCount(0, FIRST_TEXT.indexOf(quote))
        return ProposedIrEvidence("original", 3, start, start + quote.codePointCount(0, quote.length), quote)
    }

    fun condition(id: String = "thursday"): ProposedIrCondition =
        ProposedIrCondition(id, IrEffect.AVAILABLE, IrInterpretation.EXPLICIT, window, listOf(evidence()))

    fun proposal(
        conditions: List<ProposedIrCondition> = listOf(condition()),
        availability: IrAvailabilityState = IrAvailabilityState.KNOWN_WINDOWS,
        rejected: List<IrQuarantine> = emptyList(),
    ): ScheduleIrProposal =
        ScheduleIrProposal(
            SCHEDULE_IR_VERSION,
            listOf(
                ProposedIrResult(firstId, availability, conditions, rejected),
                ProposedIrResult(secondId, IrAvailabilityState.UNKNOWN, emptyList()),
            ),
        )

    fun expectedCondition(proposed: ProposedIrCondition = condition()): IrCondition =
        IrCondition(
            proposed.id,
            proposed.effect,
            proposed.interpretation,
            proposed.scope,
            proposed.evidence.map { IrEvidenceSpan(it.sourceId, it.revision, it.startCodePoint, it.endCodePoint, sha(it.quote)) },
            proposed.assertion,
            proposed.supersedes,
            proposed.alternativeGroup,
            proposed.alternative,
        )

    fun envelope(context: ScheduleIrContext = context()): ScheduleIrEnvelope =
        ScheduleIrEnvelope(
            SCHEDULE_IR_VERSION,
            context,
            listOf(
                IrResult(firstId, IrAvailabilityState.KNOWN_WINDOWS, listOf(expectedCondition()), emptyList()),
                IrResult(secondId, IrAvailabilityState.UNKNOWN, emptyList(), emptyList()),
            ),
        )

    private fun binding(
        id: UUID,
        text: String,
        participant: Long,
    ): IrInputBinding =
        IrInputBinding(
            id,
            UUID(0, participant),
            7,
            3,
            sha("synthetic immutable input binding $id"),
            listOf(IrSourceBinding("original", IrSourceKind.ORIGINAL, 3, text.codePointCount(0, text.length), sha(text), reference)),
        )
}
