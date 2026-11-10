package com.meetme.server.coordination.application.service

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.adapter.output.integration.GeminiScheduleDslProviderAdapter
import com.meetme.server.coordination.adapter.output.integration.ScheduleDslProviderContract
import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslResponseParser
import com.meetme.server.coordination.adapter.output.persistence.JdbcScheduleIrExecutionRegistry
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.ScheduleDslCallBudget
import com.meetme.server.coordination.application.port.output.ScheduleDslResponseParser
import com.meetme.server.coordination.application.port.output.ScheduleIrExecutionRegistry
import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.coordination.domain.ir.IrExecutionTicket
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslRepository
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleIrV2Repository
import com.meetme.server.submission.application.port.output.ScheduleDslRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_V2_VERSION
import com.meetme.server.support.ScheduleDslWireFixture
import com.meetme.server.support.ScheduleIrV2SyntheticFixture
import com.meetme.server.support.mappedPortReadyPostgres
import com.sun.net.httpserver.HttpServer
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Explicit new worker only; loopback HTTP and real PostgreSQL, never operational routing or a real model. */
@SpringBootTest
@Import(ScheduleDslWorkerPostgresTest.InactiveDslConfiguration::class)
class ScheduleDslWorkerPostgresTest {
    @Autowired private lateinit var service: ScheduleDslAnalysisService

    @Autowired private lateinit var registry: ScheduleIrExecutionRegistry

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var transactions: PlatformTransactionManager
    private val f = ScheduleIrV2SyntheticFixture
    private val w = ScheduleDslWireFixture
    private lateinit var legacyBefore: Map<String, List<String>>

