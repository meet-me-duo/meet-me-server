package com.meetme.server.experiment

import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentAdmission
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentCall
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentTransportFailure
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Cumulative durable experiment policy only. Synthetic opaque identities and no SDK/network/provider invocation. */
class ExperimentCallLedgerContractTest {
    @TempDir lateinit var temporary: Path
    private val runId = UUID(0, 900)
    private val diagnosticRoom = UUID(0, 201)
    private val e2eRoom = UUID(0, 202)
    private val diagnosticExecution = UUID(0, 301)
    private val e2eExecution = UUID(0, 302)
    private val source = "a".repeat(64)
    private val config = "b".repeat(64)
    private val approval = "c".repeat(64)
    private val path get() = temporary.resolve("ledger.json")

    @Test
    fun `cap ten survives reopening and separates conservative reservations from emitted headers`() {
        val ledger = ledger()
        connected(ledger)
        val pending = (2..10).map { admission(it, AiPhysicalOperation.entries[it % AiPhysicalOperation.entries.size]) }
        ledger.reserveForExecution(e2eRoom, e2eExecution, pending)
        ledger.reserveForExecution(e2eRoom, e2eExecution, pending)
        pending.take(2).forEachIndexed { index, admission -> emit(ledger, call(index + 2, admission), 200) }
        val before = ledger.snapshot()
        assertEquals(10, before.admittedCalls)
        assertEquals(3, before.sdkCalls)
        assertEquals(3, before.headersStarted)
        assertEquals(3, before.headersEmitted)

        val reopened = ledger()
        assertEquals(before, reopened.snapshot())
        assertFailsWith<ExperimentCallStop> {
            reopened.reserveForExecution(e2eRoom, e2eExecution, listOf(admission(11)))
        }
        assertEquals(10, reopened.snapshot().admittedCalls)
        assertEquals(3, reopened.snapshot().headersEmitted)
        assertEquals(ExperimentStopReason.BUDGET_EXHAUSTED, reopened.snapshot().stopReason)
    }

    @Test
    fun `count and generation group cannot partially consume the last reservation`() {
        val ledger = ledger()
        connected(ledger)
        ledger.reserveForExecution(e2eRoom, e2eExecution, (2..9).map { admission(it) })
        val group = listOf(admission(10, AiPhysicalOperation.PRECHECK_COUNT), admission(11, AiPhysicalOperation.PRECHECK_GENERATION))

        assertFailsWith<ExperimentCallStop> { ledger.reserveForExecution(e2eRoom, e2eExecution, group) }
        assertEquals(9, ledger.snapshot().admittedCalls)
    }

    @Test
    fun `source authority refusal or exception is first and never creates a reservation`() {
        val ledger = ledger()
        val helper = ExperimentCounterAdmission(ledger)
        val request = request(diagnosticRoom, diagnosticExecution)
        var sourceCalls = 0
        assertNull(
            helper.authorize<Instant>({
                sourceCalls++
                null
            }, request),
        )
        val original = IllegalStateException("PRIVATE_ORIGINAL_AUTHORITY")
        assertSame(
            original,
            assertFailsWith<IllegalStateException> {
                helper.authorize<Instant>({
                    sourceCalls++
                    throw original
                }, request)
            },
        )
        assertEquals(2, sourceCalls)
        assertEquals(0, ledger.snapshot().admittedCalls)
        assertNull(ledger.snapshot().stopReason)
    }

    @Test
    fun `positive counter reservation follows the exact original source grant`() {
        val ledger = ledger()
        ledger.beginConnectivityAttempt()
        ledger.bindRoom(diagnosticRoom)
        val grant = Instant.parse("2026-11-02T03:00:15Z")
        var sourceCalls = 0
        val result =
            ExperimentCounterAdmission(ledger).authorize({
                sourceCalls++
                assertEquals(0, ledger.snapshot().admittedCalls)
                grant
            }, request(diagnosticRoom, diagnosticExecution))
        assertSame(grant, result)
        assertEquals(1, sourceCalls)
        assertEquals(1, ledger.snapshot().admittedCalls)
        assertEquals(0, ledger.snapshot().headersEmitted)
    }

