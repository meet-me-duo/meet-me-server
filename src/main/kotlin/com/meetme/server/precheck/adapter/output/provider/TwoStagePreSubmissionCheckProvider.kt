package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.precheck.application.port.output.AmbiguityAssessment
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationReason
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationRequest
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationResult
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.adapter.output.budget.DefaultDenyAiCallBudget
import com.meetme.server.shared.application.port.output.AiCallBudgetPort
import com.meetme.server.shared.application.port.output.AiCallBudgetRequest
import com.meetme.server.shared.application.port.output.AiCallBudgetReservation
import com.meetme.server.shared.application.port.output.AiCallObservation
import com.meetme.server.shared.application.port.output.AiCallOutcome
import com.meetme.server.shared.application.port.output.AiCallPurpose
import com.meetme.server.shared.application.port.output.AiCallStage
import com.meetme.server.shared.application.port.output.AiDispatchFingerprints
import java.security.MessageDigest
import java.time.Duration
import java.util.function.LongSupplier

class TwoStagePreSubmissionCheckProvider(
    private val classifier: AmbiguityClassificationPort,
    private val generator: PreSubmissionCheckProvider,
    private val budget: AiCallBudgetPort = DefaultDenyAiCallBudget(),
    private val nanoTime: LongSupplier = LongSupplier(System::nanoTime),
    private val physicalDispatch: AiHttpDispatchGate? = null,
) : PreSubmissionCheckProvider {
    override fun check(request: PrecheckProviderRequest): PrecheckProviderResult {
        if (physicalDispatch?.properties?.enabled == true) return checkPhysical(request)
        val started = nanoTime.asLong
        val timeout = request.timeout.coerceAtMost(Duration.ofSeconds(15))
        if (timeout.isNegative || timeout.isZero) fail("TIMEOUT")
        val durationNanos = timeout.toNanos()
        val roomId = request.roomId ?: fail("INVALID_REQUEST")
        val generation = request.executionGeneration ?: fail("INVALID_REQUEST")
        if (generation < 0) fail("INVALID_REQUEST")

        fun remaining(): Duration {
            val nanos = durationNanos - (nanoTime.asLong - started)
            if (nanos <= 0) fail("TIMEOUT")
            return Duration.ofNanos(nanos)
        }

        val source = AiDispatchFingerprints.personalSource(request.rawText, request.evidence)
        val context =
            fingerprint(
                request.referenceAt.toString(),
                request.timeZone.id,
                request.locale,
                request.searchStartDate.toString(),
                request.searchEndDate.toString(),
            )

        fun reserve(stage: AiCallStage): AiCallBudgetReservation {
            remaining()
            val classification = stage == AiCallStage.CLASSIFICATION
            val intent =
                AiCallBudgetRequest(
                    roomId,
                    request.checkId,
                    generation,
                    stage,
                    if (classification) AiCallPurpose.PRECHECK_CLASSIFICATION else AiCallPurpose.PRECHECK_GENERATION,
                    source,
                    context,
                    fingerprint(
                        if (classification) "precheck-classification-v1" else "precheck-generation-count-bundle-v1",
                        request.maximumOutputTokens.toString(),
                    ),
                    if (classification) 1 else 2,
                )
            val reservation =
                try {
                    budget.reserve(intent)
                } catch (_: Exception) {
                    fail("BUDGET_UNAVAILABLE")
                } ?: fail("BUDGET_DENIED")
            if (reservation.request != intent ||
                !reservation.dispatchGranted ||
                !reservation.quote.verified ||
                reservation.quote.evidenceFingerprint.isBlank() ||
                reservation.quote.profileFingerprint != intent.profileFingerprint ||
                reservation.quote.maximumPhysicalCalls < intent.maximumPhysicalCalls
            ) {
                fail("BUDGET_DENIED")
            }
            remaining()
            return reservation
        }

        val firstReservation = reserve(AiCallStage.CLASSIFICATION)
        var classified: AmbiguityClassificationResult? = null
        val first =
            invoke(
                firstReservation,
                ::remaining,
                call = {
                    classifier
                        .classify(
                            AmbiguityClassificationRequest(
                                request.rawText,
                                request.referenceAt,
                                request.timeZone,
                                request.locale,
                                request.searchStartDate,
                                request.searchEndDate,
                                it,
                                evidence = request.evidence,
                            ),
                        ).also {
                            classified = it
                            validate(it)
                        }
                },
                observation = { failure ->
                    AiCallObservation(
                        outcome(failure),
                        classified?.inputTokens,
                        classified?.outputTokens,
                        classified?.usageComplete ?: false,
                        failureCode = failure,
                        provider = classified?.provider,
                        model = classified?.model,
                    )
                },
            )
        if (decision(request, first) == AmbiguityAssessment.CLEAR) {
            return PrecheckProviderResult(
                PrecheckVerdict.CLEAR,
                inputTokens = first.inputTokens,
                // Decision usage belongs to its stage observation, outside the generator's output cap.
                outputTokens = null,
                provider = first.provider,
                model = first.model,
            )
        }
        unresolvedAfterAnswer(request, first)?.let { return it }
        val secondReservation = reserve(AiCallStage.GENERATION)
        var generated: PrecheckProviderResult? = null
        return invoke(
            secondReservation,
            ::remaining,
            call = { generator.check(request.copy(timeout = it)).also { generated = it } },
            observation = { failure ->
                AiCallObservation(
                    outcome(failure),
                    generated?.inputTokens,
                    generated?.outputTokens,
                    // The count+generation bundle does not expose count billing or usage.
                    usageComplete = false,
                    failureCode = failure,
                    provider = generated?.provider,
                    model = generated?.model,
                )
            },
        )
    }

    private fun checkPhysical(request: PrecheckProviderRequest): PrecheckProviderResult {
        val timeout = request.timeout.coerceAtMost(Duration.ofSeconds(15))
        if (timeout.isNegative || timeout.isZero) fail("TIMEOUT")
        val started = nanoTime.asLong
        val localCutoff = started + timeout.toNanos()
        val original = request.dispatchContext ?: fail("CONFIGURATION")
        val cutoff = if (original.deadlineNanos - localCutoff <= 0) original.deadlineNanos else localCutoff
        val context = original.copy(deadlineNanos = cutoff)

        fun remaining(): Duration {
            val nanos = cutoff - nanoTime.asLong
            if (nanos <= 0) fail("TIMEOUT")
            return Duration.ofNanos(nanos)
        }
        try {
            val first =
                classifier.classify(
                    AmbiguityClassificationRequest(
                        request.rawText,
                        request.referenceAt,
                        request.timeZone,
                        request.locale,
                        request.searchStartDate,
                        request.searchEndDate,
                        remaining(),
                        dispatchContext = context,
                        evidence = request.evidence,
                    ),
                )
            validate(first)
            remaining()
            if (decision(request, first) ==
                AmbiguityAssessment.CLEAR
            ) {
                return PrecheckProviderResult(
                    PrecheckVerdict.CLEAR,
                    inputTokens = first.inputTokens,
                    provider = first.provider,
                    model = first.model,
                )
            }
            unresolvedAfterAnswer(request, first)?.let { return it }
            return generator.check(request.copy(timeout = remaining(), dispatchContext = context)).also { remaining() }
        } catch (exception: Exception) {
            fail(failureCode(exception))
        }
    }

    private fun unresolvedAfterAnswer(
        request: PrecheckProviderRequest,
        result: AmbiguityClassificationResult,
    ): PrecheckProviderResult? {
        if (request.evidence?.supplement == null) {
            return null
        }
        return PrecheckProviderResult(
            PrecheckVerdict.UNRESOLVED,
            inputTokens = result.inputTokens,
            provider = result.provider,
            model = result.model,
            unresolvedReason =
                when {
                    decision(request, result) == AmbiguityAssessment.NEEDS_REVIEW -> "AFTER_ANSWER_REVIEW"
                    result.reason == AmbiguityClassificationReason.LOW_CONFIDENCE -> "CLASSIFIER_LOW_CONFIDENCE"
                    result.reason == AmbiguityClassificationReason.MODEL_UNCERTAIN -> "CLASSIFIER_MODEL_UNCERTAIN"
                    else -> fail("INVALID_RESPONSE")
                },
        )
    }

    /** Semantic evidence wins only in V2; V1 retains its normalized confidence policy. */
    private fun decision(
        request: PrecheckProviderRequest,
        result: AmbiguityClassificationResult,
    ): AmbiguityAssessment = if (request.evidence != null) result.semanticAssessment ?: result.assessment else result.assessment

    private fun <T> invoke(
        reservation: AiCallBudgetReservation,
        remaining: () -> Duration,
        call: (Duration) -> T,
        observation: (String?) -> AiCallObservation,
    ): T {
        val timeout = remaining()
        var result: T? = null
        var failure: String? = null
        try {
            result = call(timeout)
            remaining()
        } catch (exception: Exception) {
            failure = failureCode(exception)
        }
        try {
            budget.record(reservation, observation(failure))
        } catch (_: Exception) {
            fail("BUDGET_UNAVAILABLE")
        }
        remaining()
        if (failure != null) fail(failure)
        return requireNotNull(result)
    }

    private fun validate(result: AmbiguityClassificationResult) {
        if (result.assessment == AmbiguityAssessment.INDETERMINATE && result.reason == AmbiguityClassificationReason.REFUSED) {
            fail("REFUSED")
        }
        val confidence = result.confidence ?: fail("INVALID_RESPONSE")
        if (!confidence.isFinite() || confidence !in 0.0..1.0) fail("INVALID_RESPONSE")
        if (result.inputTokens?.let { it < 0 } == true || result.outputTokens?.let { it < 0 } == true) fail("INVALID_RESPONSE")
        when (result.assessment) {
            AmbiguityAssessment.CLEAR, AmbiguityAssessment.NEEDS_REVIEW -> if (result.reason != null) fail("INVALID_RESPONSE")
            AmbiguityAssessment.INDETERMINATE ->
                if (result.reason != AmbiguityClassificationReason.LOW_CONFIDENCE &&
                    result.reason != AmbiguityClassificationReason.MODEL_UNCERTAIN
                ) {
                    fail("INVALID_RESPONSE")
                }
        }
    }

    private fun failureCode(exception: Exception): String {
        val code =
            when (exception) {
                is PrecheckProviderException -> exception.failureCode
                is AmbiguityClassificationException -> exception.failureCode
                else -> null
            }
        return code?.takeIf { it in FAILURE_CODES } ?: "PROVIDER_UNAVAILABLE"
    }

    private fun outcome(failure: String?): AiCallOutcome =
        when (failure) {
            null -> AiCallOutcome.SUCCESS
            "REFUSED" -> AiCallOutcome.REFUSED
            "TIMEOUT" -> AiCallOutcome.TIMED_OUT
            else -> AiCallOutcome.FAILED
        }

    private fun fingerprint(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fail(code: String): Nothing = throw PrecheckProviderException(code)

    companion object {
        private val FAILURE_CODES =
            setOf(
                "CONFIGURATION",
                "TIMEOUT",
                "CONNECTION",
                "CONNECTION_FAILED",
                "RATE_LIMITED",
                "PROVIDER_UNAVAILABLE",
                "INVALID_RESPONSE",
                "SERVER",
                "INVALID_REQUEST",
                "REFUSED",
            )
    }
}
