package com.meetme.server.coordination.application.service

import com.meetme.server.config.ReliabilityProperties
import com.meetme.server.coordination.adapter.output.persistence.KomapperOutboxRepository
import com.meetme.server.coordination.adapter.output.redis.RedisCoordinationWorkQueue
import com.meetme.server.coordination.application.port.output.CoordinationEventProcessor
import com.meetme.server.coordination.application.port.output.CoordinationWorkQueuePort
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.shared.application.port.output.ApplicationMetricsPort
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.support.mappedPortReadyPostgres
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.komapper.jdbc.JdbcDatabase
import org.mockito.Mockito.mock
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real production relay/repository/worker/stream adapters. Synthetic event processor; no full model pipeline. */
@EnabledIfSystemProperty(named = "portfolio.run", matches = "true")
class PortfolioQueueExperimentTest {
    @Test
    fun `QRX01 real subprocess crashes preserve accepted outbox work and suppress completed duplicates`() {
        val pg = mappedPortReadyPostgres().withCommand("postgres", "-c", "max_connections=300")
        val redis = GenericContainer(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379)
        pg.start()
        redis.start()
        val dir = Path.of(System.getProperty("portfolio.results"))
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
            QueueLab(env).use { lab ->
                for (n in listOf(10, 100)) {
                    for (round in 1..if (n == 10) 1 else 3) {
                        for (scenario in listOf("before_publish", "after_publish", "worker_during", "before_ack")) {
                            lab.reset()
                            repeat(n) { lab.seed() }
                            if (scenario in setOf("worker_during", "before_ack")) lab.relay()
                            val marker = dir.resolve("marker-$scenario-$n-$round")
                            Files.deleteIfExists(marker)
                            val child =
                                ProcessBuilder(
                                    System.getProperty("java.home") + "/bin/java",
                                    "-Xmx384m",
                                    "-cp",
                                    System.getProperty("portfolio.classpath"),
                                    PortfolioCrashProcess::class.java.name,
                                    scenario,
                                    n.toString(),
                                    marker.toString(),
                                ).redirectErrorStream(true)
                                    .redirectOutput(dir.resolve("child-$scenario-$n-$round.log").toFile())
                            child.environment().putAll(env)
                            val proc = child.start()
                            try {
                                val limit = System.nanoTime() + Duration.ofSeconds(60).toNanos()
                                while (!Files.exists(marker) && proc.isAlive && System.nanoTime() < limit) Thread.sleep(20)
                                assertTrue(Files.exists(marker), "Child failed before checkpoint: $scenario; see child log")
                                proc.destroyForcibly()
                                assertTrue(proc.waitFor(10, TimeUnit.SECONDS))
                                assertNotEquals(0, proc.exitValue())
                                val start = System.nanoTime()
                                if (scenario == "after_publish") {
                                    assertEquals(n.toLong(), lab.count("status='PENDING'"))
                                    assertEquals(n.toLong(), lab.streamSize())
                                }
                                if (scenario == "worker_during") Thread.sleep(300)
                                lab.relay()
                                val deliveries = lab.drain()
                                val processed = lab.count("status='PROCESSED'")
                                assertEquals(n.toLong(), processed)
                                assertEquals(n.toLong(), lab.processorCount())
                                assertEquals(0L, lab.count("status NOT IN ('PROCESSED','DEAD_LETTERED')"))
                                assertEquals(0L, lab.queue.backlog().pendingCount)
                                assertEquals(0L, lab.streamSize())
                                rows +=
                                    mapOf(
                                        "phase" to if (n == 10) "pilot" else "main",
                                        "scenario" to scenario,
                                        "round" to round,
                                        "jobs" to n,
                                        "processed" to processed,
                                        "processorInvocations" to lab.processorCount(),
                                        "recoveryDeliveries" to deliveries,
                                        "recoveryMillis" to (System.nanoTime() - start) / 1_000_000.0,
                                        "leaseMillis" to 250,
                                        "recoveryMode" to "manual methods, 1ms Redis pending eligibility; no scheduler latency",
                                    )
                                Files.writeString(dir.resolve("queue-crashes.json"), ObjectMapper().writeValueAsString(rows))
                            } finally {
                                if (proc.isAlive) proc.destroyForcibly()
                            }
                        }
                    }
                }
                // One default-lease smoke: no accelerated DB lease, no artificial DB timestamp edits.
                lab.reset()
                val event = lab.seed()
                lab.relay()
                val message = lab.queue.readNew(1).single()
                lab.repo.claimProcessing(event.id, Instant.now(), Instant.now().plusSeconds(120))
                assertNull(lab.repo.claimProcessing(event.id, Instant.now(), Instant.now().plusSeconds(120)))
                val start = System.nanoTime()
                while (Duration.ofNanos(System.nanoTime() - start) < Duration.ofSeconds(121)) Thread.sleep(1000)
                val claimed = lab.queue.claimStale(Duration.ofMinutes(2), 10)
                assertEquals(1, claimed.size)
                lab.worker(Duration.ofMinutes(2)).process(claimed.single())
                assertEquals(1L, lab.count("status='PROCESSED'"))
                rows +=
                    mapOf(
                        "phase" to "default_lease_smoke",
                        "jobs" to 1,
                        "processed" to 1,
                        "recoveryMillis" to (System.nanoTime() - start) / 1_000_000.0,
                        "leaseMillis" to 120000,
                        "note" to "lease/pending retained for 121s in real DB/Redis; logical worker crash via abandoned claim",
                    )
                Files.writeString(dir.resolve("queue-crashes.json"), ObjectMapper().writeValueAsString(rows))
            }
        } finally {
            redis.stop()
            pg.stop()
        }
    }
}

