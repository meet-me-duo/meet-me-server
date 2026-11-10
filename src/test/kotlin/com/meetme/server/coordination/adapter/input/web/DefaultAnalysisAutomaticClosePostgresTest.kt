package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.service.FinalAnalysisRouteSelectionService
import com.meetme.server.coordination.domain.analysis.FinalAnalysisContractSelection
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.application.service.CollectionClosureService
import com.meetme.server.meetingroom.domain.ClosureReason
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Real same-request PUT -> PostgreSQL source -> automatic close; no chosen rounding formula or paid provider. */
@Import(DefaultAnalysisAutomaticClosePostgresTest.Configuration::class)
class DefaultAnalysisAutomaticClosePostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var clock: BoundaryClock

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var closure: CollectionClosureService

    @Autowired private lateinit var rooms: MeetingRoomRepository

    @BeforeEach fun startClock() = clock.set(BASE)

    @ParameterizedTest
    @ValueSource(ints = [123456499, 123456501, 999999999])
    fun `DAC01 the final submission request closes normally across PostgreSQL microsecond rounding boundaries`(nanos: Int) {
        val actors = collecting(expected = 2)
        val at = BASE.plusSeconds(60).plusNanos(nanos.toLong())
        clock.set(at)
        mvc
            .perform(save(actors.member, "2027-01-08 19:00~21:00 synthetic second participant"))
            .andExpect(status().isOk)
        assertFrozen(actors)
    }

    @Test
    fun `DAC02 a genuinely future persisted source is rejected instead of clamped or rewritten to pass freezing`() {
        val actors = collecting(expected = 3)
        clock.set(BASE.plusSeconds(60))
        mvc
            .perform(save(actors.member, "2027-01-08 19:00~21:00 synthetic second participant"))
            .andExpect(status().isOk)
        val future = clock.instant().plusSeconds(2)
        val member =
            UUID.fromString(
                requireNotNull(
                    jdbc.queryForObject(
                        "SELECT id::text FROM submission_versions WHERE raw_text=?",
                        String::class.java,
                        "2027-01-08 19:00~21:00 synthetic second participant",
                    ),
                ),
            )
        jdbc.update("UPDATE submission_versions SET created_at=? WHERE id=?", Timestamp.from(future), member)
        val before = jdbc.queryForObject("SELECT created_at::text FROM submission_versions WHERE id=?", String::class.java, member)
        assertFailsWith<IllegalArgumentException> {
            TransactionTemplate(transactionManager).executeWithoutResult {
                val room = assertNotNull(rooms.findByIdForUpdate(MeetingRoomId(actors.room)))
                closure.close(room, ClosureReason.MANUAL, 2, clock.instant(), FinalAnalysisContractSelection.DSL_STRUCTURAL_V5)
            }
        }
        assertEquals(before, jdbc.queryForObject("SELECT created_at::text FROM submission_versions WHERE id=?", String::class.java, member))
        assertEquals(
            "COLLECTING",
            jdbc.queryForObject("SELECT collection_status FROM meeting_rooms WHERE id=?", String::class.java, actors.room),
        )
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM coordination_runs", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM final_analysis_routes", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events", Int::class.java))
    }

    @ParameterizedTest
    @ValueSource(ints = [123456499, 123456501, 999999999])
    fun `DAC03 the existing explicit V5 path accepts valid same instant persisted sources without default selection`(nanos: Int) {
        val actors = collecting(expected = 3)
        clock.set(BASE.plusSeconds(60).plusNanos(nanos.toLong()))
        mvc
            .perform(save(actors.member, "2027-01-08 19:00~21:00 synthetic second participant"))
            .andExpect(status().isOk)
        val result =
            mvc
                .perform(
                    post("/api/rooms/${actors.host.code}/close")
                        .cookie(actors.host.cookie())
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"confirm_early":true,"analysis_contract":"DSL_STRUCTURAL_V5"}"""),
                ).andReturn()
        val latestSource = jdbc.queryForObject("SELECT max(created_at)::text FROM submission_versions", String::class.java)
        assertEquals(
            200,
            result.response.status,
            "Valid same-clock close at=${clock.instant()}, persisted latestSource=$latestSource; " +
                result.resolvedException?.stackTraceToString(),
        )
        assertFrozen(actors)
    }

    @ParameterizedTest
    @ValueSource(strings = ["omitted", "explicitLegacy"])
    fun `DAC04 real manual close honors omission versus explicit Legacy and a repeated omitted close stays idempotent`(kind: String) {
        val actors = collecting(expected = 3)
        clock.set(BASE.plusSeconds(60))
        mvc
            .perform(save(actors.member, "2027-01-08 19:00~21:00 synthetic second participant"))
            .andExpect(status().isOk)
        val body =
            if (kind == "omitted") {
                """{"confirm_early":true}"""
            } else {
                """{"confirm_early":true,"analysis_contract":"LEGACY"}"""
            }
        mvc
            .perform(
                post("/api/rooms/${actors.host.code}/close")
                    .cookie(actors.host.cookie())
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isOk)
        if (kind == "omitted") {
            assertFrozen(actors)
        } else {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM final_analysis_routes", Int::class.java))
        }
        val run = jdbc.queryForObject("SELECT active_run_id::text FROM meeting_rooms WHERE id=?", String::class.java, actors.room)
        val counts = workCounts()
        mvc
            .perform(
                post("/api/rooms/${actors.host.code}/close")
                    .cookie(actors.host.cookie())
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isOk)
        assertEquals(counts, workCounts())
        assertEquals(run, jdbc.queryForObject("SELECT active_run_id::text FROM meeting_rooms WHERE id=?", String::class.java, actors.room))
    }

    private fun assertFrozen(actors: Actors) {
        val runId =
            UUID.fromString(
                requireNotNull(
                    jdbc.queryForObject(
                        "SELECT active_run_id::text FROM meeting_rooms WHERE id=?",
                        String::class.java,
                        actors.room,
                    ),
                ),
            )
        val run = jdbc.queryForMap("SELECT * FROM coordination_runs WHERE id=?", runId)
        val batchId = run.getValue("batch_id") as UUID
        val batch = jdbc.queryForMap("SELECT * FROM submission_batches WHERE id=?", batchId)
        assertEquals(actors.room, run.getValue("room_id"))
        assertEquals(actors.room, batch.getValue("room_id"))
        assertEquals(
            "CLOSED",
            jdbc.queryForObject("SELECT collection_status FROM meeting_rooms WHERE id=?", String::class.java, actors.room),
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM submission_batch_items b JOIN submission_versions v ON v.id=b.submission_version_id " +
                    "JOIN submission_batches s ON s.id=b.batch_id WHERE b.batch_id=? AND v.created_at>s.fixed_at",
                Int::class.java,
                batchId,
            ),
        )
        val ids =
            jdbc
                .queryForList(
                    "SELECT submission_version_id FROM submission_batch_items WHERE batch_id=?",
                    UUID::class.java,
                    batchId,
                ).toSet()
        val current =
            jdbc
                .queryForList(
                    "SELECT latest_version_id FROM submission_heads WHERE room_id=?",
                    UUID::class.java,
                    actors.room,
                ).toSet()
        assertEquals(2, ids.size)
        assertEquals(current, ids)
        val route = assertNotNull(routes.find(runId))
        val choice = route.choice as FinalAnalysisRouteChoice.DslStructuralV5
        assertEquals(runId, route.runId)
        assertEquals(batchId, route.batchId)
        assertEquals(actors.room, route.roomId)
        assertEquals(runId, choice.context.runId)
        assertEquals(batchId, choice.context.batchId)
        assertEquals(
            ids,
            choice.context.inputs
                .map { it.submissionVersionId }
                .toSet(),
        )
        assertEquals("schedule-dsl-evidence-response-5", choice.identity.responseContractVersion)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=?", Int::class.java, runId))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM analysis_invocations", Int::class.java))
    }

    private fun collecting(expected: Int): Actors {
        val host = accounts.newAccount("default host")
        val response =
            mvc
                .perform(
                    post("/api/rooms").cookie(host).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(
                        """{"purpose":"synthetic default closure","meeting_mode":"REMOTE","expected_participants":$expected,
               "search_start_date":"2027-01-08","search_end_date":"2027-01-12"}""",
                    ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        val code = document(response).getValue("invite_code").toString()
        // Historical direct-submission protocol is a supported source cohort, not an evidence rewrite.
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='LEGACY' WHERE invite_code=?", code)
        val member = accounts.newAccount("default member")
        mvc
            .perform(
                post("/api/rooms/$code/participants")
                    .cookie(member)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isCreated)
        val hostActor = CorrectionSession(code, host.value)
        mvc
            .perform(save(hostActor, "2027-01-08 19:00~21:00 synthetic first participant"))
            .andExpect(status().isOk)
        val id =
            UUID.fromString(
                requireNotNull(jdbc.queryForObject("SELECT id::text FROM meeting_rooms WHERE invite_code=?", String::class.java, code)),
            )
        return Actors(CorrectionSession(code, member.value), id, hostActor)
    }

    private data class Actors(
        val member: CorrectionSession,
        val room: UUID,
        val host: CorrectionSession,
    )

    class BoundaryClock : Clock() {
        private val value = AtomicReference(BASE)

        fun set(at: Instant) {
            value.set(at)
        }

        override fun instant(): Instant = value.get()

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Configuration {
        @Bean @Primary
        fun defaultClosureClock() = BoundaryClock()

        @Bean @Primary
        fun configuredDefaultClosure(
            rooms: MeetingRoomRepository,
            submissions: SubmissionRepository,
            runs: CoordinationRunRepository,
            outbox: OutboxRepository,
            ids: IdGenerator,
            selection: FinalAnalysisRouteSelectionService,
        ) = CollectionClosureService(
            rooms,
            submissions,
            runs,
            outbox,
            ids,
            selection,
            defaultAnalysisContract = FinalAnalysisContractSelection.DSL_STRUCTURAL_V5,
        )
    }

    companion object {
        private val BASE = Instant.parse("2027-01-01T00:00:00Z")
        private val postgres = mappedPortReadyPostgres().apply { start() }

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registerCorrectionDatabase(registry, postgres)
            for (flag in listOf(
                "union-preferred-availability",
                "server-evidence-references",
                "search-relative-dates",
                "calendar-month-facts",
                "indexed-evidence-selections",
                "weekly-schedule-rules",
            )) {
                registry.add("meetme.experiment.$flag") { true }
            }
        }

        @JvmStatic @AfterAll
        fun stop() = postgres.stop()
    }
}
