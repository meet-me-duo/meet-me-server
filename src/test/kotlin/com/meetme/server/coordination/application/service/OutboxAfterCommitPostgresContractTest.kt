package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.adapter.output.redis.RedisCoordinationWorkQueue
import com.meetme.server.coordination.application.port.output.CoordinationQueueMessage
import com.meetme.server.coordination.application.port.output.CoordinationWorkMessage
import com.meetme.server.coordination.application.port.output.CoordinationWorkQueuePort
import com.meetme.server.coordination.application.port.output.DeadLetterMessage
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.port.output.OutboxStatus
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OC01..OC05: actual Spring transactions, production repository/relay/listener, controlled dispatch and real PostgreSQL. */
@SpringBootTest(properties = ["meetme.reliability.enabled=false", "meetme.data-retention.enabled=false", "meetme.rate-limit.enabled=false"])
@Import(OutboxAfterCommitPostgresContractTest.ControlledPublication::class)
class OutboxAfterCommitPostgresContractTest {
    @Autowired private lateinit var repository: OutboxRepository

    @Autowired private lateinit var relay: OutboxRelay

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var manager: PlatformTransactionManager

    @Autowired private lateinit var executor: ControlledCommitExecutor

    @Autowired private lateinit var queue: RecordingQueue

    @Autowired private lateinit var actualRedisQueue: RedisCoordinationWorkQueue

    @BeforeEach
    fun reset() {
        executor.reset()
        queue.reset()
        jdbc.execute("TRUNCATE outbox_events")
    }

    @ParameterizedTest
    @ValueSource(strings = ["SubmissionBatchStructuringRequested", "SubmissionBatchMatchingRequested"])
    fun `OC01 central Pending insert submits one wake-up only after commit for either event family`(eventType: String) {
        val event = event(eventType)
        val committed =
            transaction().execute {
                repository.insert(event)
                assertEquals(0, executor.pendingCount(), "Work cannot be submitted before PostgreSQL commit")
                assertEquals(OutboxStatus.PENDING, assertNotNull(repository.findById(event.id)).status)
                event.id
            }

        assertEquals(event.id, committed)
        assertEquals(1, executor.pendingCount())
        assertTrue(queue.published.isEmpty(), "The commit caller must not perform Redis I/O")
        executor.runOne()

        val published = assertNotNull(repository.findById(event.id))
        assertEquals(OutboxStatus.PUBLISHED, published.status)
        assertNotNull(published.publishedAt)
        assertEquals(listOf(event.id), queue.published.toList())
        assertEquals(listOf(true), queue.transactionActive.toList(), "Targeted publication must use a real new transaction")
        assertEquals(0, executor.pendingCount())
        assertEquals(0, relay.relayPending())
    }

    @Test
    fun `OC02 rolled back insert produces neither a durable event nor an after-commit task`() {
        val event = event()

        assertFailsWith<IllegalStateException> {
            transaction().executeWithoutResult {
                repository.insert(event)
                throw IllegalStateException("synthetic business rollback")
            }
        }

        assertNull(repository.findById(event.id))
        assertEquals(0, executor.pendingCount())
        assertTrue(queue.published.isEmpty())
    }

