package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.CoordinationEventProcessor
import com.meetme.server.coordination.application.port.output.CoordinationRetryScheduled
import com.meetme.server.support.mappedPortReadyPostgres
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals

/** Real queue/DB/worker, synthetic handler and controlled time. This is not model-quality or latency evidence. */
@EnabledIfSystemProperty(named = "portfolio.run", matches = "true")
class PortfolioStreamRetryExperimentTest {
    @Test
    fun `SRX01 three hundred events survive scheduled retries reconnects and completed redelivery`() {
        val pg = mappedPortReadyPostgres().apply { start() }
        val redis = GenericContainer(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379).apply { start() }
        val env =
            mapOf(
                "LAB_DB_URL" to pg.jdbcUrl,
                "LAB_DB_USER" to pg.username,
                "LAB_DB_PASSWORD" to pg.password,
                "LAB_REDIS_HOST" to redis.host,
                "LAB_REDIS_PORT" to redis.getMappedPort(6379).toString(),
            )
        val rows = mutableListOf<Map<String, Any>>()
        try {
            Flyway
                .configure()
                .dataSource(pg.jdbcUrl, pg.username, pg.password)
                .load()
                .migrate()
            repeat(3) { round ->
                val clock = ExperimentClock()
                QueueLab(env, clock).use { lab ->
                    lab.reset()
                    repeat(100) { lab.seed() }
                    lab.relay()
                }
                var messages = emptyList<com.meetme.server.coordination.application.port.output.CoordinationQueueMessage>()
                var prematureCalls = 0L
                for (attempt in 1..4) {
                    // New connections, repository, queue, worker and handler after each scheduled round.
                    QueueLab(env, clock).use { lab ->
                        val processor =
                            CoordinationEventProcessor { _, batch ->
                                lab.jdbc.update("INSERT INTO portfolio_processor_calls VALUES (?)", batch.value)
                                val count =
                                    lab.jdbc.queryForObject(
                                        "SELECT count(*) FROM portfolio_processor_calls WHERE batch_id = ?",
                                        Int::class.java,
                                        batch.value,
                                    )!!
                                if (count <= 3) throw CoordinationRetryScheduled(clock.instant().plusSeconds(1L shl (count - 1)), "SERVER")
                            }
                        messages = if (attempt == 1) lab.queue.readNew(1000) else lab.queue.claimStale(Duration.ZERO, 1000)
                        assertEquals(100, messages.size)
                        messages.forEach { lab.worker(processor = processor).process(it) }
                        assertEquals(attempt * 100L, lab.processorCount())
                        if (attempt < 4) {
                            assertEquals(0L, lab.count("status='PROCESSED'"))
                            assertEquals(
                                100L,
                                lab.count("status='PUBLISHED' AND next_attempt_at IS NOT NULL AND processing_lease_until IS NULL"),
                            )
                            assertEquals(100L, lab.queue.backlog().pendingCount)
                            val before = lab.processorCount()
                            lab.queue.claimStale(Duration.ZERO, 1000).forEach { lab.worker(processor = processor).process(it) }
                            prematureCalls += lab.processorCount() - before
                            assertEquals(0L, prematureCalls)
                            assertEquals(0L, lab.count("unexpected_failures<>0"))
                            clock.advance(Duration.ofSeconds(1L shl (attempt - 1)))
                        } else {
                            assertEquals(100L, lab.count("status='PROCESSED'"))
                            assertEquals(0L, lab.queue.backlog().pendingCount)
                            assertEquals(0L, lab.streamSize())
                            assertEquals(0L, lab.count("next_attempt_at IS NOT NULL"))
                            messages.forEach { lab.worker(processor = processor).process(it) }
                            assertEquals(400L, lab.processorCount(), "Completed duplicate must not reenter handler")
                        }
                    }
                }
                rows +=
                    mapOf(
                        "round" to round + 1,
                        "events" to 100,
                        "handlerCalls" to 400,
                        "scheduledRetries" to 300,
                        "prematureCalls" to prematureCalls,
                        "processed" to 100,
                        "pendingAfterCompletion" to 0,
                        "reconnections" to 4,
                        "completedRedeliveryExtraCalls" to 0,
                    )
            }
            val dir = Path.of(System.getProperty("portfolio.results"))
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("stream-retry-lifecycle.json"), ObjectMapper().writeValueAsString(rows))
        } finally {
            redis.stop()
            pg.stop()
        }
    }

    private class ExperimentClock : Clock() {
        private var now = Instant.parse("2026-11-19T00:00:00Z")

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        fun advance(by: Duration) {
            now = now.plus(by)
        }
    }
}
