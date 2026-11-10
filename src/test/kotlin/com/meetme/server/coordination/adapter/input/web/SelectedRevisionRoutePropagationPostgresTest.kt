package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflinePipeline
import com.meetme.server.coordination.adapter.output.integration.SelectedApprovedTermsProviderPostgresTest
import com.meetme.server.coordination.application.port.input.AnalyzeRevisionCommand
import com.meetme.server.coordination.application.port.input.InputRevisionUseCase
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse
import com.meetme.server.coordination.application.service.SelectedScheduleDslMatchingPostgresTest
import com.meetme.server.coordination.application.service.SelectedScheduleDslProcessingPersistenceService
import com.meetme.server.coordination.application.service.SelectedScheduleDslPublicationPostgresTest
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.shared.domain.time.DatedTimeRange
import com.meetme.server.shared.domain.time.LocalTimeRange
import com.meetme.server.shared.testsupport.AccountPrincipalTestFixture
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.ManualAvailability
import com.meetme.server.support.SelectedDslPhysicalInputFixture
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F

/** Three real PG/application actors. Existing business account fixture, not JWT/precheck/provider accuracy proof. */
@Import(
    SelectedApprovedTermsProviderPostgresTest.ApprovedPublicationConfig::class,
    SelectedScheduleDslMatchingPostgresTest.Configuration::class,
)
class SelectedRevisionRoutePropagationPostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var publication: SelectedScheduleDslProcessingPersistenceService

    @Autowired private lateinit var revision: InputRevisionUseCase

    @Autowired private lateinit var credentials: GuestCredentialPort

    @Autowired private lateinit var clock: SelectedScheduleDslPublicationPostgresTest.PublicationClock
    private lateinit var fixture: CompletedCorrectionFixture
    private lateinit var sourceChoice: FinalAnalysisRouteChoice.DslApprovedTermsV3
    private lateinit var sourceEnvelopes: List<String>
    private lateinit var sourceInputs: List<String>

    @BeforeEach
    fun originalSelectedSourceUsingKnownAcceptedFixtureAndPublication() {
        clock.set(F.selectedAt)
        val input = SelectedDslPhysicalInputFixture.approvedInput()
        SelectedDslPhysicalInputFixture.seed(jdbc, mapper, input)
        val hostCookie = accounts.newAccount("host")
        val hostAccount = UUID.fromString(hostCookie.value.removePrefix(AccountPrincipalTestFixture.PREFIX))
        jdbc.update("UPDATE participants SET guest_session_id=NULL,user_id=? WHERE id=?", hostAccount, F.ir.firstParticipant)
        jdbc.update("UPDATE guest_browser_sessions SET credential_digest=? WHERE id=?", credentials.digest(MEMBER), UUID(0, 502))
        fixture =
            CompletedCorrectionFixture(
                CorrectionSession(INVITE, hostCookie.value),
                CorrectionSession(INVITE, MEMBER),
                F.ir.room,
                F.ir.run,
            )
        sourceChoice =
            FinalAnalysisRouteChoice.DslApprovedTermsV3(
                IrExecutionIdentity("schedule-dsl-response-3", "schedule-ir-product-4", input.policy.version),
                input.policy,
                ScheduleDslOfflinePipeline.snapshotApprovedTerms(input).snapshot.context,
                input.meetingMode,
                "ko-time-terms-1",
            )
        routes.freeze(F.ir.run, 1, sourceChoice)
        val started = assertNotNull(publication.start(SubmissionBatchId(F.ir.batch)))
        val admitted = assertNotNull(publication.admit(started, UUID(0, 9971), Duration.ofSeconds(60)))
        assertTrue(publication.completeDsl(admitted.binding, admitted.attempt, response3()) { true })
        // Existing CorrectionRound fixture's synthetic partial-completion boundary makes source reopenable.
        completePartial(F.ir.run)
        sourceEnvelopes = F.rows(jdbc, "schedule_ir_envelopes")
        sourceInputs = F.rows(jdbc, "schedule_ir_inputs")
    }

    @Test
    fun `SRP01 changed accepted source keeps C3 descriptor with fresh successor context and immutable old IR`() {
        val round = openRound(fixture)
        val original = assertNotNull(submissions.findByParticipant(ParticipantId(F.ir.firstParticipant)))
        clock.set(F.selectedAt.plusSeconds(1))
        val changedId = SubmissionVersionId(UUID(0, 9972))
        val changed =
            original.revise(
                changedId,
                requireNotNull(original.latest.rawText) + " Synthetic corrected source.",
                emptyList(),
                Locale.KOREAN,
                clock.instant(),
                InputEvidence(
                    4,
                    clock.instant(),
                    interpretation = InputInterpretation.UNRESOLVED,
                    unresolvedReason = SelectedDslPhysicalInputFixture.REVIEW_REASON,
                ),
            )
        submissions.save(changed)
        mvc.perform(analyze(fixture, round)).andExpect(status().isAccepted)
        val nextId = activeRun(fixture.host)
        val next = successor(nextId)
        assertDescriptor(next)
        val currentIds = submissions.findLatestByRoom(MeetingRoomId(F.ir.room)).map { it.latest.id.value }.toSet()
        assertEquals(
            currentIds,
            next.context.inputs
                .map { it.submissionVersionId }
                .toSet(),
        )
        assertTrue(changedId.value in currentIds)
        assertTrue(F.ir.first !in currentIds)
        val oldBinding = sourceChoice.context.inputs.single { it.participantId == F.ir.firstParticipant }
        val newBinding = next.context.inputs.single { it.participantId == F.ir.firstParticipant }
        assertNotEquals(oldBinding.contentSha256, newBinding.contentSha256)
        assertEquals(sourceChoice, assertNotNull(routes.find(F.ir.run)).choice)
        assertNoOldIrReuse(nextId)
    }

    @Test
    fun `SRP02 force reparse keeps native bindings but creates independent selected run batch and no copied IR`() {
        val round = openRound(fixture)
        val ids = versionIds()
        val request = UUID.randomUUID()
        mvc.perform(analyze(fixture, round, request, force = true)).andExpect(status().isAccepted)
        val nextId = activeRun(fixture.host)
        val next = successor(nextId)
        assertDescriptor(next)
        assertEquals(sourceChoice.context.inputs, next.context.inputs)
        assertEquals(ids, versionIds())
        assertNotEquals(sourceChoice.context, next.context)
        assertNoOldIrReuse(nextId)
        val after = workCounts()
        mvc.perform(analyze(fixture, round, request, force = true)).andExpect(status().isAccepted)
        assertEquals(after, workCounts())
        assertEquals(nextId, activeRun(fixture.host))
    }

    @Test
    fun `SRP03 unchanged reuse preserves original selection then unsupported successor rolls back the whole revision`() {
        val originalRoute = assertNotNull(routes.find(F.ir.run))
        val firstRound = openRound(fixture)
        val beforeReuse = workCounts()
        mvc.perform(analyze(fixture, firstRound)).andExpect(status().isOk)
        assertEquals(F.ir.run, activeRun(fixture.host))
        assertEquals(beforeReuse, workCounts())
        assertEquals(originalRoute, routes.find(F.ir.run))
        assertEquals(sourceEnvelopes, F.rows(jdbc, "schedule_ir_envelopes"))
        val secondRound = openRound(fixture, generation = 1)
        val original = assertNotNull(submissions.findByParticipant(ParticipantId(F.ir.firstParticipant)))
        clock.set(F.selectedAt.plusSeconds(1))
        val manual =
            original.revise(
                SubmissionVersionId(UUID(0, 9973)),
                null,
                listOf(
                    ManualAvailability.Dated(
                        DatedTimeRange(LocalDate.of(2027, 1, 8), LocalTimeRange.of(LocalTime.of(18, 0), LocalTime.of(22, 0))),
                    ),
                ),
                Locale.KOREAN,
                clock.instant(),
            )
        submissions.save(manual)
        val beforeReject = revisionRows()
        assertFailsWith<IllegalArgumentException> {
            asHost {
                revision.analyze(AnalyzeRevisionCommand(INVITE, null, secondRound, UUID.randomUUID(), forceReparse = true))
            }
        }
        assertEquals(beforeReject, revisionRows())
        assertEquals(F.ir.run, activeRun(fixture.host))
        assertEquals(originalRoute, routes.find(F.ir.run))
        assertEquals(sourceEnvelopes, F.rows(jdbc, "schedule_ir_envelopes"))
        assertEquals(sourceInputs, F.rows(jdbc, "schedule_ir_inputs"))
    }

    private fun successor(nextId: UUID): FinalAnalysisRouteChoice.DslApprovedTermsV3 {
        assertNotEquals(F.ir.run, nextId)
        val nextRun = assertNotNull(runs.findById(CoordinationRunId(nextId)))
        val route = assertNotNull(routes.find(nextId), "A selected successor cannot silently become Legacy")
        val choice = route.choice
        assertTrue(choice is FinalAnalysisRouteChoice.DslApprovedTermsV3)
        assertEquals(nextId, choice.context.runId)
        assertEquals(nextRun.batch.id.value, choice.context.batchId)
        assertEquals(F.ir.room, choice.context.roomId)
        assertNotEquals(F.ir.batch, choice.context.batchId)
        assertEquals(nextId, jdbc.queryForObject("SELECT active_run_id FROM meeting_rooms WHERE id=?", UUID::class.java, F.ir.room))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=?", Int::class.java, nextId))
        return choice
    }

    private fun assertDescriptor(next: FinalAnalysisRouteChoice.DslApprovedTermsV3) {
        assertEquals(sourceChoice.identity, next.identity)
        assertEquals(sourceChoice.policy, next.policy)
        assertEquals(sourceChoice.vocabularyVersion, next.vocabularyVersion)
        assertEquals(sourceChoice.meetingMode, next.meetingMode)
    }

    private fun assertNoOldIrReuse(nextId: UUID) {
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM schedule_ir_envelopes WHERE run_id=?", Int::class.java, nextId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM schedule_ir_inputs WHERE run_id=?", Int::class.java, nextId))
        assertEquals(sourceEnvelopes, F.rows(jdbc, "schedule_ir_envelopes"))
        assertEquals(sourceInputs, F.rows(jdbc, "schedule_ir_inputs"))
    }

    private fun revisionRows() =
        listOf(
            "submission_batches",
            "submission_batch_items",
            "coordination_runs",
            "final_analysis_routes",
            "outbox_events",
            "input_revision_rounds",
            "meeting_rooms",
            "schedule_ir_envelopes",
            "schedule_ir_inputs",
        ).associateWith { F.rows(jdbc, it) }

    private fun <T> asHost(action: () -> T): T {
        val previous = RequestContextHolder.getRequestAttributes()
        val request = MockHttpServletRequest().apply { setCookies(fixture.host.cookie()) }
        try {
            RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
            return action()
        } finally {
            RequestContextHolder.resetRequestAttributes()
            previous?.let { RequestContextHolder.setRequestAttributes(it) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun response3(): ScheduleDslProviderResponse {
        val root = mapper.readValue(F.q.wire.json(), MutableMap::class.java) as MutableMap<String, Any?>
        root["semantic_version"] = "schedule-ir-product-4"
        root["vocabulary_version"] = "ko-time-terms-1"
        (root["results"] as List<MutableMap<String, Any?>>).forEach { it["approved_terms"] = emptyList<Any>() }
        return ScheduleDslProviderResponse(
            mapper.writeValueAsString(root),
            ParserUsage(17, 23, 100),
            AnalysisProvider.GEMINI,
            "gemini-3.8-flash",
        )
    }

    companion object {
        private const val INVITE = "route-synthetic-000001"
        private const val MEMBER = "synthetic-selected-revision-member"
        private val postgres = mappedPortReadyPostgres().apply { start() }

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registerCorrectionDatabase(registry, postgres)
            registry.add("meetme.openai.api-key") { "" }
        }

        @JvmStatic @AfterAll
        fun stop() {
            postgres.stop()
        }
    }
}
