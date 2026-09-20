package com.meetme.server.meetingroom.adapter.output.security

import com.meetme.server.meetingroom.application.port.output.InviteCodeGenerator
import com.meetme.server.meetingroom.domain.InviteCode
import java.security.SecureRandom

class SecureInviteCodeGenerator(
    private val secureRandom: SecureRandom,
) : InviteCodeGenerator {
    override fun next(): InviteCode {
        val entropy = ByteArray(16)
        secureRandom.nextBytes(entropy)
        return InviteCode.fromEntropy(entropy)
    }
}
