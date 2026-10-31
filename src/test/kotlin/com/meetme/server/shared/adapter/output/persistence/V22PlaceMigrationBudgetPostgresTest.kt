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

/** Independent V22 safety oracle: bounded DDL, atomic failure/cancellation, unchanged history and safe retry. */
class V22PlaceMigrationBudgetPostgresTest {
    @BeforeEach
    fun prepareHistoricalSchema() {
        execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public")
        flyway("21").migrate()
        execute(
            """
            INSERT INTO account_users(id,display_name,created_at)
              VALUES ('00000000-0000-0000-0000-000000000001','synthetic owner','2026-10-31T00:00Z');
            INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,
              search_end_date,search_range_source,manual_only,collection_status,created_at)
              VALUES ('00000000-0000-0000-0000-000000000002','v22budgetfixture000001','synthetic room',
              'IN_PERSON','Asia/Seoul','2026-10-31','2026-11-10','DEFAULTED',true,'COLLECTING','2026-10-31T00:00Z');
            INSERT INTO participants(id,room_id,user_id,role,display_name,joined_at)
              VALUES ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002',
              '00000000-0000-0000-0000-000000000001','HOST','synthetic owner','2026-10-31T00:00Z');
            """.trimIndent(),
        )
    }

    @Test
    fun `V22B01 actual table DDL has finite budgets without leaking them to pooled sessions`() {
        execute(
            """
            CREATE TABLE v22_budget_observations(backend integer,lock_ms bigint,statement_ms bigint);
            CREATE FUNCTION observe_v22_budget() RETURNS event_trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
              INSERT INTO v22_budget_observations SELECT pg_backend_pid(),
                (SELECT setting::bigint FROM pg_settings WHERE name='lock_timeout'),
                (SELECT setting::bigint FROM pg_settings WHERE name='statement_timeout');
            END ${'$'}${'$'};
            CREATE EVENT TRIGGER observe_v22_budget ON ddl_command_start
              WHEN TAG IN ('ALTER TABLE') EXECUTE FUNCTION observe_v22_budget();
            """.trimIndent(),
        )
        val before = businessSnapshot()
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
                    .target("22")
                    .load()
                    .migrate()
                    .migrationsExecuted,
            )
            val observations = rows("SELECT backend || ',' || lock_ms || ',' || statement_ms FROM v22_budget_observations")
            assertTrue(observations.isNotEmpty(), "Inspect executed DDL through PostgreSQL, rather than SQL spelling")
            for (observation in observations) {
                val values = observation.split(',').map(String::toLong)
                assertTrue(values[1] in 1L..5000L, "Actual V22 lock budget must be finite and at most 5000ms: $observation")
                assertTrue(values[2] in 1L..30000L, "Actual V22 statement budget must be finite and at most 30000ms: $observation")
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
        assertEquals(before, businessSnapshot())
        flyway().validate()
    }

    @Test
    fun `V22B02 lock timeout rolls back earlier DDL and history then retry preserves old values`() {
        assertAtomicBlockedMigration(cancelBackend = false, expectedState = "55P03")
    }

    @Test
    fun `V22B03 explicit cancellation rolls back earlier DDL and history then retry preserves old values`() {
        assertAtomicBlockedMigration(cancelBackend = true, expectedState = "57014")
    }

