package com.meetme.server.shared.adapter.output.persistence

import com.meetme.server.meetingroom.adapter.output.persistence.KomapperMeetingRoomRepository
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.ClosureReason
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.participant.application.port.output.GuestSessionRepository
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.participant.domain.GuestSession
import com.meetme.server.participant.domain.Participant
import com.meetme.server.participant.domain.ParticipantDisplayName
import com.meetme.server.precheck.application.port.output.PrecheckRepository
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
import org.komapper.jdbc.JdbcDatabase
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Independent S5-F01..F05: isolated PostgreSQL, no external provider or room. */
@SpringBootTest(
    properties = [
        "meetme.reliability.enabled=false", "meetme.data-retention.enabled=false", "meetme.precheck.evidence-enabled=false",
    ],
)
class Stage5EvidenceRoomProtocolPostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var database: JdbcDatabase

    @Autowired private lateinit var defaultOffRooms: MeetingRoomRepository

    @Autowired private lateinit var prechecks: PrecheckRepository

    @Autowired private lateinit var sessions: GuestSessionRepository

    @Autowired private lateinit var participants: ParticipantRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @BeforeEach
    fun clean() = jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")

    @Test
    fun `S5-F01 F03 F04 OFF ON OFF applies only at successful new room insertion`() {
        val original = room()
        defaultOffRooms.insert(original)
        val v2 = room()
        val enabled = KomapperMeetingRoomRepository(database, jdbc, true)
        assertTrue(enabled.insertIfInviteAvailable(v2))
        val later = room()
        assertTrue(defaultOffRooms.insertIfInviteAvailable(later))

        assertEquals("PRE_SUBMIT_V1", prechecks.protocol(original.id.value))
        assertEquals("PRE_SUBMIT_V2", prechecks.protocol(v2.id.value))
        assertEquals("PRE_SUBMIT_V1", prechecks.protocol(later.id.value))
        enabled.update(original.close(ClosureReason.MANUAL, NOW.plusSeconds(1), 0))
        defaultOffRooms.update(v2.close(ClosureReason.MANUAL, NOW.plusSeconds(1), 0))
        assertEquals("PRE_SUBMIT_V1", prechecks.protocol(original.id.value), "ON must not convert an existing V1 room")
        assertEquals("PRE_SUBMIT_V2", prechecks.protocol(v2.id.value), "OFF must not downgrade an existing V2 room")
    }

    @Test
    fun `S5-F05 invite collision cannot change an existing rooms protocol`() {
        val original = room()
        defaultOffRooms.insert(original)
        val collision = room(original.inviteCode)
        assertFalse(KomapperMeetingRoomRepository(database, jdbc, true).insertIfInviteAvailable(collision))
        assertEquals("PRE_SUBMIT_V1", prechecks.protocol(original.id.value))
        assertEquals(original.id, assertNotNull(defaultOffRooms.findByInviteCode(original.inviteCode)).id)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM meeting_rooms", Int::class.java))
    }

    @Test
    fun `S5-F03 OFF startup retains original V2 evidence and frozen accepted version readers`() {
        val existing = room()
        KomapperMeetingRoomRepository(database, jdbc, true).insert(existing)
        val session = GuestSession(GuestSessionId(UUID.randomUUID()), UUID.randomUUID().toString(), NOW.plusSeconds(86400), null, NOW)
        sessions.insert(session)
        val participant =
            Participant.host(ParticipantId(UUID.randomUUID()), existing.id, session.id, ParticipantDisplayName.of("독립 합성 참여자"), NOW)
        participants.insert(participant)
        val originalText = "수요일 7시 이후"
        val evidence =
            InputEvidence(
                1,
                NOW,
                InputSupplement(UUID.randomUUID(), 1, "7시", 4, "비공개 확인 질문", "오후 7시", "PM", NOW.plusSeconds(1), NOW),
                InputInterpretation.UNRESOLVED,
                "AFTER_ONE_ANSWER_UNRESOLVED",
            )
        val original =
            Submission.start(
                SubmissionId(UUID.randomUUID()),
                existing.id,
                participant.id,
                SubmissionVersionId(UUID.randomUUID()),
                originalText,
                emptyList(),
                Locale.KOREAN,
                NOW,
                evidence,
            )
        submissions.insert(original)
        val later =
            original.revise(
                SubmissionVersionId(UUID.randomUUID()),
                "금요일 오후 8시 가능",
                emptyList(),
                Locale.KOREAN,
                NOW.plusSeconds(10),
                InputEvidence(3, NOW.plusSeconds(10), interpretation = InputInterpretation.CONFIRMED),
            )
        submissions.save(later)

        assertEquals("PRE_SUBMIT_V2", prechecks.protocol(existing.id.value))
        assertEquals(later.latest, assertNotNull(submissions.findByParticipant(participant.id)).latest)
        val frozen = submissions.findFrozenByVersionIds(existing.id, listOf(original.latest.id)).single().latest
        assertEquals(original.latest, frozen)
        assertEquals(originalText, frozen.rawText)
        assertEquals("오후 7시", frozen.evidence?.supplement?.answerText)
        assertEquals(InputInterpretation.UNRESOLVED, frozen.evidence?.interpretation)
        assertEquals("AFTER_ONE_ANSWER_UNRESOLVED", frozen.evidence?.unresolvedReason)
    }

    private fun room(
        inviteCode: InviteCode =
            InviteCode.fromEntropy(
                UUID
                    .randomUUID()
                    .toString()
                    .toByteArray()
                    .take(16)
                    .toByteArray(),
            ),
    ) = MeetingRoom.create(
        MeetingRoomId(UUID.randomUUID()),
        inviteCode,
        "독립 설정 경계 검증",
        MeetingMode.REMOTE,
        MeetingTimeZone.of("Asia/Seoul"),
        SearchDateRange.explicit(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-09")),
        ClosurePolicy.of(manualOnly = true),
        NOW,
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
