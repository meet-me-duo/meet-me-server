package com.meetme.server.config

import com.meetme.server.meetingroom.application.port.output.RoomDataRetentionPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

@ConfigurationProperties("meetme.data-retention")
data class DataRetentionProperties(
    val enabled: Boolean = true,
    val roomRetention: Duration = Duration.ofDays(30),
    val batchSize: Int = 100,
) {
    init {
        require(!roomRetention.isNegative && !roomRetention.isZero)
        require(batchSize > 0)
    }
}

@Configuration
@EnableConfigurationProperties(DataRetentionProperties::class)
class DataRetentionConfiguration

@Component
@ConditionalOnProperty(prefix = "meetme.data-retention", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class DataRetentionScheduler(
    private val repository: RoomDataRetentionPort,
    private val properties: DataRetentionProperties,
    private val clock: Clock,
) {
    @Scheduled(cron = "\${meetme.data-retention.cron:0 20 3 * * *}")
    fun deleteExpiredData() {
        val now = clock.instant()
        repository.deleteExpiredRooms(now.minus(properties.roomRetention), properties.batchSize, now)
    }
}
