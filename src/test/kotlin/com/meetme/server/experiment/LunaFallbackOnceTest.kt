package com.meetme.server.experiment

import com.meetme.server.MeetMeServerApplication
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest(classes = [MeetMeServerApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LunaFallbackOnceApplication::class)
abstract class LunaFallbackOnceBase {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var json: ObjectMapper

    @Autowired private lateinit var settings: LunaFallbackOnceSettings

    @Test
    fun `synthetic Gemini 503 switches to one original Luna adapter and validates stored results and host APIs`() {
        val started = System.nanoTime()
        var flowCompleted = false
        var failure = false
        var snapshotAvailable = false
        var stage = "HTTP_FLOW"
        var post: Map<String, Any?> = emptyMap()
        try {
            val flow = PrimaryGeminiSmokeHttpFlow(port, jdbc, tokens, json, LiveEvaluationSafety { emptyList() }).run()
            flowCompleted = flow["analysisCompleted"] == true
            if (flowCompleted) {
                stage = "POST_VALIDATION"
                post = validateAndSelect()
                stage = "FINISHED"
            }
        } catch (_: Throwable) {
            failure = true
        } finally {
            val snapshot = runCatching { snapshot() }.getOrElse { mapOf("available" to false, "counts" to null) }
            snapshotAvailable = snapshot["available"] == true
            val evidence =
                mapOf(
                    "mode" to if (settings.paid) "LUNA_FALLBACK_ONCE" else "LUNA_FALLBACK_FREE_MOCK",
                    "model" to "gpt-6-luna",
                    "realGeminiRequests" to 0,
                    "syntheticPrimaryStatus" to 503,
                    "syntheticRetryAfterMillis" to 40_000,
                    "syntheticPrimaryLogicalAttempts" to settings.primaryCalls.get(),
                    "lunaParserInvocations" to settings.lunaCalls.get(),
                    "maximumLunaPhysicalRequests" to 1,
                    "maximumLunaTimeoutMillis" to 27_000,
                    "actualWireAdmissions" to settings.wire.admissions,
                    "wireEvents" to settings.events.toList(),
                    "transportTimeouts" to settings.timings.toList(),
                    "parserOutcomes" to settings.parseOutcomes.toList(),
                    "originalJdbcSourceChecks" to settings.sourceChecks.get(),
                    "syntheticLunaResponses" to settings.syntheticResponses.get(),
                    "stageAtCapture" to stage,
                    "productionPromptSchemaAndOutputCapUnchanged" to true,
                    "maximumOutputTokens" to 32_768,
                    "analysisCompleted" to flowCompleted,
                    "postValidation" to post,
                    "localFlowFailure" to failure,
                    "elapsedNanos" to System.nanoTime() - started,
                    "database" to snapshot,
                    "billedCost" to null,
                    "billedCostObserved" to false,
                    "jevCalls" to 0,
                    "lunaClarificationCalls" to 0,
                    "automaticRetries" to 0,
                    "limitations" to
                        listOf(
                            "Synthetic primary503 with Retry-After40s skips retry under existing Gemini30s stage budget",
                            "CLEAR prechecks are test doubles; no blind or Jev/question evaluation",
                            "Client sent-header counts are not vendor billing proof",
                        ),
                )
            val encoded = json.writeValueAsString(evidence)
            val sensitive =
                listOf("GEMINI_API_KEY", "OPENAI_API_KEY", "TYPESAFE_API_KEY")
                    .mapNotNull {
                        System.getenv(it)?.takeIf { value ->
                            value.length >
                                4
                        }
                    }.any(encoded::contains)
            Files.writeString(
                settings.output.resolve("results.json"),
                if (sensitive) "{\"result\":\"SAFE_EXPORT_DENIED\"}" else encoded,
                StandardOpenOption.CREATE_NEW,
            )
            check(!sensitive) { "Safe export refused" }
        }
        check(
            !failure && snapshotAvailable && flowCompleted && post["checksPassed"] == true,
        ) { "One-shot failed or incomplete; inspect safe results; never rerun" }
        check(settings.primaryCalls.get() == 1 && settings.lunaCalls.get() == 1 && settings.wire.admissions == if (settings.paid) 1 else 0)
        check(settings.sourceChecks.get() > 0 && settings.syntheticResponses.get() == if (settings.paid) 0 else 1)
    }

    private fun count(table: String): Long = checkNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java))

    private fun snapshot(): Map<String, Any?> =
        mapOf(
            "available" to true,
            "asOf" to Instant.now().toString(),
            "counts" to
                mapOf(
                    "frozen" to count("submission_batch_items"),
                    "structured" to count("structured_submission_results"),
                    "candidates" to count("candidates"),
                ),
            "attempts" to
                jdbc.queryForList(
                    "SELECT provider,model,attempt_number,failure_code,failure_kind,input_tokens,output_tokens,response_bytes,finished_at IS NOT NULL AS finished FROM coordination_attempts ORDER BY started_at,attempt_number",
                ),
            "transport" to jdbc.queryForList("SELECT call_number,elapsed_nanos,outcome,http_status FROM parser_transport_client_calls"),
            "structured" to
                jdbc.queryForList("SELECT conditions::text AS normalized_conditions,rejection_code FROM structured_submission_results"),
            "rooms" to jdbc.queryForList("SELECT collection_status FROM meeting_rooms"),
            "invocations" to
                jdbc.queryForList("SELECT gemini_attempts,luna_attempts,finished_at IS NOT NULL AS finished FROM analysis_invocations"),
        )

    private fun validateAndSelect(): Map<String, Any?> {
        val invite = jdbc.queryForObject("SELECT invite_code FROM meeting_rooms", String::class.java)!!
        val host = jdbc.queryForMap("SELECT id,display_name FROM account_users")
        val bearer = "Bearer ${tokens.issue(AccountPrincipal(host["id"] as UUID, host["display_name"] as String))}"
        val path = "/api/rooms/$invite"
        val candidates = call("GET", "$path/candidates", bearer)
        val recommendations = call("GET", "$path/recommendations", bearer)
        val unapplied = call("GET", "$path/candidates/unapplied-inputs", bearer)
        val options =
            recommendations.second
                .path("options")
                .values()
                .toList()
        val validWindows =
            options.isNotEmpty() &&
                options.all {
                    val start = Instant.parse(it.path("time_range").path("start_at").asText())
                    val end = Instant.parse(it.path("time_range").path("end_at").asText())
                    start >= Instant.parse("2026-11-12T09:00:00Z") && end <= Instant.parse("2026-11-12T11:00:00Z") && end > start
                }
        val frozen = count("submission_batch_items")
        val structured = count("structured_submission_results")
        val aligned =
            jdbc.queryForObject(
                "SELECT count(*) FROM structured_submission_results r JOIN submission_batch_items b ON b.batch_id=r.batch_id AND b.submission_version_id=r.submission_version_id",
                Long::class.java,
            ) ==
                3L
        val positive =
            candidates.first == 200 &&
                recommendations.first == 200 &&
                unapplied.first == 200 &&
                validWindows &&
                frozen == 3L &&
                structured == 3L &&
                aligned &&
                jdbc.queryForObject(
                    "SELECT count(*) FROM structured_submission_results WHERE rejection_code IS NOT NULL OR jsonb_array_length(conditions)=0",
                    Long::class.java,
                ) ==
                0L &&
                candidates.second.path("applied_submissions").asInt() == 3 &&
                candidates.second.path("total_submissions").asInt() == 3 &&
                unapplied.second.isArray &&
                unapplied.second.size() == 0
        val result =
            mutableMapOf<String, Any?>(
                "candidatesStatus" to candidates.first,
                "recommendationsStatus" to recommendations.first,
                "unappliedStatus" to unapplied.first,
                "candidateCount" to candidates.second.path("candidates").size(),
                "recommendationCount" to options.size,
                "expectedWindowMatched" to validWindows,
                "frozenStructuredVersionJoinMatched" to aligned,
                "allThreeInputsContributed" to positive,
                "hostSelectionAttempted" to false,
                "checksPassed" to false,
            )
        if (positive) {
            val option = options.first()
            val variant = option.path("variants").values().first()
            val confirmed =
                call(
                    "POST",
                    "$path/recommendations/${option.path("option_id").asText()}/confirmation",
                    bearer,
                    mapOf(
                        "analysis_id" to recommendations.second.path("analysis_id").asText(),
                        "variant_id" to variant.path("variant_id").asText(),
                        "start_at" to option.path("time_range").path("start_at").asText(),
                        "end_at" to option.path("time_range").path("end_at").asText(),
                    ),
                )
            val selected = call("GET", "$path/result", bearer)
            val room = call("GET", path, bearer)
            result["hostSelectionAttempted"] = true
            result["confirmationStatus"] = confirmed.first
            result["resultStatus"] = selected.first
            result["publicStatus"] = room.second.path("public_status").asText()
            val selection = selected.second.path("selection")
            val selectionMatched =
                selection.path("analysis_id").asText() == recommendations.second.path("analysis_id").asText() &&
                    selection.path("option_id").asText() == option.path("option_id").asText() &&
                    selection.path("variant_id").asText() == variant.path("variant_id").asText() &&
                    selection.path("start_at").asText() == option.path("time_range").path("start_at").asText() &&
                    selection.path("end_at").asText() == option.path("time_range").path("end_at").asText()
            result["persistedSelectionMatchesRequestedIdsAndWindow"] = selectionMatched
            result["checksPassed"] =
                confirmed.first == 200 &&
                selected.first == 200 &&
                room.second.path("public_status").asText() == "CONFIRMED" &&
                selectionMatched &&
                variant.path("attendance_count").asInt() == 3
        }
        return result
    }

    private fun call(
        method: String,
        path: String,
        bearer: String,
        body: Any? = null,
    ): Pair<Int, JsonNode> {
        val request =
            HttpRequest
                .newBuilder(
                    URI("http://127.0.0.1:$port$path"),
                ).timeout(Duration.ofSeconds(5))
                .header("Authorization", bearer)
        if (method == "POST") request.header("Origin", "http://localhost:18931").header("Content-Type", "application/json")
        request.method(
            method,
            body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody(),
        )
        val response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to json.readTree(response.body())
    }

    companion object {
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = LunaFallbackOnceSettings.properties(registry)

        @JvmStatic @AfterAll
        fun stop() {
            PrimaryGeminiSmokeInfrastructure.stop()
        }
    }
}

@DisabledIfEnvironmentVariable(named = "MEETME_LUNA_FALLBACK_MODE", matches = ".+")
class LunaFallbackOnceApplicationContractTest : LunaFallbackOnceBase()

@EnabledIfEnvironmentVariable(named = "MEETME_LUNA_FALLBACK_MODE", matches = "LUNA_FALLBACK_ONCE")
class LunaFallbackOncePaidTest : LunaFallbackOnceBase()