    @Test
    fun `different SDK call cannot reuse the consumed reservation of the same attempt`() {
        val ledger = ledger()
        connected(ledger)
        val admission = admission(2)
        ledger.reserveForExecution(e2eRoom, e2eExecution, listOf(admission))
        val first = call(2, admission)
        emit(ledger, first, 200)
        val hiddenRetry = first.copy(callId = UUID(0, 999))
        var extraHeaders = 0

        assertFailsWith<ExperimentCallStop> {
            ledger.callStarted(hiddenRetry)
            ledger.beforeHeaders(hiddenRetry)
            extraHeaders++
        }
        assertEquals(0, extraHeaders)
        assertEquals(2, ledger.snapshot().headersEmitted)
    }

    @Test
    fun `repeated observations and parser notification for one UUID are one failure`() {
        val ledger = ledger()
        connected(ledger)
        val admission = admission(2)
        ledger.reserveForExecution(e2eRoom, e2eExecution, listOf(admission))
        val call = call(2, admission)
        ledger.callStarted(call)
        ledger.callStarted(call)
        ledger.beforeHeaders(call)
        ledger.headersEmitted(call)
        ledger.headersEmitted(call)
        ledger.callFinished(call, 503, AiHttpExperimentTransportFailure.NETWORK)
        ledger.technicalFailure(call.callId, ExperimentTechnicalCode.SERVER)
        ledger.callFinished(call, 503, AiHttpExperimentTransportFailure.NETWORK)
        ledger.technicalFailure(call.callId, ExperimentTechnicalCode.SERVER)
        ledger.checkpoint()

        assertEquals(1, ledger.snapshot().errors[ExperimentTechnicalCode.SERVER])
        assertEquals(
            1,
            ledger
                .snapshot()
                .errors.values
                .sum(),
        )
        assertEquals(2, ledger.snapshot().headersEmitted)
        assertNull(ledger.snapshot().stopReason)
    }

    @ParameterizedTest
    @EnumSource(ExperimentTechnicalCode::class, names = ["SERVER", "TIMEOUT"])
    fun `typed parser evidence replaces generic network evidence for the same physical call`(typed: ExperimentTechnicalCode) {
        val ledger = ledger()
        connected(ledger)
        val admission = admission(2)
        ledger.reserveForExecution(e2eRoom, e2eExecution, listOf(admission))
        val call = call(2, admission)
        emit(ledger, call, null, AiHttpExperimentTransportFailure.NETWORK)
        ledger.technicalFailure(call.callId, typed)
        ledger.checkpoint()

        assertEquals(1, ledger.snapshot().errors[typed])
        assertEquals(0, ledger.snapshot().errors[ExperimentTechnicalCode.NETWORK] ?: 0)
        assertEquals(
            1,
            ledger
                .snapshot()
                .errors.values
                .sum(),
        )
        assertNull(ledger.snapshot().stopReason)
    }

    @Test
    fun `known HTTP 503 never becomes NETWORK merely because the transport used IOException`() {
        val ledger = ledger()
        connected(ledger)
        val admission = admission(2)
        ledger.reserveForExecution(e2eRoom, e2eExecution, listOf(admission))
        val call = call(2, admission)
        emit(ledger, call, 503, AiHttpExperimentTransportFailure.NETWORK)
        assertEquals(0, ledger.snapshot().errors[ExperimentTechnicalCode.NETWORK] ?: 0)
        ledger.technicalFailure(call.callId, ExperimentTechnicalCode.SERVER)
        assertEquals(1, ledger.snapshot().errors[ExperimentTechnicalCode.SERVER])
    }

    @Test
    fun `two distinct physical calls with the same normalized code stop regardless of provider stage`() {
        val ledger = ledger()
        connected(ledger)
        val firstAdmission = admission(2, AiPhysicalOperation.FINAL_PRIMARY)
        ledger.reserveForExecution(e2eRoom, e2eExecution, listOf(firstAdmission))
        val first = call(2, firstAdmission)
        emit(ledger, first, 503)
        ledger.providerFailure(first.callId, "SERVER")
        val nextAdmission = admission(3, AiPhysicalOperation.FINAL_FALLBACK)
        ledger.reserveForExecution(e2eRoom, e2eExecution, listOf(nextAdmission))
        val next = call(3, nextAdmission)
        runCatching {
            emit(ledger, next, 503)
            ledger.providerFailure(next.callId, "SERVER")
        }

        assertFailsWith<ExperimentCallStop> { ledger.checkpoint() }
        assertEquals(2, ledger.snapshot().errors[ExperimentTechnicalCode.SERVER])
        assertEquals(ExperimentStopReason.REPEATED_TECHNICAL_ERROR, ledger.snapshot().stopReason)
        assertFailsWith<ExperimentCallStop> { ledger().checkpoint() }
    }

