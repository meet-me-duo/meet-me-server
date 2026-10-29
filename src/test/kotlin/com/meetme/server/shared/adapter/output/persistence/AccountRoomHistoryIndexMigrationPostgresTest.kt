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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Independent candidate V20 contract: data/owner constraints survive an account-history access path. */
class AccountRoomHistoryIndexMigrationPostgresTest {
    @BeforeEach
    fun resetSchema() {
        execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public")
    }

    @Test
    fun `fresh latest migration supports account history without narrowing owners and validates on rerun`() {
        val latest = flyway()
        latest.migrate()
        latest.validate()
        assertAccountHistoryAccessPath()
        assertEquals(0, latest.migrate().migrationsExecuted)
    }

    @Test
    fun `upgrade from nineteen preserves every fixture value and original ownership constraints`() {
        flyway("19").migrate()
        val fixture = seed()
        val before = snapshot()
        val latest = flyway()
        assertEquals(1, latest.migrate().migrationsExecuted)
        latest.validate()
        assertEquals(before, snapshot(), "Index migration must not rewrite any account, guest, room, participant or submission value")
        assertOriginalConstraints(fixture)
        assertAccountHistoryAccessPath()
    }

    @Test
    fun `claimed owner enters account lookup while other rooms and saved submission remain unchanged`() {
        flyway("19").migrate()
        val fixture = seed()
        flyway().migrate()
        val beforeSubmission = rows("SELECT to_jsonb(v)::text FROM submission_versions v ORDER BY id")
        val beforeHead = rows("SELECT to_jsonb(h)::text FROM submission_heads h ORDER BY id")
        execute(
            "UPDATE participants SET guest_session_id=NULL,user_id='${fixture.accountA}' WHERE id='${fixture.claimParticipant}'",
        )
        assertEquals(fixture.accountA.toString(), scalar("SELECT user_id::text FROM participants WHERE id='${fixture.claimParticipant}'"))
        assertEquals(null, scalar("SELECT guest_session_id::text FROM participants WHERE id='${fixture.claimParticipant}'"))
        assertEquals(beforeSubmission, rows("SELECT to_jsonb(v)::text FROM submission_versions v ORDER BY id"))
        assertEquals(beforeHead, rows("SELECT to_jsonb(h)::text FROM submission_heads h ORDER BY id"))
        assertEquals(
            fixture.guestA.toString(),
            scalar("SELECT guest_session_id::text FROM participants WHERE id='${fixture.untouchedGuestParticipant}'"),
        )
        assertEquals(null, scalar("SELECT user_id::text FROM participants WHERE id='${fixture.untouchedGuestParticipant}'"))
        assertEquals("3", scalar("SELECT count(*)::text FROM participants WHERE user_id='${fixture.accountA}'"))
        reject("23505", member(fixture.claimRoom, null, fixture.accountA))
        assertAccountHistoryAccessPath()
    }

    private fun assertAccountHistoryAccessPath() {
        // The migration's purpose is a usable user-leading lookup for both roles, not a specific SQL spelling.
        val indexes =
            rows(
                """
                SELECT c.relname FROM pg_index i
                JOIN pg_class t ON t.oid=i.indrelid
                JOIN pg_class c ON c.oid=i.indexrelid
                JOIN pg_am am ON am.oid=c.relam
                JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=i.indkey[0]
                WHERE t.oid='participants'::regclass AND a.attname='user_id'
                  AND am.amname='btree' AND i.indisvalid AND i.indisready AND i.indislive
                  AND (i.indpred IS NULL OR pg_get_expr(i.indpred,i.indrelid)='(user_id IS NOT NULL)')
                """.trimIndent(),
            )
        assertTrue(indexes.isNotEmpty(), "Need a valid user-leading access path covering account HOST and MEMBER, not just HOST")
        // Keep the original single-host uniqueness. A performance index is not its replacement.
        val singleHost =
            scalar(
                """
                SELECT count(*)::text FROM pg_index i JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=i.indkey[0]
                WHERE i.indrelid='participants'::regclass AND i.indisunique AND i.indisvalid AND a.attname='room_id'
                  AND pg_get_expr(i.indpred,i.indrelid) LIKE '%role%HOST%'
                """.trimIndent(),
            )
        assertNotNull(singleHost)
        assertTrue(singleHost.toInt() > 0)
    }