    @Test
    fun `OC01 task submission occurs after the actual Spring commit phase`() {
        val committed = AtomicBoolean(false)
        val observedAtSubmission = AtomicReference<Boolean>()
        executor.onSubmission = { observedAtSubmission.set(committed.get()) }

        transaction().executeWithoutResult {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        committed.set(true)
                    }
                },
            )
            repository.insert(event())
        }

        assertEquals(true, observedAtSubmission.get(), "Submitting at BEFORE_COMMIT is not an AFTER_COMMIT wake-up")
        assertEquals(1, executor.pendingCount())
        assertTrue(queue.published.isEmpty())
    }

    @Test
    fun `OC02 insert without transaction leaves Pending for the fallback poller`() {
        val event = event()

        repository.insert(event)

        assertEquals(0, executor.pendingCount(), "No transaction must not be mistaken for after commit")
        assertEquals(OutboxStatus.PENDING, assertNotNull(repository.findById(event.id)).status)
        assertEquals(1, relay.relayPending())
        assertEquals(listOf(event.id), queue.published.toList())
        assertEquals(OutboxStatus.PUBLISHED, assertNotNull(repository.findById(event.id)).status)
    }

    @Test
    fun `OC09 targeted relay commits independently of an enclosing transaction rollback`() {
        val event = event()
        repository.insert(event)
        assertEquals(0, executor.pendingCount())

        transaction().executeWithoutResult { outer ->
            assertEquals(OutboxStatus.PENDING, assertNotNull(repository.findById(event.id)).status)
            assertTrue(relay.relayCommitted(event.id))
            outer.setRollbackOnly()
        }

        assertEquals(OutboxStatus.PUBLISHED, assertNotNull(repository.findById(event.id)).status)
        assertEquals(listOf(event.id), queue.published.toList())
    }

    @Test
    fun `OC03 Redis failure preserves the committed Pending record and polling later recovers it`() {
        val event = committedEvent()
        assertEquals(1, executor.pendingCount())
        queue.failPublication = true

        executor.runOne()

        assertEquals(OutboxStatus.PENDING, assertNotNull(repository.findById(event.id)).status)
        assertTrue(queue.published.isEmpty())
        queue.failPublication = false
        assertEquals(1, relay.relayPending())
        assertEquals(OutboxStatus.PUBLISHED, assertNotNull(repository.findById(event.id)).status)
        assertEquals(listOf(event.id), queue.published.toList())
    }

    @Test
    fun `OC03 Published write failure rolls back the relay transaction while allowing duplicate Redis delivery on recovery`() {
        val event = committedEvent()
        assertEquals(1, executor.pendingCount())
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT synthetic_reject_publication CHECK (status <> 'PUBLISHED')")
        try {
            executor.runOne()
            assertEquals(OutboxStatus.PENDING, assertNotNull(repository.findById(event.id)).status)
            assertNull(assertNotNull(repository.findById(event.id)).publishedAt)
            assertEquals(listOf(event.id), queue.published.toList(), "Redis success cannot be rolled back with PostgreSQL")
        } finally {
            jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT synthetic_reject_publication")
        }

        assertEquals(1, relay.relayPending())
        assertEquals(listOf(event.id, event.id), queue.published.toList())
        assertEquals(OutboxStatus.PUBLISHED, assertNotNull(repository.findById(event.id)).status)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `OC04 concurrent immediate publication and polling share one row lock and publish once`(listenerFirst: Boolean) {
        val event = committedEvent()
        assertEquals(1, executor.pendingCount())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        queue.entered = entered
        queue.release = release
        val threads = Executors.newFixedThreadPool(2)
        try {
            val first = threads.submit { if (listenerFirst) executor.runOne() else relay.relayPending() }
            assertTrue(entered.await(5, TimeUnit.SECONDS), "First publisher must hold the PostgreSQL row lock")
            val contender = threads.submit { if (listenerFirst) relay.relayPending() else executor.runOne() }
            contender.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(event.id), queue.published.toList())
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            threads.shutdownNow()
        }
        assertEquals(OutboxStatus.PUBLISHED, assertNotNull(repository.findById(event.id)).status)
        assertEquals(listOf(event.id), queue.published.toList())
    }

    @Test
    fun `OC05 executor rejection cannot turn a successful business commit into a failed request`() {
        executor.reject = true
        val event = event()

        val returned =
            transaction().execute {
                repository.insert(event)
                event.id
            }

        assertEquals(event.id, returned)
        assertEquals(OutboxStatus.PENDING, assertNotNull(repository.findById(event.id)).status)
        assertEquals(0, executor.pendingCount())
        assertTrue(queue.published.isEmpty())
        assertEquals(1, relay.relayPending())
        assertEquals(listOf(event.id), queue.published.toList())
    }

    @Test
    fun `OC05 slow Redis task cannot hold the original transaction commit response`() {
        val event = event()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        queue.entered = entered
        queue.release = release
        val thread = Executors.newSingleThreadExecutor()
        try {
            val returned =
                thread.submit<OutboxEventId?> {
                    transaction().execute {
                        repository.insert(event)
                        event.id
                    }
                }
            assertEquals(event.id, returned.get(5, TimeUnit.SECONDS))
            assertEquals(1, executor.pendingCount())
            assertTrue(queue.published.isEmpty())
            val background = thread.submit { executor.runOne() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(returned.isDone, "The original response already completed while Redis is still blocked")
            release.countDown()
            background.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            thread.shutdownNow()
        }
        assertEquals(OutboxStatus.PUBLISHED, assertNotNull(repository.findById(event.id)).status)
    }

    @Test
    fun `OC01 explicit requeue emits a new commit wake-up through the same repository boundary`() {
        val event =
            event().copy(
                status = OutboxStatus.DEAD_LETTERED,
                publishedAt = Instant.now(),
                deadLetteredAt = Instant.now(),
                unexpectedFailures = 5,
            )
        repository.insert(event)
        assertEquals(0, executor.pendingCount(), "Non-Pending inserts do not request publication")

        transaction().executeWithoutResult { repository.requeue(event.id) }

        assertEquals(1, executor.pendingCount())
        assertEquals(OutboxStatus.PENDING, assertNotNull(repository.findById(event.id)).status)
        executor.runOne()
        assertEquals(OutboxStatus.PUBLISHED, assertNotNull(repository.findById(event.id)).status)
        assertEquals(0, assertNotNull(repository.findById(event.id)).unexpectedFailures)
        assertEquals(listOf(event.id), queue.published.toList())
    }

    @Test
    fun `OC08 targeted after-commit publication reaches a real Redis Stream and commits Published`() {
        queue.delegate = actualRedisQueue
        val event = committedEvent()
        assertEquals(1, executor.pendingCount())

        executor.runOne()

        val message = assertIs<CoordinationWorkMessage>(actualRedisQueue.readNew(10).single())
        assertEquals(event.id, message.eventId)
        assertEquals(OutboxStatus.PUBLISHED, assertNotNull(repository.findById(event.id)).status)
        actualRedisQueue.acknowledge(message.streamRecordId)
    }

    private fun transaction() = TransactionTemplate(manager)

    private fun committedEvent(): OutboxEvent = event().also { event -> transaction().executeWithoutResult { repository.insert(event) } }

    private fun event(eventType: String = "SubmissionBatchStructuringRequested"): OutboxEvent {
        val eventId = OutboxEventId(UUID.randomUUID())
        return OutboxEvent(
            eventId,
            "CoordinationRun",
            UUID.randomUUID(),
            eventType,
            """{"event_id":"${eventId.value}","submission_batch_id":"${UUID.randomUUID()}"}""",
            occurredAt = Instant.now(),
        )
    }

    class ControlledCommitExecutor : Executor {
        private val tasks = ConcurrentLinkedQueue<Runnable>()

        @Volatile var reject = false

        @Volatile var onSubmission: (() -> Unit)? = null

        override fun execute(command: Runnable) {
            if (reject) throw RejectedExecutionException("synthetic executor saturation")
            onSubmission?.invoke()
            tasks.add(command)
        }

        fun pendingCount(): Int = tasks.size

        fun runOne() = assertNotNull(tasks.poll(), "A committed repository write must enqueue a wake-up").run()

        fun reset() {
            tasks.clear()
            reject = false
            onSubmission = null
        }
    }

    class RecordingQueue : CoordinationWorkQueuePort {
        val published = CopyOnWriteArrayList<OutboxEventId>()
        val transactionActive = CopyOnWriteArrayList<Boolean>()

        @Volatile var failPublication = false

        @Volatile var entered: CountDownLatch? = null

        @Volatile var release: CountDownLatch? = null

        @Volatile var delegate: CoordinationWorkQueuePort? = null

        override fun publish(event: OutboxEvent) {
            if (failPublication) throw IllegalStateException("synthetic Redis failure")
            transactionActive += TransactionSynchronizationManager.isActualTransactionActive()
            published += event.id
            entered?.countDown()
            release?.let { check(it.await(10, TimeUnit.SECONDS)) { "Synthetic publisher was not released" } }
            delegate?.publish(event)
        }

        fun reset() {
            published.clear()
            transactionActive.clear()
            failPublication = false
            entered = null
            release = null
            delegate = null
        }

        override fun readNew(limit: Int): List<CoordinationQueueMessage> = emptyList()

        override fun claimStale(
            minIdleTime: Duration,
            limit: Int,
        ): List<CoordinationQueueMessage> = emptyList()

        override fun acknowledge(streamRecordId: String) = Unit

        override fun deadLetter(message: DeadLetterMessage) = Unit
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ControlledPublication {
        @Bean fun controlledCommitExecutor() = ControlledCommitExecutor()

        @Bean @Primary
        fun recordingQueue() = RecordingQueue()

        @Bean fun contractCommitListener(
            relay: OutboxRelay,
            executor: ControlledCommitExecutor,
        ) = OutboxCommitListener(relay, executor)
    }

    companion object {
        private val postgres = mappedPortReadyPostgres().apply { start() }
        private val redis = GenericContainer(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379).apply { start() }

        @JvmStatic @DynamicPropertySource
        fun infrastructure(registry: DynamicPropertyRegistry) {
            registry.add("spring.flyway.enabled") { true }
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
        }

        @JvmStatic @AfterAll
        fun stop() {
            redis.stop()
            postgres.stop()
        }
    }
}