    @Test
    fun `contradictory same rank typed codes are unknown rather than two failures or a chosen winner`() {
        val ledger = ledger()
        connected(ledger)
        val admission = admission(2)
        ledger.reserveForExecution(e2eRoom, e2eExecution, listOf(admission))
        val call = call(2, admission)
        emit(ledger, call, 200)
        ledger.technicalFailure(call.callId, ExperimentTechnicalCode.SERVER)
        runCatching { ledger.technicalFailure(call.callId, ExperimentTechnicalCode.TIMEOUT) }

        assertFailsWith<ExperimentCallStop> { ledger.checkpoint() }
        assertEquals(ExperimentStopReason.OBSERVATION_UNAVAILABLE, ledger.snapshot().stopReason)
        assertTrue(
            ledger
                .snapshot()
                .errors.values
                .sum() <= 1,
        )
    }

    @Test
    fun `unknown failure and unavailable observation fail closed without storing raw code`() {
        val ledger = ledger()
        connected(ledger)
        val admission = admission(2)
        ledger.reserveForExecution(e2eRoom, e2eExecution, listOf(admission))
        val call = call(2, admission)
        emit(ledger, call, 200)
        runCatching { ledger.providerFailure(call.callId, "PRIVATE_UNKNOWN_FAILURE_KEY") }

        assertFailsWith<ExperimentCallStop> { ledger.checkpoint() }
        assertFalse(ledger.snapshot().observationCoverageKnown)
        assertEquals(ExperimentStopReason.OBSERVATION_UNAVAILABLE, ledger.snapshot().stopReason)
        assertFalse(Files.readString(path).contains("PRIVATE_"))
    }

    @Test
    fun `started headers without emission completion stay UNKNOWN and prevent the next transmission`() {
        val ledger = ledger()
        connected(ledger)
        val admission = admission(2)
        ledger.reserveForExecution(e2eRoom, e2eExecution, listOf(admission))
        val call = call(2, admission)
        ledger.callStarted(call)
        ledger.beforeHeaders(call)
        runCatching { ledger.callFinished(call, null, AiHttpExperimentTransportFailure.NETWORK) }

        assertFailsWith<ExperimentCallStop> { ledger.checkpoint() }
        assertFalse(ledger.snapshot().observationCoverageKnown)
        assertEquals(ExperimentStopReason.UNKNOWN_EMISSION, ledger.snapshot().stopReason)
        assertEquals(1, ledger.snapshot().headersEmitted)
    }

    @Test
    fun `observer unavailability is a durable sticky stop`() {
        val ledger = ledger()
        connected(ledger)
        runCatching { ledger.observationUnavailable() }
        assertFailsWith<ExperimentCallStop> { ledger.checkpoint() }
        assertFalse(ledger.snapshot().observationCoverageKnown)
        assertEquals(ExperimentStopReason.OBSERVATION_UNAVAILABLE, ledger.snapshot().stopReason)
        assertFailsWith<ExperimentCallStop> { ledger().checkpoint() }
    }

    @Test
    fun `phase transition with an unfinished SDK call cannot open a second diagnostic`() {
        val ledger = ledger()
        ledger.beginConnectivityAttempt()
        ledger.bindRoom(diagnosticRoom)
        val admission = admission(1)
        ledger.reserveForExecution(diagnosticRoom, diagnosticExecution, listOf(admission))
        ledger.callStarted(call(1, admission).copy(executionId = diagnosticExecution))
        runCatching { ledger.finishConnectivityAttempt(false) }
        assertFailsWith<ExperimentCallStop> { ledger.beginConnectivityAttempt() }
        assertFalse(ledger.snapshot().observationCoverageKnown)
        assertEquals(0, ledger.snapshot().headersEmitted)
    }

