package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.config.ManagedAiRuntimeSelection
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderHttpTransport
import com.meetme.server.precheck.application.service.PreSubmissionCheckWorker
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.shared.application.port.output.NewRoomAiBudgetPort
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Synthetic approved configuration only; real PG/fence/authority/worker, canonical URLs, loopback TLS, no READY seed. */
@Import(DurableAnalysisInvocationPostgresTest.ControlledTime::class, Issue131ManagedRuntimeRoomPostgresTest.Transport::class)
class Issue131ManagedRuntimeRoomPostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var worker: PreSubmissionCheckWorker

    @Autowired private lateinit var budgets: DurableAiBudgetPort

    @Autowired private lateinit var bootstrap: NewRoomAiBudgetPort

    @Autowired private lateinit var selection: ManagedAiRuntimeSelection

    @Autowired private lateinit var time: DurableAnalysisInvocationPostgresTest.DurableClock

    @BeforeEach
    fun resetSyntheticContext() {
        time.now = NOW
        proxy.clear()
    }

    @Test
    fun `MB06 actual CREATE initializes only its own brand new zero ledger from explicit immutable policy`() {
        val created = create()
        val actual = assertNotNull(budgets.snapshot(created.id))
        assertEquals("READY", actual.state.name)
        assertNotNull(actual.epochId)
        assertEquals(BigInteger("1000"), actual.cap)
        assertEquals(BigInteger.ZERO, actual.held)
        assertEquals(BigInteger.ZERO, actual.settled)
        assertEquals(policyHash, budgetRow(created.id)["policy_fingerprint"])
        val unit = assertNotNull(actual.unit)
        assertEquals("TEST_SUBUNIT", unit.code)
        assertEquals(0, unit.scale)
        assertEquals(
            created.at,
            assertNotNull(
                jdbc.queryForObject(
                    "SELECT coverage_started_at FROM ai_room_budgets WHERE room_id=?",
                    OffsetDateTime::class.java,
                    created.id,
                ),
            ).toInstant(),
        )
        assertTrue(selection.enabled)
        assertEquals(0, proxy.connections.get())
        assertEquals(0, count("ai_call_reservations"))
    }

    @Test
    fun `MB09 actual CREATE then own POST worker A count B uses canonical physical network and same PG budget without seed`() {
        val created = create()
        val raw = "10월 10일  8시에 가능해요 😀"
        proxy.enqueue(DECISION)
        proxy.enqueue("""{"totalTokens":256}""")
        proxy.enqueue(GENERATION)
        submit(created, raw)
        assertTrue(assertDoesNotThrow { worker.runOne() })
        val current =
            mvc
                .perform(get("/api/rooms/{code}/submission/checks/current", created.code).cookie(created.host))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = mapper.readTree(current)
        assertEquals("CLEAR", json.path("check").path("status").asText())
        assertTrue(json.path("submission_complete").asBoolean())
        assertEquals(raw, json.path("candidate").path("raw_text").asText())
        assertEquals(raw, json.path("accepted").path("raw_text").asText())
        assertEquals("PRECHECK", json.path("accepted").path("confirmation_source").asText())
        assertEquals(3, proxy.connections.get(), "Each counted attempt must reach the real loopback socket exactly once")
        assertEquals(3, proxy.requests.size)
        assertTrue(proxy.failures.isEmpty())
        assertEquals(
            listOf(
                "api.typesafe.ai",
                "generativelanguage.googleapis.com",
                "generativelanguage.googleapis.com",
            ),
            proxy.requests.map {
                it.host
            },
        )
        assertEquals(
            listOf(
                "/v1/systemone",
                "/v1beta/models/gemini-3.8-flash:countTokens",
                "/v1beta/models/gemini-3.8-flash:generateContent",
            ),
            proxy.requests.map {
                it.path
            },
        )
        assertTrue(proxy.requests.all { it.method == "POST" })
        val decisionWire = mapper.readTree(proxy.requests.first().body)
        assertEquals("jev-1.14.0", decisionWire.path("model").asText())
        assertEquals(raw, decisionWire.path("state").path("raw_text").asText())
        val snapshot = assertNotNull(budgets.snapshot(created.id))
        assertEquals("READY", snapshot.state.name)
        assertEquals(BigInteger("12"), snapshot.held, "Usage is observed but does not authorize a complete-bill refund")
        assertEquals(BigInteger.ZERO, snapshot.settled)
        val rows =
            jdbc.queryForList(
                "SELECT operation,epoch_id,policy_fingerprint,whole_payload_fingerprint,maximum_amount FROM ai_call_reservations WHERE room_id=? ORDER BY operation",
                created.id,
            )
        assertEquals(3, rows.size)
        assertEquals(setOf("PRECHECK_CLASSIFICATION", "PRECHECK_COUNT", "PRECHECK_GENERATION"), rows.map { it["operation"] }.toSet())
        assertTrue(rows.all { it["epoch_id"] == snapshot.epochId && it["policy_fingerprint"] == policyHash })
        assertEquals(3, rows.map { it["whole_payload_fingerprint"] }.toSet().size)
        val observations =
            jdbc.queryForList(
                "SELECT input_tokens,output_tokens,usage_complete FROM ai_call_observations o JOIN ai_call_reservations r ON r.id=o.reservation_id WHERE r.room_id=? AND kind='USAGE'",
                created.id,
            )
        assertEquals(3, observations.size)
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM submission_versions v JOIN submission_heads h ON h.id=v.submission_id WHERE h.room_id=? AND v.raw_text=?",
                Int::class.java,
                created.id,
                raw,
            ),
        )
        assertFalse(worker.runOne())
        assertEquals(3, proxy.connections.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] updateRoom={0}")
    @ValueSource(booleans = [false, true])
    fun `MB07 previously committed UNCONFIGURED room cannot be adopted even with exact createdAt or a current room update`(
        updateRoom: Boolean,
    ) {
        val old = committedUnconfigured()
        val before = budgetRow(old)
        assertFails {
            TransactionTemplate(transactionManager).executeWithoutResult {
                if (updateRoom) jdbc.update("UPDATE meeting_rooms SET purpose=purpose WHERE id=?", old)
                bootstrap.initialize(old, createdAt(old))
            }
        }
        assertEquals(before, budgetRow(old))
        assertEquals(0, proxy.connections.get())
    }

    @Test
    fun `MB07 historical UNKNOWN room remains unchanged rather than receiving fresh capacity`() {
        val old = committedUnconfigured()
        jdbc.update("UPDATE ai_room_budgets SET state='HISTORICAL_UNKNOWN' WHERE room_id=?", old)
        val before = budgetRow(old)
        assertFails { TransactionTemplate(transactionManager).executeWithoutResult { bootstrap.initialize(old, createdAt(old)) } }
        assertEquals(before, budgetRow(old))
        assertEquals(0, proxy.connections.get())
    }

    @Test
    fun `MB07 repeating bootstrap cannot reset configured capacity or holds`() {
        val created = create()
        // Synthetic existing hold fixture only; the positive CREATE and managed-dispatch tests never write READY.
        jdbc.update("UPDATE ai_room_budgets SET held=17,version=version+1 WHERE room_id=?", created.id)
        val before = budgetRow(created.id)
        assertFails { TransactionTemplate(transactionManager).executeWithoutResult { bootstrap.initialize(created.id, created.at) } }
        assertEquals(before, budgetRow(created.id))
        assertEquals(BigDecimal("17"), budgetRow(created.id)["held"])
    }

    @Test
    fun `MB08 policy expiring after startup rolls back actual CREATE room host and ledger as one transaction`() {
        val host = accounts.newAccount("expired-create")
        val before = listOf(count("meeting_rooms"), count("participants"), count("ai_room_budgets"))
        time.now = EXPIRES.plusSeconds(1)
        mvc.perform(createRequest(host)).andExpect(status().isInternalServerError)
        assertEquals(before, listOf(count("meeting_rooms"), count("participants"), count("ai_room_budgets")))
        assertEquals(0, proxy.connections.get())
    }

    @Test
    fun `MB08 expired operation authority permits no provider socket and does not replace preserved input`() {
        val created = create()
        time.now = EXPIRES.plusSeconds(1)
        val raw = "보존해야 하는 합성 원문"
        submit(created, raw)
        assertTrue(assertDoesNotThrow { worker.runOne() })
        assertEquals(0, proxy.connections.get())
        assertEquals(0, count("ai_call_reservations"))
        assertEquals(BigInteger.ZERO, assertNotNull(budgets.snapshot(created.id)).held)
        assertEquals(raw, jdbc.queryForObject("SELECT raw_text FROM precheck_candidates WHERE room_id=?", String::class.java, created.id))
        val current =
            mvc
                .perform(get("/api/rooms/{code}/submission/checks/current", created.code).cookie(created.host))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertTrue(mapper.readTree(current).path("accepted").isNull)
    }

    private fun create(): Created {
        val host = accounts.newAccount("new-room-host")
        val response =
            mvc
                .perform(createRequest(host))
                .andExpect(status().isCreated)
                .andReturn()
                .response
        val code = document(response).getValue("invite_code").toString()
        val id = assertNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code=?", UUID::class.java, code))
        return Created(id, code, host, createdAt(id))
    }

    private fun createRequest(host: Cookie) =
        post("/api/rooms")
            .cookie(host)
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """{"purpose":"synthetic runtime binding","meeting_mode":"REMOTE","host_display_name":"host","manual_only":false,"expected_participants":2,"search_start_date":"2026-10-07","search_end_date":"2026-10-12"}""",
            )

    private fun submit(
        created: Created,
        raw: String,
    ) {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "request_id" to UUID.randomUUID().toString(),
                    "expected_revision" to 0,
                    "raw_text" to raw,
                    "locale" to "ko-KR",
                ),
            )
        mvc
            .perform(
                post("/api/rooms/{code}/submission/checks", created.code)
                    .cookie(created.host)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isAccepted)
    }

    private fun committedUnconfigured(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO meeting_rooms(id,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,search_range_source,expected_participants,manual_only,collection_status,created_at,invite_code) VALUES (?,'old synthetic','REMOTE','Asia/Seoul','2026-10-07','2026-10-12','HOST_SPECIFIED',2,false,'COLLECTING',?,?)",
            id,
            NOW.atOffset(ZoneOffset.UTC),
            id.toString().replace("-", "").take(22),
        )
        assertEquals("UNCONFIGURED", budgetRow(id)["state"])
        return id
    }

    private fun createdAt(id: UUID): Instant =
        assertNotNull(jdbc.queryForObject("SELECT created_at FROM meeting_rooms WHERE id=?", OffsetDateTime::class.java, id)).toInstant()

    private fun budgetRow(id: UUID): Map<String, Any?> = jdbc.queryForMap("SELECT * FROM ai_room_budgets WHERE room_id=?", id)

    private fun count(table: String): Int = assertNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private data class Created(
        val id: UUID,
        val code: String,
        val host: Cookie,
        val at: Instant,
    )

    @TestConfiguration
    class Transport {
        @Bean @Primary
        fun canonicalLoopbackTransport() = PrecheckProviderHttpTransport(proxy.client())
    }

    companion object {
        private val NOW = Instant.parse("2026-10-07T00:00:00Z")
        private val EXPIRES = NOW.plusSeconds(3600)
        private val directory = Files.createTempDirectory("issue131-synthetic-policy-")
        private val proxy = Issue131CanonicalTlsProxy(directory)
        private val profiles =
            listOf(
                "c8d01d4a04aa077e0488400dc3b4791753b8428db48c6712aa78f903608a4dfc",
                "658a65563b34d43f854cc47dcd93d274d23789745e718b5f7ef49e1b59e8e307",
                "b9ce9802d9dc3f4396f49c796cfe173fda708c6a35ac40c79d5bdc5091419363",
                "0a3fc94ba00d368b4e7c9a41d0b231faab5058aa893dc11082ef786fc0a08d04",
                "9c5021916c80178f58d235e55bbbad4889875560b710a505fbde5dedd1a824ab",
            )
        private val operations =
            listOf("PRECHECK_CLASSIFICATION", "PRECHECK_COUNT", "PRECHECK_GENERATION", "FINAL_PRIMARY", "FINAL_FALLBACK")
        private val policy =
            Files.writeString(
                directory.resolve("policy.json"),
                """{"protocol":"meetme-managed-ai-policy-v1","state":"CONFIGURED",""" +
                    """"unit":{"code":"TEST_SUBUNIT","scale":0},"roomCap":"1000",""" +
                    """"minimumConfidence":0.73,"operations":[""" +
                    operations
                        .mapIndexed {
                            i,
                            op,
                            ->
                            """{"operation":"$op","profileFingerprint":"${profiles[i]}","evidenceFingerprint":"${hash(
                                "synthetic:$op",
                            )}","maximumAmount":"${i + 3}","validUntil":"$EXPIRES"}"""
                        }.joinToString(",") +
                    "]}",
            )
        private val policyHash = hash(Files.readString(policy))
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }
        private const val DECISION =
            """{"model":"jev-1.14.0","answers":{"material_ambiguity":{"type":"choice",""" +
                """"choice":"NEEDS_REVIEW","confidence":0.85,"probabilities":{"CLEAR":0.05,""" +
                """"NEEDS_REVIEW":0.9,"INDETERMINATE":0.05}}},"usage":{"input_tokens":100,"output_tokens":0}}"""
        private const val GENERATION =
            """{"candidates":[{"content":{"parts":[{"text":"{\"verdict\":\"CLEAR\"}"}]},""" +
                """"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":256,"candidatesTokenCount":12,"thoughtsTokenCount":20}}"""

        private fun hash(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
                "%02x".format(it)
            }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registerCorrectionDatabase(registry, postgres)
            registry.add("meetme.precheck.provider.enabled") { true }
            registry.add("meetme.precheck.decision-routing.enabled") { true }
            registry.add("meetme.precheck.classifier.enabled") { true }
            registry.add("meetme.precheck.classifier.minimum-confidence") { 0.73 }
            registry.add("meetme.ai.budget.enabled") { true }
            registry.add("meetme.ai.budget.policy-path") { policy.toString() }
            registry.add("meetme.ai.budget.policy-sha256") { policyHash }
            registry.add("meetme.gemini.api-key") { "synthetic-not-a-real-key" }
            registry.add("meetme.typesafe.api-key") { "synthetic-not-a-real-key" }
        }

        @JvmStatic @AfterAll
        fun stopFixture() {
            proxy.close()
            postgres.stop()
        }
    }
}
