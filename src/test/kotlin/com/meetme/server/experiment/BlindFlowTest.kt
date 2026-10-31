package com.meetme.server.experiment

import com.meetme.server.MeetMeServerApplication
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.meetingroom.domain.MeetingMode
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
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant

@EnabledIfEnvironmentVariable(named = "MEETME_BLIND20_MODE", matches = "BLIND20")
@SpringBootTest(classes = [MeetMeServerApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BlindFlowApplication::class)
class BlindFlowPaidTest {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var json: ObjectMapper

    @Test
    fun `approved fixed pilot and conditionally opened sealed flow`() {
        val settings = BlindFlowSettings.current
        val cases = mutableListOf<Map<String, Any?>>()
        var stage = "PILOT"
        var failure: String? = null
        try {
            for (name in listOf("pilot", "sealed")) {
                stage = name.uppercase()
                val output = settings.output.resolve(name)
                Files.createDirectory(output)
                val inputPath = Path.of(settings.manifest.path("scenariosDirectory").asText()).resolve("$name/input.json")
                val input = json.readTree(Files.readAllBytes(inputPath))
                val flow = BlindFlowHttpFlow(port, jdbc, tokens, json, output) { settings.budget.checkpoint() }
                // Execution metadata is separate from the immutable authored scenario.
                // There is deliberately no REMOTE fallback for a missing mode.
                val mode =
                    MeetingMode.valueOf(
                        settings.manifest
                            .path("meetingModes")
                            .path(name)
                            .asText(),
                    )
                val result = flow.run(input, mode)
                val snapshot = blindSnapshot(jdbc)
                write(output.resolve("database.json"), snapshot)
                val report = result + mapOf("case" to name, "snapshotAvailable" to true)
                cases += report
                write(output.resolve("complete.json"), report)
                settings.budget.assertComplete()
                if (name == "pilot") {
                    if ((result["questionCount"] as Int) == 0 || (result["answerCount"] as Int) == 0) {
                        stage = "SEALED_HELD_ROUTE_NOT_EXERCISED"
                        break
                    }
                    val gate = output.resolve("independent-grade.json")
                    val started = System.nanoTime()
                    while (!Files.exists(gate)) {
                        settings.budget.checkpoint()
                        check(System.nanoTime() - started < Duration.ofMinutes(10).toNanos())
                        Thread.sleep(250)
                    }
                    if (!json.readTree(Files.readAllBytes(gate)).path("pilotPass").asBoolean()) {
                        stage = "SEALED_HELD_INDEPENDENT_GRADE"
                        break
                    }
                }
                stage = "FINISHED"
            }
        } catch (error: Throwable) {
            failure = error.javaClass.simpleName
            settings.budget.stop(BlindFlowStop.PROVIDER_FAILURE)
        } finally {
            val database = runCatching { blindSnapshot(jdbc) }.getOrElse { mapOf("available" to false) }
            write(settings.output.resolve("database.json"), database)
            val report =
                mapOf(
                    "stage" to stage,
                    "failureClass" to failure,
                    "cases" to cases,
                    "budget" to settings.budget.snapshot(),
                    "finishedAt" to Instant.now().toString(),
                    "billedCostObserved" to false,
                    "billedCost" to null,
                    "realGeminiRequests" to 0,
                    "browserFlowVerified" to false,
                    "providerEvents" to BlindFlowApplication.events.toList(),
                    "syntheticPrimaryCalls" to BlindFlowApplication.syntheticPrimaryCalls.get(),
                )
            write(settings.output.resolve("results.json"), report)
        }
        check(failure == null && cases.size == 2 && stage == "FINISHED") { "Incomplete approved blind flow; inspect preserved evidence" }
    }

    private fun write(
        path: Path,
        value: Any?,
    ) {
        Files.writeString(path, json.writeValueAsString(value), StandardOpenOption.CREATE_NEW)
    }

    companion object {
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = BlindFlowSettings.properties(registry)

        @JvmStatic @AfterAll
        fun stop() = PrimaryGeminiSmokeInfrastructure.stop()
    }
}

internal fun blindSnapshot(jdbc: JdbcTemplate): Map<String, Any?> =
    mapOf(
        "available" to true,
        "prechecks" to jdbc.queryForList(CUMULATIVE_PRECHECK_EXECUTIONS_SQL),
        "attempts" to
            jdbc.queryForList(
                "SELECT provider,model,attempt_number,failure_code,failure_kind,input_tokens,output_tokens,response_bytes,finished_at IS NOT NULL AS finished FROM coordination_attempts ORDER BY started_at,attempt_number",
            ),
        "transport" to jdbc.queryForList("SELECT call_number,elapsed_nanos,outcome,http_status FROM parser_transport_client_calls"),
        "versions" to
            jdbc.queryForList(
                "SELECT v.id,v.revision,v.raw_text,v.input_evidence::text,h.room_id,h.participant_id FROM submission_versions v JOIN submission_heads h ON h.id=v.submission_id",
            ),
        "frozen" to jdbc.queryForList("SELECT batch_id,submission_version_id FROM submission_batch_items"),
        "structured" to
            jdbc.queryForList("SELECT batch_id,submission_version_id,conditions::text,rejection_code FROM structured_submission_results"),
        "rooms" to jdbc.queryForList("SELECT id,collection_status FROM meeting_rooms"),
        "invocations" to
            jdbc.queryForList(
                "SELECT gemini_attempts,luna_attempts,finished_at IS NOT NULL AS finished FROM analysis_invocations",
            ),
    )

@SpringBootTest(classes = [MeetMeServerApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LunaFallbackOnceApplication::class)
class BlindFlowHttpContractTest {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var json: ObjectMapper

    @Autowired private lateinit var settings: LunaFallbackOnceSettings

    @Test
    fun `new HTTP driver and database snapshot operate on isolated free original source guarded fixture`() {
        val output = Files.createTempDirectory("blind20-http-free-")
        val input =
            json.valueToTree<tools.jackson.databind.JsonNode>(
                mapOf(
                    "title" to "합성 무료 하네스 검증",
                    "startDate" to "2026-11-12",
                    "endDate" to "2026-11-13",
                    "participants" to (1..3).map { mapOf("id" to "p$it", "rawInput" to "11월 12일 오후 6시부터 오후 8시까지 온라인 참석 가능합니다.") },
                ),
            )
        val result = BlindFlowHttpFlow(port, jdbc, tokens, json, output) {}.run(input, MeetingMode.REMOTE)
        check(result["analysisCompleted"] == true)
        check(result["selectionPersisted"] == true)
        check(blindSnapshot(jdbc)["available"] == true)
        check(settings.wire.admissions == 0)
    }

    companion object {
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = LunaFallbackOnceSettings.properties(registry)

        @JvmStatic @AfterAll
        fun stop() = PrimaryGeminiSmokeInfrastructure.stop()
    }
}
