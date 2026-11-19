package com.meetme.server.coordination.application.service

import com.meetme.server.config.ReliabilityConfiguration
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.config.FixedDelayTask
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** OC06/OC07: enable switch, bounded executor and configurable fallback cadence are public operational contracts. */
class OutboxCommitConfigurationContractTest {
    private val configuration = ApplicationContextRunner().withUserConfiguration(ReliabilityConfiguration::class.java)

    @Test
    fun `OC06 default enabled configuration registers one bounded aborting commit executor`() {
        configuration.run { context ->
            val executor = context.getBean("outboxCommitExecutor", ThreadPoolTaskExecutor::class.java)
            assertEquals(1, executor.corePoolSize)
            assertEquals(1, executor.maxPoolSize)
            assertEquals(100, executor.queueCapacity)
            assertTrue(executor.threadPoolExecutor.rejectedExecutionHandler is ThreadPoolExecutor.AbortPolicy)
        }
    }

    @Test
    fun `OC06 disabled reliability configuration creates no immediate publication executor`() {
        configuration.withPropertyValues("meetme.reliability.enabled=false").run { context ->
            assertFalse(context.containsBean("outboxCommitExecutor"))
        }
    }

    @Test
    fun `OC06 listener follows the reliability enabled switch`() {
        for (enabled in listOf(true, false)) {
            ApplicationContextRunner()
                .withUserConfiguration(OutboxCommitListener::class.java)
                .withBean(OutboxRelay::class.java, { mock(OutboxRelay::class.java) })
                .withBean("outboxCommitExecutor", Executor::class.java, { Executor { } })
                .withPropertyValues("meetme.reliability.enabled=$enabled")
                .run { context ->
                    assertEquals(if (enabled) 1 else 0, context.getBeansOfType(OutboxCommitListener::class.java).size)
                }
        }
    }

    @Test
    fun `OC07 pending fallback uses five second fixed delay by default`() {
        val schedule = assertNotNull(OutboxRelayScheduler::class.java.getMethod("relay").getAnnotation(Scheduled::class.java))
        assertEquals("\${meetme.reliability.relay-delay:5000}", schedule.fixedDelayString)
        assertScheduledDelay(null, Duration.ofSeconds(5))
    }

    @Test
    fun `OC07 explicit polling delay override remains effective`() {
        assertScheduledDelay("777", Duration.ofMillis(777))
    }

    private fun assertScheduledDelay(
        configuredDelay: String?,
        expected: Duration,
    ) {
        val runner =
            ApplicationContextRunner()
                .withUserConfiguration(ReliabilityConfiguration::class.java, OutboxRelayScheduler::class.java)
                .withBean(OutboxRelay::class.java, { mock(OutboxRelay::class.java) })
        (
            if (configuredDelay ==
                null
            ) {
                runner
            } else {
                runner.withPropertyValues("meetme.reliability.relay-delay=$configuredDelay")
            }
        ).run { context ->
            val processor = context.getBean(ScheduledAnnotationBeanPostProcessor::class.java)
            val tasks = processor.scheduledTasks.map { it.task }.filterIsInstance<FixedDelayTask>()
            assertEquals(2, tasks.size, "Pending publication and Published recovery must both retain fixed-delay scheduling")
            assertEquals(setOf(expected, Duration.ofSeconds(30)), tasks.map { it.intervalDuration }.toSet())
        }
    }
}
