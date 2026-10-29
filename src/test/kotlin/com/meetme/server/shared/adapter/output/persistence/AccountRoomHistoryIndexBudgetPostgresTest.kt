package com.meetme.server.shared.adapter.output.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Independent V20 safety contract: finite DDL budgets, transactional failure and retry. */
class AccountRoomHistoryIndexBudgetPostgresTest {
    @BeforeEach
    fun prepareNineteen() {
        execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public")
        flyway("19").migrate()
        execute(
            """
            INSERT INTO account_users(id,display_name,created_at)
              VALUES ('00000000-0000-0000-0000-000000000001','synthetic owner','2026-10-28T00:00Z');
            INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,
              search_end_date,search_range_source,manual_only,collection_status,created_at)
              VALUES ('00000000-0000-0000-0000-000000000002','budgetfixture000000001','synthetic room',
              'EITHER','Asia/Seoul','2026-10-28','2026-11-10','DEFAULTED',true,'COLLECTING','2026-10-28T00:00Z');
            INSERT INTO participants(id,room_id,user_id,role,display_name,joined_at)
              VALUES ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002',
              '00000000-0000-0000-0000-000000000001','HOST','synthetic owner','2026-10-28T00:00Z');
            """.trimIndent(),
        )
    }

    @Test
    fun `actual index DDL has finite lock and statement budgets that do not leak into pooled sessions`() {
        execute(
            """
            CREATE TABLE index_budget_observations(backend integer,lock_ms bigint,statement_ms bigint);
            CREATE FUNCTION observe_index_budget() RETURNS event_trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
              INSERT INTO index_budget_observations SELECT pg_backend_pid(),
                (SELECT setting::bigint FROM pg_settings WHERE name='lock_timeout'),
                (SELECT setting::bigint FROM pg_settings WHERE name='statement_timeout');
            END ${'$'}${'$'};
            CREATE EVENT TRIGGER observe_index_budget ON ddl_command_start
              WHEN TAG IN ('CREATE INDEX') EXECUTE FUNCTION observe_index_budget();
            """.trimIndent(),
        )
        val before = snapshot()
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
                minimumIdle = 2
                connectionTimeout = 10000
                connectionInitSql = "SET lock_timeout='0'; SET statement_timeout='0'"
            },
        ).use { pool ->
            assertEquals(
                1,
                Flyway
                    .configure()
                    .dataSource(pool)
                    .load()
                    .migrate()
                    .migrationsExecuted,
            )
            val observations = rows("SELECT backend || ',' || lock_ms || ',' || statement_ms FROM index_budget_observations")
            assertTrue(observations.isNotEmpty(), "Observe the actual CREATE INDEX execution, not SQL spelling")
            for (observation in observations) {
                val values = observation.split(',').map(String::toLong)
                assertTrue(values[1] in 1L..5000L, "Actual index DDL must have a positive lock budget at most 5000ms: $observation")
                assertTrue(
                    values[2] in 1L..120000L,
                    "Actual index DDL must have a positive statement budget at most 120000ms: $observation",
                )
            }
            pool.connection.use { first ->
                pool.connection.use { second ->
                    val sessions = listOf(first, second)
                    val backends = sessions.map { scalar(it, "SELECT pg_backend_pid()") }
                    assertTrue(observations.all { it.substringBefore(',') in backends }, "Inspect the same physical migration session")
                    for (session in sessions) {
                        assertEquals("0", scalar(session, "SELECT setting FROM pg_settings WHERE name='lock_timeout'"))
                        assertEquals("0", scalar(session, "SELECT setting FROM pg_settings WHERE name='statement_timeout'"))
                    }
                }
            }
        }
        assertEquals(before, snapshot())
        flyway().validate()
    }

    @Test
    fun `blocked index creation times out without partial state and succeeds after blocker release`() {
        val before = snapshot()
        val history = rows("SELECT to_jsonb(h)::text FROM flyway_schema_history h ORDER BY installed_rank")
        val indexes = rows("SELECT indexname || ':' || indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY indexname")
        val executor = Executors.newSingleThreadExecutor()
        try {
            connection().use { blocker ->
                blocker.autoCommit = false
                blocker.createStatement().use { it.execute("LOCK TABLE participants IN ACCESS EXCLUSIVE MODE") }
                val migration = executor.submit<Throwable?> { runCatching { flyway().migrate() }.exceptionOrNull() }
                var exceededBudget = false
                val failure =
                    try {
                        migration.get(12, TimeUnit.SECONDS)
                    } catch (_: TimeoutException) {
                        exceededBudget = true
                        null
                    } finally {
                        blocker.rollback()
                    }
                // Release even a broken unbounded migration, then join it before assertions or fixture cleanup.
                migration.get(15, TimeUnit.SECONDS)
                assertFalse(
                    exceededBudget,
                    "Blocked V20 must fail within its finite lock budget; harness released the blocker after 12 seconds",
                )
                assertNotNull(failure, "A blocked index must fail rather than record migration success")
                val states = generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().map { it.sqlState }.toList()
                assertTrue("55P03" in states, "Failure must be PostgreSQL lock timeout, not fixture or connection failure: $states")
            }
            assertEquals(before, snapshot(), "Failed DDL must preserve every seeded business value")
            assertEquals(history, rows("SELECT to_jsonb(h)::text FROM flyway_schema_history h ORDER BY installed_rank"))
            assertEquals(indexes, rows("SELECT indexname || ':' || indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY indexname"))
            assertEquals("0", rows("SELECT count(*)::text FROM pg_class WHERE relname='ix_account_rooms'").single())
            val retry = flyway()
            assertEquals(1, retry.migrate().migrationsExecuted)
            retry.validate()
            assertEquals(before, snapshot())
            assertEquals(
                "1",
                rows(
                    "SELECT count(*)::text FROM pg_index WHERE indexrelid='ix_account_rooms'::regclass AND indisvalid AND indisready",
                ).single(),
            )
            assertEquals(0, retry.migrate().migrationsExecuted)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(15, TimeUnit.SECONDS)
        }
    }

    private fun snapshot(): Map<String, List<String>> =
        listOf("account_users", "meeting_rooms", "participants")
            .associateWith { table -> rows("SELECT to_jsonb(r)::text FROM $table r ORDER BY id") }

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun execute(sql: String) {
        connection().use { c -> c.createStatement().use { it.execute(sql) } }
    }

    private fun rows(sql: String): List<String> =
        connection().use { c ->
            c.createStatement().use { statement ->
                statement.executeQuery(sql).use { result -> buildList { while (result.next()) add(result.getString(1)) } }
            }
        }

    private fun scalar(
        connection: Connection,
        sql: String,
    ): String =
        connection.createStatement().use { s ->
            s.executeQuery(sql).use { r ->
                check(r.next())
                r.getString(1)
            }
        }

    private fun flyway(target: String? = null): Flyway {
        val config = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        if (target != null) config.target(target)
        return config.load()
    }

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
