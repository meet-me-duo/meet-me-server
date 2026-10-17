package com.meetme.server.participant.application.service

import com.meetme.server.participant.application.port.output.GuestPasswordPort
import com.meetme.server.participant.domain.GuestReaccessDecision
import com.meetme.server.participant.domain.GuestReaccessProof
import com.meetme.server.participant.domain.GuestReaccessRecord

class GuestReaccessPolicy(
    private val passwords: GuestPasswordPort,
) {
    fun evaluate(
        record: GuestReaccessRecord?,
        proof: GuestReaccessProof,
    ): GuestReaccessDecision {
        if (record == null || record.roomId != proof.roomId || record.participantId != proof.participantId) {
            return GuestReaccessDecision.DENIED
        }
        if (record.ownerSessionId == null) return GuestReaccessDecision.DENIED
        if (record.ownerSessionId == proof.activeSessionId) return GuestReaccessDecision.OWNER
        if (record.memberLinked) return GuestReaccessDecision.DENIED
        if (record.passwordHash == null) return GuestReaccessDecision.REPLACE_ONLY
        val password = proof.rawPassword ?: return GuestReaccessDecision.DENIED
        return if (passwords.verify(password, record.passwordHash)) {
            GuestReaccessDecision.VERIFIED_GUEST
        } else {
            GuestReaccessDecision.DENIED
        }
    }
}
