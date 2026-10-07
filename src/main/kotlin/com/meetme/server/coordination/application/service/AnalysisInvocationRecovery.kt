package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Clock

/** Recovers an abandoned execution without authorizing another paid provider attempt. */
@Service
@ConditionalOnProperty(prefix = "meetme.reliability", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class AnalysisInvocationRecovery(
    private val invocations: AnalysisInvocationRepository,
    private val persistence: GeminiProcessingPersistenceService,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${meetme.reliability.recovery-delay:30000}")
    fun recoverExpired() {
        invocations.findExpired(clock.instant(), 100).forEach { invocation ->
            runCatching { persistence.expire(invocation) }.onFailure {
                logger.warn("analysis_invocation_recovery_failed failure_kind={}", it::class.simpleName)
            }
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(AnalysisInvocationRecovery::class.java)
    }
}
