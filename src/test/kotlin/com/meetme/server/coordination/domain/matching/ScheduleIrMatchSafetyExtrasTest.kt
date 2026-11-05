package com.meetme.server.coordination.domain.matching

import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrAvailabilityState
import com.meetme.server.submission.domain.ir.IrCondition
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import com.meetme.server.submission.domain.ir.IrInputBinding
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrProblem
import com.meetme.server.submission.domain.ir.IrQuarantine
import com.meetme.server.submission.domain.ir.IrResult
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrSourceBinding
import com.meetme.server.submission.domain.ir.IrSourceKind
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_VERSION
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import com.meetme.server.submission.domain.ir.ScheduleIrEnvelope
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Further independent boundary oracles; no production policy defaults or source changes. */
class ScheduleIrMatchSafetyExtrasTest {
    private val a = UUID(0, 1801)
    private val b = UUID(0, 1802)
    private val pa = ParticipantId(UUID(0, 1901))
    private val pb = ParticipantId(UUID(0, 1902))
    private val raw = "합성 일정 원문. 가능 시간과 불명확한 예외를 따로 보존한다."
    private val friday = scope("2027-01-08T18:00:00", "2027-01-08T21:00:00")

    @ParameterizedTest
    @EnumSource(IrProblem::class)
    fun `X01 every unscoped quarantine kind retains global time influence`(problem: IrProblem) {
        val input =
            envelope(listOf(condition("available", IrEffect.AVAILABLE, friday))).let { original ->
                original.copy(
                    results =
                        original.results.map { result ->
                            if (result.submissionVersionId == a) {
                                result.copy(
                                    availability = IrAvailabilityState.UNKNOWN,
                                    quarantined =
                                        listOf(
                                            IrQuarantine(
                                                if (problem ==
                                                    IrProblem.AVAILABILITY
                                                ) {
                                                    null
                                                } else {
                                                    "invalid"
                                                },
                                                problem,
                                                null,
                                            ),
                                        ),
                                )
                            } else {
                                result
                            }
                        },
                )
            }
        val policy = IrMatchingPolicy("extras-synthetic-1", setOf(IrInterpretation.EXPLICIT), emptyList(), false)

        val prepared = ScheduleIrMatchInputPolicy.project(input, policy)

        assertTrue(windows(prepared, pa).isEmpty())
        assertEquals(listOf(instant("2027-01-08T09:00:00Z", "2027-01-08T12:00:00Z")), windows(prepared, pb))
        assertEquals(setOf(pa, pb), prepared.participants.map { it.participantId }.toSet())
        val evidence = prepared.evidence.getValue(pa)
        assertTrue(evidence.unresolvedTime)
        assertEquals(1, evidence.quarantineCount)
        assertEquals(IrAvailabilityState.UNKNOWN, evidence.declaredAvailability)
        assertEquals(friday, evidence.conditions.single().scope)
    }

    @ParameterizedTest
    @ValueSource(strings = ["NULL_SCOPE", "TIME_WITHOUT_INTERVAL", "BOUNDED_ALL"])
    fun `X02 valid unresolved scope affects all time when unknown and only its bounded interval when ALL`(kind: String) {
        val unresolvedScope =
            when (kind) {
                "NULL_SCOPE" -> null
                "TIME_WITHOUT_INTERVAL" -> IrScope(IrDimension.TIME)
                else -> scope("2027-01-08T19:00:00", "2027-01-08T20:00:00").copy(dimension = IrDimension.ALL)
            }
        val unresolved = condition("unknown", IrEffect.UNRESOLVED, unresolvedScope).copy(interpretation = IrInterpretation.UNRESOLVED)
        val input = envelope(listOf(condition("available", IrEffect.AVAILABLE, friday), unresolved))
        val policy =
            IrMatchingPolicy("extras-synthetic-1", setOf(IrInterpretation.EXPLICIT, IrInterpretation.UNRESOLVED), emptyList(), false)

        val prepared = ScheduleIrMatchInputPolicy.project(input, policy)

        val expected =
            if (kind == "BOUNDED_ALL") {
                listOf(instant("2027-01-08T09:00:00Z", "2027-01-08T10:00:00Z"), instant("2027-01-08T11:00:00Z", "2027-01-08T12:00:00Z"))
            } else {
                emptyList()
            }
        assertEquals(expected, windows(prepared, pa))
        assertEquals(listOf(instant("2027-01-08T09:00:00Z", "2027-01-08T12:00:00Z")), windows(prepared, pb))
        val evidence = prepared.evidence.getValue(pa)
        assertTrue(evidence.unresolvedTime)
        assertEquals(0, evidence.quarantineCount)
        assertEquals(unresolvedScope, evidence.conditions.single { it.conditionId == "unknown" }.scope)
    }

