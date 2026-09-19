package com.meetme.server.config

import com.meetme.server.application.port.output.JitterPort
import com.meetme.server.application.port.output.MonotonicTimePort
import com.meetme.server.application.port.output.RetryDelayPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ThreadLocalRandom

@Configuration
class RetryConfiguration {
    @Bean
    fun retryDelayPort() = RetryDelayPort { Thread.sleep(it.toMillis()) }

    @Bean
    fun jitterPort() = JitterPort { upperExclusive -> ThreadLocalRandom.current().nextLong(upperExclusive) }

    @Bean
    fun monotonicTimePort() = MonotonicTimePort(System::nanoTime)
}
