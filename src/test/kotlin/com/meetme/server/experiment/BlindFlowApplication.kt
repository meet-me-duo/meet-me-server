package com.meetme.server.experiment

import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationReason
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.adapter.output.persistence.JdbcAiBudgetExecutionFence
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** No real Gemini client is invoked. Every other provider delegates to its unchanged original bean. */
@TestConfiguration(proxyBeanMethods = false)
class BlindFlowApplication {
    @Bean @Primary
    fun blindTokens(clock: Clock): AccessTokenPort = PrimaryGeminiSmokeApplication().primarySyntheticTokens(clock)

    @Bean @Primary
    fun blindSource(delegate: JdbcAiBudgetExecutionFence): AiRuntimeDispatchGuardPort = activeBudget().sourceFirst(delegate)

    companion object {
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val syntheticPrimaryCalls = AtomicInteger()

        @Bean @JvmStatic
        fun blindProviders(): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any =
                    when {
                        bean is AiHttpDispatchGate -> bean.also { it.installExperimentWireHook(activeBudget()) }
                        bean is AmbiguityClassificationPort ->
                            AmbiguityClassificationPort { request ->
                                observed("CLASSIFICATION") {
                                    bean.classify(request).also { result ->
                                        events +=
                                            mapOf(
                                                "stage" to "CLASSIFICATION",
                                                "executionId" to request.dispatchContext?.execution?.executionId,
                                                "assessment" to result.assessment,
                                                "semanticAssessment" to result.semanticAssessment,
                                                "reason" to result.reason,
                                                "confidence" to result.confidence,
                                                "probabilities" to result.probabilities,
                                                "inputTokens" to result.inputTokens,
                                                "outputTokens" to result.outputTokens,
                                            )
                                        if (result.reason == AmbiguityClassificationReason.REFUSED) {
                                            activeBudget().stop(BlindFlowStop.PROVIDER_FAILURE)
                                        }
                                    }
                                }
                            }
                        bean is PreSubmissionCheckProvider ->
                            PreSubmissionCheckProvider { request ->
                                observed("PRECHECK") {
                                    bean.check(request).also { result ->
                                        events +=
                                            mapOf(
                                                "stage" to "PRECHECK",
                                                "checkId" to request.checkId,
                                                "bean" to beanName,
                                                "verdict" to result.verdict,
                                                "questionOffered" to (result.prompt != null),
                                                "choiceCount" to result.choices.size,
                                                "inputTokens" to result.inputTokens,
                                                "outputTokens" to result.outputTokens,
                                            )
                                    }
                                }
                            }
                        bean is NaturalLanguageParserPort && beanName == "geminiNaturalLanguageParserAdapter" ->
                            NaturalLanguageParserPort {
                                check(syntheticPrimaryCalls.incrementAndGet() <= if (pilotTen()) 1 else 2)
                                // Expected test double is deliberately excluded from the actual-provider stop condition.
                                throw NaturalLanguageParserException(ParserFailureKind.SERVER, retryAfterMillis = 40_000)
                            }
                        bean is NaturalLanguageParserPort && beanName == "lunaParser" ->
                            NaturalLanguageParserPort { request ->
                                observed("FINAL_FALLBACK") {
                                    check(request.callTimeout > Duration.ZERO && request.callTimeout <= Duration.ofSeconds(27))
                                    finalTiming(request.callTimeout, request.dispatchContext?.execution?.executionId) {
                                        bean.parse(request).also { result ->
                                            events +=
                                                mapOf(
                                                    "stage" to "FINAL_FALLBACK",
                                                    "executionId" to request.dispatchContext?.execution?.executionId,
                                                    "resultCount" to result.results.size,
                                                    "inputTokens" to result.usage.inputTokens,
                                                    "outputTokens" to result.usage.outputTokens,
                                                )
                                        }
                                    }
                                }
                            }
                        bean is NaturalLanguageParserPort ->
                            NaturalLanguageParserPort {
                                activeBudget().stop(BlindFlowStop.INVALID_METADATA)
                                throw NaturalLanguageParserException(ParserFailureKind.CONFIGURATION)
                            }
                        else -> bean
                    }
            }

        /** Retains caller timing even when parsing or final observation throws; never logs exception text. */
        internal fun <T> finalTiming(
            callerBudget: Duration,
            executionId: UUID?,
            action: () -> T,
        ): T {
            val start = System.nanoTime()
            var outcome = "FAILED"
            try {
                return action().also { outcome = "SUCCEEDED" }
            } finally {
                events +=
                    mapOf(
                        "stage" to "FINAL_FALLBACK_TIMING",
                        "executionId" to executionId,
                        "elapsedNanos" to System.nanoTime() - start,
                        "callerBudgetNanos" to callerBudget.toNanos(),
                        "outcome" to outcome,
                    )
            }
        }

        private fun <T> observed(
            stage: String,
            action: () -> T,
        ): T =
            try {
                action()
            } catch (failure: Exception) {
                activeBudget().stop(BlindFlowStop.PROVIDER_FAILURE)
                events += mapOf("stage" to stage, "outcome" to "FAILED", "causeClass" to failure.javaClass.simpleName)
                throw failure
            }

        private fun pilotTen() = System.getenv("MEETME_PILOT10_MODE") == "PILOT10"

        private fun activeBudget(): BlindFlowBudget = if (pilotTen()) PilotTenSettings.current.budget else BlindFlowSettings.current.budget
    }
}
