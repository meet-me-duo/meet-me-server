package com.meetme.server.adapter.output.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnonymousRoomLifecycleMigrationTest {
    @BeforeEach
    fun recreatePublicSchema() {
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP SCHEMA public CASCADE")
                statement.execute("CREATE SCHEMA public")
            }
        }
    }

    @Test
    fun `V2까지 최초 마이그레이션하고 검증한다`() {
        val flyway = flyway()

        flyway.migrate()

        assertEquals(
            "2",
            flyway
                .info()
                .current()
                .version.version,
        )
        flyway.validate()
    }

    @Test
    fun `데이터가 있는 V1을 V2로 올리면 초대 코드와 표시 이름을 유효하게 채운다`() {
        val v1Flyway = flyway(target = "1")
        v1Flyway.migrate()
        insertV1RoomWithParticipants()

        val latestFlyway = flyway()
        latestFlyway.migrate()

        val inviteCodes = queryStrings("SELECT invite_code FROM meeting_rooms")
        val displayNames = queryStrings("SELECT display_name FROM participants ORDER BY joined_at")
        assertEquals(2, inviteCodes.size)
        assertEquals(2, inviteCodes.distinct().size)
        assertTrue(inviteCodes.all { it.matches(Regex("^[A-Za-z0-9_-]{22}$")) })
        assertEquals(3, displayNames.size)
        assertTrue(displayNames.all { it.isNotBlank() && it.length <= 50 })
        latestFlyway.validate()
    }

    @Test
    fun `V2는 방 초대 코드 중복을 거부한다`() {
        flyway().migrate()
        val sharedInviteCode = "abcdefghijklmnopqrstuv"

        insertRoom(UUID.randomUUID(), sharedInviteCode)

        val failure =
            kotlin
                .runCatching {
                    insertRoom(UUID.randomUUID(), sharedInviteCode)
                }.exceptionOrNull()
        assertTrue(failure != null)
    }

    @Test
    fun `V2는 참여자 표시 이름 누락을 거부한다`() {
        flyway().migrate()
        val roomId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        insertRoom(roomId, "abcdefghijklmnopqrstuv")
        insertSession(sessionId, "a".repeat(64))

        val failure =
            kotlin
                .runCatching {
                    connection().use { connection ->
                        connection
                            .prepareStatement(
                                """
                                INSERT INTO participants (id, room_id, guest_session_id, role, joined_at, display_name)
                                VALUES (?, ?, ?, 'MEMBER', TIMESTAMPTZ '2026-09-19 00:00:00Z', NULL)
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setObject(1, UUID.randomUUID())
                                statement.setObject(2, roomId)
                                statement.setObject(3, sessionId)
                                statement.executeUpdate()
                            }
                    }
                }.exceptionOrNull()
        assertTrue(failure != null)
    }

    private fun insertV1RoomWithParticipants() {
        val firstRoomId = UUID.randomUUID()
        val secondRoomId = UUID.randomUUID()
        val hostSessionId = UUID.randomUUID()
        val memberSessionId = UUID.randomUUID()
        val secondHostSessionId = UUID.randomUUID()
        insertV1Room(firstRoomId)
        insertV1Room(secondRoomId)
        insertSession(hostSessionId, "a".repeat(64))
        insertSession(memberSessionId, "b".repeat(64))
        insertSession(secondHostSessionId, "c".repeat(64))
        insertV1Participant(firstRoomId, hostSessionId, "HOST", "2026-09-19 00:00:00Z")
        insertV1Participant(firstRoomId, memberSessionId, "MEMBER", "2026-09-19 00:01:00Z")
        insertV1Participant(secondRoomId, secondHostSessionId, "HOST", "2026-09-19 00:02:00Z")
    }

    private fun insertV1Room(roomId: UUID) {
        connection().use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO meeting_rooms (
                        id, purpose, duration_minutes, meeting_mode, time_zone_id,
                        search_start_date, search_end_date, search_range_source,
                        expected_participants, submission_deadline, manual_only,
                        collection_status, closure_reason, closed_at, created_at, version
                    ) VALUES (
                        ?, '기존 방', 60, 'EITHER', 'Asia/Seoul',
                        DATE '2026-09-20', DATE '2026-09-27', 'HOST_SPECIFIED',
                        NULL, NULL, TRUE, 'COLLECTING', NULL, NULL,
                        TIMESTAMPTZ '2026-09-19 00:00:00Z', 0
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, roomId)
                    statement.executeUpdate()
                }
        }
    }

    private fun insertRoom(
        roomId: UUID,
        inviteCode: String,
    ) {
        connection().use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO meeting_rooms (
                        id, purpose, duration_minutes, meeting_mode, time_zone_id,
                        search_start_date, search_end_date, search_range_source,
                        expected_participants, submission_deadline, manual_only,
                        collection_status, closure_reason, closed_at, created_at, version, invite_code
                    ) VALUES (
                        ?, '새 방', 60, 'EITHER', 'Asia/Seoul',
                        DATE '2026-09-20', DATE '2026-09-27', 'HOST_SPECIFIED',
                        NULL, NULL, TRUE, 'COLLECTING', NULL, NULL,
                        TIMESTAMPTZ '2026-09-19 00:00:00Z', 0, ?
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, roomId)
                    statement.setString(2, inviteCode)
                    statement.executeUpdate()
                }
        }
    }

    private fun insertSession(
        sessionId: UUID,
        digest: String,
    ) {
        connection().use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO guest_browser_sessions (id, credential_digest, expires_at, revoked_at, created_at)
                    VALUES (?, ?, TIMESTAMPTZ '2026-10-19 00:00:00Z', NULL, TIMESTAMPTZ '2026-09-19 00:00:00Z')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, sessionId)
                    statement.setString(2, digest)
                    statement.executeUpdate()
                }
        }
    }

    private fun insertV1Participant(
        roomId: UUID,
        sessionId: UUID,
        role: String,
        joinedAt: String,
    ) {
        connection().use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO participants (id, room_id, guest_session_id, role, joined_at)
                    VALUES (?, ?, ?, ?, ?::timestamptz)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, roomId)
                    statement.setObject(3, sessionId)
                    statement.setString(4, role)
                    statement.setString(5, joinedAt)
                    statement.executeUpdate()
                }
        }
    }

    private fun queryStrings(sql: String): List<String> =
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    buildList {
                        while (result.next()) {
                            add(result.getString(1))
                        }
                    }
                }
            }
        }

    private fun flyway(target: String? = null): Flyway {
        val configuration =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        if (target != null) {
            configuration.target(target)
        }
        return configuration.load()
    }

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine")

        init {
            postgres.start()
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() {
            postgres.stop()
        }
    }
}
