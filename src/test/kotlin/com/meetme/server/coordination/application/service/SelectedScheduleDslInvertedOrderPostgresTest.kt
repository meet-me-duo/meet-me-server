package com.meetme.server.coordination.application.service

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.adapter.output.integration.GeminiSelectedScheduleDslProviderAdapter
import com.meetme.server.coordination.adapter.output.integration.ScheduleDslGeminiWire
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslInvocation
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.application.port.output.AiBudgetAuthorityRegistryPort
import com.meetme.server.shared.application.port.output.AiBudgetBillRequest
import com.meetme.server.shared.application.port.output.AiBudgetQuoteRequest
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiBudgetUnit
import com.meetme.server.shared.application.port.output.AiDispatchFingerprints
import com.meetme.server.shared.application.port.output.AiDispatchInputSnapshot
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchProfiles
import com.meetme.server.shared.application.port.output.AiVerifiedBudgetQuote
import com.meetme.server.shared.application.port.output.AiVerifiedCompleteBill
import com.meetme.server.shared.application.port.output.AiVerifiedFinalReserve
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.shared.domain.SubmissionBatchId
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
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F
import com.meetme.server.support.SelectedDslInvertedOrderFixture as Inverted

/** Actual enabled DurableAiBudgetService + JDBC execution fence; test authority supplies arbitrary TEST units only. */
@SpringBootTest
@Import(
    SelectedScheduleDslPublicationPostgresTest.Configuration::class,
    SelectedScheduleDslInvertedOrderPostgresTest.AuthorityConfig::class,
)
class SelectedScheduleDslInvertedOrderPostgresTest {
    @Autowired private lateinit var service: SelectedScheduleDslProcessingPersistenceService

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var budget: DurableAiBudgetPort

    @Autowired private lateinit var runtimeGuard: AiRuntimeDispatchGuardPort

    @Autowired private lateinit var authority: TestAuthority

    @Autowired private lateinit var clock: SelectedScheduleDslPublicationPostgresTest.PublicationClock

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @BeforeEach
    fun trustedNativeAndExplicitCoverage() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        clock.set(F.selectedAt)
        authority.allow = true
        Inverted.seed(jdbc, mapper)
        routes.freeze(F.ir.run, 1, Inverted.choice())
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
    fun `SDGI01 native inverted identities dispatch by participant order and wire by version order`() {
        val admitted = admitted()
        val request = request(admitted)
        val input = Inverted.input()
        val participants = input.inputs.sortedBy { it.participantId }
        val versions = input.inputs.sortedBy { it.submissionVersionId }
        val expected =
            participants.map { item ->
                AiDispatchInputSnapshot(
                    item.submissionVersionId.toString(),
                    item.rawText,
                    item.locale,
                    requireNotNull(item.evidence).referenceAt.atZone(ZoneId.of(input.timeZone)).toLocalDate(),
                    item.evidence,
                )
            }
        assertNotEquals(participants.map { it.submissionVersionId }, versions.map { it.submissionVersionId })
        assertEquals(expected, request.dispatchInputs)
        assertEquals(
            versions.map { it.submissionVersionId },
            request.inputCapsule.snapshot.context.inputs
                .map { it.submissionVersionId },
        )
        assertEquals(AiDispatchFingerprints.batchSource(expected), request.dispatchContext.execution.sourceFingerprint)
        assertEquals(
            AiDispatchFingerprints.batchContext(ZoneId.of(input.timeZone), input.searchStart, input.searchEnd, expected),
            request.dispatchContext.execution.contextFingerprint,
        )
        Loopback(mapper).use { http ->
            val response = adapter(http).generate(request)
            assertEquals(1, http.requests.size)
            val wire = mapper.readTree(http.requests.single())
            val neutral =
                mapper.readTree(
                    requireNotNull(
                        wire
                            .path("contents")
                            .single()
                            .path("parts")
                            .single()
                            .path("text")
                            .asString(),
                    ),
                )
            val wireIds = neutral.path("inputs").toList().map { requireNotNull(it.path("input_ref").asString()) }
            assertEquals(versions.map { it.submissionVersionId.toString() }, wireIds)
            assertContentEquals(
                ScheduleDslGeminiWire.requestContextBytes(mapper, request.inputCapsule, "gemini-3.8-flash"),
                http.requests.single(),
            )
            assertEquals(
                AiDispatchFingerprints.batchSource(expected),
                jdbc.queryForObject("SELECT source_fingerprint FROM ai_call_reservations", String::class.java),
            )
            assertEquals(
                AiDispatchFingerprints.batchContext(
                    ZoneId.of(input.timeZone),
                    input.searchStart,
                    input.searchEnd,
                    expected,
                ),
                jdbc.queryForObject("SELECT context_fingerprint FROM ai_call_reservations", String::class.java),
            )
            assertEquals(1, count("ai_call_reservations"))
            assertTrue(service.completeDsl(admitted.binding, admitted.attempt, response) { true })
            assertEquals("MATCHING", jdbc.queryForObject("SELECT status FROM coordination_runs", String::class.java))
        }
    }