    @Test
    fun `counter persistence failure immediately prevents the first physical callback`() {
        var callbacks = 0
        val failure =
            assertFailsWith<ExperimentCallStop> {
                val ledger = ledger(persist = { throw IOException("PRIVATE_PERSISTENCE_KEY") })
                ledger.beginConnectivityAttempt()
                ledger.bindRoom(diagnosticRoom)
                ledger.reserveForExecution(diagnosticRoom, diagnosticExecution, listOf(admission(1)))
                callbacks++
            }
        assertEquals(0, callbacks)
        assertFalse(failure.toString().contains("PRIVATE_"))
    }

    @Test
    fun `reopen cannot reset missing corrupted or mismatched initialized state`() {
        val ledger = ledger()
        connected(ledger)
        assertFailsWith<ExperimentCallStop> { ExperimentCallLedger(path, runId, "d".repeat(64), config, approval).checkpoint() }
        assertFailsWith<ExperimentCallStop> { ExperimentCallLedger(path, runId, source, "d".repeat(64), approval).checkpoint() }
        assertFailsWith<ExperimentCallStop> { ExperimentCallLedger(path, runId, source, config, "d".repeat(64)).checkpoint() }
        Files.writeString(path, "PRIVATE_CORRUPTED_LEDGER")
        assertFailsWith<ExperimentCallStop> { ledger().checkpoint() }
        Files.deleteIfExists(path)
        assertFailsWith<ExperimentCallStop> { ledger().checkpoint() }
    }

    @Test
    fun `the second failed diagnostic ends connectivity even when its technical code differs`() {
        val ledger = ledger()
        failedDiagnostic(ledger, diagnosticRoom, 1, ExperimentTechnicalCode.SERVER)
        assertEquals(ExperimentPhase.BETWEEN_DIAGNOSTICS, ledger.snapshot().phase)
        failedDiagnostic(ledger, UUID(0, 203), 2, ExperimentTechnicalCode.TIMEOUT)

        assertEquals(2, ledger.snapshot().connectivityAttempts)
        assertFalse(ledger.snapshot().connectivitySucceeded)
        assertFailsWith<ExperimentCallStop> { ledger.beginE2E() }
        assertFailsWith<ExperimentCallStop> { ledger.beginConnectivityAttempt() }
    }

    @Test
    fun `a first failed connectivity attempt never grants an E2E room`() {
        val ledger = ledger()
        failedDiagnostic(ledger, diagnosticRoom, 1, ExperimentTechnicalCode.SERVER)
        assertFailsWith<ExperimentCallStop> { ledger.beginE2E() }
        assertEquals(0, ledger.snapshot().e2eRooms)
        assertFalse(ledger.snapshot().connectivitySucceeded)
    }

    @Test
    fun `successful fresh second diagnostic allows only one E2E room and survives reopen`() {
        val ledger = ledger()
        failedDiagnostic(ledger, diagnosticRoom, 1, ExperimentTechnicalCode.SERVER)
        val reopened = ledger()
        assertEquals(2, reopened.beginConnectivityAttempt())
        val room = UUID(0, 203)
        val execution = UUID(0, 303)
        reopened.bindRoom(room)
        val admission = admission(2)
        reopened.reserveForExecution(room, execution, listOf(admission))
        emit(reopened, call(2, admission).copy(executionId = execution), 200)
        reopened.finishConnectivityAttempt(true)
        reopened.beginE2E()
        reopened.bindRoom(e2eRoom)
        reopened.finishE2E()
        val final = ledger()

        assertEquals(1, final.snapshot().e2eRooms)
        assertTrue(final.snapshot().connectivitySucceeded)
        assertEquals(ExperimentPhase.FINISHED, final.snapshot().phase)
        assertFailsWith<ExperimentCallStop> { final.beginE2E() }
    }

    @Test
    fun `opening a second phase never revives a stale previous room worker`() {
        val ledger = ledger()
        failedDiagnostic(ledger, diagnosticRoom, 1, ExperimentTechnicalCode.SERVER)
        ledger.beginConnectivityAttempt()
        ledger.bindRoom(UUID(0, 203))
        var originalFenceCalls = 0
        var sdk = 0

        assertFailsWith<ExperimentCallStop> {
            ExperimentCounterAdmission(ledger).authorize({
                originalFenceCalls++
                Instant.parse("2026-11-02T03:00:15Z")
            }, request(diagnosticRoom, diagnosticExecution))
            sdk++
        }
        assertTrue(originalFenceCalls <= 1)
        assertEquals(0, sdk)
    }

