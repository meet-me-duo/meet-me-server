package com.meetme.server.participant.adapter.output.persistence

import org.komapper.annotation.KomapperEntityDef
import org.komapper.annotation.KomapperId
import org.komapper.annotation.KomapperTable
import java.time.OffsetDateTime
import java.util.UUID

data class GuestSessionRecord(
    val id: UUID,
    val credentialDigest: String,
    val expiresAt: OffsetDateTime,
    val revokedAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
)

@KomapperEntityDef(GuestSessionRecord::class)
@KomapperTable("guest_browser_sessions")
data class GuestSessionRecordDef(
    @KomapperId val id: Nothing,
)

data class ParticipantRecord(
    val id: UUID,
    val roomId: UUID,
    val guestSessionId: UUID,
    val displayName: String,
    val role: String,
    val joinedAt: OffsetDateTime,
)

@KomapperEntityDef(ParticipantRecord::class)
@KomapperTable("participants")
data class ParticipantRecordDef(
    @KomapperId val id: Nothing,
)
