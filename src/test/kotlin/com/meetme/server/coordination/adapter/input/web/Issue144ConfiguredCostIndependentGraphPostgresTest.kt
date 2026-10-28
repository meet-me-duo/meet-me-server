package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.config.ManagedAiRuntimeSelection
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.adapter.output.integration.OpenAiLunaNaturalLanguageParserAdapter
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.JitterPort
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.application.service.GeminiBatchProcessor
import com.meetme.server.coordination.application.service.GeminiProcessingPersistenceService
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.precheck.adapter.output.classification.AmbiguityClassifierRuntimeProperties
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderHttpTransport
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.precheck.application.service.PreSubmissionCheckWorker
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.application.port.output.AiDispatchModePort
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.testsupport.AccountPrincipalTestFixture
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** OA01/03 actual Spring, PG claims and canonical loopback TLS; no monetary policy or artificial quote. */
@Import(DurableAnalysisInvocationPostgresTest.ControlledTime::class, Issue144ConfiguredCostIndependentGraphPostgresTest.Transport::class)
class Issue144ConfiguredCostIndependentGraphPostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var worker: PreSubmissionCheckWorker

    @Autowired private lateinit var selection: ManagedAiRuntimeSelection

    @Autowired private lateinit var classifier: AmbiguityClassifierRuntimeProperties

    @Autowired private lateinit var gate: AiHttpDispatchGate

    @Autowired private lateinit var dispatchMode: AiDispatchModePort

    @Autowired private lateinit var clock: Clock

    @Autowired private lateinit var time: DurableAnalysisInvocationPostgresTest.DurableClock

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var structured: StructuredSubmissionRepository

    @Autowired private lateinit var attempts: CoordinationAttemptRepository

    @Autowired private lateinit var invocations: AnalysisInvocationRepository

    @Autowired private lateinit var persistence: GeminiProcessingPersistenceService

    @Autowired private lateinit var prechecks: PrecheckRepository

    @BeforeEach
    fun resetClockAndProxy() {
        time.now = NOW
        proxy.clear()
    }

    @ParameterizedTest
    @CsvSource(
        "false,false,false",
        "true,false,false",
        "false,true,false",
        "true,true,false",
        "false,false,true",
        "true,false,true",
        "false,true,true",
        "true,true,true",
    )
    fun `OA04 new and existing rooms analyze regardless of a missing or exhausted monetary ledger`(
        existing: Boolean,
        exhausted: Boolean,
        expiredPolicy: Boolean,
    ) {
        assertEquals(0.73, classifier.minimumConfidence)
        assertTrue(dispatchMode.isEnabled())
        assertTrue(gate.properties.enabled)
        assertFalse(gate.enforceBudget)
        if (expiredPolicy) time.now = EXPIRES.plusSeconds(1)
        val created = create(existing)
        if (exhausted) exhaustedLedger(created.id)
        val originalBudget = budgetRow(created.id)
        val member = accounts.newAccount("synthetic production member")
        mvc
            .perform(
                post("/api/rooms/{code}/participants", created.code)
                    .cookie(member)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isCreated)
        for ((owner, raw) in listOf(created.host to "first synthetic condition", member to "second synthetic condition")) {
            proxy.enqueue(JEV_REVIEW)
            proxy.enqueue(GENERATION)
            submit(created.code, owner, raw)
            assertTrue(assertDoesNotThrow { worker.runOne() })
            val response =
                mvc
                    .perform(get("/api/rooms/{code}/submission/checks/current", created.code).cookie(owner))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString
            val document = mapper.readTree(response)
            assertEquals("CLEAR", document.path("check").path("status").asText())
            assertTrue(document.path("submission_complete").asBoolean())
            assertEquals(raw, document.path("accepted").path("raw_text").asText())
        }
        assertEquals(4, proxy.requests.size, "Each personal review still uses Jev and Luna; financial state is not admission")
        assertFalse(worker.runOne())
        val active = assertNotNull(jdbc.queryForObject("SELECT active_run_id FROM meeting_rooms WHERE id=?", UUID::class.java, created.id))
        val run = assertNotNull(runs.findById(CoordinationRunId(active)))
        val payload =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to "3",
                    "results" to
                        run.batch.submissionVersionIds.map {
                            mapOf(
                                "input_ref" to it.value.toString(),
                                "rejection_code" to null,
                                "conditions" to
                                    listOf(
                                        mapOf(
                                            "type" to "TIME_WINDOW",
                                            "polarity" to "AVAILABLE",
                                            "date" to "2026-10-07",
                                            "day_of_week" to null,
                                            "start_time" to "19:00",
                                            "end_time" to "21:00",
                                        ),
                                    ),
                            )
                        },
                ),
            )
        proxy.enqueue(
            mapper.writeValueAsString(
                mapOf(
                    "status" to "completed",
                    "output" to
                        listOf(
                            mapOf(
                                "type" to "message",
                                "content" to listOf(mapOf("type" to "output_text", "text" to payload)),
                            ),
                        ),
                    "usage" to
                        mapOf(
                            "input_tokens" to 256,
                            "output_tokens" to 32,
                            "output_tokens_details" to mapOf("reasoning_tokens" to 20),
                        ),
                ),
            ),
        )
        val fallback =
            OpenAiLunaNaturalLanguageParserAdapter(
                OpenAiProperties(apiKey = "synthetic-not-real"),
                mapper,
                proxy.client(),
                URI("https://api.openai.com/v1/responses"),
                dispatchGate = gate,
            )
        val localPrimaryFailure = NaturalLanguageParserPort { throw NaturalLanguageParserException(ParserFailureKind.NETWORK) }
        GeminiBatchProcessor(
            runs,
            submissions,
            structured,
            attempts,
            localPrimaryFailure,
            IdGenerator { UUID.randomUUID() },
            RetryDelayPort {},
            JitterPort { 0 },
            MonotonicTimePort { System.nanoTime() },
            clock,
            persistence,
            fallbackParser = fallback,
            invocationRepository = invocations,
            prechecks = prechecks,
            dispatchMode = dispatchMode,
        ).process(run.batch.id)
        assertEquals(5, proxy.requests.size)
        assertEquals(
            listOf("api.typesafe.ai", "api.openai.com", "api.typesafe.ai", "api.openai.com", "api.openai.com"),
            proxy.requests.map { it.host },
        )
        assertTrue(proxy.requests.all { it.method == "POST" })
        assertTrue(proxy.failures.isEmpty())
        assertEquals(CoordinationStatus.MATCHING, assertNotNull(runs.findById(run.id)).status)
        val invocation =
            jdbc.queryForMap(
                "SELECT gemini_attempts,luna_attempts FROM analysis_invocations WHERE coordination_run_id=?",
                run.id.value,
            )
        assertEquals(4, (invocation["gemini_attempts"] as Number).toInt())
        assertEquals(1, (invocation["luna_attempts"] as Number).toInt())
        assertEquals(originalBudget, budgetRow(created.id))
        for (table in listOf("ai_call_reservations", "ai_call_observations", "ai_budget_profiles")) {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))
        }
    }

    private fun exhaustedLedger(id: UUID) {
        // Valid synthetic historical metadata, with held + settled already above cap.
        jdbc.update(
            "UPDATE ai_room_budgets SET state='READY',epoch_id=?,unit_code='TEST_SUBUNIT',unit_scale=0," +
                "cap=1,settled=1,held=2,policy_fingerprint=?,coverage_started_at=? WHERE room_id=?",
            UUID.randomUUID(),
            "f".repeat(64),
            NOW.atOffset(ZoneOffset.UTC),
            id,
        )
    }

    private fun budgetRow(id: UUID) = jdbc.queryForMap("SELECT * FROM ai_room_budgets WHERE room_id=?", id)

    private fun create(existing: Boolean): Created {
        val host = accounts.newAccount("synthetic production host")
        if (!existing) {
            val response =
                mvc
                    .perform(
                        post("/api/rooms")
                            .cookie(host)
                            .header("Origin", ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_ROOM),
                    ).andExpect(status().isCreated)
                    .andReturn()
                    .response
            val code = document(response).getValue("invite_code").toString()
            val id = assertNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code=?", UUID::class.java, code))
            return Created(id, code, host)
        }
        val id = UUID.randomUUID()
        val code = id.toString().replace("-", "").take(22)
        val old = NOW.minusSeconds(86400).atOffset(ZoneOffset.UTC)
        jdbc.update(
            "INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date," +
                "search_end_date,search_range_source,expected_participants,manual_only,collection_status,created_at) " +
                "VALUES (?,?,'existing synthetic','REMOTE','Asia/Seoul','2026-10-07','2026-10-12','HOST_SPECIFIED',2,false,'COLLECTING',?)",
            id,
            code,
            old,
        )
        jdbc.update(
            "INSERT INTO participants(id,room_id,user_id,role,display_name,joined_at) VALUES (?,?,?,'HOST','existing host',?)",
            UUID.randomUUID(),
            id,
            UUID.fromString(host.value.removePrefix(AccountPrincipalTestFixture.PREFIX)),
            old,
        )
        return Created(id, code, host)
    }

    private fun submit(
        code: String,
        owner: Cookie,
        raw: String,
    ) {
        mvc
            .perform(
                post("/api/rooms/{code}/submission/checks", code)
                    .cookie(owner)
                    .header("Origin", ORIGIN)
                    .header("Accept-Language", "ko-KR")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(mapOf("request_id" to UUID.randomUUID(), "expected_revision" to 0, "raw_text" to raw)),
                    ),
            ).andExpect(status().isAccepted)
    }

    private data class Created(
        val id: UUID,
        val code: String,
        val host: Cookie,
    )

    @TestConfiguration
    class Transport {
        @Bean @Primary
        fun loopbackOnlyTransport() = PrecheckProviderHttpTransport(proxy.client())
    }

    companion object {
        private val NOW = Instant.parse("2026-10-07T00:00:00Z")
        private val directory = Files.createTempDirectory("issue144-production-mock-")
        private val proxy = Issue131CanonicalTlsProxy(directory)
        private val EXPIRES = NOW.plusSeconds(3600)
        private val profiles =
            listOf(
                "c8d01d4a04aa077e0488400dc3b4791753b8428db48c6712aa78f903608a4dfc",
                "658a65563b34d43f854cc47dcd93d274d23789745e718b5f7ef49e1b59e8e307",
                "86e4593b78ec903e1c9b4bbbffe204d2b8343e1752d2b6353703ce3fb9b65714",
                "0a3fc94ba00d368b4e7c9a41d0b231faab5058aa893dc11082ef786fc0a08d04",
                "9c5021916c80178f58d235e55bbbad4889875560b710a505fbde5dedd1a824ab",
            )
        private val operations =
            listOf(
                "PRECHECK_CLASSIFICATION",
                "PRECHECK_COUNT",
                "PRECHECK_GENERATION",
                "FINAL_PRIMARY",
                "FINAL_FALLBACK",
            )

        private fun policyFingerprint(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private val policyRows =
            operations
                .mapIndexed { index, operation ->
                    val evidence = policyFingerprint("synthetic:$operation".toByteArray())
                    """{"operation":"$operation","profileFingerprint":"${profiles[index]}","evidenceFingerprint":"$evidence",""" +
                        """"maximumAmount":"${index + 3}","validUntil":"$EXPIRES"}"""
                }.joinToString(",")
        private val policy =
            Files.writeString(
                directory.resolve("policy.json"),
                """{"protocol":"meetme-managed-ai-policy-v1","state":"CONFIGURED",""" +
                    """"unit":{"code":"TEST_SUBUNIT","scale":0},"roomCap":"1000","minimumConfidence":0.73,"operations":[$policyRows]}""",
            )
        private val policyHash = policyFingerprint(Files.readAllBytes(policy))
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }
        private const val CREATE_ROOM =
            """{"purpose":"synthetic production","meeting_mode":"REMOTE","host_display_name":"host",""" +
                """"manual_only":false,"expected_participants":2,"search_start_date":"2026-10-07","search_end_date":"2026-10-12"}"""
        private const val JEV_REVIEW =
            """{"model":"jev-1.14.0","answers":{"material_ambiguity":{"type":"choice",""" +
                """"choice":"NEEDS_REVIEW","confidence":0.85,"probabilities":{"CLEAR":0.05,"NEEDS_REVIEW":0.9,"INDETERMINATE":0.05}}},""" +
                """"usage":{"input_tokens":100,"output_tokens":0}}"""
        private const val GENERATION =
            """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text",""" +
                """"text":"{\"verdict\":\"CLEAR\",\"prompt\":null,\"sourceExcerpt\":null,\"choices\":null}"}]}],""" +
                """"usage":{"input_tokens":256,"output_tokens":32,"output_tokens_details":{"reasoning_tokens":20}}}"""

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registerCorrectionDatabase(registry, postgres)
            for (name in listOf(
                "meetme.precheck.provider.enabled",
                "meetme.precheck.decision-routing.enabled",
                "meetme.precheck.classifier.enabled",
            )) {
                registry.add(name) { true }
            }
            registry.add("meetme.ai.budget.enabled") { true }
            registry.add("meetme.precheck.classifier.minimum-confidence") { 0.73 }
            registry.add("meetme.ai.budget.policy-path") { policy.toString() }
            registry.add("meetme.ai.budget.policy-sha256") { policyHash }
            for (name in listOf("meetme.gemini.api-key", "meetme.openai.api-key", "meetme.typesafe.api-key")) {
                registry.add(name) { "synthetic-not-real" }
            }
        }

        @JvmStatic @AfterAll
        fun stop() {
            proxy.close()
            postgres.stop()
        }
    }
}
