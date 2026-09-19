package com.meetme.server.participant.adapter.output.persistence

import com.meetme.server.participant.application.port.output.GuestSessionRepository
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.participant.domain.GuestSession
import com.meetme.server.participant.domain.Participant
import com.meetme.server.shared.domain.GuestSessionId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import org.komapper.core.dsl.Meta
import org.komapper.core.dsl.QueryDsl
import org.komapper.core.dsl.query.firstOrNull
import org.komapper.jdbc.JdbcDatabase
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class KomapperGuestSessionRepository(
    private val database: JdbcDatabase,
    private val jdbcTemplate: JdbcTemplate,
) : GuestSessionRepository {
    override fun insert(session: GuestSession) {
        database.runQuery { QueryDsl.insert(Meta.guestSessionRecord).single(PersistenceMappers.toRecord(session)) }
    }

    override fun findById(id: GuestSessionId): GuestSession? =
        database
            .runQuery {
                QueryDsl
                    .from(Meta.guestSessionRecord)
                    .where { Meta.guestSessionRecord.id eq id.value }
                    .firstOrNull()
            }?.let(PersistenceMappers::toDomain)

    override fun findByCredentialDigest(credentialDigest: String): GuestSession? =
        database
            .runQuery {
                QueryDsl
                    .from(Meta.guestSessionRecord)
                    .where { Meta.guestSessionRecord.credentialDigest eq credentialDigest }
                    .firstOrNull()
            }?.let(PersistenceMappers::toDomain)

    override fun findByCredentialDigestForUpdate(credentialDigest: String): GuestSession? {
        val id =
            jdbcTemplate
                .query(
                    "SELECT id FROM guest_browser_sessions WHERE credential_digest = ? FOR UPDATE",
                    { resultSet, _ -> resultSet.getObject("id", java.util.UUID::class.java) },
                    credentialDigest,
                ).firstOrNull() ?: return null
        return findById(GuestSessionId(id))
    }
}

@Repository
class KomapperParticipantRepository(
    private val database: JdbcDatabase,
    private val jdbcTemplate: JdbcTemplate,
) : ParticipantRepository {
    override fun insert(participant: Participant) {
        database.runQuery { QueryDsl.insert(Meta.participantRecord).single(PersistenceMappers.toRecord(participant)) }
    }

    override fun findById(id: ParticipantId): Participant? =
        database
            .runQuery {
                QueryDsl
                    .from(Meta.participantRecord)
                    .where { Meta.participantRecord.id eq id.value }
                    .firstOrNull()
            }?.let(PersistenceMappers::toDomain)

    override fun findByRoomAndGuestSession(
        roomId: MeetingRoomId,
        guestSessionId: GuestSessionId,
    ): Participant? =
        database
            .runQuery {
                QueryDsl
                    .from(Meta.participantRecord)
                    .where { Meta.participantRecord.roomId eq roomId.value }
                    .where { Meta.participantRecord.guestSessionId eq guestSessionId.value }
                    .firstOrNull()
            }?.let(PersistenceMappers::toDomain)

    override fun countByRoom(roomId: MeetingRoomId): Int =
        requireNotNull(
            jdbcTemplate.queryForObject("SELECT count(*) FROM participants WHERE room_id = ?", Int::class.java, roomId.value),
        )
}
