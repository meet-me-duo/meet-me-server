package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.precheck.application.port.output.AmbiguityAssessment
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationReason
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationResult
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckClassificationCapture
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiCallBudgetPort
import com.meetme.server.shared.application.port.output.AiCallBudgetRequest
import com.meetme.server.shared.application.port.output.AiCallBudgetReservation
import com.meetme.server.shared.application.port.output.AiCallObservation
import com.meetme.server.shared.application.port.output.AiCallQuote
import com.meetme.server.shared.application.port.output.AiCallStage
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import com.meetme.server.shared.domain.InputEvidence
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Issue241: optional first-stage evidence never becomes a routing or billing authority. No HTTP is used. */
class PrecheckClassificationCaptureTest {
    @Test
    fun `PCD01 absent classification remains absent and an effective route never invents a first verdict`() {
        val capture = PrecheckClassificationCapture()
        assertNull(capture.snapshot())
        capture.routed(AmbiguityAssessment.CLEAR)
        assertNull(capture.snapshot())
    }

    @Test
    fun `PCD02 first classification and first route are immutable and probability maps are defensive`() {
        val capture = PrecheckClassificationCapture()
        val distribution =
            mutableMapOf(
                AmbiguityAssessment.CLEAR to 0.7,
                AmbiguityAssessment.NEEDS_REVIEW to 0.2,
                AmbiguityAssessment.INDETERMINATE to 0.1,
            )
        val first = ClassificationCaptureFixture.first().copy(probabilities = distribution)
        capture.classified(first)
        assertNull(assertNotNull(capture.snapshot()).effectiveAssessment)
        distribution[AmbiguityAssessment.CLEAR] = 0.1
        capture.routed(AmbiguityAssessment.CLEAR)
        capture.classified(first.copy(assessment = AmbiguityAssessment.NEEDS_REVIEW, provider = "SECOND_CLASSIFIER"))
        capture.routed(AmbiguityAssessment.NEEDS_REVIEW)
        val actual = assertNotNull(capture.snapshot())
        assertEquals(1, actual.version)
        assertEquals(first.assessment, actual.assessment)
        assertEquals(first.reason, actual.reason)
        assertEquals(first.confidence, actual.confidence)
        assertEquals(first.semanticAssessment, actual.semanticAssessment)
        assertEquals(first.provider, actual.provider)
        assertEquals(first.model, actual.model)
        assertEquals(AmbiguityAssessment.CLEAR, actual.effectiveAssessment)
        assertEquals(0.7, actual.probabilities[AmbiguityAssessment.CLEAR])
        try {
            (actual.probabilities as? MutableMap<AmbiguityAssessment, Double>)?.set(AmbiguityAssessment.CLEAR, 0.0)
        } catch (_: UnsupportedOperationException) {
            // An unmodifiable snapshot and a defensive snapshot-on-read both satisfy this contract.
        }
        assertEquals(0.7, assertNotNull(capture.snapshot()).probabilities[AmbiguityAssessment.CLEAR])
    }

    @ParameterizedTest
    @ValueSource(strings = ["provider", "model", "nan", "infinite", "negative", "above-one"])
    fun `PCD03 invalid optional metadata drops only the diagnostic without throwing`(defect: String) {
        val capture = PrecheckClassificationCapture()
        val first = ClassificationCaptureFixture.first()
        val invalid =
            when (defect) {
                "provider" -> first.copy(provider = "P".repeat(129))
                "model" -> first.copy(model = "M".repeat(129))
                else ->
                    first.copy(
                        probabilities =
                            mapOf(
                                AmbiguityAssessment.CLEAR to
                                    when (defect) {
                                        "nan" -> Double.NaN
                                        "infinite" -> Double.POSITIVE_INFINITY
                                        "negative" -> -0.01
                                        else -> 1.01
                                    },
                            ),
                    )
            }
        capture.classified(invalid)
        capture.routed(AmbiguityAssessment.CLEAR)
        assertNull(capture.snapshot())
    }