    @BeforeEach
    fun seedTrustedFrozenInput() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        seed()
        legacyBefore = legacySnapshot()
    }

    private fun identity() = IrExecutionIdentity(SCHEDULE_DSL_RESPONSE_CONTRACT, SCHEDULE_DSL_SEMANTIC_VERSION, f.POLICY)

    private fun policy() = w.f.policy

    private fun prepare() = service.prepare(CoordinationRunId(f.run), 1, identity(), policy(), PREPARED)

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun expected() = InstantTimeRange(Instant.parse("2027-01-08T10:00:00Z"), Instant.parse("2027-01-08T12:00:00Z"))

    private fun worker(http: SyntheticGemini) =
        ScheduleDslAnalysisWorker(
            service,
            GeminiScheduleDslProviderAdapter(
                GeminiProperties(apiKey = "synthetic-loopback-only"),
                mapper,
                ScheduleDslCallBudget { _, _, _ -> http.admissions.incrementAndGet() },
                http.endpoint,
            ),
            Clock.fixed(PROCESSED, ZoneOffset.UTC),
        )

    private fun noConsumedRows(ticket: IrExecutionTicket) {
        assertEquals(ticket, JdbcScheduleIrExecutionRegistry(jdbc, mapper).find(f.run))
        assertEquals(1, count("schedule_ir_execution_bindings"))
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
    }

    @Test
    fun `DSLG01 actual Gemini HTTP worker parses stores restores and evaluates AST intersection then replays without dispatch`() {
        val ticket = prepare()
        SyntheticGemini(200, gemini(w.json())).use { http ->
            val result = worker(http).process(ticket, Duration.ofSeconds(5))
            assertEquals(IrCalculationStatus.CALCULATED, result.status)
            assertEquals(
                listOf(expected()),
                result.candidates.candidates
                    .single()
                    .timeRanges,
            )
            assertEquals(listOf(expected()), result.recommendations.options.map { it.window })
            // A union would be18-22; the AST conjunction is19-21. Seeded legacy results are rejected/empty.
            assertEquals(1, http.requests.size)
            assertEquals(1, http.admissions.get())
            val request = mapper.readTree(http.requests.single())
            assertTrue(request.isObject)
            val content = request.path("contents").single()
            assertEquals("user", content.path("role").asString())
            val neutralText = content.path("parts").single().path("text")
            assertTrue(neutralText.isString)
            assertContentEquals(
                ScheduleDslProviderContract(
                    mapper,
                ).requestBytes(service.input(ticket), SCHEDULE_DSL_RESPONSE_CONTRACT, SCHEDULE_DSL_SEMANTIC_VERSION, SCHEDULE_AST_VERSION),
                requireNotNull(neutralText.asString()).toByteArray(Charsets.UTF_8),
            )
            val config = request.path("generationConfig")
            assertEquals("application/json", config.path("responseMimeType").asString())
            assertEquals(
                mapper.readTree(mapper.writeValueAsBytes(ScheduleDslProviderContract(mapper).responseSchema())),
                config.path("responseJsonSchema"),
            )
            assertEquals(1, config.path("candidateCount").asInt())
            assertEquals(32768, config.path("maxOutputTokens").asInt())
            assertEquals("LOW", config.path("thinkingConfig").path("thinkingLevel").asString())
            val requestText = mapper.writeValueAsString(request)
            for (token in listOf(
                SCHEDULE_DSL_RESPONSE_CONTRACT,
                SCHEDULE_DSL_SEMANTIC_VERSION,
                SCHEDULE_AST_VERSION,
                "ALL_OF",
                "ANY_OF",
                "PREFER",
            )) {
                assertTrue(requestText.contains(token))
            }
            assertEquals(result.envelope, JdbcScheduleDslRepository(jdbc, mapper).find(ticket.context))
            assertEquals(
                "3",
                jdbc.queryForObject("SELECT format_version::text FROM schedule_ir_envelopes WHERE run_id=?", String::class.java, f.run),
            )
            assertEquals(
                SCHEDULE_DSL_SEMANTIC_VERSION,
                jdbc.queryForObject("SELECT semantic_version FROM schedule_ir_envelopes WHERE run_id=?", String::class.java, f.run),
            )
            assertEquals(2, count("schedule_ir_inputs"))
            assertEquals(result, service.replay(ticket))
            assertEquals(result, worker(http).process(ticket, Duration.ofSeconds(5)))
            assertEquals(1, http.requests.size)
            assertEquals(1, http.admissions.get())
            assertEquals(legacyBefore, legacySnapshot())
            assertFailsWith<RuntimeException> { JdbcScheduleIrV2Repository(jdbc, mapper).findV2(ticket.context) }
        }
    }

    @Test
    fun `DSLG02 consume joins outer transaction and marker identity proves successful work was rolled back`() {
        val ticket = prepare()
        val marker = IllegalStateException("synthetic DSL outer rollback")
        var consumed = false
        val failure =
            assertFailsWith<IllegalStateException> {
                TransactionTemplate(transactions).executeWithoutResult {
                    val result = service.consume(ticket, w.json(), PROCESSED)
                    assertEquals(listOf(expected()), result.recommendations.options.map { it.window })
                    assertEquals(1, count("schedule_ir_envelopes"))
                    assertEquals(2, count("schedule_ir_inputs"))
                    consumed = true
                    throw marker
                }
            }
        assertTrue(failure === marker)
        assertTrue(consumed)
        noConsumedRows(ticket)
        assertEquals(legacyBefore, legacySnapshot())
    }

    @ParameterizedTest
    @ValueSource(strings = ["run_version", "source_revision", "source_text", "inactive_run", "room_mode"])
    fun `DSLG03 stale input is rejected before any provider admission or physical dispatch`(defect: String) {
        val ticket = prepare()
        when (defect) {
            "run_version" -> jdbc.update("UPDATE coordination_runs SET version=2 WHERE id=?", f.run)
            "source_revision" -> jdbc.update("UPDATE submission_versions SET revision=8 WHERE id=?", f.first)
            "source_text" ->
                jdbc.update(
                    "UPDATE submission_versions SET raw_text=? WHERE id=?",
                    "Synthetic changed frozen source.",
                    f.first,
                )
            "inactive_run" -> jdbc.update("UPDATE meeting_rooms SET active_run_id=NULL WHERE id=?", f.room)
            else -> jdbc.update("UPDATE meeting_rooms SET meeting_mode='IN_PERSON' WHERE id=?", f.room)
        }
        SyntheticGemini(200, gemini(w.json())).use { http ->
            assertFailsWith<RuntimeException> { worker(http).process(ticket, Duration.ofSeconds(5)) }
            assertEquals(0, http.requests.size)
            assertEquals(0, http.admissions.get())
        }
        noConsumedRows(ticket)
    }

    @ParameterizedTest
    @ValueSource(strings = ["policy", "contract", "semantic"])
    fun `DSLG04 immutable full selection cannot be changed on the same run`(field: String) {
        val ticket = prepare()
        val changed =
            when (field) {
                "policy" -> ticket.copy(policy = ticket.policy.copy(allowExplicitUnconstrained = true))
                "contract" -> ticket.copy(identity = ticket.identity.copy(responseContractVersion = "foreign-contract"))
                else -> ticket.copy(identity = ticket.identity.copy(irSemanticVersion = SCHEDULE_IR_V2_VERSION))
            }
        assertEquals(ticket.context, changed.context)
        assertFailsWith<RuntimeException> { registry.bind(changed) }
        assertFailsWith<RuntimeException> { service.prepare(CoordinationRunId(f.run), 1, changed.identity, changed.policy, PREPARED) }
        noConsumedRows(ticket)
        assertEquals(legacyBefore, legacySnapshot())
    }

    @ParameterizedTest
    @ValueSource(strings = ["http_failure", "invalid_json"])
    fun `DSLG05 failed actual provider response has one attempt no retry and no legacy fallback`(defect: String) {
        val ticket = prepare()
        SyntheticGemini(
            if (defect ==
                "http_failure"
            ) {
                503
            } else {
                200
            },
            if (defect == "http_failure") "{}" else gemini("not JSON"),
        ).use { http ->
            assertFailsWith<RuntimeException> { worker(http).process(ticket, Duration.ofSeconds(5)) }
            assertEquals(1, http.requests.size)
            assertEquals(1, http.admissions.get())
        }
        noConsumedRows(ticket)
        assertEquals(legacyBefore, legacySnapshot())
    }

    @Test
    fun `DSLG06 malformed AST retains claims but cannot silently calculate flat IR2 availability`() {
        val ticket = prepare()
        val document = w.document()
        w.nodes(document).last()["children"] = listOf("ar", "missing")
        SyntheticGemini(200, gemini(w.encode(document))).use { http ->
            val result = worker(http).process(ticket, Duration.ofSeconds(5))
            assertTrue(result.recommendations.options.isEmpty())
            assertEquals(
                null,
                result.envelope.programs
                    .first()
                    .program,
            )
            assertTrue(
                result.envelope.programs
                    .first()
                    .problems
                    .isNotEmpty(),
            )
            assertEquals(
                w.claims.map(f::verified),
                result.envelope.claims.results
                    .first()
                    .conditions,
            )
            assertEquals(result.envelope, JdbcScheduleDslRepository(jdbc, mapper).find(ticket.context))
            assertEquals(1, http.requests.size)
            assertEquals(1, http.admissions.get())
            assertEquals(legacyBefore, legacySnapshot())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["format", "semantic"])
    fun `DSLG07 persisted unknown format or semantic identity is rejected on fresh read`(field: String) {
        val ticket = prepare()
        service.consume(ticket, w.json(), PROCESSED)
        if (field == "format") {
            jdbc.update("UPDATE schedule_ir_envelopes SET format_version=99 WHERE run_id=?", f.run)
        } else {
            jdbc.update("UPDATE schedule_ir_envelopes SET semantic_version='schedule-ir-product-99' WHERE run_id=?", f.run)
        }
        assertFailsWith<RuntimeException> { JdbcScheduleDslRepository(jdbc, mapper).find(ticket.context) }
    }

    @Test
    fun `DSLG08 denied call admission never sends HTTP or publishes consumed results`() {
        val ticket = prepare()
        val marker = IllegalStateException("synthetic call admission denied")
        SyntheticGemini(200, gemini(w.json())).use { http ->
            val denied =
                GeminiScheduleDslProviderAdapter(
                    GeminiProperties(apiKey = "synthetic-loopback-only"),
                    mapper,
                    ScheduleDslCallBudget {
                        _,
                        _,
                        _,
                        ->
                        throw marker
                    },
                    http.endpoint,
                )
            assertFailsWith<RuntimeException> {
                ScheduleDslAnalysisWorker(service, denied, Clock.fixed(PROCESSED, ZoneOffset.UTC)).process(ticket, Duration.ofSeconds(5))
            }
            assertEquals(0, http.requests.size)
        }
        noConsumedRows(ticket)
        assertEquals(legacyBefore, legacySnapshot())
    }

    private fun gemini(json: String) =
        w.encode(
            mapOf(
                "candidates" to
                    listOf(
                        mapOf("content" to mapOf("role" to "model", "parts" to listOf(mapOf("text" to json))), "finishReason" to "STOP"),
                    ),
                "usageMetadata" to mapOf("promptTokenCount" to 10, "candidatesTokenCount" to 20, "totalTokenCount" to 30),
                "modelVersion" to "gemini-3.8-flash",
            ),
        )

    private class SyntheticGemini(
        private val status: Int,
        private val response: String,
    ) : AutoCloseable {
        val requests: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        val admissions = AtomicInteger()
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    requests.add(exchange.requestBody.use { it.readAllBytes() })
                    val bytes = response.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    exchange.close()
                }
                start()
            }
        val endpoint: URI get() = URI("http://127.0.0.1:${server.address.port}")

        override fun close() {
            server.stop(0)
        }
    }

    private fun seed() {
        jdbc.update(
            "INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,search_range_source,manual_only,collection_status,closure_reason,closed_at,created_at) VALUES (?,'syntheticir00000000001','synthetic IR2','REMOTE','Asia/Seoul','2027-01-04','2027-01-11','HOST_SPECIFIED',true,'CLOSED','MANUAL',?,?)",
            f.room,
            Timestamp.from(FIXED),
            Timestamp.from(f.reference),
        )
        for ((index, version) in listOf(f.first, f.second).withIndex()) {
            val participant = if (index == 0) f.firstParticipant else f.secondParticipant
            val text = if (index == 0) f.FIRST_TEXT else f.SECOND_TEXT
            require(text.isNotBlank())
            val guest = UUID(0, 501L + index)
            val head = UUID(0, 401L + index)
            jdbc.update(
                "INSERT INTO guest_browser_sessions(id,credential_digest,expires_at,created_at) VALUES (?,?,?,?)",
                guest,
                "synthetic-v2-guest-$index",
                Timestamp.from(PROCESSED.plusSeconds(86400)),
                Timestamp.from(f.reference),
            )
            jdbc.update(
                "INSERT INTO participants(id,room_id,guest_session_id,role,display_name,joined_at) VALUES (?,?,?,?,?,?)",
                participant,
                f.room,
                guest,
                if (index ==
                    0
                ) {
                    "HOST"
                } else {
                    "MEMBER"
                },
                "synthetic-$index",
                Timestamp.from(f.reference),
            )
            jdbc.update("INSERT INTO submission_heads(id,room_id,participant_id) VALUES (?,?,?)", head, f.room, participant)
            val evidence =
                "{\"originalRevision\":3,\"referenceAt\":\"2027-01-04T00:00:00Z\",\"supplement\":null,\"interpretation\":\"PENDING\",\"unresolvedReason\":null,\"protocol\":\"PRE_SUBMIT_V2\"}"
            jdbc.update(
                "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at,input_evidence) VALUES (?,?,7,?,'ko-KR',?,CAST(? AS jsonb))",
                version,
                head,
                text,
                Timestamp.from(f.reference.plusSeconds(300)),
                evidence,
            )
            jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", version, head)
        }
        jdbc.update("INSERT INTO submission_batches(id,room_id,fixed_at) VALUES (?,?,?)", f.batch, f.room, Timestamp.from(FIXED))
        for (version in listOf(f.first, f.second)) {
            jdbc.update("INSERT INTO submission_batch_items(batch_id,submission_version_id) VALUES (?,?)", f.batch, version)
            jdbc.update(
                "INSERT INTO structured_submission_results(batch_id,submission_version_id,conditions,rejection_code,processed_at) VALUES (?,?,'[]'::jsonb,'AMBIGUOUS_INPUT',?)",
                f.batch,
                version,
                Timestamp.from(FIXED),
            )
        }
        jdbc.update(
            "INSERT INTO coordination_runs(id,room_id,batch_id,status,created_at,version) VALUES (?,?,?,'STRUCTURING',?,1)",
            f.run,
            f.room,
            f.batch,
            Timestamp.from(FIXED),
        )
        jdbc.update("UPDATE meeting_rooms SET active_run_id=? WHERE id=?", f.run, f.room)
    }

    private fun legacySnapshot(): Map<String, List<String>> =
        listOf(
            "meeting_rooms",
            "participants",
            "submission_heads",
            "submission_versions",
            "submission_batches",
            "submission_batch_items",
            "structured_submission_results",
            "coordination_runs",
            "candidates",
            "recommendation_analyses",
            "recommendation_options",
            "recommendation_variants",
            "recommendation_selections",
        ).associateWith { table ->
            jdbc
                .queryForList(
                    "SELECT to_jsonb(t)::text FROM $table t ORDER BY to_jsonb(t)::text",
                    String::class.java,
                ).map {
                    requireNotNull(it)
                }
        }

    @TestConfiguration(proxyBeanMethods = false)
    class InactiveDslConfiguration {
        @Bean fun dslRegistry(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
        ): ScheduleIrExecutionRegistry = JdbcScheduleIrExecutionRegistry(jdbc, mapper)

        @Bean fun dslParser(mapper: ObjectMapper): ScheduleDslResponseParser =
            StrictScheduleDslResponseParser(mapper, SCHEDULE_DSL_RESPONSE_CONTRACT)

        @Bean fun dslRepository(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
        ): ScheduleDslRepository = JdbcScheduleDslRepository(jdbc, mapper)

        @Bean fun dslService(
            runs: CoordinationRunRepository,
            rooms: MeetingRoomRepository,
            submissions: SubmissionRepository,
            registry: ScheduleIrExecutionRegistry,
            repository: ScheduleDslRepository,
            parser: ScheduleDslResponseParser,
        ) = ScheduleDslAnalysisService(runs, rooms, submissions, registry, repository, parser)
    }

    companion object {
        private val FIXED = Instant.parse("2027-01-04T01:00:00Z")
        private val PREPARED = Instant.parse("2027-01-04T02:00:00Z")
        private val PROCESSED = Instant.parse("2027-01-04T02:01:00Z")
        private val postgres = mappedPortReadyPostgres().apply { start() }

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("meetme.reliability.enabled") { false }
            registry.add("meetme.data-retention.enabled") { false }
            registry.add("meetme.rate-limit.enabled") { false }
            registry.add("meetme.gemini.api-key") { "" }
            registry.add("meetme.openai.api-key") { "" }
            registry.add("meetme.typesafe.api-key") { "" }
        }

        @JvmStatic @AfterAll
        fun stop() {
            postgres.stop()
        }
    }
}
