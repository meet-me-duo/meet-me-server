package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.service.FrozenSubmissionReader
import com.meetme.server.coordination.application.service.ScheduleDslStoredReadService
import com.meetme.server.coordination.application.service.ScheduleEvidenceSnapshotFactory
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.analysis.FrozenFinalAnalysisRoute
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModePolicy
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import com.meetme.server.submission.domain.dsl.PublicPlaceConstraintDescription
import com.meetme.server.submission.domain.dsl.SchedulePlaceConstraintKind
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real HTTP, matcher, frozen cohort, result service, selection and PG; no provider request.
 * Only the selected-route read boundary is stubbed after the ordinary synthetic match is published.
 * These tests prove response wiring; source/format restoration is covered by separate native repository tests.
 */
class TravelPlaceSummaryHttpPostgresTest : RecommendationPostgresFixture() {
    @Autowired private lateinit var travelStructured: StructuredSubmissionRepository

    @Autowired private lateinit var travelRuns: CoordinationRunRepository

    @Autowired private lateinit var travelSubmissions: SubmissionRepository

    @Autowired private lateinit var travelRooms: MeetingRoomRepository

    @MockitoSpyBean private lateinit var routes: FinalAnalysisRouteRegistry

    @MockitoBean private lateinit var storedDsl: ScheduleDslStoredReadService

