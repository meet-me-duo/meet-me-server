package com.meetme.server.experiment

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.shared.adapter.output.aihttp.AiHttpProviderStatusException
import com.meetme.server.shared.adapter.output.persistence.JdbcAiBudgetExecutionFence
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Explicit primary-only diagnostic fixture. It is never imported by the full blind evaluation. */
@TestConfiguration(proxyBeanMethods = false)
class PrimaryGeminiSmokeFixtures(
    private val observations: PrimaryGeminiSmokeObservations = PrimaryGeminiSmokeObservations(),
) {
    private var physicalIdentity: AiBudgetReserveRequest? = null

    @Bean @Primary
    fun primarySmokePrecheck(): PreSubmissionCheckProvider =
        PreSubmissionCheckProvider { PrecheckProviderResult(PrecheckVerdict.CLEAR, provider = "TEST_DOUBLE", model = "TEST_DOUBLE") }

    @Bean @Primary
    fun primaryOnlyDispatch(
        delegate: JdbcAiBudgetExecutionFence,
        admission: LiveEvaluationAdmission,
    ): AiRuntimeDispatchGuardPort =
        AiRuntimeDispatchGuardPort { request, at ->
            admission.authorize({ delegate.authorize(request, at) }) { admitPrimary(request) }
        }

    @Synchronized
    private fun admitPrimary(request: AiBudgetReserveRequest): Boolean {
        val call = request.calls.singleOrNull() ?: return false
        if (call.operation != AiPhysicalOperation.FINAL_PRIMARY || call.ordinal != 1) return false
        val frozen = request.copy(calls = request.calls.toList())
        val existing = physicalIdentity
        if (existing != null) return existing == frozen
        physicalIdentity = frozen
        return true
    }

    fun primaryParser(
        delegate: NaturalLanguageParserPort,
        safety: LiveEvaluationSafety,
    ): NaturalLanguageParserPort =
        NaturalLanguageParserPort { request ->
            safety.checkpoint()
            try {
                delegate.parse(request)
            } catch (failure: Exception) {
                observations.record(failure)
                throw failure
            }
        }

    fun excludedParser(
        delegate: NaturalLanguageParserPort,
        safety: LiveEvaluationSafety,
    ): NaturalLanguageParserPort =
        NaturalLanguageParserPort {
            safety.checkpoint()
            // The diagnostic does not grant a fallback role; the actual delegate is never called.
            throw NaturalLanguageParserException(ParserFailureKind.CONFIGURATION)
        }

    @Bean
    fun primarySmokeObservations(): PrimaryGeminiSmokeObservations = observations
}

class PrimaryGeminiSmokeObservations {
    private val recorded = CopyOnWriteArrayList<Map<String, Any?>>()

    fun signals(): List<LiveEvaluationSignal> =
        recorded.map { event ->
            LiveEvaluationSignal(
                "FINAL_PRIMARY",
                httpStatus = event["httpStatus"] as? Int,
                parserFailureCode = event["failureCode"] as? String,
                transportFailureClass = event["transportFailureClass"] as? String,
            )
        }

    fun record(failure: Exception) {
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(16).toList()
        recorded +=
            mapOf(
                "stage" to "FINAL_PRIMARY",
                "failureCode" to ((failure as? NaturalLanguageParserException)?.kind?.name ?: "UNCLASSIFIED"),
                "exceptionClass" to failure.javaClass.name,
                "causeClasses" to causes.map { it.javaClass.name },
                "httpStatus" to causes.filterIsInstance<AiHttpProviderStatusException>().firstOrNull()?.status,
                "transportFailureClass" to
                    causes
                        .filterIsInstance<IOException>()
                        .firstOrNull()
                        ?.javaClass
                        ?.name,
            )
    }

    fun events(): List<Map<String, Any?>> = recorded.toList()
}
