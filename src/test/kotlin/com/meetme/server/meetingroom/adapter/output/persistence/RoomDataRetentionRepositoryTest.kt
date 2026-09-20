package com.meetme.server.meetingroom.adapter.output.persistence

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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest
class RoomDataRetentionRepositoryTest {
    @Autowired
    private lateinit var repository: RoomDataRetentionRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE meeting_rooms, guest_browser_sessions CASCADE")
    }

    @Test
    fun `30일이 지난 방의 참여 데이터와 만료된 미사용 게스트 세션을 삭제한다`() {
        val now = Instant.parse("2026-09-20T12:00:00Z")
        val expiredSession = insertSession(now.minusSeconds(40L * 86_400), now.minusSeconds(10L * 86_400))
        val oldRoom = insertRoom(now.minusSeconds(31L * 86_400), expiredSession)
        val activeSession = insertSession(now.minusSeconds(5L * 86_400), now.plusSeconds(25L * 86_400))
        val activeRoom = insertRoom(now.minusSeconds(5L * 86_400), activeSession)

        assertEquals(1, repository.deleteExpiredRooms(now.minusSeconds(30L * 86_400), 100, now))

        assertEquals(0, count("meeting_rooms", oldRoom))
        assertEquals(1, count("meeting_rooms", activeRoom))
        assertEquals(0, count("guest_browser_sessions", expiredSession))
        assertEquals(1, count("guest_browser_sessions", activeSession))
    }

    private fun insertSession(
        createdAt: Instant,
        expiresAt: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO guest_browser_sessions(id, credential_digest, expires_at, created_at)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            id,
            UUID.randomUUID().toString(),
            expiresAt.atOffset(ZoneOffset.UTC),
            createdAt.atOffset(ZoneOffset.UTC),
        )
        return id
    }

    private fun insertRoom(
        createdAt: Instant,
        guestSessionId: UUID,
    ): UUID {
        val roomId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO meeting_rooms(
                id, invite_code, purpose, meeting_mode, time_zone_id, search_start_date, search_end_date,
                search_range_source, expected_participants, submission_deadline, manual_only,
                collection_status, closure_reason, closed_at, created_at, version
            ) VALUES (?, ?, '보관 테스트', 'REMOTE', 'Asia/Seoul', DATE '2026-09-20', DATE '2026-09-27',
                'HOST_SPECIFIED', NULL, NULL, TRUE, 'COLLECTING', NULL, NULL, ?, 0)
            """.trimIndent(),
            roomId,
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(22),
            OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
        )
        jdbcTemplate.update(
            "INSERT INTO participants(id, room_id, guest_session_id, role, display_name, joined_at) VALUES (?, ?, ?, 'HOST', '호스트', ?)",
            UUID.randomUUID(),
            roomId,
            guestSessionId,
            OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
        )
        return roomId
    }

    private fun count(
        table: String,
        id: UUID,
    ): Int = requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM $table WHERE id = ?", Int::class.java, id))

    companion object {
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
        fun stopContainer() = postgres.stop()
    }
}
