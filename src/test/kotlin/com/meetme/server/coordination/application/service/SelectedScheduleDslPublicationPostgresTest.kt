package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslContextResponseParser
import com.meetme.server.coordination.adapter.output.persistence.JdbcFinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslInvocation
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslRepository
import com.meetme.server.submission.application.port.output.ScheduleDslRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.ir.ScheduleIrContext
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
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F

/** Real native rows and parser/JDBC publication. Does not claim a physical dispatch occurred. */
@SpringBootTest
@Import(SelectedScheduleDslPublicationPostgresTest.Configuration::class)
class SelectedScheduleDslPublicationPostgresTest {
    @Autowired private lateinit var service: SelectedScheduleDslProcessingPersistenceService

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var transactions: PlatformTransactionManager

    @Autowired private lateinit var repository: PublicationRepository

    @Autowired private lateinit var clock: PublicationClock
    private lateinit var legacy: List<String>

    @BeforeEach
    fun nativeRowsOnly() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        clock.set(F.selectedAt)
        repository.afterPublish = {}
        F.seed(jdbc, mapper)
        routes.freeze(F.ir.run, 1, F.choice())
        legacy = F.rows(jdbc, "structured_submission_results")
    }

    private fun admitted(): SelectedScheduleDslInvocation {
        val run = assertNotNull(service.start(SubmissionBatchId(F.ir.batch)))
        return assertNotNull(service.admit(run, UUID(0, 9901), Duration.ofSeconds(60)))
    }

    private fun response() =
        ScheduleDslProviderResponse(
            F.q.wire.json(),
            ParserUsage(17, 23, 100),
            AnalysisProvider.GEMINI,
            "gemini-3.8-flash",
        )

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun status() = jdbc.queryForObject("SELECT status FROM coordination_runs", String::class.java)

    private fun noPublication() {
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
        assertEquals("STRUCTURING", status())
        assertEquals(
            0,
            jdbc.queryForObject("SELECT count(*) FROM analysis_invocations WHERE winner_attempt_id IS NOT NULL", Int::class.java),
        )
        assertEquals(legacy, F.rows(jdbc, "structured_submission_results"))
        assertEquals(1, count("final_analysis_routes"))
    }

    @Test
    fun `SDSP01 actual parser and fresh JDBC envelope atomically publish one winner and MATCHING`() {
        val invocation = admitted()
        val version = invocation.binding.invocation.runVersion
        assertTrue(service.completeDsl(invocation.binding, invocation.attempt, response()) { true })
        assertEquals("MATCHING", status())
        assertEquals(version + 1, jdbc.queryForObject("SELECT version FROM coordination_runs", Long::class.java))
        assertEquals(1, count("schedule_ir_envelopes"))
        assertEquals(2, count("schedule_ir_inputs"))
        assertEquals(invocation.attempt.id, jdbc.queryForObject("SELECT winner_attempt_id FROM analysis_invocations", UUID::class.java))
        assertNotNull(jdbc.queryForObject("SELECT finished_at FROM analysis_invocations", java.sql.Timestamp::class.java))
        assertNotNull(JdbcScheduleDslRepository(jdbc, mapper).find(F.choice().context))
        assertEquals(legacy, F.rows(jdbc, "structured_submission_results"))
        assertEquals(0, count("schedule_ir_execution_bindings"))
    }

    @Test
    fun `SDSP02 duplicate delivery does not admit another invocation or attempt`() {
        val first = admitted()
        val run = assertNotNull(service.start(SubmissionBatchId(F.ir.batch)))
        assertNull(service.admit(run, UUID(0, 9902), Duration.ofSeconds(60)))
        assertEquals(1, count("analysis_invocations"))
        assertEquals(1, count("coordination_attempts"))
        assertEquals(first.binding.invocation.id, first.attempt.invocationId)
        assertEquals("bounded-luna-v1", first.attempt.policyVersion)
        assertEquals(AnalysisProvider.GEMINI, first.attempt.provider)
        noPublication()
    }

    @Test
    fun `SDSP03 prepare request preserves native question capsule and actual dispatch identity`() {
        val admitted = admitted()
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        val request = service.request(admitted.binding, admitted.attempt, deadline)
        assertEquals(admitted.binding, request.binding)
        assertEquals(admitted.attempt, request.attempt)
        assertEquals(admitted.attempt.id, request.dispatchContext.finalAttemptId)
        assertEquals(1, request.dispatchContext.providerOrdinal)
        assertEquals(admitted.binding.invocation.ownerToken, request.dispatchContext.execution.ownerToken)
        val nativeOrder =
            F.q
                .input2()
                .inputs
                .sortedBy { it.participantId }
        assertEquals(nativeOrder.map { it.submissionVersionId.toString() }, request.dispatchInputs.map { it.inputRef })
        assertEquals(nativeOrder.map { it.evidence }, request.dispatchInputs.map { it.evidence })
        assertEquals(F.q.snapshot().context, request.inputCapsule.snapshot.context)
        F.q.snapshot().context.inputs.forEach { input ->
            input.sources.forEach { source ->
                assertEquals(
                    F.q.snapshot().sourceText(input.submissionVersionId, source.sourceId),
                    request.inputCapsule.snapshot.sourceText(input.submissionVersionId, source.sourceId),
                )
            }
        }
        assertTrue(request.timeout <= Duration.ofSeconds(15))
        noPublication()
    }

    @Test
    fun `SDSP04 caller rollback preserves exact marker and undoes all publication writes`() {
        val admitted = admitted()
        val beforeAttempts = F.rows(jdbc, "coordination_attempts")
        val beforeInvocations = F.rows(jdbc, "analysis_invocations")
        val marker = IllegalStateException("synthetic-publication-rollback")
        val thrown =
            assertFailsWith<IllegalStateException> {
                TransactionTemplate(transactions).executeWithoutResult {
                    assertTrue(service.completeDsl(admitted.binding, admitted.attempt, response()) { true })
                    assertEquals("MATCHING", status())
                    assertEquals(1, count("schedule_ir_envelopes"))
                    throw marker
                }
            }
        assertSame(marker, thrown)
        noPublication()
        assertEquals(beforeAttempts, F.rows(jdbc, "coordination_attempts"))
        assertEquals(beforeInvocations, F.rows(jdbc, "analysis_invocations"))
    }

    @Test
    fun `SDSP05 public monotonic callback expiry after actual publish rolls back`() {
        val admitted = admitted()
        val allowed = AtomicBoolean(true)
        val published = AtomicBoolean(false)
        repository.afterPublish = {
            published.set(true)
            allowed.set(false)
        }
        assertFailsWith<RuntimeException> {
            service.completeDsl(admitted.binding, admitted.attempt, response()) { allowed.get() }
        }
        assertTrue(published.get())
        noPublication()
        assertNull(jdbc.queryForObject("SELECT finished_at FROM coordination_attempts", java.sql.Timestamp::class.java))
    }

    @Test
    fun `SDSP06 wall deadline expiry after actual publish rolls back`() {
        val admitted = admitted()
        val published = AtomicBoolean(false)
        repository.afterPublish = {
            published.set(true)
            clock.set(
                admitted.binding.invocation.deadlineAt
                    .plusSeconds(1),
            )
        }
        assertFailsWith<RuntimeException> { service.completeDsl(admitted.binding, admitted.attempt, response()) { true } }
        assertTrue(published.get())
        noPublication()
    }

    @Test
    fun `SDSP07 evidence ref insert failure rolls back envelope winner and transition`() {
        val admitted = admitted()
        jdbc.execute(
            """
            CREATE FUNCTION synthetic_fail_ir_ref() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN RAISE EXCEPTION ''synthetic ref failure''; END;'
            """.trimIndent(),
        )
        jdbc.execute(
            "CREATE TRIGGER synthetic_ir_ref BEFORE INSERT ON schedule_ir_inputs FOR EACH ROW EXECUTE FUNCTION synthetic_fail_ir_ref()",
        )
        try {
            assertFailsWith<RuntimeException> { service.completeDsl(admitted.binding, admitted.attempt, response()) { true } }
            noPublication()
        } finally {
            jdbc.execute("DROP TRIGGER synthetic_ir_ref ON schedule_ir_inputs")
            jdbc.execute("DROP FUNCTION synthetic_fail_ir_ref()")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["version", "owner", "mode", "source"])
    fun `SDSP08 stale publication cannot create result or winner`(change: String) {
        val admitted = admitted()
        when (change) {
            "version" -> jdbc.update("UPDATE coordination_runs SET version=version+1")
            "owner" -> jdbc.update("UPDATE analysis_invocations SET owner_token=?", UUID(0, 9999))
            "mode" -> jdbc.update("UPDATE meeting_rooms SET meeting_mode='IN_PERSON'")
            else -> jdbc.update("UPDATE submission_versions SET raw_text=raw_text || ' changed'")
        }
        // APIs may deny with false or a sanitized exception; neither may publish.
        runCatching { assertFalse(service.completeDsl(admitted.binding, admitted.attempt, response()) { true }) }
            .onFailure { if (it is AssertionError) throw it }
        noPublication()
    }

    @Test
    fun `SDSP09 typed failure finishes without winner and delays without fallback`() {
        val admitted = admitted()
        service.fail(admitted.binding, admitted.attempt, ParserFailureKind.NETWORK, ParserUsage(17, 23, 100))
        assertEquals("ANALYSIS_DELAYED", status())
        assertEquals("NETWORK", jdbc.queryForObject("SELECT failure_kind FROM coordination_attempts", String::class.java))
        assertEquals(1, count("coordination_attempts"))
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
        assertEquals(
            0,
            jdbc.queryForObject("SELECT count(*) FROM analysis_invocations WHERE winner_attempt_id IS NOT NULL", Int::class.java),
        )
        assertNotNull(jdbc.queryForObject("SELECT finished_at FROM analysis_invocations", java.sql.Timestamp::class.java))
        assertEquals(legacy, F.rows(jdbc, "structured_submission_results"))
    }

    @Test
    fun `SDSP10 concurrent publication has exactly one winner and one run transition`() {
        val admitted = admitted()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results =
                (1..2).map {
                    executor.submit(
                        Callable {
                            ready.countDown()
                            check(start.await(5, TimeUnit.SECONDS))
                            runCatching { service.completeDsl(admitted.binding, admitted.attempt, response()) { true } }
                        },
                    )
                }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, results.map { it.get(10, TimeUnit.SECONDS) }.count { it.getOrNull() == true })
            assertEquals("MATCHING", status())
            assertEquals(
                admitted.binding.invocation.runVersion + 1,
                jdbc.queryForObject("SELECT version FROM coordination_runs", Long::class.java),
            )
            assertEquals(1, count("schedule_ir_envelopes"))
            assertEquals(2, count("schedule_ir_inputs"))
            assertEquals(
                admitted.attempt.id,
                jdbc.queryForObject("SELECT winner_attempt_id FROM analysis_invocations", UUID::class.java),
            )
            assertEquals(legacy, F.rows(jdbc, "structured_submission_results"))
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    class PublicationClock : Clock() {
        private val current = AtomicReference(F.selectedAt)

        fun set(value: Instant) {
            current.set(value)
        }

        override fun instant(): Instant = current.get()

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }

    class PublicationRepository(
        private val delegate: ScheduleDslRepository,
    ) : ScheduleDslRepository {
        var afterPublish: () -> Unit = {}

        override fun publish(
            envelope: ScheduleDslEnvelope,
            expectedRunVersion: Long,
            processedAt: Instant,
        ) {
            delegate.publish(envelope, expectedRunVersion, processedAt)
            afterPublish()
        }

        override fun find(context: ScheduleIrContext) = delegate.find(context)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Configuration {
        @Bean @Primary
        fun publicationClock() = PublicationClock()

        @Bean fun publicationRoutes(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
            clock: PublicationClock,
        ): FinalAnalysisRouteRegistry = JdbcFinalAnalysisRouteRegistry(jdbc, mapper, clock)

        @Bean fun publicationRepository(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
        ) = PublicationRepository(JdbcScheduleDslRepository(jdbc, mapper))

        @Bean fun selectedPublicationService(
            runs: CoordinationRunRepository,
            rooms: MeetingRoomRepository,
            submissions: SubmissionRepository,
            routes: FinalAnalysisRouteRegistry,
            envelopes: PublicationRepository,
            invocations: AnalysisInvocationRepository,
            attempts: CoordinationAttemptRepository,
            clock: PublicationClock,
            mapper: ObjectMapper,
        ) = SelectedScheduleDslProcessingPersistenceService(
            runs,
            rooms,
            submissions,
            routes,
            envelopes,
            StrictScheduleDslContextResponseParser(mapper),
            invocations,
            attempts,
            clock,
        )
    }

    companion object {
        private val postgres = mappedPortReadyPostgres().apply { start() }

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.flyway.enabled") { true }
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }

        @JvmStatic @AfterAll
        fun stopDatabase() {
            postgres.stop()
        }
    }
}
