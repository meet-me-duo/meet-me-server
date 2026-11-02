package com.meetme.server.support

import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration

/** Keep PostgreSQL readiness and also require the host-mapped port used by JDBC. */
fun mappedPortReadyPostgres(): PostgreSQLContainer =
    PostgreSQLContainer("postgres:18-alpine").apply {
        waitingFor(
            WaitAllStrategy()
                .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
                .withStrategy(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofSeconds(60)),
        )
    }
