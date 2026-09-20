package com.meetme.server.participant.adapter.output.persistence

import com.meetme.server.participant.domain.GuestSession
import com.meetme.server.participant.domain.Participant
import com.meetme.server.participant.domain.ParticipantDisplayName
import com.meetme.server.participant.domain.ParticipantRole
import com.meetme.server.shared.domain.GuestSessionId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import java.time.ZoneOffset

object PersistenceMappers {
    fun toRecord(domain: GuestSession): GuestSessionRecord =
        GuestSessionRecord(
            domain.id.value,
            domain.credentialDigest,
            domain.expiresAt.atOffset(ZoneOffset.UTC),
            domain.revokedAt?.atOffset(ZoneOffset.UTC),
            domain.createdAt.atOffset(ZoneOffset.UTC),
        )

    fun toDomain(record: GuestSessionRecord): GuestSession =
        GuestSession(
            GuestSessionId(record.id),
            record.credentialDigest,
            record.expiresAt.toInstant(),
            record.revokedAt?.toInstant(),
            record.createdAt.toInstant(),
        )

    fun toRecord(domain: Participant): ParticipantRecord =
        ParticipantRecord(
            domain.id.value,
            domain.roomId.value,
            domain.guestSessionId.value,
            domain.displayName.value,
            domain.role.name,
            domain.joinedAt.atOffset(ZoneOffset.UTC),
        )

    fun toDomain(record: ParticipantRecord): Participant =
        Participant.restore(
            ParticipantId(record.id),
            MeetingRoomId(record.roomId),
            GuestSessionId(record.guestSessionId),
            ParticipantDisplayName.of(record.displayName),
            ParticipantRole.valueOf(record.role),
            record.joinedAt.toInstant(),
        )
}
