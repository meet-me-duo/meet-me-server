package com.meetme.server.coordination.application.port.output

import java.time.Duration

/** One execution reserves Luna and completion time before admitting Gemini retries. */
object AnalysisInvocationBudget {
    val TOTAL_TIMEOUT: Duration = Duration.ofSeconds(60)
    val GEMINI_CALL_TIMEOUT: Duration = Duration.ofSeconds(15)
    val LUNA_CALL_TIMEOUT: Duration = Duration.ofSeconds(27)
    val COMPLETION_RESERVE: Duration = Duration.ofSeconds(3)
}
