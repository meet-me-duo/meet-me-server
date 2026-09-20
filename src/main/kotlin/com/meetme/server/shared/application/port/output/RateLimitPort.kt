package com.meetme.server.shared.application.port.output

import java.time.Duration

data class RateLimitDecision(
    val allowed: Boolean,
    val retryAfter: Duration,
)

fun interface RateLimitPort {
    fun check(
        key: String,
        limit: Int,
        window: Duration,
    ): RateLimitDecision
}