    private fun assertAtomicBlockedMigration(
        cancelBackend: Boolean,
        expectedState: String,
    ) {
        val business = businessSnapshot()
        val history = historicalRows()
        val schema = targetSchema()
        val executor = Executors.newSingleThreadExecutor()
        try {
            connection().use { blocker ->
                blocker.autoCommit = false
                // The second target is blocked: a migration may already have altered the first table.
                blocker.createStatement().use { it.execute("LOCK TABLE recommendation_variant_participants IN ACCESS EXCLUSIVE MODE") }
                val migration = executor.submit<Throwable?> { runCatching { flyway().migrate() }.exceptionOrNull() }
                var exceededBudget = false
                val failure =
                    try {
                        if (cancelBackend) {
                            val backend = awaitBlockedMigration()
                            assertEquals("true", rows("SELECT pg_cancel_backend($backend)::text").single())
                        }
                        migration.get(12, TimeUnit.SECONDS)
                    } catch (_: TimeoutException) {
                        exceededBudget = true
                        null
                    } finally {
                        blocker.rollback()
                        // Join even a broken unbounded migration before the fixture or container can be removed.
                        migration.get(15, TimeUnit.SECONDS)
                    }
                assertFalse(exceededBudget, "V22 must stop within its own finite DDL budget; the harness released the blocker at 12s")
                assertNotNull(failure, "Blocked or cancelled DDL cannot be recorded as migration success")
                val states = generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().map { it.sqlState }.toList()
                assertTrue(expectedState in states, "Expected actual PostgreSQL timeout/cancellation, not another fixture failure: $states")
            }
            assertEquals(business, businessSnapshot(), "A failed migration must not rewrite pre-existing business values")
            assertEquals(history, historicalRows(), "No partial or failed V22 history may survive transactional DDL failure")
            assertEquals(schema, targetSchema(), "All earlier column/CHECK/function/trigger changes must roll back together")
            assertEquals("0", rows("SELECT count(*)::text FROM flyway_schema_history WHERE version='22'").single())
            val retry = flyway()
            assertEquals(1, retry.migrate().migrationsExecuted)
            retry.validate()
            assertEquals(business, businessSnapshot())
            assertEquals(history, historicalRows())
            assertEquals("1", rows("SELECT count(*)::text FROM flyway_schema_history WHERE version='22' AND success").single())
            assertEquals(0, retry.migrate().migrationsExecuted)
            assertEquals(
                "2",
                rows(
                    "SELECT count(*)::text FROM information_schema.columns WHERE table_schema='public' " +
                        "AND table_name IN ('candidate_participants','recommendation_variant_participants') AND column_name='place_unverified'",
                ).single(),
            )
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(15, TimeUnit.SECONDS)
        }
    }

    private fun awaitBlockedMigration(): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (System.nanoTime() < deadline) {
            val waiting =
                rows(
                    "SELECT pid::text FROM pg_stat_activity WHERE application_name='v22-budget-migration' " +
                        "AND wait_event_type='Lock' AND query LIKE '%recommendation_variant_participants%'",
                )
            if (waiting.size == 1) return waiting.single().toInt()
            Thread.sleep(20)
        }
        error("Migration did not reach the independently held table lock within four seconds")
    }

    private fun businessSnapshot(): Map<String, List<String>> =
        listOf("account_users", "meeting_rooms", "participants")
            .associateWith { table -> rows("SELECT to_jsonb(r)::text FROM $table r ORDER BY id") }

    private fun historicalRows(): List<String> =
        rows("SELECT to_jsonb(h)::text FROM flyway_schema_history h WHERE version <> '22' OR version IS NULL ORDER BY installed_rank")

    private fun targetSchema(): Map<String, List<String>> =
        mapOf(
            "columns" to
                rows(
                    """
                    SELECT table_name || ':' || column_name || ':' || data_type || ':' ||
                      coalesce(column_default,'') || ':' || is_nullable
                    FROM information_schema.columns WHERE table_schema='public'
                    AND table_name IN ('candidate_participants','recommendation_variant_participants','recommendation_variants')
                    ORDER BY table_name,ordinal_position
                    """.trimIndent(),
                ),
            "checks" to
                rows(
                    """
                    SELECT conrelid::regclass::text || ':' || conname || ':' || pg_get_constraintdef(oid)
                    FROM pg_constraint WHERE conrelid IN (
                      'candidate_participants'::regclass,'recommendation_variant_participants'::regclass,
                      'recommendation_variants'::regclass) ORDER BY conname
                    """.trimIndent(),
                ),
            "place_guards" to
                rows(
                    "SELECT proname || ':' || pg_get_functiondef(oid) FROM pg_proc " +
                        "WHERE pronamespace='public'::regnamespace AND proname='guard_pending_place_participant' ORDER BY proname",
                ),
            "triggers" to
                rows(
                    """
                    SELECT tgrelid::regclass::text || ':' || tgname || ':' || pg_get_triggerdef(oid) FROM pg_trigger
                    WHERE NOT tgisinternal AND tgrelid IN (
                      'candidate_participants'::regclass,'recommendation_variant_participants'::regclass)
                    ORDER BY tgrelid::regclass::text,tgname
                    """.trimIndent(),
                ),
        )

    private fun connection(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

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

    private fun flyway(target: String = "22"): Flyway =
        Flyway
            .configure()
            .dataSource(
                postgres.jdbcUrl + (if ('?' in postgres.jdbcUrl) "&" else "?") + "ApplicationName=v22-budget-migration",
                postgres.username,
                postgres.password,
            ).target(target)
            .load()

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