internal class QueueLab(
    private val env: Map<String, String>,
    private val labClock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val source = DriverManagerDataSource(env.getValue("LAB_DB_URL"), env.getValue("LAB_DB_USER"), env.getValue("LAB_DB_PASSWORD"))
    val jdbc = JdbcTemplate(source)
    val repo =
        KomapperOutboxRepository(
            JdbcDatabase(env.getValue("LAB_DB_URL"), env.getValue("LAB_DB_USER"), env.getValue("LAB_DB_PASSWORD")),
            jdbc,
        )
    private val redisFactory =
        LettuceConnectionFactory(env.getValue("LAB_REDIS_HOST"), env.getValue("LAB_REDIS_PORT").toInt()).also {
            it.afterPropertiesSet()
        }
    private val template = StringRedisTemplate(redisFactory).also { it.afterPropertiesSet() }
    private val properties =
        ReliabilityProperties(
            streamKey = "lab:work",
            consumerGroup = "lab-workers",
            consumerName =
                "lab-" + UUID.randomUUID(),
            deadLetterStreamKey = "lab:dlq",
            pollBatchSize = 1000,
        )
    val queue = RedisCoordinationWorkQueue(template, ObjectMapper(), properties, labClock)
    private val transactions = TransactionTemplate(DataSourceTransactionManager(source))

    init {
        jdbc.execute("CREATE TABLE IF NOT EXISTS portfolio_processor_calls (batch_id uuid NOT NULL)")
    }

    fun reset() {
        jdbc.execute("TRUNCATE outbox_events,portfolio_processor_calls")
        template.delete(listOf("lab:work", "lab:dlq"))
    }

    fun seed(): OutboxEvent {
        val batch = UUID.randomUUID()
        val event =
            OutboxEvent(
                OutboxEventId(UUID.randomUUID()),
                "CoordinationRun",
                UUID.randomUUID(),
                "SubmissionBatchStructuringRequested",
                """{"submission_batch_id":"$batch"}""",
                occurredAt = Instant.now(),
            )
        repo.insert(event)
        return event
    }

    fun relay(q: CoordinationWorkQueuePort = queue) =
        transactions.execute {
            OutboxRelay(repo, q, properties, labClock, mock(ApplicationMetricsPort::class.java)).relayPending()
        }!!

    fun worker(
        lease: Duration = Duration.ofMillis(250),
        q: CoordinationWorkQueuePort = queue,
        processor: CoordinationEventProcessor =
            CoordinationEventProcessor { _, batch -> jdbc.update("INSERT INTO portfolio_processor_calls VALUES (?)", batch.value) },
    ) = CoordinationWorker(
        repo,
        q,
        processor,
        DeadLetterPersistence {
            id,
            kind,
            _,
            ->
            repo.markDeadLettered(id, Instant.now(), kind)
        },
        labClock,
        lease,
    )

    fun drain(): Int {
        var deliveries = 0
        repeat(20) {
            val messages = queue.readNew(1000) + queue.claimStale(Duration.ofMillis(1), 1000)
            if (messages.isEmpty()) return deliveries
            messages.forEach {
                worker().process(it)
                deliveries++
            }
        }
        error("Queue did not drain")
    }

    fun count(where: String) = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE $where", Long::class.java)!!

    fun processorCount() = jdbc.queryForObject("SELECT count(*) FROM portfolio_processor_calls", Long::class.java)!!

    fun streamSize() = template.opsForStream<String, String>().size(properties.streamKey) ?: 0L

    override fun close() {
        redisFactory.destroy()
    }
}

/** Real isolated process. Parent kills at checkpoint without running finally/catch cleanup. */
object PortfolioCrashProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        val scenario = args[0]
        val n = args[1].toInt()
        val marker = Path.of(args[2])

        fun barrier() {
            Files.writeString(marker, "ready")
            CountDownLatch(1).await()
        }
        QueueLab(System.getenv()).use { lab ->
            when (scenario) {
                "before_publish" -> barrier()
                "after_publish" -> {
                    val published = AtomicInteger()
                    val q =
                        object : CoordinationWorkQueuePort by lab.queue {
                            override fun publish(event: OutboxEvent) {
                                lab.queue.publish(event)
                                if (published.incrementAndGet() == n) barrier()
                            }
                        }
                    lab.relay(q)
                }
                "worker_during", "before_ack" -> {
                    val reached = AtomicInteger()
                    val q =
                        object : CoordinationWorkQueuePort by lab.queue {
                            override fun acknowledge(streamRecordId: String) {
                                if (scenario == "before_ack") {
                                    if (reached.incrementAndGet() == n) Files.writeString(marker, "ready")
                                    CountDownLatch(1).await()
                                } else {
                                    lab.queue.acknowledge(streamRecordId)
                                }
                            }
                        }
                    val processor =
                        CoordinationEventProcessor { _, batch ->
                            if (scenario == "worker_during") {
                                if (reached.incrementAndGet() == n) Files.writeString(marker, "ready")
                                CountDownLatch(1).await()
                            } else {
                                lab.jdbc.update("INSERT INTO portfolio_processor_calls VALUES (?)", batch.value)
                            }
                        }
                    val pool = Executors.newFixedThreadPool(n)
                    lab.queue.readNew(n).forEach { msg ->
                        pool.submit {
                            try {
                                lab.worker(q = q, processor = processor).process(msg)
                            } catch (
                                failure: Throwable,
                            ) {
                                failure.printStackTrace()
                                throw failure
                            }
                        }
                    }
                    CountDownLatch(1).await()
                }
                else -> error("Unknown checkpoint")
            }
        }
    }
}
