package com.meetme.server.participant.adapter.output.security

import com.meetme.server.participant.application.port.output.GuestPasswordPort
import com.meetme.server.participant.application.port.output.GuestReaccessAttemptPort
import com.meetme.server.participant.application.service.GuestReaccessPolicy
import com.meetme.server.shared.application.port.output.RateLimitPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class GuestReaccessConfiguration {
    @Bean
    fun guestReaccessPolicy(passwords: GuestPasswordPort): GuestReaccessPolicy = GuestReaccessPolicy(passwords)

    @Bean
    fun guestReaccessAttempts(limiter: RateLimitPort): GuestReaccessAttemptPort = GuestReaccessAttemptGuard(limiter)
}
