package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.input.OperatorReanalysisReceipt
import com.meetme.server.coordination.application.port.input.OperatorReanalysisRequest
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.OperatorReanalysisCommandPort
import com.meetme.server.coordination.application.port.output.OperatorReanalysisCommandRepository
import com.meetme.server.coordination.application.service.FinalAnalysisRouteSelectionService
import com.meetme.server.coordination.application.service.FrozenSubmissionReader
import com.meetme.server.coordination.application.service.SelectedScheduleDslProcessingPersistenceService
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.coordination.domain.analysis.FinalAnalysisContractSelection
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.application.port.output.RoomDataRetentionPort
import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.ClosureReason
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.shared.application.port.input.operations.OperatorQueryException
import com.meetme.server.shared.application.port.output.RateLimitDecision
import com.meetme.server.shared.application.port.output.RateLimitPort
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.shared.domain.InputSupplement
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F
import com.meetme.server.support.ScopedTermEvidenceTestFixture as S

/** ORP: independent Issue236 accepted behavior, synthetic PostgreSQL rows, no paid provider calls. */
@SpringBootTest
@Import(OperatorReanalysisPostgresTest.Configuration::class)
class OperatorReanalysisPostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var commands: OperatorReanalysisCommandPort

    @Autowired private lateinit var records: OperatorReanalysisCommandRepository

    @Autowired private lateinit var rooms: MeetingRoomRepository

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var transactions: PlatformTransactionManager

    @Autowired private lateinit var retention: RoomDataRetentionPort

    @Autowired private lateinit var publication: SelectedScheduleDslProcessingPersistenceService

    @Autowired private lateinit var clock: TestClock

    @Autowired private lateinit var rate: TestRateLimit

    private val roomId get() = F.ir.room
    private val sourceId get() = F.ir.run
    private val input get() = S.input(RAW)

    @BeforeEach
    fun seedExactFrozenSourceAndNonzeroAccounting() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users,operations_access_audits,outbox_events CASCADE")
        clock.at = NOW
        rate.reset()
        F.seed(jdbc, mapper, input)
        TransactionTemplate(transactions).executeWithoutResult {
            val source = assertNotNull(runs.findById(CoordinationRunId(sourceId)))
            oldSelector().freeze(
                source,
                assertNotNull(rooms.findById(MeetingRoomId(roomId))),
                FrozenSubmissionReader.read(submissions, source.batch),
                FinalAnalysisContractSelection.DSL_STRUCTURAL_V5,
            )
        }
        jdbc.update("UPDATE coordination_runs SET status='COMPLETED',candidate_quality='COMPLETE',version=2 WHERE id=?", sourceId)
        jdbc.update("UPDATE meeting_rooms SET version=8,revision_generation=2,correction_analysis_count=3 WHERE id=?", roomId)
        jdbc.update(
            "INSERT INTO candidates(id,coordination_run_id,rank,meeting_mode,attendance_count,total_participants) VALUES (?,?,1,'REMOTE',2,2)",
            OLD_CANDIDATE,
            sourceId,
        )
        jdbc.update(
            "INSERT INTO candidate_time_ranges(candidate_id,range_order,start_at,end_at) VALUES (?,0,?,?)",
            OLD_CANDIDATE,
            Timestamp.from(NOW.plusSeconds(3600)),
            Timestamp.from(NOW.plusSeconds(7200)),
        )
        assertEquals(1, count("ai_room_budgets"), "Room insertion must retain the automatic room budget row")
        assertEquals(
            1,
            jdbc.update("UPDATE ai_room_budgets SET state='FROZEN',settled=37,held=19,version=4 WHERE room_id=?", roomId),
        )
        val accounting = jdbc.queryForMap("SELECT state,settled,held,version FROM ai_room_budgets WHERE room_id=?", roomId)
        assertEquals("FROZEN", accounting["state"])
        assertEquals(37L, (accounting.getValue("settled") as Number).toLong())
        assertEquals(19L, (accounting.getValue("held") as Number).toLong())
        assertEquals(4L, (accounting.getValue("version") as Number).toLong())
    }

    @Test
    @DisplayName("ORP01 current contract creates one active new execution while preserving original source result and accounting")
    fun createsActiveCurrentContractWithHistoryPreserved() {
        val protected = protectedRows()
        val sourceRoom = assertNotNull(rooms.findById(MeetingRoomId(roomId)))
        val sourceRoute = assertNotNull(routes.find(sourceId))
        val request = request()

        val receipt = commands.enqueue(ACTOR, roomId, request)

        assertReceipt(receipt, request)
        val current = assertNotNull(rooms.findById(MeetingRoomId(roomId)))
        assertEquals(receipt.analysisId, current.activeRunId)
        assertEquals(sourceRoom.collectionStatus, current.collectionStatus)
        assertEquals(sourceRoom.closedAt, current.closedAt)
        assertEquals(sourceRoom.closureReason, current.closureReason)
        assertEquals(sourceRoom.createdAt, current.createdAt)
        assertEquals(sourceRoom.mode, current.mode)
        assertEquals(sourceRoom.timeZone, current.timeZone)
        assertEquals(sourceRoom.searchRange, current.searchRange)
        assertEquals(sourceRoom.closurePolicy, current.closurePolicy)
        assertEquals(sourceRoom.purpose, current.purpose)
        assertEquals(9L, current.version)
        assertEquals(2L, current.revisionGeneration)
        assertEquals(3, current.correctionAnalysisCount, "Operator repair does not spend a HOST correction")
        val next = assertNotNull(runs.findById(CoordinationRunId(receipt.analysisId)))
        assertEquals(CoordinationStatus.QUEUED, next.status)
        assertNotEquals(F.ir.batch, next.batch.id.value)
        assertEquals(
            input.inputs.map { it.submissionVersionId }.toSet(),
            next.batch.submissionVersionIds
                .map { it.value }
                .toSet(),
        )
        val selected = assertNotNull(routes.find(receipt.analysisId)).choice as FinalAnalysisRouteChoice.DslStructuralV5
        assertEquals(NEW_CONTRACT, selected.identity.responseContractVersion)
        assertEquals(sourceRoute, routes.find(sourceId))
        assertEquals(sourceRoute.choice.let { (it as FinalAnalysisRouteChoice.DslStructuralV5).context.inputs }, selected.context.inputs)
        assertEquals(receipt.analysisId, selected.context.runId)
        assertEquals(next.batch.id.value, selected.context.batchId)
        assertEquals(protected, protectedRows())
        assertEquals(1, count("operations_reanalysis_commands"))
        assertEquals(1, count("outbox_events"))
        val event = jdbc.queryForMap("SELECT aggregate_type,aggregate_id,event_type,payload::text AS payload,status FROM outbox_events")
        assertEquals("CoordinationRun", event["aggregate_type"])
        assertEquals(receipt.analysisId, event["aggregate_id"])
        assertEquals("SubmissionBatchStructuringRequested", event["event_type"])
        assertEquals("PENDING", event["status"])
        val payload = mapper.readValue(event.getValue("payload") as String, Map::class.java)
        assertEquals(
            next.batch.id.value
                .toString(),
            payload["submission_batch_id"],
        )
        assertEquals(setOf("event_id", "submission_batch_id"), payload.keys)
        assertEquals(1, rate.calls.get())
        noProviderAdmission()
    }

    @Test
    @DisplayName("ORP02 newer participant heads cannot replace the immutable requested source cohort")
    fun readsHistoricalVersionsWhenHeadsHaveAdvanced() {
        input.inputs.forEachIndexed { index, item ->
            val newer = UUID(0, 236200L + index)
            jdbc.update(
                "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at,input_evidence) VALUES (?,?,?,?,'ko-KR',?,CAST(? AS jsonb))",
                newer,
                item.submissionId,
                item.submissionRevision + 1,
                "다음 입력은 이 재분석의 대상이 아닙니다.",
                Timestamp.from(NOW.minusSeconds(1)),
                mapper.writeValueAsString(InputEvidence(item.submissionRevision + 1, NOW.minusSeconds(2))),
            )
            jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", newer, item.submissionId)
        }
        val originals = rows("submission_versions")
        val heads = rows("submission_heads")

        val receipt = commands.enqueue(ACTOR, roomId, request())

        val next = assertNotNull(runs.findById(CoordinationRunId(receipt.analysisId)))
        assertEquals(
            input.inputs.map { it.submissionVersionId }.toSet(),
            next.batch.submissionVersionIds
                .map { it.value }
                .toSet(),
        )
        assertEquals(originals, rows("submission_versions"))
        assertEquals(heads, rows("submission_heads"))
        val selected = assertNotNull(routes.find(receipt.analysisId)).choice as FinalAnalysisRouteChoice.DslStructuralV5
        assertEquals(
            input.inputs.map { it.submissionVersionId }.toSet(),
            selected.context.inputs
                .map { it.submissionVersionId }
                .toSet(),
        )
        noProviderAdmission()
    }

    @Test
    @DisplayName("ORP03 original and scoped supplement reference timestamps survive latest-contract refreezing")
    fun preservesSupplementAndOriginalSourceReferences() {
        val first = input.inputs.first()
        val originalReference = assertNotNull(first.evidence).referenceAt
        val answeredAt = originalReference.plusSeconds(1)
        val evidence =
            InputEvidence(
                originalRevision = first.submissionRevision,
                referenceAt = originalReference,
                supplement =
                    InputSupplement(
                        UUID(0, 236301),
                        first.submissionRevision,
                        RAW,
                        0,
                        "언제부터인가요?",
                        "오후 다섯 시부터입니다.",
                        null,
                        answeredAt,
                        answeredAt,
                    ),
                interpretation = InputInterpretation.CONFIRMED,
            )
        jdbc.update(
            "UPDATE submission_versions SET input_evidence=CAST(? AS jsonb),created_at=? WHERE id=?",
            mapper.writeValueAsString(evidence),
            Timestamp.from(answeredAt),
            first.submissionVersionId,
        )
        // Freeze a logically consistent old descriptor for this independent supplement fixture.
        refreezeSource()
        val previous = (assertNotNull(routes.find(sourceId)).choice as FinalAnalysisRouteChoice.DslStructuralV5).context.inputs
        val protected = protectedRows()

        val receipt = commands.enqueue(ACTOR, roomId, request())

        val after = (assertNotNull(routes.find(receipt.analysisId)).choice as FinalAnalysisRouteChoice.DslStructuralV5).context.inputs
        assertEquals(previous, after)
        val bound = after.single { it.submissionVersionId == first.submissionVersionId }
        assertEquals(setOf(originalReference, answeredAt), bound.sources.map { it.referenceAt }.toSet())
        assertEquals(protected, protectedRows())
    }

    @ParameterizedTest
    @ValueSource(strings = ["COMPLETED", "ANALYSIS_DELAYED", "DEAD_LETTERED"])
    @DisplayName("ORP04 all supported unconfirmed terminal states can start current-contract analysis")
    fun acceptsSupportedTerminalStates(status: String) {
        if (status != "COMPLETED") clearLegacyCandidates()
        jdbc.update(
            "UPDATE coordination_runs SET status=?,resume_stage=?,candidate_quality=? WHERE id=?",
            status,
            if (status == "COMPLETED") null else "STRUCTURING",
            if (status == "COMPLETED") "COMPLETE" else null,
            sourceId,
        )
        val before = rows("coordination_runs")
        val receipt = commands.enqueue(ACTOR, roomId, request())
        assertEquals(NEW_CONTRACT, receipt.analysisContractVersion)
        assertTrue(rows("coordination_runs").containsAll(before))
        assertEquals(receipt.analysisId, rooms.findById(MeetingRoomId(roomId))?.activeRunId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["QUEUED", "STRUCTURING", "MATCHING", "COLLECTING", "OPEN_ROUND", "CONFIRMED", "RECOMMENDATION_CONFIRMED"])
    @DisplayName("ORP05 active collection analysis editing and confirmed results remain protected")
    fun rejectsProtectedLifecycleStates(state: String) {
        when (state) {
            "COLLECTING" ->
                jdbc.update(
                    "UPDATE meeting_rooms SET collection_status='COLLECTING',closure_reason=NULL,closed_at=NULL WHERE id=?",
                    roomId,
                )
            "OPEN_ROUND" -> openRound()
            "RECOMMENDATION_CONFIRMED" -> confirmedRecommendation()
            "CONFIRMED" ->
                jdbc.update(
                    "INSERT INTO final_confirmations(coordination_run_id,candidate_id,confirmed_at) VALUES (?,?,?)",
                    sourceId,
                    OLD_CANDIDATE,
                    Timestamp.from(NOW),
                )
            else -> {
                clearLegacyCandidates()
                jdbc.update("UPDATE coordination_runs SET status=?,candidate_quality=NULL WHERE id=?", state, sourceId)
            }
        }
        val before = allBusinessRows()
        failure("OPERATOR_REANALYSIS_CONFLICT") { commands.enqueue(ACTOR, roomId, request()) }
        assertEquals(before, allBusinessRows())
        noProviderAdmission()
    }

    @ParameterizedTest
    @ValueSource(strings = ["SOURCE", "VERSION"])
    @DisplayName("ORP06 stale analysis identity or room state cannot select an unintended source")
    fun rejectsStaleCommandPreconditions(kind: String) {
        val bad = if (kind == "SOURCE") request().copy(sourceAnalysisId = UUID(0, 236991)) else request().copy(expectedStateVersion = 7)
        val before = allBusinessRows()
        failure("OPERATOR_REANALYSIS_CONFLICT") { commands.enqueue(ACTOR, roomId, bad) }
        assertEquals(before, allBusinessRows())
    }

    @Test
    @DisplayName("ORP07 missing room is distinct only after the authenticated command boundary")
    fun missingRoomDoesNotCreateACommand() {
        val before = allBusinessRows()
        failure("OPERATOR_NOT_FOUND") { commands.enqueue(ACTOR, UUID(0, 236992), request()) }
        assertEquals(before, allBusinessRows())
    }

    @Test
    @DisplayName("ORP08 manual or incomplete immutable input never silently falls back to a legacy analysis")
    fun rejectsUnsupportedFrozenNativeInput() {
        jdbc.update("DELETE FROM final_analysis_routes WHERE run_id=?", sourceId)
        jdbc.update("UPDATE submission_versions SET raw_text=NULL,input_evidence=NULL WHERE id=?", input.inputs.first().submissionVersionId)
        val before = allBusinessRows()
        failure("OPERATOR_REANALYSIS_UNSUPPORTED_INPUT") { commands.enqueue(ACTOR, roomId, request()) }
        assertEquals(before, allBusinessRows())
        noProviderAdmission()
    }

    @Test
    @DisplayName("ORP09 same accepted operation replays its original receipt after current state has advanced")
    fun replaysWithoutRequeueOrPointerRewind() {
        val request = request()
        val first = commands.enqueue(ACTOR, roomId, request)
        jdbc.update("UPDATE coordination_runs SET status='COMPLETED',candidate_quality='COMPLETE',version=2 WHERE id=?", first.analysisId)
        jdbc.update("UPDATE meeting_rooms SET version=version+1 WHERE id=?", roomId)
        val before = allBusinessRows()
        rate.fail = true

        assertEquals(first, commands.enqueue(ACTOR, roomId, request))

        assertEquals(before, allBusinessRows())
        assertEquals(1, rate.calls.get(), "A receipt replay does not need a fresh Redis permit")
        assertEquals(first.analysisId, rooms.findById(MeetingRoomId(roomId))?.activeRunId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ACTOR", "SOURCE", "VERSION"])
    @DisplayName("ORP10 accepted request keys are bound to operator and exact original body")
    fun rejectsIdempotencyKeyRebinding(field: String) {
        val request = request()
        commands.enqueue(ACTOR, roomId, request)
        val before = allBusinessRows()
        val changed =
            when (field) {
                "SOURCE" -> request.copy(sourceAnalysisId = UUID(0, 236993))
                "VERSION" -> request.copy(expectedStateVersion = request.expectedStateVersion + 1)
                else -> request
            }
        failure("OPERATOR_REANALYSIS_CONFLICT") {
            commands.enqueue(if (field == "ACTOR") UUID(0, 236994) else ACTOR, roomId, changed)
        }
        assertEquals(before, allBusinessRows())
        assertEquals(1, rate.calls.get())
    }

    @Test
    @DisplayName("ORP11 parallel identical accepted requests commit exactly one run operation and outbox event")
    fun concurrentIdenticalRequestsAreOneOperation() {
        val request = request()
        val outcomes = race(listOf({ commands.enqueue(ACTOR, roomId, request) }, { commands.enqueue(ACTOR, roomId, request) }))
        assertTrue(outcomes.all { it.isSuccess })
        assertEquals(outcomes.first().getOrThrow(), outcomes.last().getOrThrow())
        assertEquals(2, count("coordination_runs"))
        assertEquals(2, count("submission_batches"))
        assertEquals(1, count("operations_reanalysis_commands"))
        assertEquals(1, count("outbox_events"))
        assertEquals(1, rate.calls.get())
    }

    @Test
    @DisplayName("ORP12 competing fresh commands on one observed state cannot both become active")
    fun concurrentDistinctRequestsUseCompareAndSet() {
        val outcomes =
            race(
                listOf(
                    { commands.enqueue(ACTOR, roomId, request(UUID(0, 236401))) },
                    { commands.enqueue(ACTOR, roomId, request(UUID(0, 236402))) },
                ),
            )
        assertEquals(1, outcomes.count { it.isSuccess })
        val failed = assertNotNull(outcomes.single { it.isFailure }.exceptionOrNull())
        assertTrue(failed is OperatorQueryException)
        assertEquals("OPERATOR_REANALYSIS_CONFLICT", failed.code.name)
        assertEquals(2, count("coordination_runs"))
        assertEquals(1, count("operations_reanalysis_commands"))
        assertEquals(1, count("outbox_events"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["outbox_events", "final_analysis_routes", "operations_reanalysis_commands"])
    @DisplayName("ORP13 failure at any durable acceptance stage rolls all business mutations back")
    fun failedAcceptanceCannotLeavePartialWrites(table: String) {
        val before = allBusinessRows()
        assertTrue(tableExists(table), "The durable acceptance table must exist before failure injection")
        jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT issue236_force_failure CHECK (false) NOT VALID")
        try {
            assertFailsWith<RuntimeException> { commands.enqueue(ACTOR, roomId, request()) }
            assertEquals(before, allBusinessRows())
        } finally {
            jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT issue236_force_failure")
        }
        noProviderAdmission()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @DisplayName("ORP14 denied and unavailable Redis permits fail closed without consuming a logical analysis")
    fun freshOperatorRequestsAlwaysRequireRedisPermit(unavailable: Boolean) {
        rate.fail = unavailable
        rate.allowed = false
        val before = allBusinessRows()
        failure(if (unavailable) "OPERATOR_UNAVAILABLE" else "OPERATOR_REANALYSIS_RATE_LIMITED") {
            commands.enqueue(ACTOR, roomId, request())
        }
        assertEquals(before, allBusinessRows())
        assertEquals(1, rate.calls.get())
    }

    @Test
    @DisplayName("ORP15 rate limit authority is operator and room scoped even when ordinary HTTP limits are disabled")
    fun rateLimitHasBoundedOperatorRoomScope() {
        commands.enqueue(ACTOR, roomId, request())
        val call = rate.observed.single()
        assertEquals(5, call.second)
        assertEquals(Duration.ofMinutes(1), call.third)
        assertFalse(call.first.contains(RAW))
        assertFalse(call.first.contains("Bearer"))
        assertTrue(call.first.isNotBlank())
    }

    @Test
    @DisplayName("ORP16 accepted command is durable and can be recovered from another JDBC connection")
    fun commandReceiptIsPersistedExactly() {
        val request = request()
        val receipt = commands.enqueue(ACTOR, roomId, request)
        val stored = assertNotNull(records.find(roomId, request.requestId))
        assertEquals(roomId, stored.roomId)
        assertEquals(ACTOR, stored.actorId)
        assertEquals(request, stored.request)
        assertEquals(receipt, stored.receipt)
        dataSource.connection.use { connection ->
            val fresh = JdbcTemplate(SingleConnectionDataSource(connection, true))
            val row =
                fresh.queryForMap(
                    "SELECT * FROM operations_reanalysis_commands WHERE room_id=? AND request_id=?",
                    roomId,
                    request.requestId,
                )
            assertEquals(receipt.analysisId, row["created_run_id"])
            assertEquals(ACTOR, row["actor_user_id"])
            assertEquals(8L, (row.getValue("expected_state_version") as Number).toLong())
            assertEquals(9L, (row.getValue("accepted_state_version") as Number).toLong())
            assertEquals(NEW_CONTRACT, row["analysis_contract_version"])
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["SELF", "FOREIGN_SOURCE", "FOREIGN_CREATED", "WRONG_VERSION"])
    @DisplayName("ORP17 database lineage constraints reject self foreign-room and inconsistent version records")
    fun lineageCannotBeForgedInStorage(fault: String) {
        val receipt = commands.enqueue(ACTOR, roomId, request())
        val otherRun = createValidForeignRun()
        val source =
            if (fault == "SELF") {
                receipt.analysisId
            } else if (fault == "FOREIGN_SOURCE") {
                otherRun
            } else {
                sourceId
            }
        val created = if (fault == "FOREIGN_CREATED") otherRun else receipt.analysisId
        val accepted = if (fault == "WRONG_VERSION") 11L else 9L
        jdbc.update("DELETE FROM operations_reanalysis_commands")
        assertFailsWith<RuntimeException> {
            jdbc.update(
                "INSERT INTO operations_reanalysis_commands(room_id,request_id,actor_user_id,source_run_id,created_run_id,expected_state_version,accepted_state_version,accepted_at,analysis_contract_version) VALUES (?,?,?,?,?,?,?,?,?)",
                roomId,
                UUID(0, 236504),
                ACTOR,
                source,
                created,
                8,
                accepted,
                Timestamp.from(NOW),
                NEW_CONTRACT,
            )
        }
        assertEquals(0, count("operations_reanalysis_commands"))
    }

    @Test
    @DisplayName("ORP18 accepted repair does not block ordinary room retention or leave orphan commands")
    fun roomRetentionDeletesOperatorCommandsWithTheRoom() {
        commands.enqueue(ACTOR, roomId, request())
        assertEquals(1, retention.deleteExpiredRooms(NOW.plusSeconds(1), 10, NOW.plusSeconds(2)))
        assertEquals(0, count("operations_reanalysis_commands"))
        assertEquals(0, count("coordination_runs"))
        assertEquals(0, count("submission_batches"))
        assertEquals(0, count("meeting_rooms"))
    }

    @Test
    @DisplayName("ORP19 ordinary selected worker can claim the newly active batch without reusing the historical batch")
    fun newBatchEntersExistingSelectedWorker() {
        val receipt = commands.enqueue(ACTOR, roomId, request())
        val next = assertNotNull(runs.findById(CoordinationRunId(receipt.analysisId)))
        assertNull(publication.start(SubmissionBatchId(F.ir.batch)))
        val started = assertNotNull(publication.start(next.batch.id))
        assertEquals(receipt.analysisId, started.id.value)
        assertEquals(CoordinationStatus.STRUCTURING, started.status)
        val admitted = assertNotNull(publication.admit(started, UUID(0, 236601), Duration.ofSeconds(60)))
        assertEquals(receipt.analysisId, admitted.binding.route.runId)
        assertEquals(
            NEW_CONTRACT,
            (admitted.binding.route.choice as FinalAnalysisRouteChoice.DslStructuralV5).identity.responseContractVersion,
        )
        assertEquals(1, count("analysis_invocations"))
        assertEquals(0, count("ai_call_reservations"), "Provider HTTP admission has not occurred")
        assertEquals(1, count("operations_reanalysis_commands"))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @DisplayName("ORP20 historical V1 anchor compatibility uses the original local date instead of inventing a new reference")
    fun historicalV1AnchorCompatibility(crossDay: Boolean) {
        val first = input.inputs.first()
        jdbc.update("UPDATE submission_versions SET input_evidence=NULL")
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='PRE_SUBMIT_V1' WHERE id=?", roomId)
        val anchor = first.acceptedAt.minusSeconds(if (crossDay) 86400 else 60)
        jdbc.update(
            "INSERT INTO precheck_candidates(id,room_id,participant_id,revision,raw_text,locale,created_at,interpretation_reference_at,owner_binding,accepted_version_id) VALUES (?,?,?,?,?,'ko-KR',?,?,?,?)",
            UUID(0, 236801),
            roomId,
            first.participantId,
            first.submissionRevision,
            first.rawText,
            Timestamp.from(first.acceptedAt),
            Timestamp.from(anchor),
            "synthetic-operator-source",
            first.submissionVersionId,
        )
        refreezeSource()
        val before = allBusinessRows()
        if (crossDay) {
            failure("OPERATOR_REANALYSIS_UNSUPPORTED_INPUT") { commands.enqueue(ACTOR, roomId, request()) }
            assertEquals(before, allBusinessRows())
        } else {
            val receipt = commands.enqueue(ACTOR, roomId, request())
            val selected = assertNotNull(routes.find(receipt.analysisId)).choice as FinalAnalysisRouteChoice.DslStructuralV5
            assertEquals(
                first.acceptedAt,
                selected.context.inputs
                    .single {
                        it.submissionVersionId == first.submissionVersionId
                    }.sources
                    .single()
                    .referenceAt,
            )
            assertEquals(before.getValue("submission_versions"), rows("submission_versions"))
        }
    }

    @Test
    @DisplayName("ORP21 supported natural language in a historical legacy run upgrades to the currently enabled native contract")
    fun legacySourceWithSupportedTextUsesCurrentContract() {
        jdbc.update("DELETE FROM final_analysis_routes WHERE run_id=?", sourceId)
        val protected = protectedRows()
        val receipt = commands.enqueue(ACTOR, roomId, request())
        assertEquals(NEW_CONTRACT, receipt.analysisContractVersion)
        assertNull(routes.find(sourceId), "The historical legacy source descriptor must not be rewritten")
        assertEquals(protected, protectedRows())
        assertEquals(
            NEW_CONTRACT,
            (
                assertNotNull(
                    routes.find(receipt.analysisId),
                ).choice as FinalAnalysisRouteChoice.DslStructuralV5
            ).identity.responseContractVersion,
        )
    }

    @Test
    @DisplayName("ORP22 any persisted OPEN revision protects edits even when its active pointer is missing")
    fun persistedOpenRoundBlocksReanalysisWithoutActivePointer() {
        openRound()
        jdbc.update("UPDATE meeting_rooms SET active_revision_round_id=NULL WHERE id=?", roomId)
        val before = allBusinessRows()
        failure("OPERATOR_REANALYSIS_CONFLICT") { commands.enqueue(ACTOR, roomId, request()) }
        assertEquals(before, allBusinessRows())
        noProviderAdmission()
    }

    private fun createValidForeignRun(): UUID =
        assertNotNull(
            TransactionTemplate(transactions).execute {
                val original = assertNotNull(rooms.findById(MeetingRoomId(roomId)))
                val foreignRoom =
                    MeetingRoom.create(
                        MeetingRoomId(UUID(0, 236501)),
                        InviteCode.fromEntropy(ByteArray(16) { 36 }),
                        "Synthetic foreign room",
                        original.mode,
                        original.timeZone,
                        original.searchRange,
                        ClosurePolicy.of(manualOnly = true),
                        NOW.minusSeconds(2),
                    )
                rooms.insert(foreignRoom)
                val versionIds =
                    (0..1).map { index ->
                        val guest = UUID(0, 236510L + index)
                        val participant = UUID(0, 236520L + index)
                        val head = UUID(0, 236530L + index)
                        val version = UUID(0, 236540L + index)
                        jdbc.update(
                            "INSERT INTO guest_browser_sessions(id,credential_digest,expires_at,created_at) VALUES (?,?,?,?)",
                            guest,
                            "orp236-foreign-guest-" + index,
                            Timestamp.from(NOW.plusSeconds(86400)),
                            Timestamp.from(NOW.minusSeconds(2)),
                        )
                        jdbc.update(
                            "INSERT INTO participants(id,room_id,guest_session_id,role,display_name,joined_at) VALUES (?,?,?,?,?,?)",
                            participant,
                            foreignRoom.id.value,
                            guest,
                            if (index ==
                                0
                            ) {
                                "HOST"
                            } else {
                                "MEMBER"
                            },
                            "Synthetic foreign member",
                            Timestamp.from(NOW.minusSeconds(2)),
                        )
                        jdbc.update(
                            "INSERT INTO submission_heads(id,room_id,participant_id) VALUES (?,?,?)",
                            head,
                            foreignRoom.id.value,
                            participant,
                        )
                        jdbc.update(
                            "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at,input_evidence) VALUES (?,?,1,?,'ko-KR',?,CAST(? AS jsonb))",
                            version,
                            head,
                            RAW,
                            Timestamp.from(NOW),
                            mapper.writeValueAsString(InputEvidence(1, NOW.minusSeconds(1))),
                        )
                        jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", version, head)
                        SubmissionVersionId(version)
                    }
                val batch = SubmissionBatch(SubmissionBatchId(UUID(0, 236502)), foreignRoom.id, versionIds, NOW)
                val run = CoordinationRun.queued(CoordinationRunId(UUID(0, 236503)), batch)
                val closed = foreignRoom.close(ClosureReason.MANUAL, NOW, 2)
                rooms.update(closed)
                runs.insert(run)
                rooms.update(closed.transition(activeRunId = run.id.value))
                run.id.value
            },
        )

    private fun oldSelector() =
        FinalAnalysisRouteSelectionService(
            runs,
            routes,
            unionPreferredAvailability = true,
            serverEvidenceReferences = true,
            searchRelativeDates = true,
            calendarMonthFacts = true,
            indexedEvidenceSelections = true,
            weeklyScheduleRules = true,
            temporalRangeAdmission = false,
        )

    private fun request(id: UUID = UUID(0, 236100)) = OperatorReanalysisRequest(id, sourceId, 8)

    private fun assertReceipt(
        receipt: OperatorReanalysisReceipt,
        request: OperatorReanalysisRequest,
    ) {
        assertEquals(request.requestId, receipt.requestId)
        assertEquals(sourceId, receipt.sourceAnalysisId)
        assertNotEquals(sourceId, receipt.analysisId)
        assertEquals(9L, receipt.acceptedStateVersion)
        assertEquals(NOW, receipt.acceptedAt)
        assertEquals(NEW_CONTRACT, receipt.analysisContractVersion)
    }

    private fun failure(
        code: String,
        action: () -> Any?,
    ) {
        assertEquals(code, assertFailsWith<OperatorQueryException> { action() }.code.name)
    }

    private fun race(actions: List<() -> OperatorReanalysisReceipt>): List<Result<OperatorReanalysisReceipt>> {
        val ready = CountDownLatch(actions.size)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(actions.size)
        return try {
            val tasks =
                actions.map { action ->
                    pool.submit(
                        Callable {
                            ready.countDown()
                            check(start.await(10, TimeUnit.SECONDS))
                            runCatching(action)
                        },
                    )
                }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            tasks.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun confirmedRecommendation() {
        val option = UUID(0, 236901)
        val variant = UUID(0, 236902)
        val start = Timestamp.from(NOW.plusSeconds(3600))
        val end = Timestamp.from(NOW.plusSeconds(7200))
        jdbc.update(
            "INSERT INTO recommendation_analyses(coordination_run_id,room_id,protocol) VALUES (?,?,'diverse-time-v1')",
            sourceId,
            roomId,
        )
        jdbc.update(
            "INSERT INTO recommendation_options(id,coordination_run_id,room_id,ordinal,primary_rank,start_at,end_at) VALUES (?,?,?,0,1,?,?)",
            option,
            sourceId,
            roomId,
            start,
            end,
        )
        jdbc.update(
            "INSERT INTO recommendation_variants(id,option_id,coordination_run_id,room_id,meeting_mode,preference_count) VALUES (?,?,?,?,'REMOTE',0)",
            variant,
            option,
            sourceId,
            roomId,
        )
        input.inputs.forEach { item ->
            jdbc.update(
                "INSERT INTO recommendation_variant_participants(variant_id,option_id,coordination_run_id,room_id,participant_id) VALUES (?,?,?,?,?)",
                variant,
                option,
                sourceId,
                roomId,
                item.participantId,
            )
        }
        jdbc.update(
            "INSERT INTO recommendation_selections(coordination_run_id,room_id,option_id,variant_id,start_at,end_at,confirmed_at) VALUES (?,?,?,?,?,?,?)",
            sourceId,
            roomId,
            option,
            variant,
            start,
            end,
            Timestamp.from(NOW),
        )
    }

    private fun openRound() {
        val id = UUID(0, 236701)
        jdbc.update(
            "INSERT INTO input_revision_rounds(id,room_id,generation,source_run_id,reopen_request_id,expected_generation,status,opened_at) VALUES (?,?,3,?,?,2,'OPEN',?)",
            id,
            roomId,
            sourceId,
            UUID(0, 236702),
            Timestamp.from(NOW),
        )
        jdbc.update("UPDATE meeting_rooms SET active_revision_round_id=? WHERE id=?", id, roomId)
    }

    private fun protectedRows(): Map<String, List<String>> =
        listOf(
            "participants",
            "submission_heads",
            "submission_versions",
            "candidates",
            "candidate_time_ranges",
            "ai_room_budgets",
            "ai_call_reservations",
        ).associateWith(::rows) +
            mapOf(
                "source_run" to
                    jdbc
                        .queryForList("SELECT to_jsonb(t)::text FROM coordination_runs t WHERE id=?", String::class.java, sourceId)
                        .map { requireNotNull(it) },
                "source_route" to
                    jdbc
                        .queryForList("SELECT to_jsonb(t)::text FROM final_analysis_routes t WHERE run_id=?", String::class.java, sourceId)
                        .map { requireNotNull(it) },
            )

    private fun allBusinessRows(): Map<String, List<String>> =
        listOf(
            "meeting_rooms",
            "participants",
            "submission_heads",
            "submission_versions",
            "submission_batches",
            "submission_batch_items",
            "input_revision_rounds",
            "coordination_runs",
            "candidates",
            "candidate_time_ranges",
            "final_confirmations",
            "recommendation_analyses",
            "recommendation_options",
            "recommendation_variants",
            "recommendation_variant_participants",
            "recommendation_selections",
            "final_analysis_routes",
            "operations_reanalysis_commands",
            "outbox_events",
            "ai_room_budgets",
            "analysis_invocations",
            "coordination_attempts",
            "ai_call_reservations",
        ).associateWith(::rows)

    private fun rows(table: String): List<String> {
        if (table == "operations_reanalysis_commands" && !tableExists(table)) return emptyList()
        return jdbc
            .queryForList("SELECT to_jsonb(t)::text FROM " + table + " t ORDER BY to_jsonb(t)::text", String::class.java)
            .map { requireNotNull(it) }
    }

    private fun tableExists(table: String): Boolean =
        jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean::class.java, table) == true

    private fun clearLegacyCandidates() {
        jdbc.update("DELETE FROM candidate_time_ranges WHERE candidate_id=?", OLD_CANDIDATE)
        jdbc.update("DELETE FROM candidates WHERE id=?", OLD_CANDIDATE)
    }

    private fun refreezeSource() {
        jdbc.update("DELETE FROM final_analysis_routes WHERE run_id=?", sourceId)
        jdbc.update("UPDATE coordination_runs SET status='STRUCTURING',candidate_quality=NULL WHERE id=?", sourceId)
        TransactionTemplate(transactions).executeWithoutResult {
            val source = assertNotNull(runs.findById(CoordinationRunId(sourceId)))
            oldSelector().freeze(
                source,
                assertNotNull(rooms.findById(MeetingRoomId(roomId))),
                FrozenSubmissionReader.read(submissions, source.batch),
                FinalAnalysisContractSelection.DSL_STRUCTURAL_V5,
            )
        }
        jdbc.update("UPDATE coordination_runs SET status='COMPLETED',candidate_quality='COMPLETE' WHERE id=?", sourceId)
    }

    private fun count(table: String): Int = assertNotNull(jdbc.queryForObject("SELECT count(*) FROM " + table, Int::class.java))

    private fun noProviderAdmission() {
        listOf("analysis_invocations", "coordination_attempts", "ai_call_reservations").forEach { assertEquals(0, count(it)) }
    }

    class TestClock : Clock() {
        @Volatile var at: Instant = NOW

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = at
    }

    class TestRateLimit : RateLimitPort {
        val calls = AtomicInteger()
        val observed = java.util.Collections.synchronizedList(mutableListOf<Triple<String, Int, Duration>>())

        @Volatile var allowed: Boolean = true

        @Volatile var fail: Boolean = false

        override fun check(
            key: String,
            limit: Int,
            window: Duration,
        ): RateLimitDecision {
            calls.incrementAndGet()
            observed += Triple(key, limit, window)
            check(!fail) { "synthetic Redis unavailable" }
            return RateLimitDecision(allowed, Duration.ofSeconds(17))
        }

        fun reset() {
            calls.set(0)
            observed.clear()
            allowed = true
            fail = false
        }
    }

    @TestConfiguration
    class Configuration {
        @Bean @Primary
        fun operatorReanalysisClock() = TestClock()

        @Bean @Primary
        fun operatorReanalysisRateLimit() = TestRateLimit()
    }

    companion object {
        private val ACTOR = UUID(0, 236001)
        private val OLD_CANDIDATE = UUID(0, 236002)
        private val NOW = F.selectedAt.plusSeconds(600)
        private const val RAW = "16:20부터 20:40까지 가능합니다."
        private const val NEW_CONTRACT = "schedule-dsl-evidence-response-6"
        private val postgres = mappedPortReadyPostgres().apply { start() }

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("meetme.reliability.enabled") { false }
            registry.add("meetme.data-retention.enabled") { false }
            registry.add("meetme.rate-limit.enabled") { false }
            registry.add("meetme.gemini.api-key") { "" }
            registry.add("meetme.openai.api-key") { "" }
            registry.add("meetme.typesafe.api-key") { "" }
            registry.add("meetme.experiment.union-preferred-availability") { true }
            registry.add("meetme.experiment.server-evidence-references") { true }
            registry.add("meetme.experiment.search-relative-dates") { true }
            registry.add("meetme.experiment.calendar-month-facts") { true }
            registry.add("meetme.experiment.indexed-evidence-selections") { true }
            registry.add("meetme.experiment.weekly-schedule-rules") { true }
            registry.add("meetme.experiment.temporal-range-admission") { true }
        }

        @JvmStatic @AfterAll
        fun stop() {
            postgres.stop()
        }
    }
}
