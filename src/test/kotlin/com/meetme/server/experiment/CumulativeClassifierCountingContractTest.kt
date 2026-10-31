package com.meetme.server.experiment

import com.meetme.server.precheck.application.port.output.AmbiguityAssessment
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationReason
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationRequest
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationResult
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentAdmission
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentCall
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Actual experimental BPP, fake typed provider result and ledger callbacks only. No API/key/SDK/network. */
class CumulativeClassifierCountingContractTest {
    @TempDir lateinit var directory: Path
    private val room = UUID(0, 20)
    private val execution = UUID(0, 30)
    private val physicalCall = UUID(0, 40)
    private val now = Instant.parse("2026-11-02T03:00:00Z")
    private val date = LocalDate.of(2026, 11, 2)

    @Test
    fun `returned REFUSED counts once and an outer router exception does not invent a generator failure`() {
        val fixture = fixture()
        val result = AmbiguityClassificationResult(AmbiguityAssessment.INDETERMINATE, AmbiguityClassificationReason.REFUSED)
        val classifier = classifier(fixture, result)
        val original = PrecheckProviderException("REFUSED")
        var returned: AmbiguityClassificationResult? = null
        val router =
            fixture.processor.postProcessAfterInitialization(
                PreSubmissionCheckProvider {
                    returned = classifier.classify(classificationRequest())
                    throw original
                },
                "preSubmissionCheckProvider",
            ) as PreSubmissionCheckProvider

        assertSame(original, assertFailsWith<PrecheckProviderException> { router.check(precheckRequest()) })
        assertSame(result, returned, "The observer must preserve the original classifier result")
        assertEquals(ExperimentTechnicalCode.REFUSED, fixture.ledger.failureForCall(physicalCall))
        assertEquals(1, fixture.ledger.snapshot().errors[ExperimentTechnicalCode.REFUSED])
        assertEquals(
            1,
            fixture.ledger
                .snapshot()
                .errors.values
                .sum(),
        )
        assertNull(fixture.ledger.snapshot().stopReason, "A duplicate outer router error is not an unknown generator failure")
    }

    @ParameterizedTest
    @EnumSource(AmbiguityClassificationReason::class, names = ["LOW_CONFIDENCE", "MODEL_UNCERTAIN"])
    fun `ordinary classifier abstention is preserved without counting a technical failure`(reason: AmbiguityClassificationReason) {
        val fixture = fixture()
        val result = AmbiguityClassificationResult(AmbiguityAssessment.INDETERMINATE, reason, confidence = 0.1)

        assertSame(result, classifier(fixture, result).classify(classificationRequest()))
        assertNull(fixture.ledger.failureForCall(physicalCall))
        assertEquals(
            0,
            fixture.ledger
                .snapshot()
                .errors.values
                .sum(),
        )
        assertNull(fixture.ledger.snapshot().stopReason)
    }

    private fun classifier(
        fixture: Fixture,
        result: AmbiguityClassificationResult,
    ): AmbiguityClassificationPort =
        fixture.processor.postProcessAfterInitialization(
            AmbiguityClassificationPort {
                val admission = AiHttpExperimentAdmission("e".repeat(64), AiPhysicalOperation.PRECHECK_CLASSIFICATION)
                fixture.ledger.reserveForExecution(room, execution, listOf(admission))
                val call = AiHttpExperimentCall(physicalCall, execution, null, admission.operation, 1, admission.opaqueId)
                emit(fixture.ledger, call)
                fixture.observations.finished(call, 200, null)
                result
            },
            "jevAmbiguityClassifier",
        ) as AmbiguityClassificationPort

    private fun fixture(): Fixture {
        val ledger = ExperimentCallLedger(directory.resolve("ledger.json"), UUID(0, 1), "a".repeat(64), "b".repeat(64), "c".repeat(64))
        ledger.beginConnectivityAttempt()
        ledger.bindRoom(UUID(0, 10))
        val diagnostic = AiHttpExperimentAdmission("d".repeat(64), AiPhysicalOperation.FINAL_PRIMARY)
        ledger.reserveForExecution(UUID(0, 10), UUID(0, 11), listOf(diagnostic))
        emit(ledger, AiHttpExperimentCall(UUID(0, 12), UUID(0, 11), UUID(0, 13), diagnostic.operation, 1, diagnostic.opaqueId))
        ledger.finishConnectivityAttempt(true)
        ledger.beginE2E()
        ledger.bindRoom(room)
        val empty = JsonMapper.builder().build().readTree("{}")
        val settings = CumulativeExperimentSettings(empty, empty, empty, empty, directory.resolve("output"), ledger)
        val observations = CumulativeExperimentObservations()
        val processor = CumulativeExperimentFixtures.cumulativeProviderObservers(provider(settings), provider(observations))
        return Fixture(ledger, observations, processor)
    }

    private fun context() =
        AiProviderDispatchContext(
            AiBudgetExecution(room, AiBudgetExecutionKind.PRECHECK, execution, 1, UUID(0, 31), 0, "a".repeat(64), "b".repeat(64)),
            System.nanoTime() + Duration.ofSeconds(15).toNanos(),
        )

    private fun classificationRequest() =
        AmbiguityClassificationRequest(
            "synthetic input",
            now,
            ZoneId.of("Asia/Seoul"),
            "ko-KR",
            date,
            date.plusDays(1),
            Duration.ofSeconds(15),
            dispatchContext = context(),
        )

    private fun precheckRequest() =
        PrecheckProviderRequest(
            execution,
            "synthetic input",
            now,
            ZoneId.of("Asia/Seoul"),
            "ko-KR",
            date,
            date.plusDays(1),
            Duration.ofSeconds(15),
            roomId = room,
            executionGeneration = 1,
            dispatchContext = context(),
        )

    private fun emit(
        ledger: ExperimentCallLedger,
        call: AiHttpExperimentCall,
    ) {
        ledger.callStarted(call)
        ledger.beforeHeaders(call)
        ledger.headersEmitted(call)
        ledger.callFinished(call, 200, null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> provider(value: T): ObjectProvider<T> {
        val provider = mock(ObjectProvider::class.java) as ObjectProvider<T>
        `when`(provider.getObject()).thenReturn(value)
        return provider
    }

    private data class Fixture(
        val ledger: ExperimentCallLedger,
        val observations: CumulativeExperimentObservations,
        val processor: BeanPostProcessor,
    )
}
