package com.meetme.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.util.UUID

@ConfigurationProperties("meetme.operations")
data class OperatorQueryProperties(
    val enabled: Boolean = false,
    val operatorUserIds: Set<UUID> = emptySet(),
    val auditRetentionDays: Long = 30,
    val auditCleanupBatchSize: Int = 100,
    val cleanupEnabled: Boolean = true,
) {
    init {
        require(auditRetentionDays in 1..365) { "Operator audit retention must be 1..365 days" }
        require(auditCleanupBatchSize in 1..1000) { "Operator audit cleanup batch must be 1..1000" }
    }
}

@Configuration
@EnableConfigurationProperties(OperatorQueryProperties::class)
class OperatorQueryConfiguration
