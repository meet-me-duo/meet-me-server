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
import java.time.Instant

/** A fresh one-room approval. Never opens or schedules a sealed scenario. */
@EnabledIfEnvironmentVariable(named = "MEETME_PILOT10_MODE", matches = "PILOT10")
@SpringBootTest(classes = [MeetMeServerApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BlindFlowApplication::class)
class PilotTenPaidTest {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var json: ObjectMapper

    @Test
    fun `new approval permits only the frozen development pilot and stops without a second run`() {
        val settings = PilotTenSettings.current
        var result: Map<String, Any?> = emptyMap()
        var failure: String? = null
        var causes: List<String> = emptyList()
        var driver: BlindFlowHttpFlow? = null
        try {
            val output = settings.output.resolve("pilot")
            Files.createDirectory(output)
            val path = Path.of(settings.manifest.path("pilotDirectory").asText()).resolve("input.json")
            val input = json.readTree(Files.readAllBytes(path))
            driver = BlindFlowHttpFlow(port, jdbc, tokens, json, output) { settings.budget.checkpoint() }
            result = driver.run(input, MeetingMode.IN_PERSON)
            settings.budget.assertComplete()
        } catch (error: Throwable) {
            failure = error.javaClass.simpleName
            causes = generateSequence(error) { it.cause }.take(8).map { it.javaClass.simpleName }.toList()
            settings.budget.stop(BlindFlowStop.PROVIDER_FAILURE)
        } finally {
            val snapshot = runCatching { blindSnapshot(jdbc) }.getOrElse { mapOf("available" to false) }
            write(settings.output.resolve("database.json"), snapshot)
            write(
                settings.output.resolve("results.json"),
                mapOf(
                    "approvalId" to PilotTenSettings.APPROVAL_ID,
                    "mode" to "PILOT10",
                    "pilot" to result,
                    "progress" to driver?.progress(),
                    "failureClass" to failure,
                    "causeClasses" to causes,
                    "budget" to settings.budget.snapshot(),
                    "providerEvents" to BlindFlowApplication.events.toList(),
                    "syntheticPrimaryCalls" to BlindFlowApplication.syntheticPrimaryCalls.get(),
                    "actualGeminiRequests" to 0,
                    "sealedOpened" to false,
                    "billedCostObserved" to false,
                    "billedCost" to null,
                    "browserFlowVerified" to false,
                    "finishedAt" to Instant.now().toString(),
                    "coverage" to pilotTenCoverage(result, driver?.progress().orEmpty()),
                ),
            )
        }
        // A valid CLEAR or no-match outcome is reported as observed, never retried to manufacture coverage.
        check(failure == null && result["analysisCompleted"] == true) { "Incomplete pilot; inspect preserved evidence without rerunning" }
    }

    private fun write(
        path: Path,
        value: Any?,
    ) {
        Files.writeString(path, json.writeValueAsString(value), StandardOpenOption.CREATE_NEW)
    }

    companion object {
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = PilotTenSettings.properties(registry)

        @JvmStatic @AfterAll
        fun stop() = PrimaryGeminiSmokeInfrastructure.stop()
    }
}

/** A failed final stage must not erase already observed questions or pretend unvisited stages failed. */
internal fun pilotTenCoverage(
    result: Map<String, Any?>,
    progress: Map<String, Any?>,
): Map<String, Boolean?> {
    val complete = result["analysisCompleted"] == true

    fun observedCount(name: String): Boolean? =
        if ((progress[name] as? Int ?: result[name] as? Int ?: 0) > 0) {
            true
        } else if (complete) {
            false
        } else {
            null
        }
    return mapOf(
        "questionObserved" to observedCount("questionCount"),
        "answerSent" to observedCount("answerCount"),
        "unappliedObserved" to (result["unappliedCount"] as? Int)?.let { it > 0 },
        "selectionPersisted" to (result["selectionPersisted"] as? Boolean),
    )
}
