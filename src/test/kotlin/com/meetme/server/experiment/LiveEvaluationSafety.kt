package com.meetme.server.experiment

import com.meetme.server.meetingroom.application.port.input.PublicRoomStatus

/** Metadata only. Never pass provider bodies, exception messages, credentials or participant text here. */
data class LiveEvaluationSignal(
    val stage: String,
    val httpStatus: Int? = null,
    val parserFailureCode: String? = null,
    val transportFailureClass: String? = null,
)

data class LiveEvaluationBarrier(
    val stage: String,
    val category: String,
    val httpStatus: Int? = null,
    val failureCode: String? = null,
)

class LiveEvaluationTechnicalStop(
    val barrier: LiveEvaluationBarrier,
) : RuntimeException("Live evaluation stopped at a protected boundary")

/** A driver boundary; provider-internal retry and fallback contracts remain owned by production code. */
class LiveEvaluationSafety(
    private val observations: () -> List<LiveEvaluationSignal>,
) {
    @Volatile private var firstBarrier: LiveEvaluationBarrier? = null

    val barrier: LiveEvaluationBarrier?
        get() = firstBarrier

    @Synchronized
    fun checkpoint() {
        firstBarrier?.let { throw LiveEvaluationTechnicalStop(it) }
        val signals =
            try {
                observations()
            } catch (_: Exception) {
                stop(LiveEvaluationBarrier("OBSERVATION", "OBSERVATION_UNAVAILABLE"))
            }
        signals.firstNotNullOfOrNull(::classify)?.let(::stop)
    }

    fun <T> guard(action: () -> T): T {
        checkpoint()
        return try {
            action()
        } finally {
            checkpoint()
        }
    }

    @Synchronized
    fun requireCompletedAnalysis(
        publicStatus: String?,
        candidateStatus: Int?,
        recommendationStatus: Int?,
    ) {
        checkpoint()
        if (!isCompletedAnalysis(publicStatus, candidateStatus, recommendationStatus)) {
            stop(LiveEvaluationBarrier("FINAL_ANALYSIS", "INCOMPLETE_ANALYSIS"))
        }
    }

    private fun stop(reason: LiveEvaluationBarrier): Nothing {
        val current = firstBarrier ?: reason.also { firstBarrier = it }
        throw LiveEvaluationTechnicalStop(current)
    }

    private fun classify(signal: LiveEvaluationSignal): LiveEvaluationBarrier? {
        val stage = signal.stage.takeIf { it.matches(STAGE) } ?: "UNKNOWN_STAGE"
        if (signal.parserFailureCode in COMMON_PROVIDER_CODES) {
            return LiveEvaluationBarrier(stage, "PROVIDER_TECHNICAL", signal.httpStatus, signal.parserFailureCode)
        }
        if (signal.httpStatus in ACCOUNT_OR_TRANSIENT_HTTP || signal.httpStatus?.let { it in 500..599 } == true) {
            return LiveEvaluationBarrier(stage, "HTTP_TECHNICAL", signal.httpStatus)
        }
        if (!signal.transportFailureClass.isNullOrBlank()) {
            return LiveEvaluationBarrier(stage, "TRANSPORT_FAILURE", signal.httpStatus, "TRANSPORT_FAILURE")
        }
        return null
    }

    companion object {
        private val COMPLETED_STATUSES =
            setOf(
                PublicRoomStatus.NO_MATCH,
                PublicRoomStatus.READY,
                PublicRoomStatus.READY_WITH_WARNINGS,
                PublicRoomStatus.CONFIRMED,
            ).map { it.name }.toSet()

        fun isCompletedAnalysis(
            publicStatus: String?,
            candidateStatus: Int?,
            recommendationStatus: Int?,
        ): Boolean = publicStatus in COMPLETED_STATUSES && candidateStatus == 200 && recommendationStatus == 200

        // These are typed provider outcomes, never missing local price/budget-policy states.
        private val COMMON_PROVIDER_CODES =
            setOf("CONFIGURATION", "SERVER", "AUTHENTICATION", "PERMISSION", "BILLING", "QUOTA", "NETWORK", "TIMEOUT", "RATE_LIMIT")
        private val ACCOUNT_OR_TRANSIENT_HTTP = setOf(401, 402, 403, 407, 408, 429)
        private val STAGE = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
}

/** Wraps the actual runtime source fence; it never grants authority itself. */
class LiveEvaluationAdmission(
    private val safety: LiveEvaluationSafety,
) {
    fun <T : Any> authorize(
        originalFence: () -> T?,
        admit: (T) -> Boolean,
    ): T? {
        val grant = originalFence() ?: return null
        safety.checkpoint()
        if (!admit(grant)) return null
        safety.checkpoint()
        return grant
    }
}
