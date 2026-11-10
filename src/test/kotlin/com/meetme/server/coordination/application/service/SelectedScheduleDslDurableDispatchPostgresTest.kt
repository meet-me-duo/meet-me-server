package com.meetme.server.coordination.application.service

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.adapter.output.integration.GeminiSelectedScheduleDslProviderAdapter
import com.meetme.server.coordination.adapter.output.integration.ProviderRejectedField
import com.meetme.server.coordination.adapter.output.integration.ProviderRejectionDiagnosticException
import com.meetme.server.coordination.adapter.output.integration.ProviderRejectionReason
import com.meetme.server.coordination.adapter.output.integration.ProviderRejectionStatus
import com.meetme.server.coordination.adapter.output.integration.ScheduleDslGeminiWire
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslInvocation
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.application.port.output.AiBudgetAuthorityRegistryPort
import com.meetme.server.shared.application.port.output.AiBudgetBillRequest
import com.meetme.server.shared.application.port.output.AiBudgetQuoteRequest
import com.meetme.server.shared.application.port.output.AiBudgetUnit
import com.meetme.server.shared.application.port.output.AiDispatchFingerprints
import com.meetme.server.shared.application.port.output.AiVerifiedBudgetQuote
import com.meetme.server.shared.application.port.output.AiVerifiedCompleteBill
import com.meetme.server.shared.application.port.output.AiVerifiedFinalReserve
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.support.SelectedDslPhysicalInputFixture
import com.meetme.server.support.mappedPortReadyPostgres
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F

/** Actual enabled DurableAiBudgetService + JDBC execution fence; test authority supplies arbitrary TEST units only. */
@SpringBootTest
@Import(
    SelectedScheduleDslPublicationPostgresTest.Configuration::class,
    SelectedScheduleDslDurableDispatchPostgresTest.AuthorityConfig::class,
)
class SelectedScheduleDslDurableDispatchPostgresTest {
    @Autowired private lateinit var service: SelectedScheduleDslProcessingPersistenceService

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var budget: DurableAiBudgetPort

    @Autowired private lateinit var authority: TestAuthority

