package com.meetme.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

data class RateLimitRule(
    val limit: Int,
    val window: Duration,
    val failClosed: Boolean,
) {
    init {
        require(limit > 0)
        require(!window.isNegative && !window.isZero)
    }
}

@ConfigurationProperties("meetme.rate-limit")
data class RateLimitProperties(
    val enabled: Boolean = true,
    val createRoom: RateLimitRule = RateLimitRule(10, Duration.ofHours(1), true),
    val joinRoom: RateLimitRule = RateLimitRule(30, Duration.ofHours(1), false),
    val submission: RateLimitRule = RateLimitRule(30, Duration.ofMinutes(1), false),
    val hostCommand: RateLimitRule = RateLimitRule(5, Duration.ofMinutes(1), true),
)

@Configuration
@EnableConfigurationProperties(RateLimitProperties::class)
class RateLimitConfiguration
