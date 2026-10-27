package com.meetme.server.participant.application.port.output

/** Optional guest edit password. Null is handled by the use case, never by the hasher. */
interface GuestPasswordPort {
    fun hash(rawPassword: String): String

    fun verify(
        rawPassword: String,
        passwordHash: String,
    ): Boolean
}
