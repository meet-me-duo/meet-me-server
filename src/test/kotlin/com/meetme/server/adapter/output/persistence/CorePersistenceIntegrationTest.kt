package com.meetme.server.adapter.output.persistence

import com.meetme.server.application.port.output.CoordinationRunRepository
import com.meetme.server.application.port.output.GuestSessionRepository
import com.meetme.server.application.port.output.MeetingRoomRepository
import com.meetme.server.application.port.output.OutboxEvent
import com.meetme.server.application.port.output.OutboxRepository
import com.meetme.server.application.port.output.ParticipantRepository
import com.meetme.server.application.port.output.SubmissionRepository
import com.meetme.server.application.service.CoordinationPersistenceService
import com.meetme.server.domain.common.CandidateId
import com.meetme.server.domain.common.CoordinationRunId
import com.meetme.server.domain.common.GuestSessionId
import com.meetme.server.domain.common.MeetingRoomId
import com.meetme.server.domain.common.OutboxEventId
import com.meetme.server.domain.common.ParticipantId
import com.meetme.server.domain.common.SubmissionBatchId
import com.meetme.server.domain.common.SubmissionId
import com.meetme.server.domain.common.SubmissionVersionId
import com.meetme.server.domain.coordination.CandidateQuality
import com.meetme.server.domain.coordination.CoordinationRun
import com.meetme.server.domain.coordination.MeetingCandidate
import com.meetme.server.domain.coordination.SubmissionBatch
import com.meetme.server.domain.meeting.ClosurePolicy
import com.meetme.server.domain.meeting.MeetingMode
import com.meetme.server.domain.meeting.MeetingRoom
import com.meetme.server.domain.participant.GuestSession
import com.meetme.server.domain.participant.Participant
import com.meetme.server.domain.submission.Submission
import com.meetme.server.domain.time.InstantTimeRange
import com.meetme.server.domain.time.MeetingDuration
import com.meetme.server.domain.time.MeetingTimeZone
import com.meetme.server.domain.time.SearchDateRange
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
class CorePersistenceIntegrationTest {
    @Autowired
    private lateinit var flyway: Flyway

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var roomRepository: MeetingRoomRepository

    @Autowired
    private lateinit var guestSessionRepository: GuestSessionRepository

    @Autowired
    private lateinit var participantRepository: ParticipantRepository

    @Autowired
    private lateinit var submissionRepository: SubmissionRepository

    @Autowired
    private lateinit var coordinationRunRepository: CoordinationRunRepository

    @Autowired
    private lateinit var outboxRepository: OutboxRepository

    @Autowired
    private lateinit var coordinationPersistenceService: CoordinationPersistenceService

    @BeforeEach
    fun cleanDatabase() {
        jdbcTemplate.execute(
            "TRUNCATE final_confirmations, candidate_time_ranges, candidates, coordination_attempts, " +
                "outbox_events, coordination_runs, submission_batch_items, submission_batches, " +
                "manual_availability_intervals, submission_versions, submission_heads, participants, " +
                "meeting_rooms, guest_browser_sessions CASCADE",
        )
    }

