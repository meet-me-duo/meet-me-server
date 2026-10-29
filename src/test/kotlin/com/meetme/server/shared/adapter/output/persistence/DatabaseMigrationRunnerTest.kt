package com.meetme.server.shared.adapter.output.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseMigrationRunnerTest {
    @Test
    fun `운영 migration 진입점이 빈 PostgreSQL에 전체 Flyway migration을 적용한다`() {
        val result =
            DatabaseMigrationRunner.migrate(
                DatabaseMigrationSettings(
                    url = postgres.jdbcUrl,
                    username = postgres.username,
                    password = postgres.password,
                ),
            )

        assertEquals(20, result.migrationsExecuted)
        assertEquals(
            "20",
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .load()
                .info()
                .current()
                .version.version,
        )
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' " +
                            "AND table_name IN ('review_user_policies','review_prompts')",
                    ).use { rows ->
                        assertTrue(rows.next())
                        assertEquals(2, rows.getInt(1), "V19 must create both review persistence tables")
                    }
            }
        }
    }

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
