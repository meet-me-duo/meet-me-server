package com.meetme.server.shared.operations

import com.meetme.server.config.OperatorQueryProperties
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAudit
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAuditPort
import com.meetme.server.shared.application.service.operations.OperatorAuditRetentionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Issue #110: separate audit retention is configurable, bounded, and strictly older than cutoff. */
class OperatorAuditRetentionServiceTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `default cleanup uses thirty day cutoff and a bounded batch`() {
        val audit = RetentionFake()
        audit.deleted = 3
        val service = OperatorAuditRetentionService(audit, OperatorQueryProperties(), clock)
        assertEquals(3, service.cleanup())
        assertEquals(listOf(now.minus(Duration.ofDays(30)) to 100), audit.calls)
    }

    @Test
    fun `configured retention and batch are forwarded without extending room retention`() {
        val audit = RetentionFake()
        val properties = OperatorQueryProperties(auditRetentionDays = 2, auditCleanupBatchSize = 7)
        OperatorAuditRetentionService(audit, properties, clock).cleanup()
        assertEquals(listOf(now.minus(Duration.ofDays(2)) to 7), audit.calls)
    }

    @Test
    fun `disabled cleanup does not delete any audit rows`() {
        val audit = RetentionFake()
        val properties = OperatorQueryProperties(cleanupEnabled = false)
        assertEquals(0, OperatorAuditRetentionService(audit, properties, clock).cleanup())
        assertEquals(emptyList<Pair<Instant, Int>>(), audit.calls)
    }

    private class RetentionFake : OperatorAccessAuditPort {
        val calls = mutableListOf<Pair<Instant, Int>>()
        var deleted = 0

        override fun append(event: OperatorAccessAudit) = error("not used by cleanup")

        override fun deleteBefore(
            cutoff: Instant,
            limit: Int,
        ): Int {
            calls += cutoff to limit
            return deleted
        }
    }
}
