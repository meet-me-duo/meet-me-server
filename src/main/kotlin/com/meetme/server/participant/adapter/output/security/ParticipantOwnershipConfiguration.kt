package com.meetme.server.participant.adapter.output.security

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestOwnershipProofPort
import com.meetme.server.participant.application.port.output.GuestSessionRepository
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.participant.application.service.ParticipantOwnershipResolver
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ParticipantOwnershipConfiguration {
    @Bean
    @ConditionalOnMissingBean(GuestOwnershipProofPort::class)
    fun guestOwnershipProof(
        sessions: GuestSessionRepository,
        credentials: GuestCredentialPort,
        participants: ParticipantRepository,
        clock: Clock,
    ): GuestOwnershipProofPort = GuestCookieOwnershipProofAdapter(sessions, credentials, participants, clock)

    @Bean
    fun participantOwnershipResolver(
        accounts: CurrentAccountPort,
        proofs: GuestOwnershipProofPort,
        participants: ParticipantRepository,
    ): ParticipantOwnershipResolver = ParticipantOwnershipResolver(accounts, proofs, participants)
}
