package com.meetme.server.coordination.application.port.output

import java.time.Duration

/** One execution reserves Luna and completion time before admitting Gemini retries. */
object AnalysisInvocationBudget {
    val TOTAL_TIMEOUT: Duration = Duration.ofSeconds(60)
    val GEMINI_CALL_TIMEOUT: Duration = Duration.ofSeconds(15)
    val LUNA_CALL_TIMEOUT: Duration = Duration.ofSeconds(27)
    val COMPLETION_RESERVE: Duration = Duration.ofSeconds(3)
    val PRIMARY_PHASE_RESERVE: Duration = Duration.ofSeconds(30)
    val C5_LUNA_CALL_TIMEOUT: Duration = Duration.ofSeconds(40)

    fun isAllowedPolicy(
        policyVersion: String,
        structural: Boolean,
    ): Boolean =
        policyVersion == AnalysisInvocation.POLICY_VERSION ||
            (structural && policyVersion == AnalysisInvocation.C5_POLICY_VERSION)

    fun lunaCallTimeout(policyVersion: String): Duration =
        when (policyVersion) {
            AnalysisInvocation.POLICY_VERSION -> LUNA_CALL_TIMEOUT
            AnalysisInvocation.C5_POLICY_VERSION -> C5_LUNA_CALL_TIMEOUT
            else -> throw IllegalArgumentException("Unsupported analysis execution policy")
        }
}
