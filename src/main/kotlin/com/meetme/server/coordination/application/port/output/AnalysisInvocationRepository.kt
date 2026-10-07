package com.meetme.server.coordination.application.port.output

import com.meetme.server.shared.domain.CoordinationRunId
import java.time.Instant
import java.util.UUID

enum class AnalysisProvider {
    GEMINI,
    OPENAI,
}

data class AnalysisInvocation(
    val id: UUID,
    val runId: CoordinationRunId,
    val runVersion: Long,
    val startedAt: Instant,
    val deadlineAt: Instant,
    val ownerToken: UUID,
    val geminiAttempts: Int = 0,
    val lunaAttempts: Int = 0,
    val finishedAt: Instant? = null,
    val winnerAttemptId: UUID? = null,
) {
    companion object {
        const val POLICY_VERSION = "bounded-luna-v1"
    }
}

/** Mutations are made under room -> exact run locks by application persistence services. */
interface AnalysisInvocationRepository {
    fun findByRunVersion(
        runId: CoordinationRunId,
        runVersion: Long,
    ): AnalysisInvocation?

    fun findLatestByRun(runId: CoordinationRunId): AnalysisInvocation?

    fun findExpired(
        now: Instant,
        limit: Int,
    ): List<AnalysisInvocation>

    fun hasAttempt(
        invocationId: UUID,
        attemptId: UUID,
    ): Boolean

    fun insert(invocation: AnalysisInvocation)

    fun update(invocation: AnalysisInvocation)
}
