package com.meetme.server.participant.adapter.output.security

import com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleException
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestOwnershipProof
import com.meetme.server.participant.application.port.output.GuestProofLevel
import com.meetme.server.participant.application.port.output.GuestSessionRepository
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.participant.domain.GuestSession
import com.meetme.server.participant.domain.Participant
import com.meetme.server.participant.domain.ParticipantDisplayName
import com.meetme.server.shared.domain.GuestSessionId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GuestCookieOwnershipProofAdapterTest {
    private lateinit var sessions: GuestSessionRepository
    private lateinit var credentials: GuestCredentialPort
    private lateinit var participants: ParticipantRepository
    private lateinit var adapter: GuestCookieOwnershipProofAdapter
    private val room = MeetingRoomId(UUID.randomUUID())
    private val sessionId = GuestSessionId(UUID.randomUUID())
    private val participant = Participant.member(ParticipantId(UUID.randomUUID()), room, sessionId, ParticipantDisplayName.of("guest"), NOW)

    @BeforeEach
    fun setup() {
        sessions = mock(GuestSessionRepository::class.java)
        credentials = mock(GuestCredentialPort::class.java)
        participants = mock(ParticipantRepository::class.java)
        adapter = GuestCookieOwnershipProofAdapter(sessions, credentials, participants, Clock.fixed(NOW, ZoneOffset.UTC))
        `when`(credentials.digest(RAW)).thenReturn(DIGEST)
        `when`(participants.findByRoomAndGuestSession(room, sessionId)).thenReturn(participant)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `active cookie maps only persisted room participant to verified proof with requested lock`(lock: Boolean) {
        val session = session(NOW.plusSeconds(1))
        if (lock) {
            `when`(sessions.findByCredentialDigestForUpdate(DIGEST)).thenReturn(session)
        } else {
            `when`(sessions.findByCredentialDigest(DIGEST)).thenReturn(session)
        }
        assertEquals(GuestOwnershipProof(room, participant.id, GuestProofLevel.VERIFIED_GUEST), adapter.resolve(room, RAW, lock))
        if (lock) {
            verify(sessions).findByCredentialDigestForUpdate(DIGEST)
            verify(sessions, never()).findByCredentialDigest(DIGEST)
        } else {
            verify(sessions).findByCredentialDigest(DIGEST)
            verify(sessions, never()).findByCredentialDigestForUpdate(DIGEST)
        }
    }

    @Test
    fun `missing or blank credential has no proof and never touches persistence`() {
        assertNull(adapter.resolve(room, null, false))
        assertNull(adapter.resolve(room, "   ", true))
        verifyNoInteractions(sessions, participants)
    }

    @Test
    fun `unrecognized digest has no proof`() {
        assertNull(adapter.resolve(room, RAW, false))
        verifyNoInteractions(participants)
    }

    @Test
    fun `expiry boundary and expired or revoked sessions never yield proof`() {
        for (session in listOf(session(NOW), session(NOW.minusNanos(1)), session(NOW.plusSeconds(60), NOW.minusSeconds(1)))) {
            `when`(sessions.findByCredentialDigest(DIGEST)).thenReturn(session)
            assertNull(adapter.resolve(room, RAW, false))
        }
        verifyNoInteractions(participants)
    }

    @Test
    fun `valid cookie without room participation is forbidden instead of inventing proof`() {
        `when`(sessions.findByCredentialDigest(DIGEST)).thenReturn(session(NOW.plusSeconds(60)))
        `when`(participants.findByRoomAndGuestSession(room, sessionId)).thenReturn(null)
        assertEquals(
            RoomLifecycleErrorCode.PARTICIPANT_REQUIRED,
            assertThrows<RoomLifecycleException> { adapter.resolve(room, RAW, false) }.code,
        )
    }

    private fun session(
        expiry: Instant,
        revoked: Instant? = null,
    ) = GuestSession(sessionId, DIGEST, expiry, revoked, NOW.minusSeconds(60))

    companion object {
        private const val RAW = "synthetic-cookie-only"
        private const val DIGEST = "synthetic-cookie-digest"
        private val NOW = Instant.parse("2026-10-17T12:00:00Z")
    }
}
