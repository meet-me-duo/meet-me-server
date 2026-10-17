package com.meetme.server.precheck.application.port.output

import com.meetme.server.participant.domain.Participant
import java.time.Instant

interface PrecheckOwnershipBindingPort {
    fun capture(
        participant: Participant,
        rawCredential: String?,
    ): String

    fun isActive(
        participant: Participant,
        binding: String,
        at: Instant,
    ): Boolean
}
