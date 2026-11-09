package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.ManagedAiRuntimeSelection
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.config.SelectedScheduleDslAutoConfiguration
import com.meetme.server.config.SelectedScheduleDslPhysicalAuthority
import com.meetme.server.coordination.adapter.output.integration.GeminiSelectedScheduleDslProviderAdapter
import com.meetme.server.coordination.adapter.output.integration.OpenAiLunaSelectedScheduleDslProviderAdapter
import com.meetme.server.coordination.adapter.output.integration.SelectedApprovedTermsProviderPostgresTest
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslInvocation
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderPort
import com.meetme.server.coordination.application.service.CoordinationEventDispatcher
import com.meetme.server.coordination.application.service.SelectedScheduleDslProcessingPersistenceService
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.meetingroom.application.service.CollectionClosureService
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.support.SelectedDslPhysicalInputFixture
import com.meetme.server.support.mappedPortReadyPostgres
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.convention.TestBean
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.ArrayDeque
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F

/** Real RANDOM_PORT HTTP/JWT, event Spring beans, PG and loopback gated providers; synthetic accepted semantics only. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SelectedOperatingHttpPostgresTest.LocalFixtures::class, SelectedApprovedTermsProviderPostgresTest.AuthorityConfig::class)
class SelectedOperatingHttpPostgresTest {
    @LocalServerPort private var port = 0

    @Autowired private lateinit var context: ApplicationContext

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var tokens: AccessTokenPort

    // Replace the existing definition before its factory runs; no extra selection bean or primary ambiguity.
    @TestBean(name = "managedAiRuntimeSelection", methodName = "syntheticMonetarySelection", enforceOverride = true)
    private lateinit var selectedMonetaryRuntime: ManagedAiRuntimeSelection

    @Autowired private lateinit var credentials: GuestCredentialPort

    @Autowired private lateinit var ids: ClosureIds

    @Autowired private lateinit var clock: HttpClock

    @Autowired private lateinit var providers: LocalProviders

    @Autowired private lateinit var events: CoordinationEventDispatcher

    // Resolve new graph only inside actors: absent registration must be assertion RED, not context preparation failure.
    private val routes get() = context.getBean(FinalAnalysisRouteRegistry::class.java)
    private val publication get() = context.getBean(SelectedScheduleDslProcessingPersistenceService::class.java)
    private val gemini get() = context.getBean("selectedScheduleDslGeminiProvider", SelectedScheduleDslProviderPort::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private lateinit var hostToken: String
    private lateinit var outsiderToken: String

    @BeforeEach
    fun freshOpenRoomAndAcceptedNativeInputs() {
        jdbc.execute("TRUNCATE outbox_events,meeting_rooms,guest_browser_sessions,account_users CASCADE")
        clock.set(F.selectedAt)
        ids.clear()
        providers.calls.set(0)
        providers.lunaCalls.set(0)
        seedOpenNative()
        providers.reply.set(response3())
        hostToken = tokens.issue(AccountPrincipal(HOST, "synthetic-host"))
        outsiderToken = tokens.issue(AccountPrincipal(OUTSIDER, "synthetic-outsider"))
        // Scope deterministic ids to the upcoming real closure transaction, not its domain behavior.
        ids.arm(F.ir.batch, F.ir.run, UUID(0, 9941))
    }

    @Test
    fun `SOHTTP00 selected production Boot configuration is registered through metadata`() {
        assertSelectedGraph()
        assertEquals(0, providers.calls.get())
    }

    @Test
    fun `SOHTTP01 JWT opt in close and actual structuring event publish authentic HTTP recommendations`() {
        assertSelectedGraph()
        val rejected = postClose(outsiderToken)
        assertEquals(403, rejected.statusCode())
        assertEquals(0, count("final_analysis_routes"))
        closeTyped()
        val selected = assertNotNull(routes.find(F.ir.run)).choice
        assertTrue(selected is FinalAnalysisRouteChoice.DslApprovedTermsV3)
        assertEquals(1, count("outbox_events"))
        assertEquals(
            CollectionClosureService.STRUCTURING_REQUESTED,
            jdbc.queryForObject("SELECT event_type FROM outbox_events", String::class.java),
        )
        events.process(CollectionClosureService.STRUCTURING_REQUESTED, SubmissionBatchId(F.ir.batch))
        assertEquals("COMPLETED", status())
        assertEquals(1, providers.calls.get())
        assertEquals(0, providers.lunaCalls.get())
        assertEquals(1, count("coordination_attempts"))
        assertEquals(1, count("ai_call_reservations"))
        assertEquals(0, count("structured_submission_results"))
        val before = resultRows()
        assertPublicResults()
        assertPublicResults()
        assertEquals(401, get("recommendations").statusCode())
        assertEquals(403, get("recommendations", bearer = outsiderToken).statusCode())
        assertEquals(401, get("recommendations", bearer = tamperSignature(hostToken)).statusCode())
        assertEquals(before, resultRows())
        assertEquals(1, providers.calls.get())
    }

    @Test
    fun `SOHTTP02 actual matching event resumes original winner without another provider or flat matching`() {
        assertSelectedGraph()
        closeTyped()
        val admitted = publishToMatchingThroughActualProvider()
        val before = upstreamRows()
        events.process(CollectionClosureService.MATCHING_REQUESTED, SubmissionBatchId(F.ir.batch))
        assertEquals("COMPLETED", status())
        assertEquals(before, upstreamRows())
        assertEquals(admitted.attempt.id, jdbc.queryForObject("SELECT winner_attempt_id FROM analysis_invocations", UUID::class.java))
        assertEquals(1, providers.calls.get())
        assertEquals(1, count("ai_call_reservations"))
        assertEquals(0, count("structured_submission_results"))
        assertPublicResults()
        val complete = resultRows()
        events.process(CollectionClosureService.MATCHING_REQUESTED, SubmissionBatchId(F.ir.batch))
        assertEquals(complete, resultRows())
        assertEquals(1, providers.calls.get())
    }

    @Test
    fun `SOHTTP03 expired original matching event cannot renew deadline or expose recommendations`() {
        assertSelectedGraph()
        closeTyped()
        val admitted = publishToMatchingThroughActualProvider()
        val before = upstreamRows()
        clock.set(
            admitted.binding.invocation.deadlineAt
                .plusNanos(1),
        )
        events.process(CollectionClosureService.MATCHING_REQUESTED, SubmissionBatchId(F.ir.batch))
        assertTrue(status() != "COMPLETED")
        assertEquals(0, count("recommendation_analyses"))
        assertEquals(0, count("candidates"))
        assertEquals(0, count("structured_submission_results"))
        assertEquals(before, upstreamRows())
        assertEquals(1, providers.calls.get())
        assertEquals(1, count("coordination_attempts"))
        assertEquals(1, count("ai_call_reservations"))
        val response = get("recommendations", bearer = hostToken)
        assertEquals(409, response.statusCode())
        assertEquals("CANDIDATES_NOT_READY", document(response).getValue("code"))
    }

    @Test
    fun `SOHTTP04 fractional close clock canonicalizes only C3 closure times and preserves native input bytes`() {
        assertSelectedGraph()
        val at = F.selectedAt.truncatedTo(ChronoUnit.SECONDS).plusNanos(123456789)
        val expected = at.truncatedTo(ChronoUnit.MICROS)
        val nativeBefore = F.rows(jdbc, "submission_versions")
        val nativeHashBefore =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(nativeBefore.joinToString("\n").toByteArray(Charsets.UTF_8))
                .toList()
        clock.set(at)
        closeTyped()
        val times =
            listOf(
                assertNotNull(jdbc.queryForObject("SELECT closed_at FROM meeting_rooms", Timestamp::class.java)).toInstant(),
                assertNotNull(jdbc.queryForObject("SELECT fixed_at FROM submission_batches", Timestamp::class.java)).toInstant(),
                assertNotNull(jdbc.queryForObject("SELECT occurred_at FROM outbox_events", Timestamp::class.java)).toInstant(),
                assertNotNull(jdbc.queryForObject("SELECT selected_at FROM final_analysis_routes", Timestamp::class.java)).toInstant(),
            )
        assertEquals(List(4) { expected }, times, "C3 closure timestamps must share the same microsecond floor")
        events.process(CollectionClosureService.STRUCTURING_REQUESTED, SubmissionBatchId(F.ir.batch))
        assertEquals("COMPLETED", status())
        val invocationStart =
            assertNotNull(
                jdbc.queryForObject("SELECT started_at FROM analysis_invocations", Timestamp::class.java),
            ).toInstant()
        assertTrue(invocationStart >= times.last(), "Invocation cannot start before its frozen route selection")
        assertPublicResults()
        val nativeAfter = F.rows(jdbc, "submission_versions")
        assertEquals(nativeBefore, nativeAfter)
        assertEquals(
            nativeHashBefore,
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(nativeAfter.joinToString("\n").toByteArray(Charsets.UTF_8))
                .toList(),
        )
        assertEquals(1, providers.calls.get())
        assertEquals(0, providers.lunaCalls.get())
    }

    private fun assertSelectedGraph() {
        assertTrue(
            context.getBeansOfType(SelectedScheduleDslAutoConfiguration::class.java).isNotEmpty(),
            "Selected production auto-configuration must be discovered through Boot metadata",
        )
    }

    private fun closeTyped() {
        val response = postClose(hostToken)
        assertEquals(200, response.statusCode())
        assertEquals("CLOSED", jdbc.queryForObject("SELECT collection_status FROM meeting_rooms", String::class.java))
        assertEquals(F.ir.run, jdbc.queryForObject("SELECT active_run_id FROM meeting_rooms", UUID::class.java))
        assertEquals(1, count("submission_batches"))
        assertEquals(2, count("submission_batch_items"))
    }

    private fun publishToMatchingThroughActualProvider(): SelectedScheduleDslInvocation {
        val run = assertNotNull(publication.start(SubmissionBatchId(F.ir.batch)))
        val admitted = assertNotNull(publication.admit(run, UUID(0, 9942), Duration.ofSeconds(60)))
        val request = publication.request(admitted.binding, admitted.attempt, System.nanoTime() + Duration.ofSeconds(15).toNanos())
        val response = gemini.generate(request)
        assertTrue(publication.completeDsl(admitted.binding, admitted.attempt, response) { true })
        assertEquals("MATCHING", status())
        assertEquals(1, providers.calls.get())
        return admitted
    }

    private fun assertPublicResults() {
        val primary = get("recommendations", bearer = hostToken)
        assertEquals(200, primary.statusCode())
        val root = document(primary)
        assertEquals(F.ir.run.toString(), root["analysis_id"])
        assertEquals("COMPLETE", root["quality"])
        val option = items(root, "options").single()
        assertWindow(child(option, "time_range"))
        assertEquals("REMOTE", items(option, "variants").single()["meeting_mode"])
        val guest = get("recommendations", guest = RAW_GUEST)
        assertEquals(200, guest.statusCode())
        assertEquals(root, document(guest))
        val candidateResponse = get("candidates", bearer = hostToken)
        assertEquals(200, candidateResponse.statusCode())
        val candidateRoot = document(candidateResponse)
        assertEquals(2, (candidateRoot.getValue("applied_submissions") as Number).toInt())
        assertEquals(0, (candidateRoot.getValue("unapplied_inputs") as Number).toInt())
        val candidate = items(candidateRoot, "candidates").single()
        assertEquals("REMOTE", candidate["meeting_mode"])
        assertWindow(items(candidate, "time_ranges").single())
        val unapplied = get("candidates/unapplied-inputs", bearer = hostToken)
        assertEquals(200, unapplied.statusCode())
        assertEquals(emptyList<Any?>(), mapper.readValue(unapplied.body(), List::class.java))
    }

    private fun postClose(bearer: String): HttpResponse<String> =
        send(
            HttpRequest
                .newBuilder(uri("/api/rooms/$INVITE/close"))
                .header("Authorization", "Bearer $bearer")
                .header("Origin", ORIGIN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"analysis_contract\":\"DSL_APPROVED_TERMS_V3\"}")),
        )

    private fun get(
        path: String,
        bearer: String? = null,
        guest: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(uri("/api/rooms/$INVITE/$path")).GET()
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        guest?.let { builder.header("Cookie", "meet_me_guest=$it") }
        return send(builder)
    }

    private fun send(builder: HttpRequest.Builder) =
        http.send(builder.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())

    private fun uri(path: String) = URI("http://127.0.0.1:$port$path")

    @Suppress("UNCHECKED_CAST")
    private fun document(response: HttpResponse<String>) = mapper.readValue(response.body(), Map::class.java) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun child(
        parent: Map<String, Any?>,
        key: String,
    ) = parent.getValue(key) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun items(
        parent: Map<String, Any?>,
        key: String,
    ) = parent.getValue(key) as List<Map<String, Any?>>

    private fun assertWindow(range: Map<String, Any?>) {
        assertEquals("2027-01-08T10:00:00Z", range["start_at"])
        assertEquals("2027-01-08T12:00:00Z", range["end_at"])
    }

    private fun tamperSignature(token: String): String {
        val parts = token.split('.').toMutableList()
        parts[2] = (if (parts[2].first() == 'A') "B" else "A") + parts[2].drop(1)
        return parts.joinToString(".")
    }

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun status() = jdbc.queryForObject("SELECT status FROM coordination_runs", String::class.java)

    private fun upstreamRows() =
        listOf(
            "schedule_ir_envelopes",
            "schedule_ir_inputs",
            "analysis_invocations",
            "coordination_attempts",
            "ai_call_reservations",
        ).associateWith {
            F.rows(jdbc, it)
        }

    private fun resultRows() =
        upstreamRows() +
            listOf("coordination_runs", "recommendation_analyses", "recommendation_options", "recommendation_variants").associateWith {
                F.rows(jdbc, it)
            }

    private fun seedOpenNative() {
        val input = SelectedDslPhysicalInputFixture.approvedInput()
        jdbc.update(
            "INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,?),(?,?,?)",
            HOST,
            "synthetic-host",
            Timestamp.from(F.ir.reference),
            OUTSIDER,
            "synthetic-outsider",
            Timestamp.from(F.ir.reference),
        )
        jdbc.update(
            """
            INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
                search_range_source,manual_only,collection_status,created_at,submission_protocol)
            VALUES (?,?,'synthetic selected HTTP','REMOTE','Asia/Seoul','2027-01-04','2027-01-11',
                'HOST_SPECIFIED',true,'COLLECTING',?,'PRE_SUBMIT_V2')
            """.trimIndent(),
            input.roomId,
            INVITE,
            Timestamp.from(F.ir.reference),
        )
        input.inputs.forEachIndexed { index, item ->
            if (index == 0) {
                jdbc.update(
                    "INSERT INTO participants(id,room_id,user_id,role,display_name,joined_at) VALUES (?,?,?,'HOST','synthetic-host',?)",
                    item.participantId,
                    input.roomId,
                    HOST,
                    Timestamp.from(F.ir.reference),
                )
            } else {
                val guest = UUID(0, 9951)
                jdbc.update(
                    "INSERT INTO guest_browser_sessions(id,credential_digest,expires_at,created_at) VALUES (?,?,?,?)",
                    guest,
                    credentials.digest(RAW_GUEST),
                    Timestamp.from(F.selectedAt.plusSeconds(86400)),
                    Timestamp.from(F.ir.reference),
                )
                jdbc.update(
                    "INSERT INTO participants(id,room_id,guest_session_id,role,display_name,joined_at) VALUES (?,?,?,'MEMBER','synthetic-member',?)",
                    item.participantId,
                    input.roomId,
                    guest,
                    Timestamp.from(F.ir.reference),
                )
            }
            jdbc.update(
                "INSERT INTO submission_heads(id,room_id,participant_id) VALUES (?,?,?)",
                item.submissionId,
                input.roomId,
                item.participantId,
            )
            jdbc.update(
                "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at,input_evidence) VALUES (?,?,?,?,?,?,CAST(? AS jsonb))",
                item.submissionVersionId,
                item.submissionId,
                item.submissionRevision,
                item.rawText,
                item.locale,
                Timestamp.from(item.acceptedAt),
                mapper.writeValueAsString(item.evidence),
            )
            jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", item.submissionVersionId, item.submissionId)
        }
        jdbc.update(
            "UPDATE ai_room_budgets SET state='READY',epoch_id=?,unit_code='TEST_SUBUNIT',unit_scale=0,cap=100,settled=0,held=0,policy_fingerprint=?,coverage_started_at=? WHERE room_id=?",
            UUID(0, 9952),
            "c".repeat(64),
            Timestamp.from(F.ir.reference.minusSeconds(1)),
            input.roomId,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun response3(): String {
        val root = mapper.readValue(F.q.wire.json(), MutableMap::class.java) as MutableMap<String, Any?>
        root["semantic_version"] = "schedule-ir-product-4"
        root["vocabulary_version"] = "ko-time-terms-1"
        (root["results"] as List<MutableMap<String, Any?>>).forEach { it["approved_terms"] = emptyList<Any>() }
        return mapper.writeValueAsString(root)
    }

    class HttpClock : Clock() {
        private val now = AtomicReference(F.selectedAt)

        fun set(value: Instant) {
            now.set(value)
        }

        override fun instant(): Instant = now.get()

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }

    class ClosureIds : IdGenerator {
        private val nextIds = ArrayDeque<UUID>()

        @Synchronized fun arm(vararg ids: UUID) {
            nextIds.clear()
            ids.forEach(nextIds::addLast)
        }

        @Synchronized fun clear() {
            nextIds.clear()
        }

        @Synchronized override fun next(): UUID = if (nextIds.isEmpty()) UUID.randomUUID() else nextIds.removeFirst()
    }

    class LocalProviders(
        mapper: ObjectMapper,
    ) : AutoCloseable {
        val calls = AtomicInteger()
        val lunaCalls = AtomicInteger()
        val reply = AtomicReference("{}")
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint: URI = URI("http://127.0.0.1:${server.address.port}")

        init {
            server.createContext("/") { exchange ->
                exchange.requestBody.use { it.readAllBytes() }
                val luna = exchange.requestURI.path == "/v1/responses"
                if (luna) lunaCalls.incrementAndGet() else calls.incrementAndGet()
                val payload =
                    if (luna) {
                        mapOf(
                            "status" to "completed",
                            "output" to
                                listOf(
                                    mapOf(
                                        "type" to "message",
                                        "role" to "assistant",
                                        "content" to listOf(mapOf("type" to "output_text", "text" to reply.get())),
                                    ),
                                ),
                            "usage" to mapOf("input_tokens" to 17, "output_tokens" to 23),
                        )
                    } else {
                        mapOf(
                            "candidates" to
                                listOf(
                                    mapOf("content" to mapOf("parts" to listOf(mapOf("text" to reply.get()))), "finishReason" to "STOP"),
                                ),
                            "usageMetadata" to mapOf("promptTokenCount" to 17, "candidatesTokenCount" to 23, "totalTokenCount" to 40),
                        )
                    }
                val bytes = mapper.writeValueAsBytes(payload)
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

    @TestConfiguration(proxyBeanMethods = false)
    class LocalFixtures {
        @Bean @Primary
        fun selectedHttpClock() = HttpClock()

        @Bean @Primary
        fun selectedClosureIds() = ClosureIds()

        @Bean(destroyMethod = "close")
        fun selectedLocalProviders(mapper: ObjectMapper) = LocalProviders(mapper)

        @Bean("selectedScheduleDslGeminiProvider")
        @Lazy
        fun selectedGemini(
            mapper: ObjectMapper,
            authority: SelectedScheduleDslPhysicalAuthority,
            providers: LocalProviders,
        ) = GeminiSelectedScheduleDslProviderAdapter(
            GeminiProperties(apiKey = "synthetic-local-only"),
            mapper,
            authority.gate,
            providers.endpoint,
        )

        @Bean("selectedScheduleDslLunaProvider")
        @Lazy
        fun selectedLuna(
            mapper: ObjectMapper,
            authority: SelectedScheduleDslPhysicalAuthority,
            providers: LocalProviders,
        ) = OpenAiLunaSelectedScheduleDslProviderAdapter(
            OpenAiProperties(apiKey = "synthetic-local-only"),
            mapper,
            authority.gate,
            providers.endpoint.resolve("/v1/responses"),
        )
    }

    companion object {
        // Actual value for explicit TEST monetary path; existing synthetic authority supplies quotes.
        @JvmStatic
        fun syntheticMonetarySelection() = ManagedAiRuntimeSelection(true, null)

        private const val INVITE = "route-synthetic-000001"
        private const val RAW_GUEST = "synthetic-http-selected-member"
        private const val ORIGIN = "https://app.meet-me.co.kr"
        private val HOST = UUID(0, 9961)
        private val OUTSIDER = UUID(0, 9962)
        private const val KEY_ID = "synthetic-selected-http"
        private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val keyDir = Files.createTempDirectory("selected-http-test-keys-")
        private val privateKey =
            keyDir.resolve("private.pem").also {
                Files.writeString(
                    it,
                    "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(keys.private.encoded) +
                        "\n-----END PRIVATE KEY-----\n",
                )
            }
        private val publicKeys =
            keyDir.resolve("verification.json").also {
                Files.writeString(it, JWKSet(RSAKey.Builder(keys.public as RSAPublicKey).keyID(KEY_ID).build()).toString())
            }
        private val postgres = mappedPortReadyPostgres().apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("meetme.auth.signing-key-id") { KEY_ID }
            registry.add("meetme.auth.signing-private-key-path") { privateKey.toString() }
            registry.add("meetme.auth.verification-jwks-path") { publicKeys.toString() }
            registry.add("meetme.anonymous.allowed-origins") { ORIGIN }
            registry.add("meetme.reliability.enabled") { false }
            registry.add("meetme.data-retention.enabled") { false }
            registry.add("meetme.rate-limit.enabled") { false }
            registry.add("meetme.ai.budget.enabled") { true }
            registry.add("meetme.precheck.provider.enabled") { true }
            registry.add("meetme.precheck.decision-routing.enabled") { true }
            registry.add("meetme.precheck.classifier.enabled") { true }
            registry.add("meetme.gemini.api-key") { "" }
            registry.add("meetme.openai.api-key") { "" }
            registry.add("server.address") { "127.0.0.1" }
        }

        @JvmStatic @AfterAll
        fun stop() {
            postgres.stop()
            Files.deleteIfExists(privateKey)
            Files.deleteIfExists(publicKeys)
            Files.deleteIfExists(keyDir)
        }
    }
}