    @ParameterizedTest
    @ValueSource(strings = ["GAP_START", "GAP_END", "OVERLAP_START", "OVERLAP_END"])
    fun `X03 search boundary validity is checked independently from valid positive condition endpoints`(boundary: String) {
        val gap = boundary.startsWith("GAP")
        val startBoundary = boundary.endsWith("START")
        val zone = if (gap) "America/Sao_Paulo" else "America/Havana"
        val date =
            when {
                gap && startBoundary -> "2018-11-04"
                gap -> "2018-11-03"
                startBoundary -> "2026-11-01"
                else -> "2026-10-31"
            }
        // Noon endpoints are unambiguous; only the search start or end midnight is invalid.
        val valid = condition("noon", IrEffect.AVAILABLE, scope("${date}T12:00:00", "${date}T13:00:00"))
        val original = envelope(listOf(valid), listOf(valid))
        val input =
            original.copy(
                context =
                    original.context.copy(
                        timeZone = zone,
                        searchStart = LocalDate.parse(date),
                        searchEnd = LocalDate.parse(date).plusDays(1),
                    ),
            )
        val policy = IrMatchingPolicy("extras-synthetic-1", setOf(IrInterpretation.EXPLICIT), emptyList(), false)

        val prepared =
            try {
                ScheduleIrMatchInputPolicy.project(input, policy)
            } catch (_: IllegalArgumentException) {
                return
            }
        assertEquals(setOf(pa, pb), prepared.participants.map { it.participantId }.toSet())
        for (participant in listOf(pa, pb)) {
            assertTrue(windows(prepared, participant).isEmpty())
            assertTrue(prepared.evidence.getValue(participant).unresolvedTime)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["SAO_PAULO", "HAVANA"])
    fun `X04 ordinary search boundaries in the same zones remain calculable`(location: String) {
        val brazil = location == "SAO_PAULO"
        val date = if (brazil) "2018-11-05" else "2026-11-02"
        val zone = if (brazil) "America/Sao_Paulo" else "America/Havana"
        val valid = condition("noon", IrEffect.AVAILABLE, scope("${date}T12:00:00", "${date}T13:00:00"))
        val original = envelope(listOf(valid), listOf(valid))
        val input =
            original.copy(
                context =
                    original.context.copy(
                        timeZone = zone,
                        searchStart = LocalDate.parse(date),
                        searchEnd = LocalDate.parse(date).plusDays(1),
                    ),
            )
        val policy = IrMatchingPolicy("extras-synthetic-1", setOf(IrInterpretation.EXPLICIT), emptyList(), false)
        // Sao Paulo UTC-02 after its 2018 transition; Havana UTC-05 after its 2026 transition.
        val expected =
            if (brazil) {
                instant("2018-11-05T14:00:00Z", "2018-11-05T15:00:00Z")
            } else {
                instant("2026-11-02T17:00:00Z", "2026-11-02T18:00:00Z")
            }

        val prepared = ScheduleIrMatchInputPolicy.project(input, policy)

        for (participant in listOf(pa, pb)) {
            assertEquals(listOf(expected), windows(prepared, participant))
            assertFalse(prepared.evidence.getValue(participant).unresolvedTime)
        }
    }

    private fun condition(
        id: String,
        effect: IrEffect,
        scope: IrScope?,
    ): IrCondition =
        IrCondition(
            id,
            effect,
            IrInterpretation.EXPLICIT,
            scope,
            listOf(IrEvidenceSpan("original", 3, 0, raw.codePointCount(0, raw.length), hash(raw))),
            IrAssertion.ASSERTED,
            emptyList(),
            null,
            null,
        )

    private fun envelope(
        left: List<IrCondition>,
        right: List<IrCondition> = listOf(condition("available", IrEffect.AVAILABLE, friday)),
    ): ScheduleIrEnvelope {
        val source =
            IrSourceBinding(
                "original",
                IrSourceKind.ORIGINAL,
                3,
                raw.codePointCount(0, raw.length),
                hash(raw),
                Instant.parse("2018-01-01T00:00:00Z"),
            )
        val context =
            ScheduleIrContext(
                UUID(0, 1811),
                UUID(0, 1812),
                UUID(0, 1813),
                "Asia/Seoul",
                LocalDate.parse("2027-01-04"),
                LocalDate.parse("2027-01-11"),
                "extras-synthetic-1",
                listOf(
                    IrInputBinding(a, pa.value, 7, 3, hash("extras-input-a"), listOf(source)),
                    IrInputBinding(b, pb.value, 7, 3, hash("extras-input-b"), listOf(source)),
                ),
            )
        return ScheduleIrEnvelope(
            SCHEDULE_IR_VERSION,
            context,
            listOf(
                IrResult(a, IrAvailabilityState.KNOWN_WINDOWS, left, emptyList()),
                IrResult(b, IrAvailabilityState.KNOWN_WINDOWS, right, emptyList()),
            ),
        )
    }

    private fun windows(
        prepared: IrPreparedMatchingInputs,
        participant: ParticipantId,
    ): List<InstantTimeRange> = prepared.participants.single { it.participantId == participant }.availableTimes

    private fun scope(
        start: String,
        end: String,
    ): IrScope = IrScope(IrDimension.TIME, LocalDateTime.parse(start), LocalDateTime.parse(end))

    private fun instant(
        start: String,
        end: String,
    ): InstantTimeRange = InstantTimeRange(Instant.parse(start), Instant.parse(end))

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