    private fun assertOriginalConstraints(f: Fixture) {
        reject("23514", member(f.roomA, f.guestA, f.accountA))
        reject("23514", member(f.roomA, null, null))
        reject("23503", member(f.roomA, null, UUID.randomUUID()))
        reject("23503", member(UUID.randomUUID(), null, f.accountA))
        reject("23503", member(f.roomA, UUID.randomUUID(), null))
        reject("23505", member(f.roomA, null, f.accountA))
        reject("23505", member(f.roomA, f.guestA, null))
        val otherHostGuest = UUID.randomUUID()
        execute(
            "INSERT INTO guest_browser_sessions(id,credential_digest,created_at,expires_at) VALUES " +
                "('$otherHostGuest','$otherHostGuest','2026-10-01T00:00Z','2026-11-01T00:00Z')",
        )
        reject("23505", member(f.roomA, otherHostGuest, null, "HOST"))
        assertEquals("2", scalar("SELECT count(*)::text FROM participants WHERE user_id='${f.accountA}'"))
        assertEquals("2", scalar("SELECT count(*)::text FROM participants WHERE guest_session_id='${f.guestA}'"))
    }

    private fun seed(): Fixture {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val guestA = UUID.randomUUID()
        val guestB = UUID.randomUUID()
        val roomA = room()
        val roomB = room()
        val claimRoom = room()
        execute(
            """
            INSERT INTO account_users(id,display_name,created_at) VALUES
              ('$a','same synthetic name','2026-10-28T00:00Z'),('$b','same synthetic name','2026-10-28T00:00Z');
            INSERT INTO guest_browser_sessions(id,credential_digest,created_at,expires_at) VALUES
              ('$guestA','synthetic-a','2026-10-01T00:00Z','2026-11-01T00:00Z'),
              ('$guestB','synthetic-b','2026-10-01T00:00Z','2026-11-01T00:00Z');
            """.trimIndent(),
        )
        execute(member(roomA, null, a, "HOST"))
        execute(member(roomA, null, b))
        execute(member(roomB, null, b, "HOST"))
        execute(member(roomB, null, a))
        val untouchedGuest = UUID.randomUUID()
        execute(member(roomA, guestA, null, id = untouchedGuest))
        execute(member(roomA, guestB, null))
        val claimParticipant = UUID.randomUUID()
        execute(member(claimRoom, guestA, null, "HOST", claimParticipant))
        val head = UUID.randomUUID()
        val version = UUID.randomUUID()
        execute(
            """
            INSERT INTO submission_heads(id,room_id,participant_id) VALUES ('$head','$claimRoom','$claimParticipant');
            INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at)
              VALUES ('$version','$head',1,'synthetic saved original','ko-KR','2026-10-28T00:00Z');
            UPDATE submission_heads SET latest_version_id='$version' WHERE id='$head';
            """.trimIndent(),
        )
        return Fixture(a, guestA, guestB, roomA, claimRoom, claimParticipant, untouchedGuest)
    }

    private fun room(): UUID {
        val id = UUID.randomUUID()
        execute(
            """
            INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,
              search_end_date,search_range_source,manual_only,collection_status,created_at)
            VALUES ('$id','${id.toString().replace("-", "").take(22)}','synthetic history','EITHER','Asia/Seoul',
              '2026-10-28','2026-11-10','DEFAULTED',true,'COLLECTING','2026-10-28T00:00Z')
            """.trimIndent(),
        )
        return id
    }

    private fun member(
        room: UUID,
        guest: UUID?,
        account: UUID?,
        role: String = "MEMBER",
        id: UUID = UUID.randomUUID(),
    ): String =
        "INSERT INTO participants(id,room_id,guest_session_id,user_id,role,display_name,joined_at) VALUES " +
            "('$id','$room',${guest?.let { "'$it'" } ?: "NULL"},${account?.let { "'$it'" } ?: "NULL"}," +
            "'$role','synthetic participant','2026-10-28T00:00Z')"

    private fun snapshot(): Map<String, List<String>> =
        listOf("account_users", "guest_browser_sessions", "meeting_rooms", "participants", "submission_heads", "submission_versions")
            .associateWith { table -> rows("SELECT to_jsonb(r)::text FROM $table r ORDER BY id") }

    private fun reject(
        state: String,
        sql: String,
    ) {
        assertEquals(state, assertThrows<SQLException> { execute(sql) }.sqlState)
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

    private fun scalar(sql: String): String? =
        connection().use { c ->
            c.createStatement().use { statement ->
                statement.executeQuery(sql).use { result -> if (result.next()) result.getString(1) else null }
            }
        }

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun flyway(target: String? = null): Flyway {
        val config = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        if (target != null) config.target(target)
        return config.load()
    }

    private data class Fixture(
        val accountA: UUID,
        val guestA: UUID,
        val guestB: UUID,
        val roomA: UUID,
        val claimRoom: UUID,
        val claimParticipant: UUID,
        val untouchedGuestParticipant: UUID,
    )

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine")

        init {
            postgres.start()
        }

        @JvmStatic @AfterAll
        fun stopContainer() {
            postgres.stop()
        }
    }
}
