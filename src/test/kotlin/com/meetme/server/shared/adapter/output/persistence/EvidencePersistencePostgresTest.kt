package com.meetme.server.shared.adapter.output.persistence

import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.participant.application.port.output.GuestSessionRepository
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.participant.domain.GuestSession
import com.meetme.server.participant.domain.Participant
import com.meetme.server.participant.domain.ParticipantDisplayName
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.precheck.domain.PrecheckCandidate
import com.meetme.server.precheck.domain.PrecheckExecution
import com.meetme.server.precheck.domain.PrecheckStatus
import com.meetme.server.shared.domain.GuestSessionId
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.shared.domain.InputSupplement
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.Submission
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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

/** EX-FREEZE: received evidence survives DB round trips and historical version selection. */
@SpringBootTest(properties = ["meetme.reliability.enabled=false", "meetme.data-retention.enabled=false"])
class EvidencePersistencePostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var rooms: MeetingRoomRepository

    @Autowired private lateinit var sessions: GuestSessionRepository

    @Autowired private lateinit var participants: ParticipantRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var prechecks: PrecheckRepository

    @BeforeEach
    fun clean() = jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")

    @Test
    fun `V21 adds nullable evidence without retroactively converting old rooms or text`() {
        val (room, participant) = owner()
        val legacy = submission(room.id, participant.id)
        submissions.insert(legacy)
        assertEquals(
            "PRE_SUBMIT_V1",
            jdbc.queryForObject("SELECT submission_protocol FROM meeting_rooms WHERE id=?", String::class.java, room.id.value),
        )
        assertNull(assertNotNull(submissions.findById(legacy.id)).latest.evidence)
        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' " +
                    "AND table_name IN ('precheck_candidates','submission_versions') AND column_name='input_evidence' AND data_type='jsonb'",
                Int::class.java,
            ),
        )
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='PRE_SUBMIT_V2' WHERE id=?", room.id.value)
        assertEquals(
            "PRE_SUBMIT_V2",
            jdbc.queryForObject("SELECT submission_protocol FROM meeting_rooms WHERE id=?", String::class.java, room.id.value),
        )
    }

    @Test
    fun `frozen older version retains original answer unresolved status and anchors after later edit`() {
        val (room, participant) = owner()
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='PRE_SUBMIT_V2' WHERE id=?", room.id.value)
        val evidence =
            InputEvidence(
                1,
                NOW,
                InputSupplement(UUID.randomUUID(), 1, "7시", 4, "오전인가요 오후인가요?", "오후 7시", "A", NOW.plusSeconds(10), NOW),
                InputInterpretation.UNRESOLVED,
                "MATERIAL_AMBIGUITY",
            )
        val original = submission(room.id, participant.id, evidence)
        submissions.insert(original)
        assertEquals(original, submissions.findById(original.id))
        val revised =
            original.revise(
                SubmissionVersionId(UUID.randomUUID()),
                "수요일 오후 8시 이후",
                emptyList(),
                Locale.KOREAN,
                NOW.plusSeconds(30),
                InputEvidence(3, NOW.plusSeconds(30), interpretation = InputInterpretation.CONFIRMED),
            )
        submissions.save(revised)
        assertEquals(revised, submissions.findById(original.id))
        val frozen = submissions.findFrozenByVersionIds(room.id, listOf(original.latest.id)).single()
        assertEquals(original.latest, frozen.latest)
        assertEquals("수요일 7시 이후", frozen.latest.rawText)
        assertEquals(
            "오후 7시",
            frozen.latest.evidence
                ?.supplement
                ?.answerText,
        )
    }

    @Test
    fun `candidate roundtrip and room text admission include the finalized scoped answer`() {
        val (room, participant) = owner()
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='PRE_SUBMIT_V2' WHERE id=?", room.id.value)
        val evidence =
            InputEvidence(
                1,
                NOW,
                InputSupplement(UUID.randomUUID(), 1, "7시", 4, "오전인가요 오후인가요?", "오후 7시", "A", NOW.plusSeconds(10), NOW),
            )
        val candidate =
            PrecheckCandidate(
                UUID.randomUUID(),
                room.id.value,
                participant.id.value,
                2,
                "수요일 7시 이후",
                "ko-KR",
                NOW.plusSeconds(10),
                NOW,
                "synthetic-evidence-binding",
                evidence = evidence,
            )
        val execution = PrecheckExecution(UUID.randomUUID(), candidate, PrecheckStatus.QUEUED, 1, 1, 0, NOW.plusSeconds(40))
        prechecks.insert(candidate, execution)
        assertEquals(execution, prechecks.current(participant.id.value))
        assertEquals(
            14,
            prechecks.latestCandidateTextLength(room.id.value, UUID.randomUUID()),
            "Original nine and answer five code points count once",
        )
        assertEquals(0, prechecks.latestCandidateTextLength(room.id.value, participant.id.value))
    }

    private fun owner(): Pair<MeetingRoom, Participant> {
        val room =
            MeetingRoom.create(
                MeetingRoomId(UUID.randomUUID()),
                InviteCode.fromEntropy(
                    UUID
                        .randomUUID()
                        .toString()
                        .toByteArray()
                        .take(16)
                        .toByteArray(),
                ),
                "evidence fixture",
                MeetingMode.REMOTE,
                MeetingTimeZone.of("Asia/Seoul"),
                SearchDateRange.explicit(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-09")),
                ClosurePolicy.of(expectedParticipants = 2),
                NOW,
            )
        rooms.insert(room)
        val session = GuestSession(GuestSessionId(UUID.randomUUID()), UUID.randomUUID().toString(), NOW.plusSeconds(86400), null, NOW)
        sessions.insert(session)
        val participant = Participant.host(ParticipantId(UUID.randomUUID()), room.id, session.id, ParticipantDisplayName.of("합성 주최자"), NOW)
        participants.insert(participant)
        return room to participant
    }

    private fun submission(
        room: MeetingRoomId,
        participant: ParticipantId,
        evidence: InputEvidence? = null,
    ) = Submission.start(
        SubmissionId(UUID.randomUUID()),
        room,
        participant,
        SubmissionVersionId(UUID.randomUUID()),
        "수요일 7시 이후",
        emptyList(),
        Locale.KOREAN,
        NOW,
        evidence,
    )

    companion object {
        private val NOW = Instant.parse("2026-11-02T00:00:00Z")
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
        }

        @JvmStatic
        @AfterAll
        fun stop() = postgres.stop()
    }
}