    @Test
    fun `Flyway 최초 마이그레이션을 적용하고 검증한다`() {
        assertEquals(
            "1",
            flyway
                .info()
                .current()
                .version.version,
        )
        flyway.validate()
        val tables =
            jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String::class.java,
            )
        assertEquals(true, tables.containsAll(listOf("meeting_rooms", "submission_versions", "coordination_runs", "outbox_events")))
    }

    @Test
    fun `Room Participant Submission을 Record와 분리해 왕복 저장한다`() {
        val fixture = createSubmissionFixture()

        assertEquals(fixture.room, roomRepository.findById(fixture.room.id))
        assertEquals(fixture.guestSession, guestSessionRepository.findById(fixture.guestSession.id))
        assertEquals(fixture.participant, participantRepository.findById(fixture.participant.id))
        assertEquals(fixture.submission, submissionRepository.findById(fixture.submission.id))
    }

    @Test
    fun `CoordinationRun과 Outbox를 하나의 트랜잭션으로 저장한다`() {
        val fixture = createCoordinationFixture()
        val event = outboxEvent(fixture.run)

        coordinationPersistenceService.persist(fixture.run, event)

        assertEquals(fixture.run, coordinationRunRepository.findById(fixture.run.id))
        assertEquals(event, outboxRepository.findById(event.id))
    }

    @Test
    fun `Outbox 저장 실패 시 CoordinationRun도 rollback한다`() {
        val fixture = createCoordinationFixture()
        val event = outboxEvent(fixture.run)
        outboxRepository.insert(event)

        assertThrows<RuntimeException> { coordinationPersistenceService.persist(fixture.run, event) }

        assertNull(coordinationRunRepository.findById(fixture.run.id))
        assertNotNull(outboxRepository.findById(event.id))
    }

    private fun createCoordinationFixture(): CoordinationFixture {
        val first = createSubmissionFixture()
        val secondSession = guestSession()
        guestSessionRepository.insert(secondSession)
        val secondParticipant =
            Participant.member(ParticipantId(UUID.randomUUID()), first.room.id, secondSession.id, NOW.plusSeconds(1))
        participantRepository.insert(secondParticipant)
        val secondSubmission = submission(first.room.id, secondParticipant.id, "목요일 저녁")
        submissionRepository.insert(secondSubmission)

        val batch =
            SubmissionBatch(
                SubmissionBatchId(UUID.randomUUID()),
                first.room.id,
                listOf(first.submission.latest.id, secondSubmission.latest.id),
                NOW.plusSeconds(10),
            )
        val candidate =
            MeetingCandidate(
                CandidateId(UUID.randomUUID()),
                1,
                listOf(InstantTimeRange(NOW.plusSeconds(3600), NOW.plusSeconds(7200))),
            )
        val run =
            CoordinationRun
                .queued(CoordinationRunId(UUID.randomUUID()), batch)
                .startMatching()
                .complete(CandidateQuality.COMPLETE, listOf(candidate))
                .confirm(candidate.id, NOW.plusSeconds(20))
        return CoordinationFixture(run)
    }

    private fun createSubmissionFixture(): SubmissionFixture {
        val room = room()
        roomRepository.insert(room)
        val guestSession = guestSession()
        guestSessionRepository.insert(guestSession)
        val participant = Participant.host(ParticipantId(UUID.randomUUID()), room.id, guestSession.id, NOW)
        participantRepository.insert(participant)
        val submission = submission(room.id, participant.id, "화요일 저녁")
        submissionRepository.insert(submission)
        return SubmissionFixture(room, guestSession, participant, submission)
    }

    private fun room() =
        MeetingRoom.create(
            MeetingRoomId(UUID.randomUUID()),
            "통합 테스트 회의",
            MeetingDuration.ofMinutes(60),
            MeetingMode.EITHER,
            MeetingTimeZone.of("Asia/Seoul"),
            SearchDateRange.explicit(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 27)),
            ClosurePolicy.of(expectedParticipants = 2),
            NOW,
        )

    private fun guestSession() =
        GuestSession(
            GuestSessionId(UUID.randomUUID()),
            UUID.randomUUID().toString().replace("-", ""),
            NOW.plusSeconds(86400),
            null,
            NOW,
        )

    private fun submission(
        roomId: MeetingRoomId,
        participantId: ParticipantId,
        text: String,
    ) = Submission.start(
        SubmissionId(UUID.randomUUID()),
        roomId,
        participantId,
        SubmissionVersionId(UUID.randomUUID()),
        text,
        emptyList(),
        Locale.forLanguageTag("ko-KR"),
        NOW,
    )

    private fun outboxEvent(run: CoordinationRun) =
        OutboxEvent(
            OutboxEventId(UUID.randomUUID()),
            "CoordinationRun",
            run.id.value,
            "CoordinationRequested",
            "{\"coordinationRunId\":\"${run.id.value}\"}",
            occurredAt = NOW.plusSeconds(10),
        )

    private data class SubmissionFixture(
        val room: MeetingRoom,
        val guestSession: GuestSession,
        val participant: Participant,
        val submission: Submission,
    )

    private data class CoordinationFixture(
        val run: CoordinationRun,
    )

    companion object {
        private val NOW = Instant.parse("2026-09-18T00:00:00Z")
        private val postgres = PostgreSQLContainer("postgres:18-alpine")

        init {
            postgres.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() {
            postgres.stop()
        }
    }
}