    @Test
    fun `fixed normalization supports typed names and aliases while unknown stays unknown`() {
        ExperimentTechnicalCode.entries.forEach { assertEquals(it, ExperimentFailureNormalization.normalizeKnownFailure(it.name)) }
        mapOf(
            "CONNECTION" to ExperimentTechnicalCode.NETWORK,
            "CONNECTION_FAILED" to ExperimentTechnicalCode.NETWORK,
            "PROVIDER_UNAVAILABLE" to ExperimentTechnicalCode.NETWORK,
            "RATE_LIMITED" to ExperimentTechnicalCode.RATE_LIMIT,
        ).forEach { (raw, normalized) -> assertEquals(normalized, ExperimentFailureNormalization.normalizeKnownFailure(raw)) }
        listOf(null, "", "PRIVATE_UNKNOWN").forEach { assertNull(ExperimentFailureNormalization.normalizeKnownFailure(it)) }
    }

    @Test
    fun `a requested cap above ten cannot expand the authorized physical limit`() {
        assertFailsWith<IllegalArgumentException> { ExperimentCallLedger(path, runId, source, config, approval, maximumPhysicalCalls = 11) }
    }

    private fun ledger(persist: ((ExperimentLedgerSnapshot) -> Unit)? = null) =
        ExperimentCallLedger(
            path,
            runId,
            source,
            config,
            approval,
            persistOverride = persist,
        )

    private fun connected(ledger: ExperimentCallLedger) {
        assertEquals(1, ledger.beginConnectivityAttempt())
        ledger.bindRoom(diagnosticRoom)
        val admission = admission(1)
        ledger.reserveForExecution(diagnosticRoom, diagnosticExecution, listOf(admission))
        emit(ledger, call(1, admission).copy(executionId = diagnosticExecution), 200)
        ledger.finishConnectivityAttempt(true)
        ledger.beginE2E()
        ledger.bindRoom(e2eRoom)
    }

    private fun failedDiagnostic(
        ledger: ExperimentCallLedger,
        room: UUID,
        ordinal: Int,
        failure: ExperimentTechnicalCode,
    ) {
        ledger.beginConnectivityAttempt()
        ledger.bindRoom(room)
        val execution = UUID(0, 300L + ordinal)
        val admission = admission(ordinal)
        ledger.reserveForExecution(room, execution, listOf(admission))
        val call = call(ordinal, admission).copy(executionId = execution)
        runCatching {
            emit(
                ledger,
                call,
                null,
                AiHttpExperimentTransportFailure.NETWORK,
            )
        }.onFailure { assertTrue(it is ExperimentCallStop) }
        runCatching { ledger.technicalFailure(call.callId, failure) }.onFailure { assertTrue(it is ExperimentCallStop) }
        runCatching { ledger.finishConnectivityAttempt(false) }.onFailure { assertTrue(it is ExperimentCallStop) }
    }

    private fun admission(
        number: Int,
        operation: AiPhysicalOperation = AiPhysicalOperation.FINAL_PRIMARY,
    ) = AiHttpExperimentAdmission(number.toString(16).padStart(64, '0'), operation)

    private fun call(
        number: Int,
        admission: AiHttpExperimentAdmission,
    ) = AiHttpExperimentCall(
        UUID(0, number.toLong()),
        e2eExecution,
        UUID(0, number.toLong() + 1_000),
        admission.operation,
        1,
        admission.opaqueId,
    )

    private fun emit(
        ledger: ExperimentCallLedger,
        call: AiHttpExperimentCall,
        status: Int?,
        failure: AiHttpExperimentTransportFailure? = null,
    ) {
        ledger.callStarted(call)
        ledger.beforeHeaders(call)
        ledger.headersEmitted(call)
        ledger.callFinished(call, status, failure)
    }

    private fun request(
        room: UUID,
        execution: UUID,
    ) = AiBudgetReserveRequest(
        AiBudgetExecution(room, AiBudgetExecutionKind.FINAL_ANALYSIS, execution, 1, UUID(0, 399), 0, source, config),
        listOf(AiPhysicalCallSpec(AiPhysicalOperation.FINAL_PRIMARY, 1, source, config, UUID(0, 398))),
    )
}