    @Test
    fun `PCD04 bounded snapshot contains only typed decision metadata and accepts inclusive 128 identifier bounds`() {
        val capture = PrecheckClassificationCapture()
        val first =
            ClassificationCaptureFixture.first().copy(
                provider = "P".repeat(128),
                model = "M".repeat(128),
                probabilities = emptyMap(),
            )
        capture.classified(first)
        val snapshot = assertNotNull(capture.snapshot())
        assertEquals(first.provider, snapshot.provider)
        assertEquals(first.model, snapshot.model)
        assertTrue(snapshot.probabilities.isEmpty())
        val mapper = JsonMapper.builder().findAndAddModules().build()
        val json = mapper.readTree(mapper.writeValueAsBytes(snapshot))
        assertEquals(
            setOf(
                "version",
                "assessment",
                "reason",
                "confidence",
                "semanticAssessment",
                "probabilities",
                "provider",
                "model",
                "effectiveAssessment",
            ),
            json.properties().map { it.key }.toSet(),
        )
        assertTrue(mapper.writeValueAsBytes(snapshot).size <= 4096)
        assertFalse(json.toString().contains(ClassificationCaptureFixture.RAW))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `PCD05 V1 normalized assessment and V2 semantic routing remain distinct while terminal results stay unchanged`(physical: Boolean) {
        for (evidence in listOf(false, true)) {
            val f = ProviderFixture(physical)
            val actual = f.provider().check(f.request(evidence))
            val snapshot = assertNotNull(f.capture.snapshot())
            assertEquals(AmbiguityAssessment.INDETERMINATE, snapshot.assessment)
            assertEquals(AmbiguityClassificationReason.LOW_CONFIDENCE, snapshot.reason)
            assertEquals(AmbiguityAssessment.CLEAR, snapshot.semanticAssessment)
            assertEquals(if (evidence) AmbiguityAssessment.CLEAR else AmbiguityAssessment.INDETERMINATE, snapshot.effectiveAssessment)
            assertEquals(1, f.classifierCalls)
            assertEquals(if (evidence) 0 else 1, f.generatorCalls)
            if (evidence) assertEquals(PrecheckVerdict.CLEAR, actual.verdict) else assertSame(f.terminal, actual)
            assertEquals(ClassificationCaptureFixture.first().provider, snapshot.provider)
            assertEquals(ClassificationCaptureFixture.first().model, snapshot.model)
            assertEquals(
                if (physical) {
                    0
                } else if (evidence) {
                    1
                } else {
                    2
                },
                f.budget.requests.size,
            )
            assertEquals(f.budget.requests.size, f.budget.observations.size)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `PCD06 real TwoStage capture preserves generated question identity content and stage accounting`(physical: Boolean) {
        val f = ProviderFixture(physical)
        f.first =
            ClassificationCaptureFixture.first().copy(
                assessment = AmbiguityAssessment.NEEDS_REVIEW,
                reason = null,
                semanticAssessment = AmbiguityAssessment.NEEDS_REVIEW,
                probabilities =
                    mapOf(
                        AmbiguityAssessment.CLEAR to 0.2,
                        AmbiguityAssessment.NEEDS_REVIEW to 0.7,
                        AmbiguityAssessment.INDETERMINATE to 0.1,
                    ),
            )
        f.terminal =
            PrecheckProviderResult(
                PrecheckVerdict.NEEDS_CHOICE,
                prompt = "합성 질문은 어느 쪽인가요?",
                sourceExcerpt = "금요일",
                provider = "SYNTHETIC_GENERATOR",
                model = "question-model",
                inputTokens = 25,
                outputTokens = 8,
            )
        assertSame(f.terminal, f.provider().check(f.request(true)))
        val snapshot = assertNotNull(f.capture.snapshot())
        assertEquals(f.first.assessment, snapshot.assessment)
        assertEquals(f.first.probabilities, snapshot.probabilities)
        assertEquals(AmbiguityAssessment.NEEDS_REVIEW, snapshot.effectiveAssessment)
        assertEquals(1, f.classifierCalls)
        assertEquals(1, f.generatorCalls)
        assertEquals(f.first.provider, snapshot.provider)
        assertEquals(
            if (physical) emptyList() else listOf(AiCallStage.CLASSIFICATION, AiCallStage.GENERATION),
            f.budget.requests.map { it.stage },
        )
        if (!physical) {
            assertEquals(
                f.first.provider,
                f.budget.observations
                    .first()
                    .provider,
            )
            assertEquals(
                f.terminal.provider,
                f.budget.observations
                    .last()
                    .provider,
            )
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `PCD07 generator failures retain the valid first result and exactly the same failure codes`(physical: Boolean) {
        for (code in listOf("TIMEOUT", "INVALID_RESPONSE", "RATE_LIMITED", "CONNECTION")) {
            val f = ProviderFixture(physical)
            f.generatorFailure = PrecheckProviderException(code)
            assertEquals(code, assertFailsWith<PrecheckProviderException> { f.provider().check(f.request(false)) }.failureCode)
            val snapshot = assertNotNull(f.capture.snapshot())
            assertEquals(f.first.assessment, snapshot.assessment)
            assertEquals(AmbiguityAssessment.INDETERMINATE, snapshot.effectiveAssessment)
            assertEquals(1, f.classifierCalls)
            assertEquals(1, f.generatorCalls)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `PCD08 a valid late first result is recorded but never claims a route was evaluated or accepts late clear`(physical: Boolean) {
        val f = ProviderFixture(physical)
        f.first = ClassificationCaptureFixture.first().copy(assessment = AmbiguityAssessment.CLEAR, reason = null)
        f.afterClassify = { f.now.set(Duration.ofSeconds(16).toNanos()) }
        assertEquals("TIMEOUT", assertFailsWith<PrecheckProviderException> { f.provider().check(f.request(true)) }.failureCode)
        val snapshot = assertNotNull(f.capture.snapshot())
        assertEquals(AmbiguityAssessment.CLEAR, snapshot.assessment)
        assertNull(snapshot.effectiveAssessment)
        assertEquals(1, f.classifierCalls)
        assertEquals(0, f.generatorCalls)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `PCD09 classification failures or invalid first results never create fabricated observations`(physical: Boolean) {
        for (invalid in listOf(false, true)) {
            val f = ProviderFixture(physical)
            if (invalid) {
                f.first = f.first.copy(confidence = Double.NaN)
            } else {
                f.classifierFailure =
                    AmbiguityClassificationException("CONNECTION")
            }
            assertEquals(
                if (invalid) "INVALID_RESPONSE" else "CONNECTION",
                assertFailsWith<PrecheckProviderException> { f.provider().check(f.request(true)) }.failureCode,
            )
            assertNull(f.capture.snapshot())
            assertEquals(1, f.classifierCalls)
            assertEquals(0, f.generatorCalls)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["record", "second-reserve"])
    fun `PCD10 outer budget failures preserve known classification and existing code without pretending to route early`(phase: String) {
        val f = ProviderFixture(false)
        f.budget.failRecord = phase == "record"
        f.budget.denyGeneration = phase == "second-reserve"
        assertEquals(
            if (phase == "record") "BUDGET_UNAVAILABLE" else "BUDGET_DENIED",
            assertFailsWith<PrecheckProviderException> { f.provider().check(f.request(false)) }.failureCode,
        )
        val snapshot = assertNotNull(f.capture.snapshot())
        assertEquals(f.first.assessment, snapshot.assessment)
        assertEquals(if (phase == "record") null else AmbiguityAssessment.INDETERMINATE, snapshot.effectiveAssessment)
        assertEquals(0, f.generatorCalls)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `PCD11 diagnostics add no monotonic reads and preserve one shared timeout and original stage call counts`(physical: Boolean) {
        for (clear in listOf(false, true)) {
            val f = ProviderFixture(physical)
            if (clear) f.first = f.first.copy(assessment = AmbiguityAssessment.CLEAR, reason = null)
            val result = f.provider().check(f.request(clear))
            assertEquals(PrecheckVerdict.CLEAR, result.verdict)
            assertEquals(
                if (physical) {
                    if (clear) {
                        3
                    } else {
                        5
                    }
                } else if (clear) {
                    6
                } else {
                    11
                },
                f.clockReads.get(),
            )
            assertEquals(1, f.classifierCalls)
            assertEquals(if (clear) 0 else 1, f.generatorCalls)
            val snapshot = assertNotNull(f.capture.snapshot())
            assertEquals(if (clear) AmbiguityAssessment.CLEAR else AmbiguityAssessment.INDETERMINATE, snapshot.effectiveAssessment)
            assertTrue(f.timeouts.all { it > Duration.ZERO && it <= Duration.ofSeconds(15) })
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `PCD12 invalid optional diagnostics cannot change successful routing response identity or failure behavior`(physical: Boolean) {
        val f = ProviderFixture(physical)
        f.first = f.first.copy(model = "M".repeat(129))
        assertSame(f.terminal, f.provider().check(f.request(false)))
        assertNull(f.capture.snapshot())
        assertEquals(1, f.classifierCalls)
        assertEquals(1, f.generatorCalls)
    }

    private class ProviderFixture(
        val physical: Boolean,
    ) {
        val capture = PrecheckClassificationCapture()
        val now = AtomicLong()
        val clockReads = AtomicInteger()
        val budget = CaptureBudget()
        var first = ClassificationCaptureFixture.first()
        var terminal = PrecheckProviderResult(PrecheckVerdict.CLEAR, provider = "SYNTHETIC_GENERATOR", model = "generator-model")
        var classifierCalls = 0
        var generatorCalls = 0
        var classifierFailure: RuntimeException? = null
        var generatorFailure: RuntimeException? = null
        var afterClassify: () -> Unit = {}
        val timeouts = mutableListOf<Duration>()

        fun request(evidence: Boolean) =
            ClassificationCaptureFixture.request().copy(
                classificationCapture = capture,
                evidence = if (evidence) InputEvidence(1, ClassificationCaptureFixture.anchor) else null,
                dispatchContext =
                    if (physical) {
                        AiProviderDispatchContext(
                            AiBudgetExecution(
                                ClassificationCaptureFixture.room,
                                AiBudgetExecutionKind.PRECHECK,
                                ClassificationCaptureFixture.check,
                                1,
                                UUID(0, 244),
                                1,
                                "source",
                                "context",
                            ),
                            Duration.ofSeconds(30).toNanos(),
                        )
                    } else {
                        null
                    },
            )

        fun provider(): TwoStagePreSubmissionCheckProvider {
            val gate =
                if (physical) {
                    mock(AiHttpDispatchGate::class.java).also {
                        `when`(it.properties).thenReturn(AiDispatchGateProperties(true))
                    }
                } else {
                    null
                }
            return TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    classifierCalls++
                    timeouts += it.timeout
                    classifierFailure?.let { failure -> throw failure }
                    afterClassify()
                    first
                },
                PreSubmissionCheckProvider {
                    generatorCalls++
                    timeouts += it.timeout
                    generatorFailure?.let { failure ->
                        throw failure
                    }
                    terminal
                },
                budget,
                LongSupplier {
                    clockReads.incrementAndGet()
                    now.get()
                },
                gate,
            )
        }
    }
}

internal object ClassificationCaptureFixture {
    val anchor: Instant = Instant.parse("2033-04-05T00:00:00Z")
    val room = UUID(0, 241)
    val check = UUID(0, 242)
    const val RAW = "합성 금요일 오후 가능 입력입니다"

    fun request() =
        PrecheckProviderRequest(
            check,
            RAW,
            anchor,
            ZoneId.of("Asia/Seoul"),
            "ko-KR",
            LocalDate.parse("2033-04-05"),
            LocalDate.parse("2033-04-12"),
            Duration.ofSeconds(15),
            roomId = room,
            executionGeneration = 1,
        )

    fun first() =
        AmbiguityClassificationResult(
            AmbiguityAssessment.INDETERMINATE,
            AmbiguityClassificationReason.LOW_CONFIDENCE,
            0.55,
            provider = "SYNTHETIC_CLASSIFIER",
            model = "synthetic-classifier-model",
            semanticAssessment = AmbiguityAssessment.CLEAR,
            probabilities =
                mapOf(
                    AmbiguityAssessment.CLEAR to 0.7,
                    AmbiguityAssessment.NEEDS_REVIEW to 0.2,
                    AmbiguityAssessment.INDETERMINATE to 0.1,
                ),
        )
}

internal class CaptureBudget : AiCallBudgetPort {
    val requests = mutableListOf<AiCallBudgetRequest>()
    val observations = mutableListOf<AiCallObservation>()
    var failRecord = false
    var denyGeneration = false

    override fun reserve(request: AiCallBudgetRequest): AiCallBudgetReservation? {
        requests += request
        if (denyGeneration && request.stage == AiCallStage.GENERATION) return null
        return AiCallBudgetReservation(
            UUID(0, requests.size.toLong()),
            request,
            AiCallQuote(request.profileFingerprint, "synthetic-verified-evidence", request.maximumPhysicalCalls, true),
            true,
        )
    }

    override fun record(
        reservation: AiCallBudgetReservation,
        observation: AiCallObservation,
    ) {
        if (failRecord) error("synthetic budget observation failure")
        observations += observation
    }
}
