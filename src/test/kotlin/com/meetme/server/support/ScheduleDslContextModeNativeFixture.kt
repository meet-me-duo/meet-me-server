package com.meetme.server.support

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.ProposedParticipantScheduleProgram
import com.meetme.server.submission.domain.dsl.ProposedScheduleModeRestriction
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.ProposedIrV2Result
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import java.time.ZoneId
import com.meetme.server.support.ApprovedHalfDayTestFixture as F

/** Synthetic native rows plus manually declared semantics. No new codec or repository is used to prepare their RED. */
object ScheduleDslContextModeNativeFixture {
    const val FIRST = "🙂 2027-01-07 18:00부터 22:00까지는 비대면만 가능. 2027-01-08에는 6시 반부터 10시까지 가능할 듯해."
    const val SECOND = "2027-01-07과 2027-01-08 각각 18:00부터 22:00까지 가능."
    const val MODE_QUOTE = "2027-01-07 18:00부터 22:00까지는 비대면만 가능"
    val policy = IrMatchingPolicy("schedule-context-mode-1", setOf(IrInterpretation.EXPLICIT, IrInterpretation.INFERRED), emptyList(), true)
    val thursday = window("2027-01-07T18:00", "2027-01-07T22:00")
    val friday = window("2027-01-08T18:30", "2027-01-08T22:00")

    fun input(): ScheduleDslOfflineInput =
        F.input(FIRST).let { input ->
            input.copy(
                policy = policy,
                meetingMode = MeetingMode.EITHER,
                inputs =
                    input.inputs.mapIndexed { index, submission ->
                        if (index == 0) {
                            submission.copy(evidence = InputEvidence(3, F.ir.reference, F.q.answer))
                        } else {
                            submission.copy(rawText = SECOND)
                        }
                    },
            )
        }

    fun snapshot(input: ScheduleDslOfflineInput = input()) = F.snapshot(input)

    fun capsule(input: ScheduleDslOfflineInput = input()) = ScheduleDslProviderInputCapsule.create(snapshot(input), F.q.native(input))

    fun proposal(input: ScheduleDslOfflineInput = input()) =
        F
            .proposal(
                input,
                claims =
                    listOf(
                        F.claim(input, "thu", scope = F.scope("2027-01-07T18:00", "2027-01-07T22:00")),
                        F.claim(input, "context", scope = F.scope("2027-01-08T18:30", "2027-01-08T22:00")).copy(
                            interpretation = IrInterpretation.INFERRED,
                            temporalResolution = IrTemporalResolution.NON_PRECISE,
                        ),
                    ),
                program =
                    F.program(
                        listOf(
                            F.ref(input, "thu-ref", "thu"),
                            F.ref(input, "context-ref", "context"),
                            F.node(input, "either-day", ScheduleDslOperator.ANY_OF, listOf("thu-ref", "context-ref")),
                        ),
                        "either-day",
                    ),
                terms = emptyList(),
            ).let { proposal ->
                val peer =
                    listOf(
                        F.claim(input, "peer-thu", scope = F.scope("2027-01-07T18:00", "2027-01-07T22:00"), index = 1),
                        F.claim(input, "peer-fri", scope = F.scope("2027-01-08T18:00", "2027-01-08T22:00"), index = 1),
                    )
                val peerProgram =
                    F.program(
                        listOf(
                            F.ref(input, "peer-thu-ref", "peer-thu", 1),
                            F.ref(input, "peer-fri-ref", "peer-fri", 1),
                            F.node(input, "peer-days", ScheduleDslOperator.ANY_OF, listOf("peer-thu-ref", "peer-fri-ref"), index = 1),
                        ),
                        "peer-days",
                    )
                proposal.copy(
                    base =
                        proposal.base.copy(
                            claims =
                                proposal.base.claims.copy(
                                    results =
                                        listOf(
                                            proposal.base.claims.results
                                                .first(),
                                            ProposedIrV2Result(F.ir.second, peer),
                                        ),
                                ),
                            programs = listOf(proposal.base.programs.first(), ProposedParticipantScheduleProgram(F.ir.second, peerProgram)),
                        ),
                )
            }

    fun restriction(input: ScheduleDslOfflineInput = input()) =
        ProposedScheduleModeRestriction(
            F.ir.first,
            "remote-thursday",
            F.scope("2027-01-07T18:00", "2027-01-07T22:00"),
            setOf(MeetingMode.REMOTE),
            IrInterpretation.EXPLICIT,
            IrInterpretation.EXPLICIT,
            IrTemporalResolution.PRECISE_INTERVAL,
            listOf(F.evidence(input, MODE_QUOTE)),
        )

    fun expectedEnvelope(input: ScheduleDslOfflineInput = input()) =
        ScheduleDslContextModeValidator().validateDeclaration(
            ScheduleDslApprovedTermsValidator().validate(proposal(input), snapshot(input)),
            listOf(restriction(input)),
            snapshot(input),
        )

    fun document(input: ScheduleDslOfflineInput = input()): MutableMap<String, Any?> =
        F.document(proposal(input)).apply {
            this["semantic_version"] = "schedule-ir-product-5"
            @Suppress("UNCHECKED_CAST")
            val results = this["results"] as MutableList<MutableMap<String, Any?>>
            results.forEachIndexed { index, result ->
                result["mode_restrictions"] = if (index == 0) mutableListOf(modeMap(input)) else mutableListOf<Any>()
            }
        }

    private fun modeMap(input: ScheduleDslOfflineInput): MutableMap<String, Any?> =
        restriction(input).let { mode ->
            linkedMapOf(
                "id" to mode.id,
                "scope" to linkedMapOf("dimension" to "TIME", "start" to "2027-01-07T18:00", "end" to "2027-01-07T22:00"),
                "permitted_modes" to mutableListOf("REMOTE"),
                "mode_interpretation" to mode.modeInterpretation.name,
                "time_interpretation" to mode.timeInterpretation.name,
                "temporal_resolution" to mode.temporalResolution.name,
                "evidence" to mode.evidence.map(F::evidenceMap).toMutableList(),
                "assertion" to mode.assertion.name,
                "supersedes" to mutableListOf<String>(),
                "alternative_group" to null,
                "alternative" to null,
            )
        }

    @Suppress("UNCHECKED_CAST")
    fun firstMode(document: MutableMap<String, Any?>) =
        (F.firstResult(document)["mode_restrictions"] as MutableList<MutableMap<String, Any?>>).first()

    fun seed(
        jdbc: JdbcTemplate,
        mapper: ObjectMapper,
    ) {
        FinalAnalysisRouteTestFixture.seed(jdbc, mapper, input())
        jdbc.update("UPDATE meeting_rooms SET meeting_mode='EITHER' WHERE id=?", F.ir.room)
    }

    private fun window(
        start: String,
        end: String,
    ) = InstantTimeRange(
        LocalDateTime.parse(start).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
        LocalDateTime.parse(end).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
    )
}