    @Test
    fun `SDGI02 version ordered dispatch fingerprints fail actual fence despite correct capsule order`() {
        val admitted = admitted()
        val request = request(admitted)
        val input = Inverted.input()
        val wrongInputs =
            input.inputs.sortedBy { it.submissionVersionId }.map { item ->
                AiDispatchInputSnapshot(
                    item.submissionVersionId.toString(),
                    item.rawText,
                    item.locale,
                    requireNotNull(item.evidence).referenceAt.atZone(ZoneId.of(input.timeZone)).toLocalDate(),
                    item.evidence,
                )
            }
        val wrongExecution =
            request.dispatchContext.execution.copy(
                sourceFingerprint = AiDispatchFingerprints.batchSource(wrongInputs),
                contextFingerprint =
                    AiDispatchFingerprints.batchContext(
                        ZoneId.of(input.timeZone),
                        input.searchStart,
                        input.searchEnd,
                        wrongInputs,
                    ),
            )
        assertNotEquals(request.dispatchContext.execution.sourceFingerprint, wrongExecution.sourceFingerprint)
        assertNotEquals(request.dispatchContext.execution.contextFingerprint, wrongExecution.contextFingerprint)
        val wrong = request.copy(dispatchInputs = wrongInputs, dispatchContext = request.dispatchContext.copy(execution = wrongExecution))
        Loopback(mapper).use { http ->
            assertFailsWith<RuntimeException> { adapter(http).generate(wrong) }
            assertEquals(0, http.requests.size)
            assertEquals(0, count("ai_call_reservations"))
            noResult()
        }
    }

    private fun canonicalRuntimeSpec(admitted: SelectedScheduleDslInvocation): AiBudgetReserveRequest {
        val request = request(admitted)
        val bytes = ScheduleDslGeminiWire.requestContextBytes(mapper, request.inputCapsule, "gemini-3.8-flash")
        return AiBudgetReserveRequest(
            request.dispatchContext.execution,
            listOf(
                AiPhysicalCallSpec(
                    AiPhysicalOperation.FINAL_PRIMARY,
                    1,
                    hash(bytes),
                    requireNotNull(AiRuntimeDispatchProfiles.fingerprints[AiPhysicalOperation.FINAL_PRIMARY]),
                    admitted.attempt.id,
                ),
            ),
        )
    }

    private fun unconfiguredMoney() {
        authority.allow = false
        jdbc.update("UPDATE ai_room_budgets SET state='UNCONFIGURED',cap=NULL,policy_fingerprint=NULL,coverage_started_at=NULL")
    }

    @Test
    fun `SDGI03 actual canonical runtime authorization does not require configured price or cap`() {
        val admitted = admitted()
        val spec = canonicalRuntimeSpec(admitted)
        unconfiguredMoney()
        val deadline = assertNotNull(runtimeGuard.authorize(spec, clock.instant()))
        assertTrue(deadline > clock.instant())
        assertTrue(
            deadline <=
                admitted.binding.invocation.deadlineAt
                    .minusSeconds(30),
        )
        assertEquals(0, count("ai_call_reservations"))
        noResult()
        // Canonical authority only: there is no loopback HTTP or claim of combined runtime transport proof.
    }

    @Test
    fun `SDGI04 actual canonical runtime authorization denies stale owner independently of price`() {
        val admitted = admitted()
        val spec = canonicalRuntimeSpec(admitted)
        unconfiguredMoney()
        // Positive control prevents a wrong-profile rejection from masking the owner fence.
        assertNotNull(runtimeGuard.authorize(spec, clock.instant()))
        jdbc.update("UPDATE analysis_invocations SET owner_token=?", UUID(0, 8804))
        assertNull(runtimeGuard.authorize(spec, clock.instant()))
        assertEquals(0, count("ai_call_reservations"))
        noResult()
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
    ) : AutoCloseable {
        val requests: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint = URI("http://127.0.0.1:${server.address.port}/v1beta/models/gemini-3.8-flash:generateContent")

        init {
            val bytes =
                mapper.writeValueAsBytes(
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
                exchange.sendResponseHeaders(200, bytes.size.toLong())
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
