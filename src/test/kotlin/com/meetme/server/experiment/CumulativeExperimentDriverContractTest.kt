package com.meetme.server.experiment

import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentAdmission
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentCall
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentTransportFailure
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Exact paid-entry orchestration seam with synthetic stages and ledger events. No SDK or network executes. */
class CumulativeExperimentDriverContractTest {
    @TempDir lateinit var directory: Path
    private val json = JsonMapper.builder().build()

    @Test
    fun `completed first connectivity permits exactly one E2E and excludes raw stage evidence`() {
        val ledger = ledger()
        val diagnostics = mutableListOf<Int>()
        val reports = mutableListOf<Map<String, Any?>>()
        var e2e = 0
        val driver =
            CumulativeExperimentDriver(ledger, { attempt ->
                diagnostics += attempt
                stage(ledger, attempt)
            }, {
                e2e++
                stage(ledger, 10)
            }, { reports += it })

        val result = driver.run()

        assertEquals(listOf(1), diagnostics)
        assertEquals(1, e2e)
        assertEquals(1, ledger.snapshot().e2eRooms)
        assertEquals(1, reports.size)
        assertEquals(true, result["analysisCompleted"])
        assertFalse(json.writeValueAsString(reports).contains("PRIVATE_"))
    }

    @Test
    fun `one known transient failure permits a fresh second diagnostic then one E2E`() {
        val ledger = ledger()
        val diagnostics = mutableListOf<Int>()
        val reports = mutableListOf<Map<String, Any?>>()
        var e2e = 0
        val driver =
            CumulativeExperimentDriver(ledger, { attempt ->
                diagnostics += attempt
                stage(ledger, attempt, if (attempt == 1) ExperimentTechnicalCode.NETWORK else null)
            }, {
                e2e++
                stage(ledger, 10)
            }, { reports += it })

        driver.run()

        assertEquals(listOf(1, 2), diagnostics)
        assertEquals(1, e2e)
        assertEquals(2, ledger.snapshot().connectivityAttempts)
        assertEquals(1, ledger.snapshot().e2eRooms)
        assertEquals(1, reports.size)
        assertEquals(true, reports.single()["analysisCompleted"])
    }

    @Test
    fun `second diagnostic failure stops even when its normalized code differs from the first`() {
        val ledger = ledger()
        val diagnostics = mutableListOf<Int>()
        val reports = mutableListOf<Map<String, Any?>>()
        var e2e = 0
        val driver =
            CumulativeExperimentDriver(ledger, { attempt ->
                diagnostics += attempt
                stage(ledger, attempt, if (attempt == 1) ExperimentTechnicalCode.NETWORK else ExperimentTechnicalCode.SERVER)
            }, {
                e2e++
                stage(ledger, 10)
            }, { reports += it })

        runCatching { driver.run() }

        assertEquals(listOf(1, 2), diagnostics)
        assertEquals(0, e2e)
        assertEquals(0, ledger.snapshot().e2eRooms)
        assertEquals(1, reports.size)
        assertEquals(false, reports.single()["analysisCompleted"])
    }

    @Test
    fun `known nonretryable refusal stops after the first diagnostic without E2E`() {
        val ledger = ledger()
        val diagnostics = mutableListOf<Int>()
        val reports = mutableListOf<Map<String, Any?>>()
        var e2e = 0
        val driver =
            CumulativeExperimentDriver(ledger, { attempt ->
                diagnostics += attempt
                stage(ledger, attempt, ExperimentTechnicalCode.REFUSED)
            }, {
                e2e++
                stage(ledger, 10)
            }, { reports += it })

        runCatching { driver.run() }

        assertEquals(listOf(1), diagnostics)
        assertEquals(0, e2e)
        assertEquals(0, ledger.snapshot().e2eRooms)
        assertEquals(1, reports.size)
        assertEquals(false, reports.single()["analysisCompleted"])
    }

    @Test
    fun `unknown exception or counter persistence failure still records exactly one safe stopped result`() {
        listOf("exception", "persistence").forEachIndexed { index, failure ->
            var rejectWrite = false
            val ledger =
                ExperimentCallLedger(
                    directory.resolve("$failure.json"),
                    UUID(0, index.toLong() + 1),
                    "a".repeat(64),
                    "b".repeat(64),
                    "c".repeat(64),
                    persistOverride = { if (rejectWrite) throw IOException("PRIVATE_STORE_MESSAGE_KEY") },
                )
            val reports = mutableListOf<Map<String, Any?>>()
            var diagnostics = 0
            var e2e = 0
            val driver =
                CumulativeExperimentDriver(ledger, { _ ->
                    diagnostics++
                    if (failure == "exception") throw IOException("PRIVATE_FLOW_MESSAGE_HEADER_KEY")
                    val room = UUID(0, 101)
                    ledger.bindRoom(room)
                    rejectWrite = true
                    ledger.reserveForExecution(room, UUID(0, 201), listOf(admission(1)))
                    CumulativeStageResult(false)
                }, {
                    e2e++
                    CumulativeStageResult(true)
                }, { reports += it })

            runCatching { driver.run() }

            assertEquals(1, diagnostics)
            assertEquals(0, e2e)
            assertEquals(1, reports.size)
            assertEquals(false, reports.single()["analysisCompleted"])
            assertFalse(json.writeValueAsString(reports).contains("PRIVATE_"))
            assertFailsWith<ExperimentCallStop> { ledger.checkpoint() }
        }
    }

    private fun stage(
        ledger: ExperimentCallLedger,
        number: Int,
        failure: ExperimentTechnicalCode? = null,
    ): CumulativeStageResult {
        val room = UUID(0, 100L + number)
        val execution = UUID(0, 200L + number)
        ledger.bindRoom(room)
        val admission = admission(number)
        ledger.reserveForExecution(room, execution, listOf(admission))
        val call =
            AiHttpExperimentCall(UUID(0, 300L + number), execution, UUID(0, 400L + number), admission.operation, 1, admission.opaqueId)
        ledger.callStarted(call)
        ledger.beforeHeaders(call)
        ledger.headersEmitted(call)
        val transport =
            when (failure) {
                ExperimentTechnicalCode.NETWORK -> AiHttpExperimentTransportFailure.NETWORK
                ExperimentTechnicalCode.TIMEOUT -> AiHttpExperimentTransportFailure.TIMEOUT
                else -> null
            }
        ledger.callFinished(
            call,
            if (failure == ExperimentTechnicalCode.SERVER) {
                503
            } else if (transport != null) {
                null
            } else {
                200
            },
            transport,
        )
        if (failure != null) ledger.technicalFailure(call.callId, failure)
        return CumulativeStageResult(
            failure == null,
            failure,
            mapOf(
                "publicStatus" to if (failure == null) "NO_MATCH" else "ANALYSIS_DELAYED",
                "candidateCount" to 0,
                "rawBody" to "PRIVATE_PROVIDER_BODY",
                "requestHeaders" to mapOf("Authorization" to "PRIVATE_HEADER_KEY"),
                "exceptionMessage" to "PRIVATE_EXCEPTION_MESSAGE",
            ),
        )
    }

    private fun admission(number: Int) = AiHttpExperimentAdmission(number.toString(16).padStart(64, '0'), AiPhysicalOperation.FINAL_PRIMARY)

    private fun ledger() =
        ExperimentCallLedger(directory.resolve("ledger.json"), UUID(0, 1), "a".repeat(64), "b".repeat(64), "c".repeat(64))
}
