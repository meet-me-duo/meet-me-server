package com.meetme.server.coordination.application.service

import com.meetme.server.config.ReliabilityConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.concurrent.Executor
import kotlin.test.assertNotSame

/** OC10: the dedicated publication pool must not suppress Boot's existing application executor. */
class OutboxCommitExecutorIsolationTest {
    @Test
    fun `OC10 dedicated commit executor coexists with the Boot application task executor`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration::class.java))
            .withUserConfiguration(ReliabilityConfiguration::class.java)
            .run { context ->
                val application = context.getBean("applicationTaskExecutor", Executor::class.java)
                val outbox = context.getBean("outboxCommitExecutor", Executor::class.java)
                assertNotSame(application, outbox)
            }
    }
}
