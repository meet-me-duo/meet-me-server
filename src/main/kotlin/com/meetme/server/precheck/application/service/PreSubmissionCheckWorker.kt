package com.meetme.server.precheck.application.service

import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckMetricsPort
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiDispatchFingerprints
import com.meetme.server.shared.application.port.output.AiDispatchModePort
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

@Service
class PreSubmissionCheckWorker(
    private val repository: PrecheckRepository,
    private val processing: PrecheckProcessingService,
    private val provider: PreSubmissionCheckProvider,
    private val clock: Clock,
    private val metrics: PrecheckMetricsPort? = null,
    private val dispatchMode: AiDispatchModePort? = null,
) {
    fun runOne(): Boolean {
        val physicalMode = dispatchMode?.isEnabled() == true
        repository.unfinishedCheckIds(128).forEach(processing::expire)
        if (repository.inFlight(clock.instant()) >= 2) return false
        for (id in repository.queuedCheckIds(128)) {
            val claim = processing.claim(id) ?: continue
            val check = claim.check
            val now = clock.instant()
            val legacyRemaining = Duration.between(now, check.deadlineAt.minusSeconds(3)).coerceAtMost(Duration.ofSeconds(15))
            var remaining = legacyRemaining
            val started = System.nanoTime()
            var result: PrecheckProviderResult? = null
            var failure: String? = null
            try {
                if (physicalMode) {
                    val claimedAt = check.claimedAt ?: throw PrecheckProviderException("CONFIGURATION")
                    remaining = minOf(legacyRemaining, Duration.between(now, claimedAt.plusSeconds(15)))
                }
                if (remaining.isNegative || remaining.isZero) throw PrecheckProviderException("TIMEOUT")
                val request =
                    PrecheckProviderRequest(
                        check.id,
                        check.candidate.rawText,
                        check.candidate.referenceAt,
                        claim.room.timeZone.value,
                        check.candidate.locale,
                        claim.room.searchRange.startInclusive,
                        claim.room.searchRange.endExclusive,
                        remaining,
                        roomId = claim.room.id.value,
                        executionGeneration = check.generation,
                        dispatchContext =
                            if (physicalMode) {
                                AiProviderDispatchContext(
                                    AiBudgetExecution(
                                        claim.room.id.value,
                                        AiBudgetExecutionKind.PRECHECK,
                                        check.id,
                                        check.generation.toLong(),
                                        check.ownerToken ?: throw PrecheckProviderException("CONFIGURATION"),
                                        check.version,
                                        AiDispatchFingerprints.personalSource(check.candidate.rawText, check.candidate.evidence),
                                        AiDispatchFingerprints.personalContext(
                                            check.candidate.referenceAt,
                                            claim.room.timeZone.value,
                                            check.candidate.locale,
                                            claim.room.searchRange.startInclusive,
                                            claim.room.searchRange.endExclusive,
                                        ),
                                    ),
                                    started + remaining.toNanos(),
                                )
                            } else {
                                null
                            },
                        evidence = check.candidate.evidence,
                    )
                result = provider.check(request)
                validate(result, check.candidate.rawText, check.candidate.evidence != null)
            } catch (exception: PrecheckProviderException) {
                failure = exception.failureCode.takeIf { it in FAILURES } ?: "PROVIDER_UNAVAILABLE"
                result = null
            } catch (_: Exception) {
                failure = "PROVIDER_UNAVAILABLE"
                result = null
            }
            val status =
                try {
                    processing.publish(claim, result, failure)
                } catch (_: PrecheckPublicationExpired) {
                    processing.rejectPublication(claim, result, failure)
                }
            metrics?.completed(
                status.name,
                Duration.between(check.deadlineAt.minusSeconds(30), requireNotNull(check.claimedAt)).coerceAtLeast(Duration.ZERO),
                Duration.ofNanos(
                    System.nanoTime() - started,
                ),
            )
            return true
        }
        return false
    }

    private fun validate(
        result: PrecheckProviderResult,
        raw: String,
        evidenceProtocol: Boolean,
    ) {
        fun size(text: String) = text.codePointCount(0, text.length)

        fun invalid(): Nothing = throw PrecheckProviderException("INVALID_RESPONSE")
        if (!evidenceProtocol && result.verdict == PrecheckVerdict.UNRESOLVED) invalid()
        if (result.unresolvedReason?.let { it.isBlank() || size(it) > 64 || it.contains('\u0000') } == true) invalid()
        if (result.inputTokens?.let { it < 0 } == true ||
            result.outputTokens?.let { it < 0 || it > 2048 } == true ||
            result.responseBytes?.let { it < 0 || it > 16384 } == true
        ) {
            invalid()
        }
        if (result.verdict != PrecheckVerdict.NEEDS_CHOICE) {
            if (result.prompt != null || result.sourceExcerpt != null || result.choices.isNotEmpty()) invalid()
            return
        }
        val prompt = result.prompt ?: invalid()
        val excerpt = result.sourceExcerpt ?: invalid()
        if (prompt.isBlank() ||
            prompt.contains('\u0000') ||
            size(prompt) > 160 ||
            excerpt.isBlank() ||
            excerpt.contains('\u0000') ||
            size(excerpt) > 200 ||
            !raw.contains(excerpt)
        ) {
            invalid()
        }
        if (evidenceProtocol && raw.indexOf(excerpt) != raw.lastIndexOf(excerpt)) invalid()
        if (evidenceProtocol && result.choices.isEmpty()) return
        if (result.choices.size != 2 ||
            result.choices
                .map { it.id }
                .distinct()
                .size != 2 ||
            result.choices
                .map { it.proposedText }
                .distinct()
                .size != 2
        ) {
            invalid()
        }
        result.choices.forEach {
            if (it.id.isBlank() ||
                it.id.contains('\u0000') ||
                size(it.id) > 32 ||
                it.label.isBlank() ||
                it.label.contains('\u0000') ||
                size(it.label) > 160 ||
                it.proposedText.isBlank() ||
                it.proposedText == raw ||
                size(it.proposedText) > 500 ||
                it.proposedText.contains('\u0000')
            ) {
                invalid()
            }
        }
    }

    companion object {
        private val FAILURES =
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
            )
    }
}
