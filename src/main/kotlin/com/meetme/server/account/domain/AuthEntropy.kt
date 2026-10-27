package com.meetme.server.account.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal object AuthEntropy {
    private val random = SecureRandom()

    fun token(): String = encode(ByteArray(32).also(random::nextBytes))

    fun digest(value: String): String = encode(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    fun same(
        left: String,
        right: String,
    ): Boolean = MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))

    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
}
