package com.meetme.server.shared.operations

import com.meetme.server.config.OperatorQueryProperties
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAudit
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAuditPort
import com.meetme.server.shared.application.service.operations.OperatorAuditRetentionService
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The documented bounded retention cleanup must actually be registered with Spring scheduling. */
class OperatorAuditSchedulingContractTest {
    @Test
    fun `retention cleanup is scheduled with a configurable hourly default`() {
        val method = OperatorAuditRetentionService::class.java.getMethod("cleanup")
        val schedule = assertNotNull(method.getAnnotation(Scheduled::class.java))
        assertEquals("\${meetme.operations.audit-cleanup-fixed-delay:PT1H}", schedule.fixedDelayString)
    }

    @Test
    fun `Spring registers cleanup while the disabled switch prevents every deletion`() {
        val deletes = AtomicInteger()
        val audit =
            object : OperatorAccessAuditPort {
                override fun append(event: OperatorAccessAudit) = error("not used")

                override fun deleteBefore(
                    cutoff: Instant,
                    limit: Int,
                ): Int {
                    deletes.incrementAndGet()
                    return 0
                }
            }
        AnnotationConfigApplicationContext().use { context ->
            context.register(Scheduling::class.java)
            context.registerBean(
                OperatorAuditRetentionService::class.java,
                java.util.function.Supplier {
                    OperatorAuditRetentionService(audit, OperatorQueryProperties(cleanupEnabled = false), Clock.systemUTC())
                },
            )
            context.refresh()
            val processor = context.getBean(ScheduledAnnotationBeanPostProcessor::class.java)
            assertTrue(processor.scheduledTasks.isNotEmpty(), "Retention cleanup must be connected to the scheduler")
            context.getBean(OperatorAuditRetentionService::class.java).cleanup()
            assertEquals(0, deletes.get())
        }
    }

    @Configuration
    @EnableScheduling
    class Scheduling
}
