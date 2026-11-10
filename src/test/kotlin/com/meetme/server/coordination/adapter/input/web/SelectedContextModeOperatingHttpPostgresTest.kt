package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.ManagedAiRuntimeSelection
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.config.SelectedScheduleDslPhysicalAuthority
import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.adapter.output.integration.GeminiSelectedScheduleDslProviderAdapter
import com.meetme.server.coordination.adapter.output.integration.OpenAiLunaSelectedScheduleDslProviderAdapter
import com.meetme.server.coordination.adapter.output.integration.SelectedApprovedTermsProviderPostgresTest
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslInvocation
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderPort
import com.meetme.server.coordination.application.service.CoordinationEventDispatcher
import com.meetme.server.coordination.application.service.MatchingProcessingPersistenceService
import com.meetme.server.coordination.application.service.SelectedScheduleDslProcessingPersistenceService
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModePolicy
import com.meetme.server.meetingroom.application.service.CollectionClosureService
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslContextModeRepository
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslStructuralRepository
import com.meetme.server.support.SelectedDslPhysicalInputFixture
import com.meetme.server.support.mappedPortReadyPostgres
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
@Import(SelectedContextModeOperatingHttpPostgresTest.LocalFixtures::class, SelectedApprovedTermsProviderPostgresTest.AuthorityConfig::class)
class SelectedContextModeOperatingHttpPostgresTest {
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
        providers.reply.set(response4())
        providers.requests.clear()
        providers.failGemini = false
        providers.syntheticGeminiStatus = null
        hostToken = tokens.issue(AccountPrincipal(HOST, "synthetic-host"))
        outsiderToken = tokens.issue(AccountPrincipal(OUTSIDER, "synthetic-outsider"))
        // Scope deterministic ids to the upcoming real closure transaction, not its domain behavior.
        ids.arm(F.ir.batch, F.ir.run, UUID(0, 9941))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `CMHTTP01 real JWT C4 provider publication preserves per interval mode and contextual eighteen thirty`(fallback: Boolean) {
        providers.failGemini = fallback
        closeTyped()
        events.process(CollectionClosureService.STRUCTURING_REQUESTED, SubmissionBatchId(F.ir.batch))
        assertEquals("COMPLETED", status())
        assertEquals(if (fallback) 4 else 1, providers.calls.get())
        assertEquals(if (fallback) 1 else 0, providers.lunaCalls.get())
        assertEquals(if (fallback) 5 else 1, count("ai_call_reservations"))
        assertEquals(0, count("structured_submission_results"))
        assertEquals(6, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
        assertPublicResults()
        assertWireNative()
        val route = assertNotNull(routes.find(F.ir.run))
        assertTrue(route.choice is FinalAnalysisRouteChoice.DslContextModeV4)
        val selected = route.choice as FinalAnalysisRouteChoice.DslContextModeV4
        val fresh = assertNotNull(JdbcScheduleDslContextModeRepository(jdbc, mapper).find(selected.context))
        assertEquals("schedule-ir-product-5", fresh.semanticVersion)
        val declared =
            fresh.base.declaration.claims.results.first {
                it.submissionVersionId ==
                    nativeInput().inputs.first().submissionVersionId
            }
        assertTrue(declared.conditions.all { it.interpretation.name == "INFERRED" && it.temporalResolution.name == "NON_PRECISE" })
        assertTrue(fresh.contextApplications.isNotEmpty())
        assertTrue(fresh.contextApplications.all { it.derivation.name == "MODEL_CONTEXT" && it.declaredScope == it.resolvedScope })
        assertEquals(selected.context, fresh.base.declaration.context)
        assertTrue(fresh.modeRestrictions.isNotEmpty())
    }

    @Test
    fun `CMHTTP06 actual C4 HTTP504 immediately falls back once to Luna and publishes mode safe IR5 recommendations`() {
        providers.syntheticGeminiStatus = 504
        closeTyped()
        events.process(CollectionClosureService.STRUCTURING_REQUESTED, SubmissionBatchId(F.ir.batch))
        assertEquals("COMPLETED", status())
        assertEquals(1, providers.calls.get())
        assertEquals(1, providers.lunaCalls.get())
        assertEquals(2, count("coordination_attempts"))
        assertEquals(2, count("ai_call_reservations"))
        assertEquals(0, count("structured_submission_results"))
        assertEquals(6, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
        assertEquals(
            "OPENAI",
            jdbc.queryForObject(
                "SELECT a.provider FROM coordination_attempts a JOIN analysis_invocations i ON i.winner_attempt_id=a.id",
                String::class.java,
            ),
        )
        val selected = assertNotNull(routes.find(F.ir.run)).choice as FinalAnalysisRouteChoice.DslContextModeV4
        val fresh = assertNotNull(JdbcScheduleDslContextModeRepository(jdbc, mapper).find(selected.context))
        assertEquals("schedule-ir-product-5", fresh.semanticVersion)
        assertTrue(fresh.contextApplications.isNotEmpty())
        assertTrue(fresh.modeRestrictions.isNotEmpty())
        assertPublicResults()
        assertWireNative()
    }

    @Test
    fun `CMHTTP02 completed worker redelivery and authenticated reads preserve mode variants without another call`() {
        closeTyped()
        events.process(CollectionClosureService.STRUCTURING_REQUESTED, SubmissionBatchId(F.ir.batch))
        val before = resultRows() + mapOf("documents" to F.rows(jdbc, "schedule_ir_envelopes"))
        assertPublicResults()
        events.process(CollectionClosureService.MATCHING_REQUESTED, SubmissionBatchId(F.ir.batch))
        assertPublicResults()
        assertEquals(before, resultRows() + mapOf("documents" to F.rows(jdbc, "schedule_ir_envelopes")))
        assertEquals(1, providers.calls.get())
        assertEquals(0, providers.lunaCalls.get())
    }

    @Test
    fun `CMHTTP03 actual JWT force revision freezes fresh C4 context without reusing old IR`() {
        closeTyped()
        events.process(CollectionClosureService.STRUCTURING_REQUESTED, SubmissionBatchId(F.ir.batch))
        val old = assertNotNull(routes.find(F.ir.run))
        val oldDocuments = F.rows(jdbc, "schedule_ir_envelopes")
        val opened =
            postJson(
                "reopen",
                mapOf(
                    "request_id" to UUID.randomUUID(),
                    "source_analysis_id" to F.ir.run,
                    "expected_generation" to 0,
                ),
            )
        assertEquals(200, opened.statusCode())
        val round = child(document(opened), "round").getValue("id").toString()
        val response = postJson("analysis", mapOf("revision_round_id" to round, "request_id" to UUID.randomUUID(), "force_reparse" to true))
        assertEquals(202, response.statusCode())
        val nextId = UUID.fromString(document(response).getValue("analysis_id").toString())
        val next = assertNotNull(routes.find(nextId))
        val choice = next.choice as FinalAnalysisRouteChoice.DslContextModeV4
        val prior = old.choice as FinalAnalysisRouteChoice.DslContextModeV4
        assertEquals(prior.identity, choice.identity)
        assertEquals(prior.policy, choice.policy)
        assertEquals(prior.vocabularyVersion, choice.vocabularyVersion)
        assertEquals(MeetingMode.EITHER, choice.meetingMode)
        assertEquals(nextId, choice.context.runId)
        assertTrue(choice.context.batchId != prior.context.batchId)
        assertEquals(prior.context.inputs, choice.context.inputs)
        assertEquals(old, routes.find(F.ir.run))
        assertEquals(oldDocuments, F.rows(jdbc, "schedule_ir_envelopes"))
        assertEquals(1, providers.calls.get())
    }

    @Test
    fun `CMHTTP04 actual JWT host matching retry retains C4 and forbids paid redispatch after original expiry`() {
        closeTyped()
        val invocation = publishToMatchingThroughActualProvider()
        val runs = context.getBean(CoordinationRunRepository::class.java)
        context.getBean(MatchingProcessingPersistenceService::class.java).delay(assertNotNull(runs.findById(CoordinationRunId(F.ir.run))))
        val outbox = context.getBean(com.meetme.server.coordination.application.port.output.OutboxRepository::class.java)
        val initialEvent = assertNotNull(jdbc.queryForObject("SELECT id FROM outbox_events", UUID::class.java))
        val initialEventId =
            com.meetme.server.shared.domain
                .OutboxEventId(initialEvent)
        outbox.markPublished(initialEventId, clock.instant())
        outbox.markProcessed(initialEventId, clock.instant())
        val original = upstreamRows()
        clock.set(
            invocation.binding.invocation.deadlineAt
                .plusSeconds(1),
        )
        assertEquals(202, postJson("analysis/retry", emptyMap<String, Any>()).statusCode())
        val ids = jdbc.queryForList("SELECT id FROM outbox_events WHERE payload->>'matching_run_version' IS NOT NULL", UUID::class.java)
        assertEquals(1, ids.size)
        outbox.markPublished(
            com.meetme.server.shared.domain
                .OutboxEventId(requireNotNull(ids.single())),
            clock.instant(),
        )
        events.process(CollectionClosureService.MATCHING_REQUESTED, SubmissionBatchId(F.ir.batch))
        assertPublicResults()
        assertEquals(original, upstreamRows())
        assertEquals(1, providers.calls.get())
        assertEquals(0, providers.lunaCalls.get())
    }

    @Test
    fun `RESBHTTP01 actual host C5 v2 matching retry after original expiry reuses winner without provider dispatch`() {
        prepareStructuralFixture()
        closeTyped("DSL_STRUCTURAL_V5")
        val run = assertNotNull(publication.start(SubmissionBatchId(F.ir.batch)))
        val admitted = assertNotNull(publication.admit(run, UUID(0, 9942), Duration.ofSeconds(60)))
        assertEquals("c5-residual-luna-v2", admitted.binding.invocation.policyVersion)
        assertEquals("c5-residual-luna-v2", admitted.attempt.policyVersion)
        val synthetic = response5()
        val response =
            ScheduleDslProviderResponse(
                synthetic,
                ParserUsage(17, 23, synthetic.toByteArray(Charsets.UTF_8).size),
                AnalysisProvider.GEMINI,
                "gemini-3.8-flash",
            )
        // This is an admitted synthetic publication, not an actual provider call or billed transport proof.
        assertTrue(publication.completeDsl(admitted.binding, admitted.attempt, response) { true })
        assertEquals("MATCHING", status())
        assertEquals(7, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
        assertEquals(
            "c5-residual-luna-v2",
            jdbc.queryForObject(
                "SELECT a.policy_version FROM coordination_attempts a JOIN analysis_invocations i ON i.winner_attempt_id=a.id",
                String::class.java,
            ),
        )
        assertEquals(admitted.attempt.id, jdbc.queryForObject("SELECT winner_attempt_id FROM analysis_invocations", UUID::class.java))
        val runs = context.getBean(CoordinationRunRepository::class.java)
        context.getBean(MatchingProcessingPersistenceService::class.java).delay(assertNotNull(runs.findById(CoordinationRunId(F.ir.run))))
        assertEquals("ANALYSIS_DELAYED", status())
        val outbox = context.getBean(OutboxRepository::class.java)
        val initialEvent = OutboxEventId(assertNotNull(jdbc.queryForObject("SELECT id FROM outbox_events", UUID::class.java)))
        outbox.markPublished(initialEvent, clock.instant())
        outbox.markProcessed(initialEvent, clock.instant())
        val original =
            upstreamRows() +
                listOf("final_analysis_routes", "submission_versions", "submission_batch_items")
                    .associateWith { F.rows(jdbc, it) }
        clock.set(
            admitted.binding.invocation.deadlineAt
                .plusSeconds(1),
        )
        assertEquals(202, postJson("analysis/retry", emptyMap<String, Any>()).statusCode())
        val retryIds =
            jdbc.queryForList(
                "SELECT id FROM outbox_events WHERE payload->>'matching_run_version' IS NOT NULL",
                UUID::class.java,
            )
        assertEquals(1, retryIds.size)
        outbox.markPublished(OutboxEventId(requireNotNull(retryIds.single())), clock.instant())
        events.process(CollectionClosureService.MATCHING_REQUESTED, SubmissionBatchId(F.ir.batch))
        assertEquals("COMPLETED", status())
        assertStructuralPublicResults()
        assertEquals(
            original,
            upstreamRows() +
                listOf("final_analysis_routes", "submission_versions", "submission_batch_items")
                    .associateWith { F.rows(jdbc, it) },
        )
        assertEquals(1, count("analysis_invocations"))
        assertEquals(1, count("coordination_attempts"))
        assertEquals(0, count("ai_call_reservations"))
        assertEquals(0, providers.calls.get())
        assertEquals(0, providers.lunaCalls.get())
    }

    @Test
    fun `CMHTTP05 actual JWT privacy fences and omitted selector retain default Legacy`() {
        assertEquals(403, postClose(outsiderToken).statusCode())
        assertEquals(401, get("recommendations").statusCode())
        assertEquals(401, get("recommendations", bearer = tamperSignature(hostToken)).statusCode())
        assertEquals(0, count("final_analysis_routes"))
        assertEquals(200, postJson("close", emptyMap<String, Any>()).statusCode())
        assertEquals(null, routes.find(F.ir.run))
        assertEquals(0, providers.calls.get())
        assertEquals(0, providers.lunaCalls.get())
    }

    private fun postJson(
        path: String,
        value: Any,
    ): HttpResponse<String> =
        send(
            HttpRequest
                .newBuilder(uri("/api/rooms/$INVITE/$path"))
                .header("Authorization", "Bearer $hostToken")
                .header("Origin", ORIGIN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(value))),
        )

    private fun assertWireNative() {
        assertTrue(providers.requests.isNotEmpty())
        providers.requests.forEach { body ->
            val root = mapper.readValue(body, Map::class.java)
            val neutral =
                if (root.containsKey("input")) {
                    root["input"].toString()
                } else {
                    val contents = root["contents"] as List<*>
                    val parts = (contents.single() as Map<*, *>)["parts"] as List<*>
                    (parts.single() as Map<*, *>)["text"].toString()
                }
            assertTrue(neutral.contains("schedule-dsl-request-4"))
            assertTrue(neutral.contains("schedule-ir-product-5"))
            assertTrue(neutral.contains("ko-time-terms-1"))
            nativeInput().inputs.forEach { assertTrue(neutral.contains(it.rawText)) }
            listOf("AM", "PM", "DAWN", "MORNING", "LUNCH", "DAYTIME", "MIDDAY", "EVENING", "NIGHT", "LATE_NIGHT", "ALL_DAY")
                .forEach { assertTrue(neutral.contains(it)) }
        }
    }

    @Test
    fun `SC5HTTP01 actual JWT C5 close worker and restored directional witnesses reach authenticated recommendations`() {
        prepareStructuralFixture()
        closeTyped("DSL_STRUCTURAL_V5")
        val frozenRows = F.rows(jdbc, "submission_versions") + F.rows(jdbc, "submission_batch_items")
        events.process(CollectionClosureService.STRUCTURING_REQUESTED, SubmissionBatchId(F.ir.batch))
        assertStructuralPublished(fallback = false)
        assertEquals(frozenRows, F.rows(jdbc, "submission_versions") + F.rows(jdbc, "submission_batch_items"))
    }

    @Test
    fun `SC5HTTP02 actual C5 HTTP504 falls back once to Luna within the same admission and preserves directional results`() {
        prepareStructuralFixture()
        providers.syntheticGeminiStatus = 504
        closeTyped("DSL_STRUCTURAL_V5")
        val frozenRows = F.rows(jdbc, "submission_versions") + F.rows(jdbc, "submission_batch_items")
        events.process(CollectionClosureService.STRUCTURING_REQUESTED, SubmissionBatchId(F.ir.batch))
        assertStructuralPublished(fallback = true)
        assertEquals(
            "TIMEOUT",
            jdbc.queryForObject("SELECT failure_kind FROM coordination_attempts WHERE provider='GEMINI'", String::class.java),
        )
        assertEquals(frozenRows, F.rows(jdbc, "submission_versions") + F.rows(jdbc, "submission_batch_items"))
    }

    /** Seed a separate accepted synthetic cohort before closure; never mutate a frozen native version. */
    private fun prepareStructuralFixture() {
        jdbc.execute("TRUNCATE outbox_events,meeting_rooms,guest_browser_sessions,account_users CASCADE")
        seedOpenNative(structuralInput())
        providers.reply.set(response5())
        ids.arm(F.ir.batch, F.ir.run, UUID(0, 9941))
    }

    private fun structuralInput() =
        nativeInput().let { old ->
            old.copy(
                responseContractVersion = "schedule-dsl-response-5",
                semanticVersion = "schedule-ir-product-6",
                inputs =
                    old.inputs.mapIndexed { index, item ->
                        item.copy(
                            rawText =
                                if (index ==
                                    0
                                ) {
                                    STRUCTURAL_FIRST
                                } else {
                                    STRUCTURAL_PEER
                                },
                        )
                    },
            )
        }

    private fun assertStructuralPublished(fallback: Boolean) {
        assertEquals("COMPLETED", status())
        assertEquals(1, providers.calls.get())
        assertEquals(if (fallback) 1 else 0, providers.lunaCalls.get())
        assertEquals(1, count("analysis_invocations"))
        assertEquals(if (fallback) 2 else 1, count("coordination_attempts"))
        assertEquals(if (fallback) 2 else 1, count("ai_call_reservations"))
        assertEquals(0, count("structured_submission_results"))
        assertEquals(1, count("schedule_ir_envelopes"))
        assertEquals(7, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
        assertEquals(
            if (fallback) "OPENAI" else "GEMINI",
            jdbc.queryForObject(
                "SELECT a.provider FROM coordination_attempts a JOIN analysis_invocations i ON i.winner_attempt_id=a.id",
                String::class.java,
            ),
        )
        assertEquals(1, jdbc.queryForObject("SELECT gemini_attempts FROM analysis_invocations", Int::class.java))
        assertEquals(if (fallback) 1 else 0, jdbc.queryForObject("SELECT luna_attempts FROM analysis_invocations", Int::class.java))
        assertEquals(
            listOf(1).let {
                if (fallback) it + 1 else it
            },
            jdbc.queryForList("SELECT ordinal FROM ai_call_reservations ORDER BY ordinal", Int::class.java),
        )
        assertEquals(1, jdbc.queryForObject("SELECT count(DISTINCT execution_id) FROM ai_call_reservations", Int::class.java))

        val route = assertNotNull(routes.find(F.ir.run))
        assertTrue(route.choice is FinalAnalysisRouteChoice.DslStructuralV5)
        val selected = route.choice as FinalAnalysisRouteChoice.DslStructuralV5
        assertEquals("schedule-dsl-response-5", selected.identity.responseContractVersion)
        assertEquals("schedule-ir-product-6", selected.identity.irSemanticVersion)
        val fresh = assertNotNull(JdbcScheduleDslStructuralRepository(jdbc, mapper).find(selected.context))
        assertEquals("schedule-ir-product-6", fresh.semanticVersion)
        assertEquals(selected.context, fresh.context)
        assertEquals(selected.context, fresh.base.base.declaration.context)
        val firstId = structuralInput().inputs.first().submissionVersionId
        val declaration =
            fresh.base.base.declaration.claims.results
                .single { it.submissionVersionId == firstId }
        assertEquals(setOf("fri", "sun"), declaration.conditions.map { it.id }.toSet())
        declaration.conditions.forEach {
            assertEquals(null, it.scope)
            assertEquals("EXPLICIT", it.interpretation.name)
            assertEquals("NON_PRECISE", it.temporalResolution.name)
        }
        assertEquals(2, fresh.directionalApplications.size)
        val applications = fresh.directionalApplications.associateBy { it.declaration.conditionId }
        val friday = applications.getValue("fri")
        val sunday = applications.getValue("sun")
        assertEquals("AFTER", friday.declaration.direction.name)
        assertEquals("20:00", friday.declaration.clock.toString())
        assertEquals("2027-01-08T20:00", friday.resolvedScope.start.toString())
        assertEquals("2027-01-09T00:00", friday.resolvedScope.end.toString())
        assertEquals("BEFORE", sunday.declaration.direction.name)
        assertEquals("17:00", sunday.declaration.clock.toString())
        assertEquals("2027-01-10T00:00", sunday.resolvedScope.start.toString())
        assertEquals("2027-01-10T17:00", sunday.resolvedScope.end.toString())
        fresh.directionalApplications.forEach {
            assertEquals(firstId, it.submissionVersionId)
            assertEquals("SERVER_DATE_SCOPE", it.derivation.name)
            assertEquals(it.resolvedScope, it.searchSupport)
        }
        assertTrue(fresh.locatedEvidence.isNotEmpty())
        assertStructuralWire()
        assertStructuralPublicResults()
        // Stored read and worker redelivery must not dispatch another physical request.
        events.process(CollectionClosureService.MATCHING_REQUESTED, SubmissionBatchId(F.ir.batch))
        assertStructuralPublicResults()
        assertEquals(1, providers.calls.get())
        assertEquals(if (fallback) 1 else 0, providers.lunaCalls.get())
    }

    private fun assertStructuralPublicResults() {
        val response = get("recommendations", bearer = hostToken)
        assertEquals(200, response.statusCode())
        assertTrue(
            response
                .headers()
                .firstValue("Cache-Control")
                .orElse("")
                .contains("no-store"),
        )
        val root = document(response)
        val options = items(root, "options")
        // Independently calculated Asia/Seoul: Friday20–22 and Sunday15–17, clipped by the peer.
        assertEquals(
            setOf("2027-01-08T11:00:00Z" to "2027-01-08T13:00:00Z", "2027-01-10T06:00:00Z" to "2027-01-10T08:00:00Z"),
            options.map { child(it, "time_range")["start_at"] to child(it, "time_range")["end_at"] }.toSet(),
        )
        options.forEach { option ->
            assertEquals(setOf("REMOTE", "IN_PERSON"), items(option, "variants").map { it["meeting_mode"] }.toSet())
            items(option, "variants").forEach { assertEquals(null, it["place"]) }
        }
        assertEquals(root, document(get("recommendations", guest = RAW_GUEST)))
        assertEquals(403, get("recommendations", bearer = outsiderToken).statusCode())
        assertEquals(401, get("recommendations").statusCode())
    }

    private fun assertStructuralWire() {
        assertEquals(providers.calls.get() + providers.lunaCalls.get(), providers.requests.size)
        providers.requests.forEach { bytes ->
            val root = mapper.readValue(bytes, Map::class.java)
            val luna = root.containsKey("input")
            val neutral =
                if (luna) {
                    requireNotNull(root["input"]).toString()
                } else {
                    val contents = root["contents"] as List<*>
                    val parts = (contents.single() as Map<*, *>)["parts"] as List<*>
                    (parts.single() as Map<*, *>)["text"].toString()
                }
            val request = mapper.readValue(neutral, Map::class.java)
            assertEquals("schedule-dsl-request-5", request["request_contract_version"])
            assertEquals("schedule-dsl-response-5", request["response_contract_version"])
            assertEquals("schedule-ir-product-6", request["semantic_version"])
            assertEquals("ko-time-terms-1", request["vocabulary_version"])
            structuralInput().inputs.forEach { assertTrue(neutral.contains(it.rawText)) }
            listOf("AM", "PM", "DAWN", "MORNING", "LUNCH", "DAYTIME", "MIDDAY", "EVENING", "NIGHT", "LATE_NIGHT", "ALL_DAY")
                .forEach { assertTrue(neutral.contains(it)) }
            val schema =
                if (luna) {
                    val format = (root["text"] as Map<*, *>)["format"] as Map<*, *>
                    assertEquals("meet_me_schedule_dsl_v5", format["name"])
                    format["schema"]
                } else {
                    (root["generationConfig"] as Map<*, *>)["responseJsonSchema"]
                }
            val schemaRoot = schema as Map<*, *>

            fun branches(
                value: Any?,
                path: List<String>,
            ): List<Pair<List<String>, Map<*, *>>> {
                val node = value as Map<*, *>
                val reference = node["\$ref"] as? String
                if (reference != null) {
                    val name = reference.substringAfterLast('/')
                    return branches((schemaRoot["\$defs"] as Map<*, *>)[name], listOf("\$defs", name))
                }
                val arms = node["anyOf"] as? List<*>
                return arms?.flatMapIndexed { index, child -> branches(child, path + listOf("anyOf", index.toString())) }
                    ?: listOf(path to node)
            }
            val result = (((schemaRoot["properties"] as Map<*, *>)["results"] as Map<*, *>)["items"] as Map<*, *>)
            val condition = ((result["properties"] as Map<*, *>)["conditions"] as Map<*, *>)["items"]
            val bankPaths =
                branches(condition, listOf("properties", "results", "items", "properties", "conditions", "items"))
                    .flatMap { (path, node) ->
                        val bank = (node["properties"] as Map<*, *>)["proofs"] as Map<*, *>
                        val arms = (bank["items"] as Map<*, *>)["anyOf"] as List<*>
                        assertEquals(2, arms.size)
                        arms.indices.map { path + listOf("properties", "proofs", "items", "anyOf", it.toString()) }
                    }.toSet()
            val evidenceSchemas =
                structuralSchemaEntries(schema, emptyList()).filter { (_, node) ->
                    (node["properties"] as? Map<*, *>)?.containsKey("source_id") == true
                }
            assertTrue(evidenceSchemas.isNotEmpty())
            assertEquals(bankPaths, evidenceSchemas.map { it.first }.filter { it in bankPaths }.toSet())
            assertTrue(evidenceSchemas.any { it.first !in bankPaths })
            evidenceSchemas.forEach { (path, node) ->
                val keys = setOf("source_id", "revision", "quote") + if (path in bankPaths) setOf("id") else emptySet()
                assertEquals(keys, (node["properties"] as Map<*, *>).keys, path.toString())
                assertEquals(keys, (node["required"] as List<*>).toSet(), path.toString())
                assertEquals(false, node["additionalProperties"])
            }
            assertTrue(structuralSchemaObjects(schema).any { (it["properties"] as? Map<*, *>)?.containsKey("directional_times") == true })
        }
    }

    private fun structuralSchemaObjects(value: Any?): List<Map<*, *>> = structuralSchemaEntries(value, emptyList()).map { it.second }

    private fun structuralSchemaEntries(
        value: Any?,
        path: List<String>,
    ): List<Pair<List<String>, Map<*, *>>> =
        when (value) {
            is Map<*, *> ->
                listOf(path to value) +
                    value.entries.flatMap { (key, child) -> structuralSchemaEntries(child, path + key.toString()) }
            is List<*> -> value.flatMapIndexed { index, child -> structuralSchemaEntries(child, path + index.toString()) }
            else -> emptyList()
        }

    private fun response5(): String {
        val input = structuralInput()

        fun locator(quote: String) = mapOf("source_id" to "original", "revision" to 3, "quote" to quote)

        fun range(
            day: String,
            start: String,
            end: String,
        ) = mapOf("dimension" to "TIME", "start" to "${day}T$start:00", "end" to "${day}T$end:00")

        fun direction(
            id: String,
            direction: String,
            date: String,
            clock: String,
        ) = mapOf(
            "condition_id" to id,
            "direction" to direction,
            "local_date" to date,
            "clock" to clock,
            "date_evidence" to listOf(locator(date)),
            "clock_evidence" to listOf(locator(clock)),
        )
        val results: List<Map<String, Any?>> =
            input.inputs.mapIndexed { index, item ->
                fun claim(
                    id: String,
                    scope: Any?,
                ): Map<String, Any?> =
                    mapOf(
                        "id" to id,
                        "effect" to "AVAILABLE",
                        "interpretation" to "EXPLICIT",
                        "temporal_resolution" to if (index == 0) "NON_PRECISE" else "PRECISE_INTERVAL",
                        "scope" to scope,
                        "evidence" to listOf(locator(item.rawText)),
                        "assertion" to "ASSERTED",
                        "supersedes" to emptyList<String>(),
                        "alternative_group" to null,
                        "alternative" to null,
                    )

                fun node(
                    id: String,
                    condition: String?,
                    children: List<String>,
                ): Map<String, Any?> =
                    mapOf(
                        "id" to id,
                        "operator" to if (condition == null) "ANY_OF" else "CLAIM_REF",
                        "claim_id" to condition,
                        "children" to children,
                        "choice_group" to null,
                        "branch_labels" to emptyList<String>(),
                        "interpretation" to "EXPLICIT",
                        "assertion" to "ASSERTED",
                        "evidence" to listOf(locator(item.rawText)),
                    )
                return@mapIndexed mapOf<String, Any?>(
                    "input_ref" to item.submissionVersionId.toString(),
                    "conditions" to
                        listOf(
                            claim("fri", if (index == 0) null else range("2027-01-08", "19:00", "22:00")),
                            claim("sun", if (index == 0) null else range("2027-01-10", "15:00", "18:00")),
                        ),
                    "program" to
                        mapOf(
                            "nodes" to
                                listOf(
                                    node("fr", "fri", emptyList()),
                                    node("su", "sun", emptyList()),
                                    node("root", null, listOf("fr", "su")),
                                ),
                            "availability_root" to "root",
                            "exclusion_root" to null,
                            "preference_root" to null,
                            "history_condition_ids" to emptyList<String>(),
                        ),
                    "approved_terms" to emptyList<Any>(),
                    "mode_restrictions" to emptyList<Any>(),
                    "directional_times" to
                        if (index == 0) {
                            listOf(direction("fri", "AFTER", "2027-01-08", "20:00"), direction("sun", "BEFORE", "2027-01-10", "17:00"))
                        } else {
                            emptyList()
                        },
                )
            }
        return mapper.writeValueAsString(
            mapOf(
                "semantic_version" to "schedule-ir-product-6",
                "ast_version" to "schedule-ast-1",
                "vocabulary_version" to "ko-time-terms-1",
                "results" to results,
            ),
        )
    }

    private fun closeTyped(analysisContract: String = "DSL_CONTEXT_MODE_V4") {
        val response = postClose(hostToken, analysisContract)
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
        val response = get("recommendations", bearer = hostToken)
        assertEquals(200, response.statusCode())
        val root = document(response)
        assertEquals("PARTIAL", root["quality"])
        val options = items(root, "options")
        assertEquals(
            setOf("2027-01-07T09:30:00Z", "2027-01-08T09:30:00Z"),
            options.map { child(it, "time_range")["start_at"] }.toSet(),
        )
        val thursday = options.filter { child(it, "time_range")["start_at"] == "2027-01-07T09:30:00Z" }
        val friday = options.filter { child(it, "time_range")["start_at"] == "2027-01-08T09:30:00Z" }
        assertTrue(thursday.isNotEmpty())
        assertTrue(friday.isNotEmpty())
        thursday.forEach { assertEquals(setOf("REMOTE"), items(it, "variants").map { v -> v["meeting_mode"] }.toSet()) }
        assertTrue(
            friday
                .flatMap { items(it, "variants") }
                .map { it["meeting_mode"] }
                .toSet()
                .containsAll(setOf("REMOTE", "IN_PERSON")),
        )
        options.forEach { option ->
            assertEquals("2027-01-0" + (if (option in thursday) "7" else "8") + "T12:00:00Z", child(option, "time_range")["end_at"])
            items(option, "variants").forEach { assertEquals(null, it["place"]) }
        }
        assertEquals(root, document(get("recommendations", guest = RAW_GUEST)))
        assertEquals(403, get("recommendations", bearer = outsiderToken).statusCode())
        assertEquals(401, get("recommendations").statusCode())
    }

    private fun postClose(
        bearer: String,
        analysisContract: String = "DSL_CONTEXT_MODE_V4",
    ): HttpResponse<String> =
        send(
            HttpRequest
                .newBuilder(uri("/api/rooms/$INVITE/close"))
                .header("Authorization", "Bearer $bearer")
                .header("Origin", ORIGIN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"analysis_contract\":\"$analysisContract\"}")),
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

    private fun seedOpenNative(input: ScheduleDslOfflineInput = nativeInput()) {
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
            VALUES (?,?,'synthetic selected HTTP','EITHER','Asia/Seoul','2027-01-04','2027-01-11',
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

    private fun nativeInput() =
        SelectedDslPhysicalInputFixture.approvedInput().let { old ->
            old.copy(
                responseContractVersion = "schedule-dsl-response-4",
                semanticVersion = "schedule-ir-product-5",
                meetingMode = MeetingMode.EITHER,
                policy = ScheduleDslContextModePolicy.contextual(),
                inputs =
                    old.inputs.mapIndexed { index, item ->
                        item.copy(
                            rawText = if (index == 0) FIRST_NATIVE else SECOND_NATIVE,
                            evidence =
                                InputEvidence(
                                    3,
                                    F.ir.reference,
                                    interpretation = InputInterpretation.UNRESOLVED,
                                    unresolvedReason = SelectedDslPhysicalInputFixture.REVIEW_REASON,
                                ),
                        )
                    },
            )
        }

    /** Numeric18:30 is a synthetic model-context proposal, not a keyword rule or model accuracy claim. */
    private fun response4(): String {
        val input = nativeInput()

        fun evidence(index: Int): List<Map<String, Any?>> {
            val item = input.inputs[index]
            return listOf(
                mapOf(
                    "source_id" to "original",
                    "revision" to 3,
                    "start_code_point" to 0,
                    "end_code_point" to item.rawText.codePointCount(0, item.rawText.length),
                    "quote" to item.rawText,
                ),
            )
        }

        fun scope(
            day: String,
            hour: String,
        ) = mapOf("dimension" to "TIME", "start" to "${day}T$hour:00", "end" to "${day}T21:00:00")

        fun claim(
            id: String,
            day: String,
            index: Int,
        ) = mapOf(
            "id" to id,
            "effect" to "AVAILABLE",
            "interpretation" to if (index == 0) "INFERRED" else "EXPLICIT",
            "temporal_resolution" to if (index == 0) "NON_PRECISE" else "PRECISE_INTERVAL",
            "scope" to scope(day, if (index == 0) "18:30" else "18:00"),
            "evidence" to evidence(index),
            "assertion" to "ASSERTED",
            "supersedes" to emptyList<String>(),
            "alternative_group" to null,
            "alternative" to null,
        )

        fun node(
            id: String,
            claim: String?,
            children: List<String>,
            index: Int,
        ) = mapOf(
            "id" to id,
            "operator" to if (claim == null) "ANY_OF" else "CLAIM_REF",
            "claim_id" to claim,
            "children" to children,
            "choice_group" to null,
            "branch_labels" to emptyList<String>(),
            "interpretation" to "EXPLICIT",
            "assertion" to "ASSERTED",
            "evidence" to evidence(index),
        )
        val results =
            input.inputs.mapIndexed { index, item ->
                mapOf(
                    "input_ref" to item.submissionVersionId.toString(),
                    "conditions" to listOf(claim("thu", "2027-01-07", index), claim("fri", "2027-01-08", index)),
                    "program" to
                        mapOf(
                            "nodes" to
                                listOf(
                                    node("tr", "thu", emptyList(), index),
                                    node("fr", "fri", emptyList(), index),
                                    node("root", null, listOf("tr", "fr"), index),
                                ),
                            "availability_root" to "root",
                            "exclusion_root" to null,
                            "preference_root" to null,
                            "history_condition_ids" to emptyList<String>(),
                        ),
                    "approved_terms" to emptyList<Any>(),
                    "mode_restrictions" to
                        if (index != 0) {
                            emptyList()
                        } else {
                            listOf(
                                mapOf(
                                    "id" to "thu-video",
                                    "scope" to scope("2027-01-07", "00:00"),
                                    "permitted_modes" to listOf("REMOTE"),
                                    "mode_interpretation" to "EXPLICIT",
                                    "time_interpretation" to "EXPLICIT",
                                    "temporal_resolution" to "PRECISE_INTERVAL",
                                    "evidence" to evidence(index),
                                    "assertion" to "ASSERTED",
                                    "supersedes" to emptyList<String>(),
                                    "alternative_group" to null,
                                    "alternative" to null,
                                ),
                            )
                        },
                )
            }
        return mapper.writeValueAsString(
            mapOf(
                "semantic_version" to "schedule-ir-product-5",
                "ast_version" to "schedule-ast-1",
                "vocabulary_version" to "ko-time-terms-1",
                "results" to results,
            ),
        )
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
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()

        @Volatile var failGemini = false

        @Volatile var syntheticGeminiStatus: Int? = null
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint: URI = URI("http://127.0.0.1:${server.address.port}")

        init {
            server.createContext("/") { exchange ->
                requests += exchange.requestBody.use { String(it.readAllBytes(), Charsets.UTF_8) }
                val luna = exchange.requestURI.path == "/v1/responses"
                if (luna) lunaCalls.incrementAndGet() else calls.incrementAndGet()
                if (!luna && failGemini) {
                    exchange.sendResponseHeaders(503, -1)
                    exchange.close()
                    return@createContext
                }
                if (!luna && syntheticGeminiStatus != null) {
                    exchange.sendResponseHeaders(requireNotNull(syntheticGeminiStatus), -1)
                    exchange.close()
                    return@createContext
                }
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

        private const val STRUCTURAL_FIRST = "🙂 2027-01-08 20:00 이후 가능. 2027-01-10 17:00 이전 가능. 장소 의견 없음."
        private const val STRUCTURAL_PEER = "2027-01-08 19:00부터 22:00까지 가능. 2027-01-10 15:00부터 18:00까지 가능. 장소 의견 없음."
        private const val FIRST_NATIVE = "🙂 2027-01-07 목요일과 2027-01-08 금요일은 평소보다 조금 일찍부터 21시까지 가능. 목요일은 화상 전용, 금요일은 대면도 가능."
        private const val SECOND_NATIVE = "2027-01-07 목요일과 2027-01-08 금요일 각각 18시부터 21시까지 가능. 장소 의견 없음."
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
