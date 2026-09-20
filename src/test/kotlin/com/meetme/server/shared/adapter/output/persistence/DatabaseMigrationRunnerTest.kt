package com.meetme.server.shared.adapter.output.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertEquals

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

        assertEquals(7, result.migrationsExecuted)
        assertEquals(
            "7",
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .load()
                .info()
                .current()
                .version.version,
        )
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
