package com.meetme.server.experiment

import com.meetme.server.MeetMeServerApplication
import com.meetme.server.account.application.port.output.AccessTokenPort
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.StandardOpenOption

internal const val CUMULATIVE_PRECHECK_EXECUTIONS_SQL =
    "SELECT check_id,generation,provider,model,failure_code,input_tokens,output_tokens,response_bytes,finished_at " +
        "FROM precheck_executions"

/** Actual providers, default OFF. A single approval/ledger covers diagnostics and the one real E2E room. */
@EnabledIfEnvironmentVariable(named = "MEETME_CAPPED_EVAL_MODE", matches = "CUMULATIVE10")
@SpringBootTest(classes = [MeetMeServerApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CumulativeExperimentRuntime::class, CumulativeExperimentFixtures::class)
class CumulativeProviderEvaluationTest {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var json: ObjectMapper

    @Autowired private lateinit var settings: CumulativeExperimentSettings

    @Autowired private lateinit var observations: CumulativeExperimentObservations

    @Test
    fun `one capped actual-provider experiment preserves a safe final report on every outcome`() {
        try {
            val driver =
                CumulativeExperimentDriver(
                    settings.ledger,
                    { attempt -> flow().connectivity(attempt) },
                    { flow().realE2E() },
                    ::record,
                )
            val result = driver.run()
            check(result["analysisCompleted"] == true)
        } catch (_: Throwable) {
            // Never let JUnit/Gradle render a provider body, message, header or original cause chain.
            throw AssertionError("Capped provider experiment stopped; inspect the safe result artifact")
        }
    }

    private fun flow() = CumulativeExperimentHttpFlow(port, jdbc, tokens, json, settings)

    private fun record(result: Map<String, Any?>) {
        val report =
            result +
                mapOf(
                    "mode" to "CUMULATIVE10",
                    "runId" to settings.manifest.path("runId").asText(),
                    "sourceCommit" to settings.manifest.path("sourceBaseRevision").asText(),
                    "sourceSha256" to settings.manifest.path("sourceSha256").asText(),
                    "configSha256" to settings.manifest.path("configSha256").asText(),
                    "payloadSha256" to settings.manifest.path("payloadSha256").asText(),
                    "answerOracleSha256" to settings.manifest.path("answerOracleSha256").asText(),
                    "timeoutDiagnostics" to observations.snapshots(),
                    "timeoutDiagnosticsScope" to "Gemini only; no detailed Luna/HTTP2 diagnostics claim",
                    "diagnosticCollectorCoverageKnown" to observations.coverageKnown(),
                    "providerEvents" to observations.providerEvents(),
                    "clientCallFacts" to observations.callFacts(),
                    "partialDatabaseSnapshot" to partialSnapshot(),
                    "unknownUsageAndCost" to null,
                    "clientLifecycleCallsDefinition" to
                        "Common OkHttp callStarted lifecycle count, including direct Jev/Luna HTTP; not transmission/billing proof",
                    "limitations" to
                        listOf(
                            "Connectivity prechecks are explicit mock CLEAR; actual E2E prechecks are real providers",
                            "No gold/holdout or old development data; no prompt/timeout/threshold tuning; " +
                                "fixed threshold0.8 is not calibrated",
                        ),
                )
        val encoded = json.writerWithDefaultPrettyPrinter().writeValueAsString(report)
        val secretPresent =
            listOf("GEMINI_API_KEY", "OPENAI_API_KEY", "TYPESAFE_API_KEY")
                .mapNotNull { System.getenv(it)?.takeIf(String::isNotBlank) }
                .any(encoded::contains)
        val exported =
            if (secretPresent) {
                runCatching { settings.ledger.observationUnavailable() }
                json.writeValueAsString(
                    mapOf(
                        "mode" to "CUMULATIVE10",
                        "analysisCompleted" to false,
                        "outcome" to "SECRET_EXPORT_DENIED",
                        "sensitiveContentExported" to false,
                    ),
                )
            } else {
                encoded
            }
        Files.writeString(settings.outputDirectory.resolve("results.json"), exported, StandardOpenOption.CREATE_NEW)
        check(!secretPresent)
    }

    private fun partialSnapshot(): Map<String, Any?> =
        try {
            mapOf(
                "available" to true,
                "counts" to
                    mapOf(
                        "rooms" to count("meeting_rooms"),
                        "participantsIncludingAccountHosts" to count("participants"),
                        "frozenVersions" to count("submission_batch_items"),
                        "structuredResults" to count("structured_submission_results"),
                        "candidates" to count("candidates"),
                    ),
                "attempts" to
                    rows(
                        "SELECT id,provider,model,attempt_number,started_at,finished_at,failure_code,failure_kind," +
                            "input_tokens,output_tokens,response_bytes FROM coordination_attempts ORDER BY started_at,attempt_number",
                    ),
                "invocations" to
                    rows(
                        "SELECT id,started_at,deadline_at,gemini_attempts,luna_attempts,finished_at,winner_attempt_id " +
                            "FROM analysis_invocations",
                    ),
                "clientCalls" to
                    rows(
                        "SELECT attempt_id,call_number,elapsed_nanos,outcome,http_status FROM parser_transport_client_calls " +
                            "ORDER BY attempt_id,call_number",
                    ),
                "precheckExecutions" to
                    rows(CUMULATIVE_PRECHECK_EXECUTIONS_SQL),
                "evidenceCounts" to
                    rows(
                        "SELECT v.input_evidence->>'protocol' AS protocol," +
                            "v.input_evidence->>'interpretation' AS interpretation,count(*) AS count " +
                            "FROM submission_batch_items b JOIN submission_versions v ON v.id=b.submission_version_id GROUP BY 1,2",
                    ),
            )
        } catch (_: Exception) {
            mapOf("available" to false, "counts" to null, "failureClass" to "DATABASE_SNAPSHOT_UNAVAILABLE")
        }

    private fun rows(sql: String): List<Map<String, Any?>> =
        jdbc.queryForList(sql).map { row ->
            row.mapValues { (_, value) ->
                when (value) {
                    is java.sql.Timestamp -> value.toInstant().toString()
                    is java.time.OffsetDateTime -> value.toInstant().toString()
                    is java.util.UUID -> value.toString()
                    is String, is Number, is Boolean, null -> value
                    else -> null
                }
            }
        }

    private fun count(table: String): Long = checkNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java))

    companion object {
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = CumulativeExperimentInfrastructure.properties(registry)

        @JvmStatic @AfterAll
        fun stop() = CumulativeExperimentInfrastructure.stop()
    }
}
