package com.meetme.server.participant.domain

@JvmInline
value class ParticipantDisplayName private constructor(
    val value: String,
) {
    companion object {
        fun of(value: String): ParticipantDisplayName {
            val normalized = value.trim()
            val length = normalized.codePointCount(0, normalized.length)
            require(length in 1..50) { "Participant display name must contain between 1 and 50 characters" }
            return ParticipantDisplayName(normalized)
        }
    }
}