    @Test
    fun `TPSH01 legacy candidates primary options and alternatives show only the selected cohorts public travel requirement`() {
        val fixture = publicTravelRoom()
        val legacy =
            document(
                mvc
                    .perform(
                        get("/api/rooms/{code}/candidates", fixture.host.code)
                            .cookie(fixture.host.cookie())
                            .param("attendance_support", "conditional-v1"),
                    ).andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        val legacyCandidates = items(legacy, "candidates")
        assertTrue(legacyCandidates.isNotEmpty())
        legacyCandidates.forEach(::assertPublicConstraint)

        val primary = recommendations(fixture.host, "conditional-v1")
        assertEquals(5, (primary.getValue("total_options") as Number).toInt())
        assertEquals(3, items(primary, "options").size)
        val additional =
            document(
                mvc
                    .perform(alternatives(fixture, limit = 10, attendanceSupport = "conditional-v1"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        assertEquals(2, items(additional, "options").size)
        for (option in items(primary, "options") + items(additional, "options")) {
            val range = child(option, "time_range")
            assertTrue(range.getValue("start_at").toString().endsWith("T09:00:00Z"))
            assertTrue(range.getValue("end_at").toString().endsWith("T12:00:00Z"))
            items(option, "variants").forEach(::assertPublicConstraint)
        }
    }

    @Test
    fun `TPSH02 actual time selection and repeated result reads preserve the public requirement and unverified attendance`() {
        val fixture = publicTravelRoom()
        val before = pendingRows(fixture.sourceRun)
        val option = items(recommendations(fixture.host, "conditional-v1"), "options").first()
        val variant = items(option, "variants").single()
        val confirmation =
            document(
                mvc
                    .perform(selection(fixture, option, variant, attendanceSupport = "conditional-v1"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        assertPublicConstraint(child(confirmation, "candidate"))
        val confirmed = result(fixture.host, "conditional-v1")
        assertPublicConstraint(child(confirmed, "candidate"))
        assertEquals(before, pendingRows(fixture.sourceRun), "Selecting a time cannot clear previously unverified travel feasibility")
        mvc
            .perform(selection(fixture, option, variant, attendanceSupport = "conditional-v1"))
            .andExpect(status().isOk)
        assertEquals(confirmed, result(fixture.host, "conditional-v1"))
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM recommendation_selections WHERE coordination_run_id=?",
                Int::class.java,
                fixture.sourceRun,
            ),
        )
    }

    private fun assertPublicConstraint(view: Map<String, Any?>) {
        assertEquals("IN_PERSON", view["meeting_mode"])
        assertEquals(2, (view.getValue("attendance_count") as Number).toInt())
        assertEquals(3, (view.getValue("total_participants") as Number).toInt())
        assertEquals(false, view["attendance_confirmed"])
        assertNull(view["place"], "An origin is not an appointed meeting venue")
        val text = view.getValue("summary").toString()
        assertTrue(text.contains(PUBLIC_ORIGIN))
        assertTrue(text.contains("45분 이내"))
        assertTrue(text.contains("확인") || text.contains("미확정"))
        assertFalse(text.contains(EXCLUDED_ORIGIN), "An absent frozen participant cannot constrain this variant")
        assertFalse(text.contains("20분"))
        assertFalse(text.contains(PRIVATE_EXPRESSION))
        assertFalse(text.contains("모두 참여 가능"))
    }

    private fun publicTravelRoom(): CompletedCorrectionFixture {
        val names = listOf("합성 참석자1", "합성 참석자2", "합성 제외자")
        val hostCookie = accounts.newAccount(names.first())
        val created =
            mvc
                .perform(
                    post("/api/rooms")
                        .cookie(hostCookie)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            mapper.writeValueAsString(
                                mapOf(
                                    "purpose" to "synthetic public place summary",
                                    "meeting_mode" to "IN_PERSON",
                                    "host_display_name" to names.first(),
                                    "manual_only" to true,
                                    "search_start_date" to "2026-10-07",
                                    "search_end_date" to "2026-10-12",
                                ),
                            ),
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        val code = document(created).getValue("invite_code").toString()
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='LEGACY' WHERE invite_code=?", code)
        val actors = mutableListOf(CorrectionSession(code, hostCookie.value))
        for (name in names.drop(1)) {
            val cookie = accounts.newAccount(name)
            mvc
                .perform(
                    post("/api/rooms/{code}/participants", code)
                        .cookie(cookie)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(mapOf("display_name" to name))),
                ).andExpect(status().isCreated)
            actors += CorrectionSession(code, cookie.value)
        }
        actors.forEachIndexed { index, actor -> mvc.perform(save(actor, "synthetic unpublished input $index")).andExpect(status().isOk) }
        mvc
            .perform(
                post("/api/rooms/{code}/close", code)
                    .cookie(actors.first().cookie())
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"confirm_early":true}"""),
            ).andExpect(status().isOk)
        val roomId = requireNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code=?", UUID::class.java, code))
        val runId = requireNotNull(jdbc.queryForObject("SELECT id FROM coordination_runs WHERE room_id=?", UUID::class.java, roomId))
        prepareMatching(runId, days = 5)
        val matching = requireNotNull(travelRuns.findById(CoordinationRunId(runId)))
        val frozen = FrozenSubmissionReader.read(travelSubmissions, matching.batch)
        val owners =
            frozen.associate { input ->
                val name =
                    requireNotNull(
                        jdbc.queryForObject(
                            "SELECT display_name FROM participants WHERE id=?",
                            String::class.java,
                            input.participantId.value,
                        ),
                    )
                name to input
            }
        travelStructured.replaceForBatch(
            matching.batch.id,
            names.map { name ->
                val input = owners.getValue(name)
                val conditions =
                    if (name == names.last()) {
                        (0L..4L).map { day ->
                            StructuredCondition.TimeWindow(
                                TimePolarity.UNAVAILABLE,
                                LocalDate.of(2026, 10, 7).plusDays(day),
                                null,
                                LocalTime.MIDNIGHT,
                                LocalTime.MIDNIGHT,
                                endsAtNextDayStart = true,
                            )
                        }
                    } else {
                        (0L..4L).map { day ->
                            StructuredCondition.TimeWindow(
                                TimePolarity.AVAILABLE,
                                LocalDate.of(2026, 10, 7).plusDays(day),
                                null,
                                LocalTime.of(18, 0),
                                LocalTime.of(21, 0),
                            )
                        } + if (name == names.first()) listOf(StructuredCondition.TravelConstraint(PRIVATE_EXPRESSION)) else emptyList()
                    }
                StructuredSubmissionResult(input.latest.id, conditions, null)
            },
            Instant.parse("2026-10-07T05:00:00Z"),
        )
        process(runId)
        val run = requireNotNull(travelRuns.findById(CoordinationRunId(runId)))
        val room = requireNotNull(travelRooms.findById(MeetingRoomId(roomId)))
        val policy = ScheduleDslContextModePolicy.contextual()
        val snapshot =
            ScheduleEvidenceSnapshotFactory().create(
                run,
                FrozenSubmissionReader.read(travelSubmissions, run.batch),
                room.timeZone.value,
                room.searchRange.startInclusive,
                room.searchRange.endExclusive,
                policy.version,
            )
        val route =
            FrozenFinalAnalysisRoute(
                runId = run.id.value,
                batchId = run.batch.id.value,
                roomId = run.roomId.value,
                choice =
                    FinalAnalysisRouteChoice.DslStructuralV5(
                        IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", policy.version),
                        policy,
                        snapshot.context,
                        MeetingMode.IN_PERSON,
                        "ko-time-terms-1",
                    ),
                selectedAt = run.batch.fixedAt,
            )
        doReturn(route).`when`(routes).find(runId)
        doReturn(
            mapOf(
                owners
                    .getValue(names.first())
                    .latest.id.value to "UNSUPPORTED_TRAVEL_CONSTRAINT",
            ),
        ).`when`(storedDsl)
            .readInputDiagnostics(run.id, run.version, route.fingerprint())
        val descriptions =
            listOf(
                PublicPlaceConstraintDescription(
                    owners.getValue(names.first()).participantId.value,
                    SchedulePlaceConstraintKind.TRAVEL_TIME_LIMIT,
                    PUBLIC_ORIGIN,
                    45,
                    null,
                ),
                PublicPlaceConstraintDescription(
                    owners.getValue(names.last()).participantId.value,
                    SchedulePlaceConstraintKind.TRAVEL_TIME_LIMIT,
                    EXCLUDED_ORIGIN,
                    20,
                    null,
                ),
            )
        // The ordinary result reader must remain in a GET read-only repeatable-read snapshot.
        // Selection has a distinct pre-write snapshot entry; its actual REQUIRES_NEW AOP behavior
        // is verified separately, while this stub checks the source version before any selection write.
        var firstSelectionSnapshotLoaded = false
        for (expectedVersion in listOf(run.version, run.version + 1)) {
            doAnswer { invocation ->
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "A result read requires an active snapshot")
                assertTrue(
                    TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                    "GET cannot reuse a writer transaction for source restoration",
                )
                assertEquals(
                    Connection.TRANSACTION_REPEATABLE_READ,
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                    "GET source restoration must retain repeatable-read isolation",
                )
                val current = requireNotNull(travelRuns.findById(run.id))
                assertEquals(current.version, invocation.getArgument<Long>(1), "Descriptions must use the current execution version")
                assertEquals(route.fingerprint(), invocation.getArgument<String>(2), "Descriptions must use the frozen route selection")
                descriptions
            }.`when`(storedDsl).readPlaceDescriptions(run.id, expectedVersion, route.fingerprint())
            doAnswer { invocation ->
                assertTrue(
                    TransactionSynchronizationManager.isActualTransactionActive(),
                    "Selection snapshot loading requires a transaction",
                )
                val request = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request
                assertEquals("POST", request.method, "GET must use the ordinary read-only result reader")
                val current = requireNotNull(travelRuns.findById(run.id))
                if (!firstSelectionSnapshotLoaded) {
                    assertFalse(current.isConfirmed, "The first selection snapshot must load before persisting the selection")
                    assertEquals(run.version, current.version)
                    firstSelectionSnapshotLoaded = true
                }
                assertEquals(
                    current.version,
                    invocation.getArgument<Long>(1),
                    "Selection descriptions must be preloaded at the current version before writing",
                )
                assertEquals(
                    route.fingerprint(),
                    invocation.getArgument<String>(2),
                    "Selection descriptions must use the frozen route selection",
                )
                descriptions
            }.`when`(storedDsl).readPlaceDescriptionsForSelection(run.id, expectedVersion, route.fingerprint())
        }
        return CompletedCorrectionFixture(actors.first(), actors[1], roomId, runId)
    }

    private fun pendingRows(run: UUID): List<List<Map<String, Any?>>> =
        listOf("candidate_participants", "recommendation_variant_participants").map { table ->
            jdbc.queryForList(
                "SELECT participant_id,place_unverified FROM $table WHERE coordination_run_id=? ORDER BY participant_id::text",
                run,
            )
        }

    companion object {
        private const val PUBLIC_ORIGIN = "가상공원역"
        private const val EXCLUDED_ORIGIN = "가상언덕역"
        private const val PRIVATE_EXPRESSION = "비공개 개인 기준점의 이동 제약"
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
