package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.adapter.output.integration.WeeklyEvidenceProviderContract
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.analysis.FrozenFinalAnalysisRoute
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralMatchingPolicy
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.coordination.domain.matching.OfflinePlaceAssessment
import com.meetme.server.coordination.domain.matching.RecommendationParticipantEvidence
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.ClosureReason
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.submission.application.port.output.ScheduleDslApprovedTermsRepository
import com.meetme.server.submission.application.port.output.ScheduleDslContextModeRepository
import com.meetme.server.submission.application.port.output.ScheduleDslRepository
import com.meetme.server.submission.application.port.output.ScheduleDslStructuralRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.Submission
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_PLACE_CONSTRAINT_VERSION
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.submission.domain.dsl.ServerEvidenceKind
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.time.APPROVED_KOREAN_TIME_TERM_VERSION
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Issue222 public native read contract. Synthetic declarations are not model-accuracy oracles. */
class ScheduleDslParticipantStatusReadTest {
    @BeforeEach
    fun readonlySnapshot() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ)
    }

    @AfterEach
    fun clearSnapshot() = TransactionSynchronizationManager.clear()

    @Test
    fun `NPS01 verified weekly native input keeps complete cohort exact disjoint windows and both mode keys`() {
        val f = Fixture()
        val before = f.mapper.writeValueAsString(f.native)
        val actual = f.read()
        assertEquals(setOf(MeetingMode.IN_PERSON, MeetingMode.REMOTE), actual.keys)
        actual.values.forEach { participants ->
            assertEquals(setOf(f.first, f.peer), participants.keys)
            participants.forEach { (owner, evidence) ->
                assertKnown(evidence)
                val input = assertNotNull(evidence.input)
                assertEquals(owner, input.participantId)
                assertEquals(f.expected, input.availableTimes)
            }
        }
        // The stored source stays UNKNOWN/NON_PRECISE: typed weekly derivation is authoritative.
        assertTrue(f.native.weeklyApplications.all { it.resolved && it.occurrences.size == 2 })
        assertTrue(
            f.native.base.base.declaration.claims.results
                .all { it.conditions.single().scope == null },
        )
        assertEquals(before, f.mapper.writeValueAsString(f.native))
        assertEquals(actual, f.read())
        f.assertReadOnlyNativeCalls()
    }

    @Test
    fun `NPS02 explicit remote restriction is a known unavailable in-person interval rather than an unknown input`() {
        val f = Fixture(restrictRemote = true)
        val actual = f.read()
        val offline = actual.getValue(MeetingMode.IN_PERSON).getValue(f.first)
        val remote = actual.getValue(MeetingMode.REMOTE).getValue(f.first)
        assertKnown(offline)
        assertKnown(remote)
        assertTrue(assertNotNull(offline.input).availableTimes.isEmpty())
        assertEquals(f.expected, assertNotNull(remote.input).availableTimes)
        actual.values.forEach { assertEquals(f.expected, assertNotNull(it.getValue(f.peer).input).availableTimes) }
        f.assertReadOnlyNativeCalls()
    }

    @Test
    fun `NPS03 an individual unverified travel constraint preserves known time and is irrelevant to remote mode`() {
        val f = Fixture(travel = true)
        val actual = f.read()
        for (mode in listOf(MeetingMode.IN_PERSON, MeetingMode.REMOTE)) {
            val own = actual.getValue(mode).getValue(f.first)
            val peer = actual.getValue(mode).getValue(f.peer)
            assertKnown(own)
            assertKnown(peer)
            assertEquals(f.expected, assertNotNull(own.input).availableTimes)
            assertEquals(f.expected, assertNotNull(peer.input).availableTimes)
            assertEquals(OfflinePlaceAssessment.UNRESTRICTED, assertNotNull(peer.input).offlinePlaceAssessment)
        }
        assertEquals(
            OfflinePlaceAssessment.UNVERIFIED,
            assertNotNull(actual.getValue(MeetingMode.IN_PERSON).getValue(f.first).input).offlinePlaceAssessment,
        )
        assertEquals(
            OfflinePlaceAssessment.UNRESTRICTED,
            assertNotNull(actual.getValue(MeetingMode.REMOTE).getValue(f.first).input).offlinePlaceAssessment,
        )
        f.assertReadOnlyNativeCalls()
    }

    @ParameterizedTest
    @ValueSource(strings = ["unknown", "program", "quarantine"])
    fun `NPS04 unresolved quarantined or unsupported own interpretation never marks a peer unknown`(problem: String) {
        val f = Fixture(problem = problem)
        val actual = f.read()
        actual.values.forEach { participants ->
            val own = participants.getValue(f.first)
            assertFalse(own.interpretationComplete)
            assertFalse(own.timeKnown)
            val peer = participants.getValue(f.peer)
            assertKnown(peer)
            assertEquals(f.expected, assertNotNull(peer.input).availableTimes)
        }
        f.assertReadOnlyNativeCalls()
    }

    @Test
    fun `NPS05 a resolved empty weekly filter remains known and is not confused with unresolved personal time`() {
        val f = Fixture(problem = "known-empty")
        assertTrue(
            f.native.weeklyApplications
                .first()
                .resolved,
        )
        assertTrue(
            f.native.weeklyApplications
                .first()
                .occurrences
                .isEmpty(),
        )
        val actual = f.read()
        actual.values.forEach { participants ->
            val own = participants.getValue(f.first)
            assertKnown(own)
            assertTrue(assertNotNull(own.input).availableTimes.isEmpty())
            assertKnown(participants.getValue(f.peer))
        }
        f.assertReadOnlyNativeCalls()
    }

    @ParameterizedTest
    @ValueSource(strings = ["IN_PERSON", "REMOTE"])
    fun `NPS06 a single-mode room has exactly its own mode and never an invented either projection`(name: String) {
        val mode = MeetingMode.valueOf(name)
        val f = Fixture(mode = mode)
        assertEquals(setOf(mode), f.read().keys)
        f.assertReadOnlyNativeCalls()
    }

    @ParameterizedTest
    @ValueSource(strings = ["version", "selection", "source", "mode", "missing-native", "bad-native"])
    fun `NPS07 changed pinned version selection source or invalid native document fails without flat fallback`(defect: String) {
        val f = Fixture()
        var version = f.run.version
        var fingerprint = f.route.fingerprint()
        when (defect) {
            "version" -> version--
            "selection" -> fingerprint = "0".repeat(64)
            "source" -> {
                val changed =
                    f.sources.first().revise(
                        SubmissionVersionId(UUID(0, 999)),
                        "금요일 20:00~22:00 가능.",
                        emptyList(),
                        Locale.KOREAN,
                        f.recordedAt,
                    )
                // Same requested immutable identity with changed bytes is an integrity failure.
                val forged =
                    Submission.restore(
                        changed.id,
                        changed.roomId,
                        changed.participantId,
                        changed.latest.copy(
                            id =
                                f.sources
                                    .first()
                                    .latest.id,
                        ),
                    )
                `when`(f.submissions.findLatestByRoom(f.room.id)).thenReturn(listOf(forged, f.sources.last()))
            }
            "mode" ->
                `when`(f.rooms.findById(f.room.id)).thenReturn(
                    MeetingRoom.restore(
                        f.room.id,
                        f.room.inviteCode,
                        f.room.purpose,
                        MeetingMode.REMOTE,
                        f.room.timeZone,
                        f.room.searchRange,
                        f.room.closurePolicy,
                        f.room.collectionStatus,
                        f.room.closureReason,
                        f.room.closedAt,
                        f.room.createdAt,
                        f.room.version,
                        f.room.activeRunId,
                    ),
                )
            "missing-native" -> `when`(f.structural.find(f.snapshot.context)).thenReturn(null)
            "bad-native" ->
                `when`(f.structural.find(f.snapshot.context)).thenReturn(
                    f.native.copy(locatedEvidence = f.native.locatedEvidence.drop(1)),
                )
        }
        assertFailsWith<RuntimeException> { f.read(version, fingerprint) }
        f.assertReadOnlyNativeCalls()
    }

    @ParameterizedTest
    @ValueSource(strings = ["database", "codec"])
    fun `NPS08 selected native repository errors propagate rather than becoming successful unknown statuses`(origin: String) {
        val f = Fixture()
        val failure =
            if (origin == "database") {
                IllegalStateException("synthetic database unavailable")
            } else {
                IllegalArgumentException("synthetic invalid native codec")
            }
        `when`(f.structural.find(f.snapshot.context)).thenThrow(failure)
        assertSame(failure, assertFailsWith<RuntimeException> { f.read() })
        f.assertReadOnlyNativeCalls()
    }

    @ParameterizedTest
    @ValueSource(strings = ["inactive", "write", "read-committed"])
    fun `NPS09 participant evidence requires the same readonly repeatable snapshot as other frozen result readers`(defect: String) {
        val f = Fixture()
        when (defect) {
            "inactive" -> TransactionSynchronizationManager.setActualTransactionActive(false)
            "write" -> TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
            "read-committed" -> TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED)
        }
        assertFailsWith<IllegalStateException> { f.read() }
        f.assertReadOnlyNativeCalls()
    }

    @Test
    fun `NPS10 native public reader declares readonly repeatable read rather than a weaker transaction`() {
        val method = ScheduleDslStoredReadService::class.java.methods.single { it.name.startsWith("readParticipantStatusInputs") }
        val transaction = assertNotNull(method.getAnnotation(Transactional::class.java))
        assertTrue(transaction.readOnly)
        assertEquals(Isolation.REPEATABLE_READ, transaction.isolation)
    }

    @Test
    fun `NPS11 native exclusion inside weekly availability keeps the precise gap instead of a flattened envelope`() {
        val f = Fixture(exclusion = true)
        val firstDate =
            listOf(
                InstantTimeRange(Instant.parse("2055-12-31T10:00:00Z"), Instant.parse("2055-12-31T10:30:00Z")),
                InstantTimeRange(Instant.parse("2055-12-31T11:00:00Z"), Instant.parse("2055-12-31T12:00:00Z")),
            )
        f.read().values.forEach { participants ->
            assertKnown(participants.getValue(f.first))
            assertEquals(firstDate + f.expected.last(), assertNotNull(participants.getValue(f.first).input).availableTimes)
            assertEquals(f.expected, assertNotNull(participants.getValue(f.peer).input).availableTimes)
        }
        f.assertReadOnlyNativeCalls()
    }

    private fun assertKnown(evidence: RecommendationParticipantEvidence) {
        assertTrue(evidence.interpretationComplete)
        assertTrue(evidence.timeKnown)
    }

    private class Fixture(
        val mode: MeetingMode = MeetingMode.EITHER,
        val restrictRemote: Boolean = false,
        val travel: Boolean = false,
        val exclusion: Boolean = false,
        val problem: String? = null,
    ) {
        val mapper = JsonMapper.builder().findAndAddModules().build()
        val first = ParticipantId(UUID(0, 11))
        val peer = ParticipantId(UUID(0, 12))
        val recordedAt: Instant = Instant.parse("2055-12-28T00:00:00Z")
        private val fixedAt = recordedAt.plusSeconds(60)
        private val policy = ScheduleDslStructuralMatchingPolicy.union()
        val room =
            MeetingRoom
                .create(
                    MeetingRoomId(UUID(0, 1)),
                    InviteCode.fromEntropy(ByteArray(16)),
                    "Synthetic native status",
                    mode,
                    MeetingTimeZone.of("Asia/Seoul"),
                    SearchDateRange.explicit(LocalDate.parse("2055-12-30"), LocalDate.parse("2056-01-09")),
                    ClosurePolicy.of(manualOnly = true),
                    recordedAt.minusSeconds(60),
                ).close(ClosureReason.MANUAL, fixedAt, 2)
                .transition(activeRunId = UUID(0, 2))
        private val ownRaw =
            when (problem) {
                "unknown" -> "금요일 공연이 끝나면 가능."
                "known-empty" -> "11월 금요일 19:00~21:00 가능."
                else ->
                    "금요일 19:00~21:00 가능." +
                        if (restrictRemote) {
                            " 비대면만 가능."
                        } else if (travel) {
                            "\n광명역에서 35분 이내인 곳."
                        } else if (exclusion) {
                            " 2055-12-31 19:30~20:00 불가."
                        } else {
                            ""
                        }
            }
        val sources =
            listOf(first, peer).mapIndexed { i, participant ->
                Submission.start(
                    SubmissionId(UUID(0, 20L + i)),
                    room.id,
                    participant,
                    SubmissionVersionId(UUID(0, 30L + i)),
                    if (i == 0) ownRaw else "금요일 19:00~21:00 가능.",
                    emptyList(),
                    Locale.KOREAN,
                    recordedAt,
                    InputEvidence(1, recordedAt),
                )
            }
        val run =
            CoordinationRun
                .queued(
                    CoordinationRunId(UUID(0, 2)),
                    SubmissionBatch(SubmissionBatchId(UUID(0, 3)), room.id, sources.map { it.latest.id }, fixedAt),
                ).startMatching()
                .complete(CandidateQuality.COMPLETE, emptyList())
        val snapshot =
            ScheduleEvidenceSnapshotFactory().create(
                run,
                sources,
                room.timeZone.value,
                room.searchRange.startInclusive,
                room.searchRange.endExclusive,
                policy.version,
            )
        private val catalog = ServerEvidenceCatalog.create(snapshot)
        val native: ScheduleDslStructuralEnvelope =
            WeeklyEvidenceProviderContract(mapper, allowPlaceConstraints = true)
                .parseProviderResponse(
                    mapper.writeValueAsString(document()),
                    snapshot,
                    SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
                    SCHEDULE_AST_VERSION,
                    APPROVED_KOREAN_TIME_TERM_VERSION,
                ).also { value ->
                    assertEquals(value, ScheduleDslStructuralValidator().validateRestored(value, snapshot))
                    if (exclusion) {
                        val declaration = value.base.base.declaration
                        val ownClaims =
                            declaration.claims.results.single {
                                it.submissionVersionId ==
                                    sources
                                        .first()
                                        .latest.id.value
                            }
                        assertTrue(ownClaims.quarantined.isEmpty())
                        assertEquals(setOf("weekly", "gap"), ownClaims.conditions.map { it.id }.toSet())
                        val gap = ownClaims.conditions.single { it.id == "gap" }
                        assertEquals(IrEffect.UNAVAILABLE, gap.effect)
                        assertEquals("2055-12-31T19:30", assertNotNull(gap.scope).start.toString())
                        assertEquals("2055-12-31T20:00", assertNotNull(gap.scope).end.toString())
                        val ownProgram =
                            declaration.programs.single {
                                it.submissionVersionId ==
                                    sources
                                        .first()
                                        .latest.id.value
                            }
                        assertTrue(ownProgram.problems.isEmpty())
                        val program = assertNotNull(ownProgram.program)
                        assertEquals("gap", program.nodes.single { it.id == program.exclusionRoot }.claimId)
                        // Prove this synthetic graph has the intended gap before invoking the new TODO reader.
                        val independentExpected =
                            listOf(
                                InstantTimeRange(Instant.parse("2055-12-31T10:00:00Z"), Instant.parse("2055-12-31T10:30:00Z")),
                                InstantTimeRange(Instant.parse("2055-12-31T11:00:00Z"), Instant.parse("2055-12-31T12:00:00Z")),
                                InstantTimeRange(Instant.parse("2056-01-07T10:00:00Z"), Instant.parse("2056-01-07T12:00:00Z")),
                            )
                        ScheduleDslStructuralCalculator.prepare(value, snapshot, policy, mode).preparedByMode.values.forEach { prepared ->
                            assertEquals(independentExpected, prepared.participants.single { it.participantId == first }.availableTimes)
                        }
                    }
                    when (problem) {
                        "unknown" -> assertFalse(value.weeklyApplications.first().resolved)
                        "program" ->
                            assertTrue(
                                value.base.base.declaration.programs
                                    .first()
                                    .problems
                                    .isNotEmpty(),
                            )
                        "quarantine" ->
                            assertTrue(
                                value.base.base.declaration.claims.results
                                    .first()
                                    .quarantined
                                    .isNotEmpty(),
                            )
                    }
                }
        val route =
            FrozenFinalAnalysisRoute(
                run.id.value,
                run.batch.id.value,
                room.id.value,
                FinalAnalysisRouteChoice.DslStructuralV5(
                    IrExecutionIdentity(SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT, SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION, policy.version),
                    policy,
                    snapshot.context,
                    mode,
                    APPROVED_KOREAN_TIME_TERM_VERSION,
                ),
                fixedAt.plusSeconds(1),
            )
        val expected =
            listOf("2055-12-31", "2056-01-07").map { date ->
                InstantTimeRange(Instant.parse("${date}T10:00:00Z"), Instant.parse("${date}T12:00:00Z"))
            }
        private val runs = mock(CoordinationRunRepository::class.java)
        val rooms = mock(MeetingRoomRepository::class.java)
        val submissions = mock(SubmissionRepository::class.java)
        private val routes = mock(FinalAnalysisRouteRegistry::class.java)
        private val flat = mock(ScheduleDslRepository::class.java)
        private val terms = mock(ScheduleDslApprovedTermsRepository::class.java)
        private val contextual = mock(ScheduleDslContextModeRepository::class.java)
        val structural = mock(ScheduleDslStructuralRepository::class.java)
        private val reader = spy(ScheduleDslStoredReadService(runs, rooms, submissions, routes, flat, terms, contextual, structural))

        init {
            `when`(runs.findById(run.id)).thenReturn(run)
            `when`(rooms.findById(room.id)).thenReturn(room)
            `when`(submissions.findLatestByRoom(room.id)).thenReturn(sources)
            `when`(routes.find(run.id.value)).thenReturn(route)
            `when`(structural.find(snapshot.context)).thenReturn(native)
            doThrow(AssertionError("participant status must not regenerate recommendations"))
                .`when`(reader)
                .readStored(run.id, run.version, route.fingerprint())
        }

        fun read(
            version: Long = run.version,
            fingerprint: String = route.fingerprint(),
        ) = reader.readParticipantStatusInputs(run.id, version, fingerprint)

        fun assertReadOnlyNativeCalls() {
            listOf(
                flat,
                terms,
                contextual,
            ).forEach { assertTrue(mockingDetails(it).invocations.isEmpty(), "No flat or older native fallback") }
            for (port in listOf(runs, rooms, submissions, routes, structural)) {
                assertTrue(mockingDetails(port).invocations.all { it.method.name.startsWith("find") }, "GET may only read ports")
            }
            assertTrue(mockingDetails(reader).invocations.none { it.method.name.startsWith("readStored") })
        }

        private fun selection(
            i: Int,
            text: String,
            kind: ServerEvidenceKind = ServerEvidenceKind.CONTEXT,
        ): Int =
            catalog.entries
                .indexOfFirst { it.inputId == sources[i].latest.id.value && it.text == text && it.kind == kind }
                .also { require(it >= 0) { "Missing synthetic catalogue evidence: $text" } }

        private fun document(): Map<String, Any?> =
            mapOf<String, Any?>(
                "semantic_version" to SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
                "ast_version" to SCHEDULE_AST_VERSION,
                "vocabulary_version" to APPROVED_KOREAN_TIME_TERM_VERSION,
                "evidence_catalog_version" to "server-evidence-catalog-1",
                "date_scope_version" to "meeting-date-scope-1",
                "calendar_facts_version" to "server-calendar-facts-2",
                "evidence_selection_version" to "server-evidence-selection-1",
                "weekly_rules_version" to "weekly-schedule-rule-1",
                "place_constraints_version" to SCHEDULE_PLACE_CONSTRAINT_VERSION,
                "results" to sources.indices.map(::result),
            )

        private fun result(i: Int): Map<String, Any?> {
            val proof = selection(i, assertNotNull(sources[i].latest.rawText))
            val isUnknown = i == 0 && problem == "unknown"
            val isEmpty = i == 0 && problem == "known-empty"
            val condition =
                mapOf<String, Any?>(
                    "id" to "weekly",
                    "effect" to if (i == 0 && problem == "program") "UNAVAILABLE" else "AVAILABLE",
                    "interpretation" to "EXPLICIT",
                    "temporal_resolution" to "NON_PRECISE",
                    "evidence" to listOf(proof),
                    "assertion" to "ASSERTED",
                    "supersedes" to emptyList<String>(),
                    "temporal" to
                        mapOf<String, Any?>(
                            "kind" to "WEEKLY",
                            "weekdays" to listOf("FRIDAY"),
                            "weekday_evidence" to listOf(proof),
                            "anchor_filter" to
                                mapOf<String, Any?>(
                                    "months" to if (isEmpty) listOf("NOVEMBER") else emptyList<String>(),
                                    "from_inclusive" to null,
                                    "until_exclusive" to null,
                                    "excluded_dates" to emptyList<String>(),
                                    "evidence" to if (isEmpty) listOf(proof) else emptyList<Int>(),
                                ),
                            "time" to
                                mapOf<String, Any?>(
                                    "kind" to if (isUnknown) "UNKNOWN" else "BOUNDS",
                                    "lower" to if (isUnknown) null else "19:00",
                                    "upper" to if (isUnknown) null else "21:00",
                                    "end_day_offset" to 0,
                                    "clock_evidence" to
                                        if (isUnknown) {
                                            emptyList()
                                        } else {
                                            listOf(
                                                selection(i, "19:00", ServerEvidenceKind.CLOCK),
                                                selection(i, "21:00", ServerEvidenceKind.CLOCK),
                                            )
                                        },
                                    "term" to null,
                                    "term_evidence" to null,
                                ),
                        ),
                )
            val conditions =
                listOf(condition) +
                    if (i == 0 && exclusion) {
                        listOf(
                            mapOf<String, Any?>(
                                "id" to "gap",
                                "effect" to "UNAVAILABLE",
                                "interpretation" to "EXPLICIT",
                                "temporal_resolution" to "PRECISE_INTERVAL",
                                "evidence" to listOf(proof),
                                "assertion" to "ASSERTED",
                                "supersedes" to emptyList<String>(),
                                "temporal" to
                                    mapOf<String, Any?>(
                                        "kind" to "RAW_SCOPE",
                                        "scope" to
                                            mapOf<String, Any?>(
                                                "dimension" to "TIME",
                                                "start" to "2055-12-31T19:30",
                                                "end" to "2055-12-31T20:00",
                                            ),
                                    ),
                            ),
                        )
                    } else if (i == 0 && problem == "quarantine") {
                        listOf(
                            mapOf<String, Any?>(
                                "id" to "malformed",
                                "effect" to "AVAILABLE",
                                "interpretation" to "EXPLICIT",
                                "temporal_resolution" to "PRECISE_INTERVAL",
                                "evidence" to listOf(proof),
                                "assertion" to "ASSERTED",
                                "supersedes" to emptyList<String>(),
                                "temporal" to
                                    mapOf<String, Any?>(
                                        "kind" to "RAW_SCOPE",
                                        "scope" to
                                            mapOf<String, Any?>("dimension" to "TIME", "start" to "not-a-date", "end" to "not-a-date"),
                                    ),
                            ),
                        )
                    } else {
                        emptyList()
                    }
            val restriction =
                if (i == 0 && restrictRemote) {
                    listOf(
                        mapOf<String, Any?>(
                            "id" to "remote-only",
                            "scope" to
                                mapOf<String, Any?>("dimension" to "TIME", "start" to "2055-12-30T00:00", "end" to "2056-01-09T00:00"),
                            "permitted_modes" to listOf("REMOTE"),
                            "mode_interpretation" to "EXPLICIT",
                            "time_interpretation" to "EXPLICIT",
                            "temporal_resolution" to "PRECISE_INTERVAL",
                            "evidence" to listOf(proof),
                            "assertion" to "ASSERTED",
                            "supersedes" to emptyList<String>(),
                            "alternative_group" to null,
                            "alternative" to null,
                        ),
                    )
                } else {
                    emptyList()
                }
            val place =
                if (i == 0 && travel) {
                    listOf(
                        mapOf<String, Any?>(
                            "id" to "travel",
                            "kind" to "TRAVEL_TIME_LIMIT",
                            "reference_kind" to "PUBLIC_NAMED_PLACE",
                            "place_evidence" to selection(i, "광명역", ServerEvidenceKind.PLACE),
                            "evidence" to selection(i, "광명역에서 35분 이내인 곳."),
                            "max_minutes" to 35,
                            "travel_mode" to null,
                            "time_scope" to "ALL_AVAILABLE_TIMES",
                        ),
                    )
                } else {
                    emptyList()
                }
            return mapOf<String, Any?>(
                "input_ref" to
                    sources[i]
                        .latest.id.value
                        .toString(),
                "conditions" to conditions,
                "program" to
                    mapOf<String, Any?>(
                        "nodes" to emptyList<Any>(),
                        "availability_root" to
                            mapOf<String, Any?>(
                                "operator" to "CLAIM_REF",
                                "claim_id" to "weekly",
                                "children" to emptyList<Any>(),
                                "interpretation" to "EXPLICIT",
                                "assertion" to "ASSERTED",
                                "evidence" to listOf(proof),
                            ),
                        "exclusion_root" to
                            if (i == 0 && exclusion) {
                                mapOf<String, Any?>(
                                    "operator" to "CLAIM_REF",
                                    "claim_id" to "gap",
                                    "children" to emptyList<Any>(),
                                    "interpretation" to "EXPLICIT",
                                    "assertion" to "ASSERTED",
                                    "evidence" to listOf(proof),
                                )
                            } else {
                                null
                            },
                        "preference_root" to null,
                        "history_condition_ids" to emptyList<String>(),
                    ),
                "approved_terms" to emptyList<Any>(),
                "directional_times" to emptyList<Any>(),
                "mode_restrictions" to restriction,
                "place_constraints" to place,
            )
        }
    }
}
