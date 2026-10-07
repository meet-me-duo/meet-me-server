package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.service.CoordinationDeadLetterPersistence
import com.meetme.server.coordination.application.service.FrozenSubmissionReader
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.application.port.output.RoomDataRetentionPort
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.submission.application.port.output.SubmissionRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.reset
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Independent issue #92 PostgreSQL identity, admission, transaction and retention invariants.
class CorrectionRoundAdditionalPostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var retention: RoomDataRetentionPort

    @Autowired private lateinit var deadLetters: CoordinationDeadLetterPersistence

    @MockitoSpyBean private lateinit var outbox: OutboxRepository

    @MockitoSpyBean private lateinit var rooms: MeetingRoomRepository

    @Test
    fun `Postgres immutable version lookup keeps original text locale date after head correction`() {
        val fixture = completedRoom()
        val batch = requireNotNull(runs.findById(CoordinationRunId(fixture.sourceRun))).batch
        val original = FrozenSubmissionReader.read(submissions, batch)
        val roundId = openRound(fixture)
        mvc.perform(save(fixture.host, "H1 edited", roundId, 1)).andExpect(status().isOk)

        val frozen = submissions.findFrozenByVersionIds(batch.roomId, batch.submissionVersionIds)
        assertEquals(original.sortedBy { it.participantId.value }, frozen.sortedBy { it.participantId.value })
        assertEquals(original, FrozenSubmissionReader.read(submissions, batch))
        assertEquals("H1 edited", owner(fixture.host)["raw_text"])
    }

    @Test
    fun `Postgres immutable version lookup never reads another room`() {
        val first = completedRoom()
        val second = completedRoom()
        val batch = requireNotNull(runs.findById(CoordinationRunId(first.sourceRun))).batch
        assertEquals(emptyList(), submissions.findFrozenByVersionIds(MeetingRoomId(second.roomId), batch.submissionVersionIds))
    }

    @Test
    fun `joined participant absent from source batch cannot edit or join correction cohort`() {
        val (fixture, observer) = completedRoomWithObserver()
        val roundId = openRound(fixture)
        val versions = versionIds()
        val counts = workCounts()
        assertEquals(false, child(room(observer), "capabilities")["can_edit_own_submission"])
        mvc.perform(save(observer, "observer must not enter cohort", roundId, 1)).andExpect(status().isForbidden)
        assertEquals(versions, versionIds())
        assertEquals(counts, workCounts())
        mvc.perform(analyze(fixture, roundId, force = true)).andExpect(status().isAccepted)
        val next = requireNotNull(runs.findById(CoordinationRunId(activeRun(fixture.host))))
        assertEquals(2, next.batch.submissionVersionIds.size)
    }

    @Test
    fun `Outbox insertion failure rolls back batch run pointer quota and round consumption`() {
        val fixture = completedRoom()
        val roundId = openRound(fixture)
        val beforeRoom = room(fixture.host)
        val counts = workCounts()
        val requestId = UUID.randomUUID()
        Mockito.clearInvocations(outbox)
        doThrow(IllegalStateException("synthetic Outbox failure")).`when`(outbox).insert(anyValue())
        try {
            assertWriteFailed {
                mvc
                    .perform(analyze(fixture, roundId, requestId, force = true))
                    .andReturn()
                    .response.status
            }
            Mockito.verify(outbox).insert(anyValue())
        } finally {
            reset(outbox)
        }
        assertEquals(beforeRoom, room(fixture.host))
        assertEquals(counts, workCounts())
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM input_revision_rounds WHERE id = ?", String::class.java, roundId))
        mvc.perform(analyze(fixture, roundId, requestId, force = true)).andExpect(status().isAccepted)
        assertEquals(2, (room(fixture.host).getValue("remaining_correction_analyses") as Number).toInt())
    }

    @Test
    fun `failure after real room pointer and quota update rolls back every correction write`() {
        val fixture = completedRoom()
        val roundId = openRound(fixture)
        val beforeRoom = room(fixture.host)
        val counts = workCounts()
        val reachedRealUpdate = AtomicBoolean()
        doAnswer {
            it.callRealMethod()
            reachedRealUpdate.set(true)
            throw IllegalStateException("synthetic post-update failure")
        }.`when`(rooms).update(anyValue())
        try {
            assertWriteFailed {
                mvc
                    .perform(analyze(fixture, roundId, force = true))
                    .andReturn()
                    .response.status
            }
        } finally {
            reset(rooms)
        }
        assertTrue(reachedRealUpdate.get(), "Failure must happen after the real room update")
        assertEquals(beforeRoom, room(fixture.host))
        assertEquals(counts, workCounts())
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM input_revision_rounds WHERE id = ?", String::class.java, roundId))
    }

    @Test
    fun `retention uses original closed anchor and removes open rounds and all run histories`() {
        val expired = completedRoom()
        val consumed = openRound(expired)
        mvc.perform(analyze(expired, consumed, force = true)).andExpect(status().isAccepted)
        val nextRun = activeRun(expired.host)
        completePartial(nextRun)
        val expiredRound = openRound(expired, generation = 1, source = nextRun)
        val fresh = completedRoom()
        val freshRound = openRound(fresh)
        val now = Instant.now()
        jdbc.update(
            "UPDATE meeting_rooms SET created_at = ?, closed_at = ? WHERE id = ?",
            now.minus(32, ChronoUnit.DAYS).atOffset(java.time.ZoneOffset.UTC),
            now.minus(31, ChronoUnit.DAYS).atOffset(java.time.ZoneOffset.UTC),
            expired.roomId,
        )
        assertEquals(1, retention.deleteExpiredRooms(now.minus(30, ChronoUnit.DAYS), 10, now))
        assertEquals(0, countById("meeting_rooms", expired.roomId))
        assertEquals(0, countById("input_revision_rounds", expiredRound))
        assertEquals(0, countById("input_revision_rounds", consumed))
        assertEquals(0, countById("coordination_runs", nextRun))
        assertEquals(0, countById("coordination_runs", expired.sourceRun))
        assertEquals(1, countById("meeting_rooms", fresh.roomId))
        assertEquals(1, countById("input_revision_rounds", freshRound))
        assertEquals(1, countById("coordination_runs", fresh.sourceRun))
    }

    @Test
    fun `database refuses active run and round pointers from another room`() {
        val first = completedRoom()
        val second = completedRoom()
        val secondRound = openRound(second)
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("UPDATE meeting_rooms SET active_run_id = ? WHERE id = ?", second.sourceRun, first.roomId)
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("UPDATE meeting_rooms SET active_revision_round_id = ? WHERE id = ?", secondRound, first.roomId)
        }
        assertEquals(first.sourceRun, activeRun(first.host))
    }

    @Test
    fun `database refuses foreign source and resolved runs in a round`() {
        val first = completedRoom()
        val roundId = openRound(first)
        val second = completedRoom()
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("UPDATE input_revision_rounds SET source_run_id = ? WHERE id = ?", second.sourceRun, roundId)
        }
        mvc.perform(analyze(first, roundId)).andExpect(status().isOk)
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("UPDATE input_revision_rounds SET resolved_run_id = ? WHERE id = ?", second.sourceRun, roundId)
        }
    }

    @Test
    fun `late dead letter for replaced run cannot publish delay or worker failure into current generation`() {
        val fixture = completedRoom()
        val roundId = openRound(fixture)
        mvc.perform(analyze(fixture, roundId, force = true)).andExpect(status().isAccepted)
        val currentId = activeRun(fixture.host)
        jdbc.update(
            "UPDATE coordination_runs SET status = 'STRUCTURING', candidate_quality = NULL WHERE id = ?",
            fixture.sourceRun,
        )
        jdbc.update(
            "UPDATE outbox_events SET status = 'PUBLISHED', published_at = now(), processed_at = NULL WHERE aggregate_id = ?",
            fixture.sourceRun,
        )
        val eventId =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT id FROM outbox_events WHERE aggregate_id = ?",
                    UUID::class.java,
                    fixture.sourceRun,
                ),
            )
        val before = room(fixture.host)
        val old = requireNotNull(runs.findById(CoordinationRunId(fixture.sourceRun)))
        val current = requireNotNull(runs.findById(CoordinationRunId(currentId)))
        deadLetters.persist(OutboxEventId(eventId), "synthetic stale delivery", 5)
        assertEquals(before, room(fixture.host))
        assertEquals(old, runs.findById(CoordinationRunId(fixture.sourceRun)))
        assertEquals(current, runs.findById(CoordinationRunId(currentId)))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM coordination_worker_failures", Int::class.java))
    }

    private fun completedRoomWithObserver(): Pair<CompletedCorrectionFixture, CorrectionSession> {
        val response =
            mvc
                .perform(
                    post("/api/rooms")
                        .header("Origin", CorrectionRoundPostgresFixture.ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"purpose":"synthetic cohort","meeting_mode":"REMOTE","host_display_name":"host","manual_only":true}""",
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        val code = document(response).getValue("invite_code").toString()
        val host = CorrectionSession(code, credential(response.getHeader(HttpHeaders.SET_COOKIE)))

        fun join(name: String): CorrectionSession {
            val joined =
                mvc
                    .perform(
                        post("/api/rooms/{code}/participants", code)
                            .header("Origin", CorrectionRoundPostgresFixture.ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"display_name":"$name"}"""),
                    ).andExpect(status().isCreated)
                    .andReturn()
                    .response
            return CorrectionSession(code, credential(joined.getHeader(HttpHeaders.SET_COOKIE)))
        }
        val member = join("member")
        val observer = join("observer")
        mvc.perform(save(host, "H0 original")).andExpect(status().isOk)
        mvc.perform(save(member, "M0 original")).andExpect(status().isOk)
        mvc
            .perform(
                post("/api/rooms/{code}/close", code)
                    .header("Origin", CorrectionRoundPostgresFixture.ORIGIN)
                    .cookie(host.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"confirm_early":true}"""),
            ).andExpect(status().isOk)
        val roomId = requireNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code = ?", UUID::class.java, code))
        val runId = requireNotNull(jdbc.queryForObject("SELECT id FROM coordination_runs WHERE room_id = ?", UUID::class.java, roomId))
        completePartial(runId)
        return CompletedCorrectionFixture(host, member, roomId, runId) to observer
    }

    private fun credential(header: String?) = requireNotNull(header).substringAfter("meet_me_guest=").substringBefore(';')

    private fun countById(
        table: String,
        id: UUID,
    ) = jdbc.queryForObject("SELECT count(*) FROM $table WHERE id = ?", Int::class.java, id)

    private fun assertWriteFailed(action: () -> Int) {
        val outcome = runCatching(action)
        assertTrue(outcome.isFailure || outcome.getOrNull() == 500, "Synthetic persistence failure must not return success")
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = CorrectionRoundPostgresFixture.registerCorrectionDatabase(registry, postgres)

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()

        @Suppress("UNCHECKED_CAST")
        private fun <T> anyValue(): T {
            Mockito.any<T>()
            return null as T
        }
    }
}
