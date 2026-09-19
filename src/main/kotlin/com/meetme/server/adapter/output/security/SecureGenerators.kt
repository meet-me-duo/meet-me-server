package com.meetme.server.adapter.output.security

import com.meetme.server.application.port.output.IdGenerator
import com.meetme.server.application.port.output.InviteCodeGenerator
import com.meetme.server.domain.meeting.InviteCode
import java.security.SecureRandom
import java.util.UUID

class SystemIdGenerator : IdGenerator {
    override fun next(): UUID = UUID.randomUUID()
}

class SecureInviteCodeGenerator(
    private val secureRandom: SecureRandom,
) : InviteCodeGenerator {
    override fun next(): InviteCode {
        val entropy = ByteArray(16)
        secureRandom.nextBytes(entropy)
        return InviteCode.fromEntropy(entropy)
    }
}