    @Autowired private lateinit var clock: SelectedScheduleDslPublicationPostgresTest.PublicationClock

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @BeforeEach
    fun trustedNativeAndExplicitCoverage() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        clock.set(F.selectedAt)
        authority.allow = true
        SelectedDslPhysicalInputFixture.seed(jdbc, mapper)
        routes.freeze(F.ir.run, 1, SelectedDslPhysicalInputFixture.contextChoice())
        jdbc.update(
            """
            UPDATE ai_room_budgets SET state='READY',epoch_id=?,unit_code='TEST_SUBUNIT',unit_scale=0,
                cap=100,settled=0,held=0,policy_fingerprint=?,coverage_started_at=? WHERE room_id=?
            """.trimIndent(),
            UUID(0, 8801),
            POLICY,
            Timestamp.from(F.ir.reference.minusSeconds(1)),
            F.ir.room,
        )
    }

    private fun admitted(): SelectedScheduleDslInvocation =
        assertNotNull(
            service.admit(assertNotNull(service.start(SubmissionBatchId(F.ir.batch))), UUID(0, 8802), Duration.ofSeconds(60)),
        )

    private fun request(admitted: SelectedScheduleDslInvocation) =
        service.request(admitted.binding, admitted.attempt, System.nanoTime() + Duration.ofSeconds(10).toNanos())

    private fun adapter(http: Loopback) =
        GeminiSelectedScheduleDslProviderAdapter(
            GeminiProperties(apiKey = "synthetic-loopback-only"),
            mapper,
            AiHttpDispatchGate(AiDispatchGateProperties(enabled = true), budget, clock),
            http.endpoint,
        )

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun noResult() {
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
        assertEquals("STRUCTURING", jdbc.queryForObject("SELECT status FROM coordination_runs", String::class.java))
    }

    @Test
    fun `SDGD01 actual C2 wire has one durable physical claim and second delivery cannot send`() {
        val admitted = admitted()
        val request = request(admitted)
        Loopback(mapper).use { http ->
            val adapter = adapter(http)
            val response = adapter.generate(request)
            assertEquals(1, http.requests.size)
            assertContentEquals(
                ScheduleDslGeminiWire.requestContextBytes(mapper, request.inputCapsule, "gemini-3.8-flash"),
                http.requests.single(),
            )
            val storedHash = jdbc.queryForObject("SELECT whole_payload_fingerprint FROM ai_call_reservations", String::class.java)
            assertEquals(hash(http.requests.single()), storedHash)
            assertEquals(1, count("ai_call_reservations"))
            assertEquals(admitted.attempt.id, jdbc.queryForObject("SELECT attempt_id FROM ai_call_reservations", UUID::class.java))
            assertEquals(1, jdbc.queryForObject("SELECT ordinal FROM ai_call_reservations", Int::class.java))
            assertTrue(count("ai_call_observations") > 0)
            assertTrue(
                jdbc.queryForObject("SELECT held FROM ai_room_budgets", java.math.BigDecimal::class.java)!! > java.math.BigDecimal.ZERO,
            )
            assertFailsWith<RuntimeException> { adapter.generate(request) }
            assertEquals(1, http.requests.size)
            assertEquals(1, count("ai_call_reservations"))
            assertTrue(service.completeDsl(admitted.binding, admitted.attempt, response) { true })
            assertEquals("MATCHING", jdbc.queryForObject("SELECT status FROM coordination_runs", String::class.java))
            assertEquals(0, count("schedule_ir_execution_bindings"))
        }
    }

    @Test
    fun `SDGD08 real gated HTTP 400 retains only safe rejection facts and one original physical admission`() {
        val admitted = admitted()
        val request = request(admitted)
        val privateText = "OFFLINE_PRIVATE_STATUS_BODY_1942"
        val secret = "sk-offline-only-status-secret-1942"
        val body =
            mapper.writeValueAsBytes(
                mapOf(
                    "error" to
                        mapOf(
                            "code" to 400,
                            "status" to "INVALID_ARGUMENT",
                            "message" to "Unsupported parameter: candidateCount. $privateText $secret",
                            "details" to
                                listOf(
                                    mapOf(
                                        "@type" to "type.googleapis.com/google.rpc.BadRequest",
                                        "fieldViolations" to
                                            listOf(
                                                mapOf("field" to "generation_config.candidate_count", "description" to privateText),
                                            ),
                                    ),
                                ),
                        ),
                ),
            )
        Loopback(mapper, status = 400, errorBody = body).use { http ->
            val failure = assertFailsWith<NaturalLanguageParserException> { adapter(http).generate(request) }
            assertEquals(ParserFailureKind.INVALID_REQUEST, failure.kind)
            assertFalse(failure.retryable)
            assertNull(failure.retryAfterMillis)
            val safe = assertIs<ProviderRejectionDiagnosticException>(failure.cause)
            assertEquals(400, safe.diagnostic.httpStatus)
            assertEquals(com.meetme.server.coordination.application.port.output.AnalysisProvider.GEMINI, safe.diagnostic.provider)
            assertEquals(ProviderRejectionStatus.INVALID_ARGUMENT, safe.diagnostic.providerStatus)
            assertEquals(ProviderRejectionReason.UNSUPPORTED_PARAMETER, safe.diagnostic.reason)
            assertEquals(setOf(ProviderRejectedField.CANDIDATE_COUNT), safe.diagnostic.fields)
            assertNull(safe.cause)
            assertTrue(safe.suppressed.isEmpty())
            val exposed = failure.stackTraceToString() + mapper.writeValueAsString(safe.diagnostic)
            assertFalse(exposed.contains(privateText))
            assertFalse(exposed.contains(secret))
            assertFalse(exposed.contains("Unsupported parameter:"))
            assertEquals(1, http.requests.size)
            assertEquals(1, count("ai_call_reservations"))
            assertEquals(admitted.attempt.id, jdbc.queryForObject("SELECT attempt_id FROM ai_call_reservations", UUID::class.java))
            assertEquals(
                hash(http.requests.single()),
                jdbc.queryForObject("SELECT whole_payload_fingerprint FROM ai_call_reservations", String::class.java),
            )
            noResult()
        }
    }

    @Test
    fun `SDGD02 missing trusted quote denies physical dispatch without fake admission`() {
        val admitted = admitted()
        val request = request(admitted)
        authority.allow = false
        Loopback(mapper).use { http ->
            assertFailsWith<RuntimeException> { adapter(http).generate(request) }
            assertEquals(0, http.requests.size)
            assertEquals(0, count("ai_call_reservations"))
            noResult()
        }
    }

    @Test
    fun `SDGD03 persisted invocation owner change fences actual gate before HTTP`() {
        val admitted = admitted()
        val request = request(admitted)
        jdbc.update("UPDATE analysis_invocations SET owner_token=?", UUID(0, 8803))
        Loopback(mapper).use { http ->
            assertFailsWith<RuntimeException> { adapter(http).generate(request) }
            assertEquals(0, http.requests.size)
            assertEquals(0, count("ai_call_reservations"))
            noResult()
        }
    }

    @Test
    fun `SDGD04 native prompt edit invalidates execution source before actual HTTP`() {
        val admitted = admitted()
        val request = request(admitted)
        jdbc.update(
            """
            UPDATE submission_versions SET input_evidence=jsonb_set(input_evidence,'{supplement,prompt}',to_jsonb('changed synthetic question'::text))
            WHERE id=?
            """.trimIndent(),
            F.ir.first,
        )
        Loopback(mapper).use { http ->
            assertFailsWith<RuntimeException> { adapter(http).generate(request) }
            assertEquals(0, http.requests.size)
            assertEquals(0, count("ai_call_reservations"))
            noResult()
        }
    }

    @Test
    fun `SDGD05 reverse ordered dispatch source fingerprint is rejected by actual DB fence`() {
        val admitted = admitted()
        val request = request(admitted)
        val wrong =
            request.copy(
                dispatchContext =
                    request.dispatchContext.copy(
                        execution =
                            request.dispatchContext.execution.copy(
                                sourceFingerprint = AiDispatchFingerprints.batchSource(request.dispatchInputs.reversed()),
                            ),
                    ),
            )
        assertNotEquals(request.dispatchContext.execution.sourceFingerprint, wrong.dispatchContext.execution.sourceFingerprint)
        Loopback(mapper).use { http ->
            assertFailsWith<RuntimeException> { adapter(http).generate(wrong) }
            assertEquals(0, http.requests.size)
            assertEquals(0, count("ai_call_reservations"))
            noResult()
        }
    }

    @Test
    fun `SDGD06 failed publication does not refund committed physical admission or observations`() {
        val admitted = admitted()
        val request = request(admitted)
        Loopback(mapper).use { http ->
            val response = adapter(http).generate(request)
            val reservations = F.rows(jdbc, "ai_call_reservations")
            val observations = F.rows(jdbc, "ai_call_observations")
            assertFalse(service.completeDsl(admitted.binding, admitted.attempt, response) { false })
            noResult()
            assertEquals(reservations, F.rows(jdbc, "ai_call_reservations"))
            assertEquals(observations, F.rows(jdbc, "ai_call_observations"))
            assertEquals(1, http.requests.size)
        }
    }

    @Test
    fun `SDGD07 physical HTTP does not retain room or run publication locks`() {
        val admitted = admitted()
        val request = request(admitted)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        Loopback(mapper, entered, release).use { http ->
            val future = executor.submit(Callable { adapter(http).generate(request) })
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                requireNotNull(jdbc.dataSource).connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT id FROM meeting_rooms FOR UPDATE NOWAIT").use { assertTrue(it.next()) }
                            statement.executeQuery("SELECT id FROM coordination_runs FOR UPDATE NOWAIT").use { assertTrue(it.next()) }
                        }
                    } finally {
                        connection.rollback()
                    }
                }
                assertEquals(1, count("ai_call_reservations"))
                release.countDown()
                future.get(5, TimeUnit.SECONDS)
                assertEquals(1, http.requests.size)
            } finally {
                release.countDown()
                future.cancel(true)
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    /** Only trusted synthetic monetary proof is replaced. Execution fence and budget coordinator remain actual beans. */
    class TestAuthority : AiBudgetAuthorityRegistryPort {
        var allow = true

        override fun quote(request: AiBudgetQuoteRequest): AiVerifiedBudgetQuote? =
            if (!allow) {
                null
            } else {
                AiVerifiedBudgetQuote(
                    hash("synthetic-quote".toByteArray()),
                    request.call.profileFingerprint,
                    request.call.wholePayloadFingerprint,
                    request.policyFingerprint,
                    hash("synthetic-evidence".toByteArray()),
                    BigInteger.TEN,
                    AiBudgetUnit("TEST_SUBUNIT", 0),
                    F.selectedAt.plusSeconds(60),
                )
            }

        override fun protectedFinalReserve(request: AiBudgetQuoteRequest) =
            AiVerifiedFinalReserve(
                request.policyFingerprint,
                BigInteger.ZERO,
                AiBudgetUnit("TEST_SUBUNIT", 0),
                F.selectedAt.plusSeconds(60),
            )

        override fun completeBill(request: AiBudgetBillRequest): AiVerifiedCompleteBill? = null
    }

    @TestConfiguration(proxyBeanMethods = false)
    class AuthorityConfig {
        @Bean @Primary
        fun selectedTestAuthority() = TestAuthority()
    }

    private class Loopback(
        mapper: ObjectMapper,
        private val entered: CountDownLatch? = null,
        private val release: CountDownLatch? = null,
        private val status: Int = 200,
        private val errorBody: ByteArray? = null,
    ) : AutoCloseable {
        val requests: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint = URI("http://127.0.0.1:${server.address.port}/v1beta/models/gemini-3.8-flash:generateContent")

        init {
            val bytes =
                errorBody ?: mapper.writeValueAsBytes(
                    mapOf(
                        "candidates" to
                            listOf(
                                mapOf("content" to mapOf("parts" to listOf(mapOf("text" to F.q.wire.json()))), "finishReason" to "STOP"),
                            ),
                        "usageMetadata" to mapOf("promptTokenCount" to 17, "candidatesTokenCount" to 23, "totalTokenCount" to 40),
                    ),
                )
            server.createContext("/") { exchange ->
                requests += exchange.requestBody.use { it.readAllBytes() }
                entered?.countDown()
                check(release?.await(8, TimeUnit.SECONDS) != false) { "Synthetic response release expired" }
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            server.start()
        }

        override fun close() {
            server.stop(0)
        }
    }

    companion object {
        private val POLICY = hash("synthetic-selected-policy".toByteArray())

        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
