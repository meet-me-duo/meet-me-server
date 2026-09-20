package com.meetme.server.shared.adapter.output.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult

data class DatabaseMigrationSettings(
    val url: String,
    val username: String,
    val password: String,
)

object DatabaseMigrationRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        migrate(settingsFromEnvironment())
    }

    fun migrate(settings: DatabaseMigrationSettings): MigrateResult =
        Flyway
            .configure()
            .dataSource(settings.url, settings.username, settings.password)
            .locations("classpath:db/migration")
            .validateMigrationNaming(true)
            .load()
            .migrate()

    private fun settingsFromEnvironment(): DatabaseMigrationSettings =
        DatabaseMigrationSettings(
            url = requireEnvironment("DATABASE_URL"),
            username = requireEnvironment("DATABASE_USERNAME"),
            password = requireEnvironment("DATABASE_PASSWORD"),
        )

    private fun requireEnvironment(name: String): String =
        requireNotNull(System.getenv(name)?.takeIf(String::isNotBlank)) {
            "$name is required"
        }
}
