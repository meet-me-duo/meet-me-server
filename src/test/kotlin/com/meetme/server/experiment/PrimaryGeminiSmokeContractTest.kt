package com.meetme.server.experiment

import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchResult
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.shared.adapter.output.aihttp.AiHttpProviderStatusException
import com.meetme.server.shared.adapter.output.persistence.JdbcAiBudgetExecutionFence
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Actual diagnostic fixture contracts with stub authority and SDK counters; no model or network calls. */
class PrimaryGeminiSmokeContractTest {
    private val now = Instant.parse("2026-11-02T03:00:00Z")
    private val deadline = now.plusSeconds(15)
    private val authority = mock(JdbcAiBudgetExecutionFence::class.java)

    @Test
    fun `diagnostic precheck explicitly records its synthetic clear result`() {
        val check = PrimaryGeminiSmokeFixtures().primarySmokePrecheck()
        val result =
            check.check(
                PrecheckProviderRequest(
                    UUID(0, 1),
                    "synthetic original",
                    now,
                    ZoneId.of("Asia/Seoul"),
                    "ko-KR",
                    LocalDate.parse("2026-11-02"),
                    LocalDate.parse("2026-11-03"),
                    Duration.ofSeconds(15),
                ),
            )

        assertEquals(PrecheckVerdict.CLEAR, result.verdict)
        assertEquals("TEST_DOUBLE", result.model)
        assertNull(result.prompt)
        assertTrue(result.choices.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(AiPhysicalOperation::class, names = ["FINAL_PRIMARY"], mode = EnumSource.Mode.EXCLUDE)
    fun `diagnostic actual factory denies every nonprimary operation`(operation: AiPhysicalOperation) {
        val request = request(operation)
        `when`(authority.authorize(request, now)).thenReturn(deadline)
        val guard = PrimaryGeminiSmokeFixtures().primaryOnlyDispatch(authority, admission())
        var sdkCalls = 0

        if (guard.authorize(request, now) != null) sdkCalls++

        assertEquals(0, sdkCalls)
        verify(authority).authorize(request, now)
    }

    @Test
    fun `diagnostic actual factory denies a mixed primary and fallback group`() {
        val primary = request()
        val request = primary.copy(calls = primary.calls + primary.calls.single().copy(operation = AiPhysicalOperation.FINAL_FALLBACK))
        `when`(authority.authorize(request, now)).thenReturn(deadline)

        assertNull(PrimaryGeminiSmokeFixtures().primaryOnlyDispatch(authority, admission()).authorize(request, now))
        verify(authority).authorize(request, now)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ATTEMPT", "PAYLOAD", "EXECUTION"])
    fun `only one distinct physical identity is granted while same call rechecks remain possible`(changed: String) {
        val first = request()
        val next =
            when (changed) {
                "ATTEMPT" -> first.copy(calls = listOf(first.calls.single().copy(attemptId = UUID(0, 8))))
                "PAYLOAD" -> first.copy(calls = listOf(first.calls.single().copy(wholePayloadFingerprint = "8".repeat(64))))
                else -> first.copy(execution = first.execution.copy(executionId = UUID(0, 8)))
            }
        `when`(authority.authorize(first, now)).thenReturn(deadline)
        `when`(authority.authorize(next, now)).thenReturn(deadline)
        val guard = PrimaryGeminiSmokeFixtures().primaryOnlyDispatch(authority, admission())

        repeat(3) { assertSame(deadline, guard.authorize(first, now)) }
        assertNull(guard.authorize(next, now))
        verify(authority, times(3)).authorize(first, now)
        verify(authority).authorize(next, now)
    }

    @Test
    fun `same call never caches permission after the original source fence revokes it`() {
        val request = request()
        `when`(authority.authorize(request, now)).thenReturn(deadline, null)
        val guard = PrimaryGeminiSmokeFixtures().primaryOnlyDispatch(authority, admission())
        var sdkCalls = 0

        if (guard.authorize(request, now) != null) sdkCalls++
        if (guard.authorize(request, now) != null) sdkCalls++

        assertEquals(1, sdkCalls)
        verify(authority, times(2)).authorize(request, now)
    }

    @Test
    fun `source fence denial grants zero calls and does not consume the only physical identity`() {
        val denied = request()
        val allowed = denied.copy(execution = denied.execution.copy(executionId = UUID(0, 8)))
        `when`(authority.authorize(denied, now)).thenReturn(null)
        `when`(authority.authorize(allowed, now)).thenReturn(deadline)
        val guard = PrimaryGeminiSmokeFixtures().primaryOnlyDispatch(authority, admission())

        assertNull(guard.authorize(denied, now))
        assertSame(deadline, guard.authorize(allowed, now))
        verify(authority).authorize(denied, now)
        verify(authority).authorize(allowed, now)
    }

    @Test
    fun `mock primary 503 is preserved and blocks every subsequent paid stage`() {
        val observations = PrimaryGeminiSmokeObservations()
        val safety = LiveEvaluationSafety { observations.signals() }
        val fixtures = PrimaryGeminiSmokeFixtures(observations)
        val request = request()
        `when`(authority.authorize(request, now)).thenReturn(deadline)
        val guard = fixtures.primaryOnlyDispatch(authority, LiveEvaluationAdmission(safety))
        val failure = NaturalLanguageParserException(ParserFailureKind.SERVER, cause = AiHttpProviderStatusException(503, null))
        var primarySdkCalls = 0
        var lunaSdkCalls = 0
        val primary =
            fixtures.primaryParser(
                NaturalLanguageParserPort {
                    assertNotNull(guard.authorize(request, now))
                    primarySdkCalls++
                    throw failure
                },
                safety,
            )
        val excluded =
            fixtures.excludedParser(
                NaturalLanguageParserPort {
                    lunaSdkCalls++
                    NaturalLanguageBatchResult(emptyList(), ParserUsage(null, null, null))
                },
                safety,
            )

        assertSame(failure, assertFailsWith<NaturalLanguageParserException> { primary.parse(batch()) })
        assertFailsWith<LiveEvaluationTechnicalStop> { primary.parse(batch()) }
        assertTrue(runCatching { excluded.parse(batch()) }.isFailure)
        assertEquals(1, primarySdkCalls)
        assertEquals(0, lunaSdkCalls)
        assertTrue(observations.signals().any { it.parserFailureCode == "SERVER" && it.httpStatus == 503 })
    }

    @Test
    fun `excluded parser cannot call Luna even when no technical failure exists`() {
        var calls = 0
        val parser =
            PrimaryGeminiSmokeFixtures().excludedParser(
                NaturalLanguageParserPort {
                    calls++
                    NaturalLanguageBatchResult(emptyList(), ParserUsage(null, null, null))
                },
                LiveEvaluationSafety { emptyList() },
            )

        assertTrue(runCatching { parser.parse(batch()) }.isFailure)
        assertEquals(0, calls)
    }

    @Test
    fun `primary smoke observations never contain provider exception text or body`() {
        val observations = PrimaryGeminiSmokeObservations()
        observations.record(
            NaturalLanguageParserException(
                ParserFailureKind.TIMEOUT,
                cause = java.io.IOException("PRIVATE_SENTINEL_PROVIDER_BODY"),
            ),
        )

        assertTrue(observations.signals().any { it.parserFailureCode == "TIMEOUT" })
        assertFalse(observations.events().toString().contains("PRIVATE_SENTINEL_PROVIDER_BODY"))
    }

    @Test
    fun `full blind actual fixture retains its existing fallback grant`() {
        val request = request(AiPhysicalOperation.FINAL_FALLBACK)
        `when`(authority.authorize(request, now)).thenReturn(deadline)
        val guard = ExperimentalLiveHttpEvaluationTest.LiveFixtures { true }.boundedActualDispatch(authority, admission())

        assertSame(deadline, guard.authorize(request, now))
        verify(authority).authorize(request, now)
    }

    private fun admission() = LiveEvaluationAdmission(LiveEvaluationSafety { emptyList() })

    private fun batch() =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            LocalDate.parse("2026-11-02"),
            LocalDate.parse("2026-11-03"),
            emptyList(),
        )

    private fun request(operation: AiPhysicalOperation = AiPhysicalOperation.FINAL_PRIMARY) =
        AiBudgetReserveRequest(
            AiBudgetExecution(
                UUID(0, 1),
                AiBudgetExecutionKind.FINAL_ANALYSIS,
                UUID(0, 2),
                0,
                UUID(0, 3),
                0,
                "1".repeat(64),
                "2".repeat(64),
            ),
            listOf(AiPhysicalCallSpec(operation, 1, "3".repeat(64), "4".repeat(64), UUID(0, 4))),
        )
}
