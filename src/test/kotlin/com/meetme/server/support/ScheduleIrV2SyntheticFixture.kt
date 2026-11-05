package com.meetme.server.support

import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import com.meetme.server.submission.domain.ir.IrInputBinding
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrSourceBinding
import com.meetme.server.submission.domain.ir.IrSourceKind
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.IrV2Condition
import com.meetme.server.submission.domain.ir.IrV2Result
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import com.meetme.server.submission.domain.ir.ProposedIrV2Condition
import com.meetme.server.submission.domain.ir.ProposedIrV2Result
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_V2_VERSION
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import com.meetme.server.submission.domain.ir.ScheduleIrV2Envelope
import com.meetme.server.submission.domain.ir.ScheduleIrV2Proposal
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** Synthetic manually assigned meaning/resolution, not language/model accuracy or real family input. */
object ScheduleIrV2SyntheticFixture {
    val first = UUID.fromString("00000000-0000-0000-0000-000000000101")
    val second = UUID.fromString("00000000-0000-0000-0000-000000000102")
    val firstParticipant = UUID(0, 301)
    val secondParticipant = UUID(0, 302)
    val room = UUID(0, 203)
    val batch = UUID(0, 202)
    val run = UUID(0, 201)
    val reference = Instant.parse("2027-01-04T00:00:00Z")
    const val POLICY = "precision-synthetic-1"
    const val FIRST_TEXT = "🙂 Synthetic source for manually assigned meaning and temporal resolution."
    const val SECOND_TEXT = "Synthetic peer with a separately assigned precise Friday interval."
    val friday = scope("2027-01-08T18:00:00", "2027-01-08T22:00:00")
    val saturday = scope("2027-01-09T00:00:00", "2027-01-10T00:00:00")
    val preference = scope("2027-01-08T19:00:00", "2027-01-08T20:00:00")
    val fullSearch = scope("2027-01-04T00:00:00", "2027-01-11T00:00:00")
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    fun evidence(firstSource: Boolean = true): ProposedIrEvidence {
        val source = if (firstSource) FIRST_TEXT else SECOND_TEXT
        return ProposedIrEvidence("original", 3, 0, source.codePointCount(0, source.length), source)
    }

    fun condition(
        id: String = "meaning",
        effect: IrEffect = IrEffect.AVAILABLE,
        resolution: IrTemporalResolution = IrTemporalResolution.NON_PRECISE,
        scope: IrScope? = null,
        interpretation: IrInterpretation = IrInterpretation.EXPLICIT,
        assertion: IrAssertion = IrAssertion.ASSERTED,
        supersedes: List<String> = emptyList(),
    ) = ProposedIrV2Condition(id, effect, interpretation, resolution, scope, listOf(evidence()), assertion, supersedes, null, null)

    fun precise(
        id: String = "clear",
        scope: IrScope = friday,
        firstSource: Boolean = true,
    ) = condition(id, resolution = IrTemporalResolution.PRECISE_INTERVAL, scope = scope).copy(evidence = listOf(evidence(firstSource)))

    fun snapshot(): ScheduleEvidenceSnapshot {
        val sources = listOf(first to FIRST_TEXT, second to SECOND_TEXT)
        val inputs =
            sources.mapIndexed { index, (version, text) ->
                IrInputBinding(
                    version,
                    if (index ==
                        0
                    ) {
                        firstParticipant
                    } else {
                        secondParticipant
                    },
                    7,
                    3,
                    hash("synthetic independent binding $version"),
                    listOf(
                        IrSourceBinding("original", IrSourceKind.ORIGINAL, 3, text.codePointCount(0, text.length), hash(text), reference),
                    ),
                )
            }
        return ScheduleEvidenceSnapshot(
            ScheduleIrContext(run, batch, room, "Asia/Seoul", LocalDate.parse("2027-01-04"), LocalDate.parse("2027-01-11"), POLICY, inputs),
            sources.associate { (id, text) ->
                id to
                    mapOf("original" to text)
            },
        )
    }

    fun proposal(
        firstConditions: List<ProposedIrV2Condition>,
        secondConditions: List<ProposedIrV2Condition> = listOf(precise(firstSource = false)),
    ) = ScheduleIrV2Proposal(
        SCHEDULE_IR_V2_VERSION,
        listOf(ProposedIrV2Result(first, firstConditions), ProposedIrV2Result(second, secondConditions)),
    )

    fun envelope(
        firstConditions: List<ProposedIrV2Condition>,
        secondConditions: List<ProposedIrV2Condition> = listOf(precise(firstSource = false)),
    ): ScheduleIrV2Envelope =
        ScheduleIrV2Envelope(
            SCHEDULE_IR_V2_VERSION,
            snapshot().context,
            listOf(
                IrV2Result(first, firstConditions.map(::verified), emptyList()),
                IrV2Result(second, secondConditions.map(::verified), emptyList()),
            ),
        )

    fun verified(condition: ProposedIrV2Condition) =
        IrV2Condition(
            condition.id,
            condition.effect,
            condition.interpretation,
            condition.temporalResolution,
            condition.scope,
            condition.evidence
                .map {
                    IrEvidenceSpan(it.sourceId, it.revision, it.startCodePoint, it.endCodePoint, hash(it.quote))
                },
            condition.assertion,
            condition.supersedes,
            condition.alternativeGroup,
            condition.alternative,
        )

    fun document(
        firstConditions: List<ProposedIrV2Condition>,
        secondConditions: List<ProposedIrV2Condition> = listOf(precise(firstSource = false)),
    ): MutableMap<String, Any?> =
        linkedMapOf(
            "semantic_version" to SCHEDULE_IR_V2_VERSION,
            "results" to
                mutableListOf(
                    linkedMapOf<String, Any?>(
                        "input_ref" to first.toString(),
                        "conditions" to firstConditions.map(::wire).toMutableList(),
                    ),
                    linkedMapOf<String, Any?>(
                        "input_ref" to second.toString(),
                        "conditions" to secondConditions.map(::wire).toMutableList(),
                    ),
                ),
        )

    fun json(
        firstConditions: List<ProposedIrV2Condition>,
        secondConditions: List<ProposedIrV2Condition> = listOf(precise(firstSource = false)),
    ) = mapper.writeValueAsString(document(firstConditions, secondConditions))

    fun wire(condition: ProposedIrV2Condition): MutableMap<String, Any?> =
        linkedMapOf(
            "id" to condition.id,
            "effect" to condition.effect.name,
            "interpretation" to condition.interpretation.name,
            "temporal_resolution" to condition.temporalResolution.name,
            "scope" to
                condition.scope?.let {
                    linkedMapOf(
                        "dimension" to it.dimension.name,
                        "start" to it.start?.toString(),
                        "end" to it.end?.toString(),
                    )
                },
            "evidence" to
                condition.evidence.map {
                    linkedMapOf(
                        "source_id" to it.sourceId,
                        "revision" to it.revision,
                        "start_code_point" to it.startCodePoint,
                        "end_code_point" to it.endCodePoint,
                        "quote" to it.quote,
                    )
                },
            "assertion" to condition.assertion.name,
            "supersedes" to condition.supersedes,
            "alternative_group" to condition.alternativeGroup,
            "alternative" to condition.alternative,
        )

    fun encode(value: Any) = mapper.writeValueAsString(value)

    fun scope(
        start: String,
        end: String,
    ) = IrScope(IrDimension.TIME, LocalDateTime.parse(start), LocalDateTime.parse(end))

    fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
