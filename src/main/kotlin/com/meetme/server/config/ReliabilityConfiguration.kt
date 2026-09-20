package com.meetme.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.math.BigDecimal
import java.time.Duration

@ConfigurationProperties("meetme.reliability")
data class ReliabilityProperties(
    val enabled: Boolean = true,
    val streamKey: String = "meetme:coordination:work:v1",
    val consumerGroup: String = "coordination-workers-v1",
    val consumerName: String = "meet-me-local",
    val deadLetterStreamKey: String = "meetme:coordination:dlq:v1",
    val pollBatchSize: Int = 10,
    val pendingMinIdle: Duration = Duration.ofMinutes(2),
    val recoveryAge: Duration = Duration.ofMinutes(2),
    val dlqRetention: Duration = Duration.ofDays(30),
    val dlqMaxEntries: Long = 10_000,
) {
    init {
        require(streamKey.isNotBlank() && consumerGroup.isNotBlank() && consumerName.isNotBlank())
        require(deadLetterStreamKey.isNotBlank())
        require(pollBatchSize > 0)
        require(!pendingMinIdle.isNegative && !pendingMinIdle.isZero)
        require(!recoveryAge.isNegative && !recoveryAge.isZero)
        require(!dlqRetention.isNegative && !dlqRetention.isZero)
        require(dlqMaxEntries > 0)
    }
}

@ConfigurationProperties("meetme.ai-cost")
data class AiCostProperties(
    val usdToKrw: BigDecimal = BigDecimal("1500"),
    val maximumKrwPerMeeting: BigDecimal = BigDecimal.TEN,
) {
    init {
        require(usdToKrw > BigDecimal.ZERO)
        require(maximumKrwPerMeeting > BigDecimal.ZERO)
    }
}

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ReliabilityProperties::class, AiCostProperties::class)
class ReliabilityConfiguration
