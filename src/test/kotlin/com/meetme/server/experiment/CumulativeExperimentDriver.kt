package com.meetme.server.experiment

/** Typed application evidence only; provider envelopes, headers, errors and secrets never belong here. */
data class CumulativeStageResult(
    val analysisCompleted: Boolean,
    val failureCode: ExperimentTechnicalCode? = null,
    val appEvidence: Map<String, Any?> = emptyMap(),
)

/** This is the exact orchestration boundary used by the default-OFF paid JUnit entry. */
class CumulativeExperimentDriver(
    private val ledger: ExperimentCallLedger,
    private val connectivity: (Int) -> CumulativeStageResult,
    private val realE2E: () -> CumulativeStageResult,
    private val recordSafeResult: (Map<String, Any?>) -> Unit,
) {
    fun run(): Map<String, Any?> {
        var completed = false
        var outcome = "STOPPED"
        val stages = mutableListOf<Map<String, Any?>>()
        var report: Map<String, Any?> = emptyMap()
        try {
            var connected = false
            for (attempt in 1..2) {
                val ordinal = ledger.beginConnectivityAttempt()
                check(ordinal == attempt)
                val result = invokeStage { connectivity(ordinal) }
                stages += stage("CONNECTIVITY", ordinal, result)
                ledger.finishConnectivityAttempt(result.analysisCompleted)
                if (result.analysisCompleted) {
                    connected = true
                    break
                }
                if (result.failureCode !in RETRYABLE) {
                    outcome = "STOP_NON_RETRYABLE_CONNECTIVITY"
                    break
                }
            }
            if (connected) {
                ledger.beginE2E()
                val result = invokeStage(realE2E)
                stages += stage("REAL_E2E", 1, result)
                ledger.finishE2E()
                completed = result.analysisCompleted
                outcome = if (completed) "COMPLETED" else "STOP_INCOMPLETE_E2E"
            }
        } catch (_: Throwable) {
            // A counter stop already preserves its cause. An uncorrelated failure never opens another stage.
            if (runCatching { ledger.snapshot().stopReason }.getOrNull() == null) {
                runCatching { ledger.observationUnavailable() }
            }
            outcome = "STOPPED"
        } finally {
            val counter = runCatching { ledger.snapshot() }.getOrNull()
            report =
                mapOf(
                    "analysisCompleted" to completed,
                    "outcome" to outcome,
                    "stages" to stages.toList(),
                    "counterAvailable" to (counter != null),
                    "counter" to counter,
                    "counterUnavailableReason" to if (counter == null) "COUNTER_UNAVAILABLE" else null,
                )
            recordSafeResult(report)
        }
        return report
    }

    private fun invokeStage(action: () -> CumulativeStageResult): CumulativeStageResult {
        val before = ledger.snapshot().errors
        val result =
            try {
                action()
            } catch (_: Throwable) {
                val after = runCatching { ledger.snapshot() }.getOrNull()
                val code =
                    after
                        ?.takeIf { it.observationCoverageKnown }
                        ?.errors
                        ?.filter { (kind, count) ->
                            count > (before[kind] ?: 0)
                        }?.keys
                        ?.singleOrNull()
                if (code == null) {
                    runCatching { ledger.observationUnavailable() }
                    throw ExperimentCallStop()
                }
                CumulativeStageResult(false, code)
            }
        if (!result.analysisCompleted && result.failureCode == null) {
            runCatching { ledger.observationUnavailable() }
            throw ExperimentCallStop()
        }
        return result
    }

    private fun stage(
        kind: String,
        ordinal: Int,
        result: CumulativeStageResult,
    ): Map<String, Any?> =
        mapOf(
            "stage" to kind,
            "ordinal" to ordinal,
            "analysisCompleted" to result.analysisCompleted,
            "failureCode" to result.failureCode?.name,
            "appEvidence" to safe(result.appEvidence),
        )

    private fun safe(
        value: Any?,
        depth: Int = 0,
    ): Any? {
        if (depth > 6) return null
        return when (value) {
            null, is Boolean -> value
            is String -> value.take(256)
            is Number -> value.takeIf { it.toDouble().isFinite() }
            is Enum<*> -> value.name
            is Map<*, *> ->
                value.entries
                    .filter { it.key is String && it.key in SAFE_FIELDS }
                    .take(64)
                    .associate { it.key as String to safe(it.value, depth + 1) }
            is Iterable<*> -> value.take(256).map { safe(it, depth + 1) }
            else -> null
        }
    }

    companion object {
        private val RETRYABLE =
            setOf(
                ExperimentTechnicalCode.NETWORK,
                ExperimentTechnicalCode.TIMEOUT,
                ExperimentTechnicalCode.RATE_LIMIT,
                ExperimentTechnicalCode.SERVER,
            )
        private val SAFE_FIELDS =
            setOf(
                "publicStatus",
                "candidatesStatus",
                "recommendationsStatus",
                "candidateCount",
                "candidateQuality",
                "appliedSubmissions",
                "totalSubmissions",
                "recommendationCount",
                "questionCount",
                "answerCount",
                "participantOutcomes",
                "recommendationWindows",
                "unappliedCount",
                "unappliedReasons",
                "httpEvents",
                "confirmationStatus",
                "selectedWindow",
                "resultStatus",
                "confirmedPublicStatus",
                "sourceParticipantId",
                "finalStatus",
                "failureCode",
                "questionObserved",
                "questionText",
                "answerSent",
                "interpretation",
                "optionId",
                "startAt",
                "endAt",
                "attendanceCounts",
                "reason",
                "failureScope",
                "method",
                "path",
                "status",
                "decisionField",
                "questionId",
                "decisionSha256",
                "answerSha256",
            )
    }
}
