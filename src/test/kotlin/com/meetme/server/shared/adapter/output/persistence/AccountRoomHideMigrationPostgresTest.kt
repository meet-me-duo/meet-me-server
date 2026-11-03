package com.meetme.server.shared.adapter.output.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals

/** #197: adding an archive preference preserves existing records and cannot hide guest relationships. */
class AccountRoomHideMigrationPostgresTest {
    @BeforeEach
    fun resetSchema() = execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public")

    @Test
    fun `upgrade preserves existing account and guest data with visible NULL preference and claim keeps it visible`() {
        flyway("22").migrate()
        val room = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val claimant = UUID.randomUUID()
        val guest = UUID.randomUUID()
        val hostParticipant = UUID.randomUUID()
        val guestParticipant = UUID.randomUUID()
        execute(
            """
            INSERT INTO account_users(id,display_name,created_at) VALUES ('$owner','host',now()),('$claimant','claimant',now());
            INSERT INTO guest_browser_sessions(id,credential_digest,created_at,expires_at)
                VALUES ('$guest','synthetic-guest',now(),now()+interval '30 days');
            INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
                search_range_source,manual_only,collection_status,created_at)
                VALUES ('$room','AAAAAAAAAAAAAAAAAAAAAA','synthetic','REMOTE','Asia/Seoul','2026-10-17','2026-10-31',
                'DEFAULTED',true,'COLLECTING',now());
            INSERT INTO participants(id,room_id,user_id,role,display_name,joined_at)
                VALUES ('$hostParticipant','$room','$owner','HOST','host',now());
            INSERT INTO participants(id,room_id,guest_session_id,role,display_name,joined_at)
                VALUES ('$guestParticipant','$room','$guest','MEMBER','guest',now());
            """.trimIndent(),
        )
        val before = rows("SELECT to_jsonb(p)::text FROM participants p ORDER BY id")
        val roomBefore = rows("SELECT to_jsonb(r)::text FROM meeting_rooms r")
        val latest = flyway()
        latest.migrate()
        assertPreferenceColumn()
        assertEquals(before, rows("SELECT (to_jsonb(p)-'account_archive_hidden_at')::text FROM participants p ORDER BY id"))
        assertEquals(roomBefore, rows("SELECT to_jsonb(r)::text FROM meeting_rooms r"))
        assertEquals("2", scalar("SELECT count(*)::text FROM participants WHERE account_archive_hidden_at IS NULL"))
        execute("UPDATE participants SET guest_session_id=NULL,user_id='$claimant' WHERE id='$guestParticipant'")
        assertEquals(
            "1",
            scalar(
                "SELECT count(*)::text FROM participants WHERE id='$guestParticipant' AND user_id='$claimant' AND account_archive_hidden_at IS NULL",
            ),
        )
        execute("UPDATE participants SET account_archive_hidden_at=now() WHERE id='$hostParticipant'")
        latest.validate()
        assertEquals(0, latest.migrate().migrationsExecuted)
        assertEquals(
            "1",
            scalar("SELECT count(*)::text FROM participants WHERE id='$hostParticipant' AND account_archive_hidden_at IS NOT NULL"),
        )
    }

    @Test
    fun `fresh migration prevents guest archive hiding while permitting account preferences`() {
        flyway().migrate()
        assertPreferenceColumn()
        val guest = UUID.randomUUID()
        val room = UUID.randomUUID()
        val participant = UUID.randomUUID()
        execute(
            """
            INSERT INTO guest_browser_sessions(id,credential_digest,created_at,expires_at)
                VALUES ('$guest','synthetic-guest',now(),now()+interval '30 days');
            INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
                search_range_source,manual_only,collection_status,created_at)
                VALUES ('$room','AAAAAAAAAAAAAAAAAAAAAA','synthetic','REMOTE','Asia/Seoul','2026-10-17','2026-10-31',
                'DEFAULTED',true,'COLLECTING',now());
            INSERT INTO participants(id,room_id,guest_session_id,role,display_name,joined_at)
                VALUES ('$participant','$room','$guest','HOST','guest',now());
            """.trimIndent(),
        )
        assertEquals(
            "23514",
            assertThrows<SQLException> {
                execute("UPDATE participants SET account_archive_hidden_at=now() WHERE id='$participant'")
            }.sqlState,
        )
        assertEquals("1", scalar("SELECT count(*)::text FROM participants WHERE account_archive_hidden_at IS NULL"))
    }

    private fun assertPreferenceColumn() {
        assertEquals(
            "1",
            scalar(
                "SELECT count(*)::text FROM information_schema.columns WHERE table_schema='public' AND table_name='participants' AND column_name='account_archive_hidden_at' AND data_type='timestamp with time zone' AND is_nullable='YES'",
            ),
            "Missing persistent nullable account archive preference",
        )
    }

    private fun execute(sql: String) {
        connection().use { c -> c.createStatement().use { it.execute(sql) } }
    }

    private fun rows(sql: String): List<String> =
        connection().use { c ->
            c.createStatement().use { statement ->
                statement.executeQuery(sql).use { result -> buildList { while (result.next()) add(result.getString(1)) } }
            }
        }

    private fun scalar(sql: String): String? = rows(sql).firstOrNull()

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun flyway(target: String? = null): Flyway {
        val config = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        if (target != null) config.target(target)
        return config.load()
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
