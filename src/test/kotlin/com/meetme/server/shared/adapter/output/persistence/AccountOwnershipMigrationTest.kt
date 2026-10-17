package com.meetme.server.shared.adapter.output.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Issue #109: migrate existing guest ownership without linking by profile attributes. */
class AccountOwnershipMigrationTest {
    @Test
    fun `account migration preserves legacy guest and submission while enforcing exclusive unique owners`() {
        flyway("10").migrate()
        val room = UUID.randomUUID()
        val guest = UUID.randomUUID()
        val participant = UUID.randomUUID()
        val head = UUID.randomUUID()
        val version = UUID.randomUUID()
        execute(
            """
            INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,
                search_end_date,search_range_source,manual_only,collection_status,created_at)
            VALUES ('$room','abcdefghijklmnopqrstuv','legacy','EITHER','Asia/Seoul',
                '2026-10-17','2026-10-31','DEFAULTED',true,'COLLECTING','2026-10-17T00:00:00Z');
            INSERT INTO guest_browser_sessions(id,credential_digest,created_at,expires_at)
            VALUES ('$guest','legacy-digest','2026-10-17T00:00:00Z','2026-11-16T00:00:00Z');
            INSERT INTO participants(id,room_id,guest_session_id,role,display_name,joined_at)
            VALUES ('$participant','$room','$guest','HOST','same profile','2026-10-17T00:00:00Z');
            INSERT INTO submission_heads(id,room_id,participant_id) VALUES ('$head','$room','$participant');
            INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at)
            VALUES ('$version','$head',1,'legacy original','ko-KR','2026-10-17T00:00:00Z');
            UPDATE submission_heads SET latest_version_id='$version' WHERE id='$head';
            """.trimIndent(),
        )

        val latest = flyway()
        latest.migrate()

        assertEquals("account_users", scalar("SELECT to_regclass('public.account_users')::text"))
        assertEquals("account_identities", scalar("SELECT to_regclass('public.account_identities')::text"))
        assertEquals(
            "YES",
            scalar("SELECT is_nullable FROM information_schema.columns WHERE table_name='participants' AND column_name='guest_session_id'"),
        )
        assertEquals(guest.toString(), scalar("SELECT guest_session_id::text FROM participants WHERE id='$participant'"))
        assertEquals(null, scalar("SELECT user_id::text FROM participants WHERE id='$participant'"))
        assertEquals(version.toString(), scalar("SELECT latest_version_id::text FROM submission_heads WHERE participant_id='$participant'"))
        assertEquals("legacy original", scalar("SELECT raw_text FROM submission_versions WHERE id='$version'"))

        val account = UUID.randomUUID()
        val anotherAccount = UUID.randomUUID()
        execute(
            "INSERT INTO account_users(id,display_name,created_at) VALUES ('$account','same profile',now()),('$anotherAccount','same profile',now())",
        )
        execute(
            "INSERT INTO account_identities(provider,subject,user_id) VALUES ('GOOGLE','shared-subject','$account'),('KAKAO','shared-subject','$anotherAccount')",
        )
        assertEquals("2", scalar("SELECT count(*)::text FROM account_identities"))
        rejectConstraint(
            "23505",
            "INSERT INTO account_identities(provider,subject,user_id) VALUES ('GOOGLE','shared-subject','$anotherAccount')",
        )
        assertEquals(null, scalar("SELECT user_id::text FROM participants WHERE id='$participant'"))

        rejectConstraint("23514", memberInsert(room, guest, account))
        rejectConstraint("23514", memberInsert(room, null, null))
        rejectConstraint("23503", memberInsert(room, null, UUID.randomUUID()))
        rejectConstraint("23505", memberInsert(room, guest, null))
        execute(memberInsert(room, null, account))
        rejectConstraint("23505", memberInsert(room, null, account))

        val legacyOwnerCount =
            scalar("SELECT count(*)::text FROM participants WHERE id='$participant' AND guest_session_id='$guest' AND user_id IS NULL")
        assertEquals("1", legacyOwnerCount)
        latest.validate()
    }

    private fun memberInsert(
        room: UUID,
        guest: UUID?,
        account: UUID?,
    ): String =
        "INSERT INTO participants(id,room_id,guest_session_id,user_id,role,display_name,joined_at) " +
            "VALUES ('${UUID.randomUUID()}','$room',${guest?.let {
                "'$it'"
            } ?: "NULL"},${account?.let { "'$it'" } ?: "NULL"},'MEMBER','member',now())"

    private fun rejectConstraint(
        state: String,
        sql: String,
    ) {
        val failure = assertThrows<SQLException> { execute(sql) }
        assertEquals(state, failure.sqlState)
        assertTrue(failure.message.orEmpty().contains("ERROR"))
    }

    private fun execute(sql: String) {
        connection().use { connection -> connection.createStatement().use { it.execute(sql) } }
    }

    private fun scalar(sql: String): String? =
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result -> if (result.next()) result.getString(1) else null }
            }
        }

    private fun flyway(target: String? = null): Flyway {
        val configuration = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        if (target != null) configuration.target(target)
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
