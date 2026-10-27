package com.meetme.server.participant.application.service

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleException
import com.meetme.server.participant.application.port.output.GuestOwnershipProof
import com.meetme.server.participant.application.port.output.GuestOwnershipProofPort
import com.meetme.server.participant.application.port.output.GuestProofLevel
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.participant.domain.Participant
import com.meetme.server.participant.domain.ParticipantDisplayName
import com.meetme.server.participant.domain.ParticipantRole
import com.meetme.server.shared.domain.GuestSessionId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/** #109/#108 shared boundary: a replacement-only grant never becomes read, host or claim authority. */
class ParticipantOwnershipResolverContractTest {
    private lateinit var proofs: GuestOwnershipProofPort
    private lateinit var participants: ParticipantRepository
    private lateinit var resolver: ParticipantOwnershipResolver
    private var account: AccountPrincipal? = null
    private val room = MeetingRoomId(UUID.randomUUID())
    private val guest = guest(room)
    private val principal = AccountPrincipal(UUID.randomUUID(), "member account")

    @BeforeEach
    fun setup() {
        account = null
        proofs = mock(GuestOwnershipProofPort::class.java)
        participants = mock(ParticipantRepository::class.java)
        resolver = ParticipantOwnershipResolver(CurrentAccountPort { account }, proofs, participants)
        `when`(participants.findById(guest.id)).thenReturn(guest)
    }

    @ParameterizedTest
    @CsvSource(
        "READ_OWN, VERIFIED_GUEST, ALLOW",
        "REPLACE_OWN, VERIFIED_GUEST, ALLOW",
        "HOST_COMMAND, VERIFIED_GUEST, ACCOUNT_REQUIRED",
        "CLAIM_ACCOUNT, VERIFIED_GUEST, ACCOUNT_REQUIRED",
        "READ_OWN, REPLACE_ONLY, GUEST_PROOF_INSUFFICIENT",
        "REPLACE_OWN, REPLACE_ONLY, ALLOW",
        "HOST_COMMAND, REPLACE_ONLY, ACCOUNT_REQUIRED",
        "CLAIM_ACCOUNT, REPLACE_ONLY, ACCOUNT_REQUIRED",
    )
    fun `guest proof operations follow the least authority matrix`(
        operation: OwnershipOperation,
        level: GuestProofLevel,
        expected: String,
    ) {
        proof(level)
        if (expected == "ALLOW") {
            assertEquals(guest, resolver.resolve(room, RAW, operation))
        } else {
            denied(RoomLifecycleErrorCode.valueOf(expected)) { resolver.resolve(room, RAW, operation) }
        }
    }

    @ParameterizedTest
    @EnumSource(value = OwnershipOperation::class, names = ["READ_OWN", "REPLACE_OWN", "HOST_COMMAND"])
    fun `authenticated account owner wins over any guest proof`(operation: OwnershipOperation) {
        account = principal
        val owned = member(role = ParticipantRole.HOST)
        `when`(participants.findByRoomAndUser(room, principal.accountId)).thenReturn(owned)
        proof(GuestProofLevel.REPLACE_ONLY)
        assertEquals(owned, resolver.resolve(room, RAW, operation))
        verifyNoInteractions(proofs)
    }

    @ParameterizedTest
    @EnumSource(value = OwnershipOperation::class, names = ["READ_OWN", "REPLACE_OWN"])
    fun `account without participation cannot fall back to a guest proof`(operation: OwnershipOperation) {
        account = principal
        proof(GuestProofLevel.VERIFIED_GUEST)
        denied(RoomLifecycleErrorCode.PARTICIPANT_REQUIRED) { resolver.resolve(room, RAW, operation) }
        verifyNoInteractions(proofs)
    }

    @Test
    fun `authenticated member never gains host authority through guest host proof`() {
        account = principal
        `when`(participants.findByRoomAndUser(room, principal.accountId)).thenReturn(member())
        proof(GuestProofLevel.VERIFIED_GUEST)
        denied(RoomLifecycleErrorCode.HOST_PERMISSION_REQUIRED) { resolver.resolve(room, RAW, OwnershipOperation.HOST_COMMAND) }
        verifyNoInteractions(proofs)
    }

    @ParameterizedTest
    @EnumSource(value = OwnershipOperation::class, names = ["READ_OWN", "REPLACE_OWN"])
    fun `a replacement proof can be supplied independently of the opaque cookie`(operation: OwnershipOperation) {
        val proof = GuestOwnershipProof(room, guest.id, GuestProofLevel.REPLACE_ONLY)
        `when`(proofs.resolve(room, null, false)).thenReturn(proof)
        if (operation == OwnershipOperation.REPLACE_OWN) {
            assertEquals(guest, resolver.resolve(room, null, operation))
        } else {
            denied(RoomLifecycleErrorCode.GUEST_PROOF_INSUFFICIENT) { resolver.resolve(room, null, operation) }
        }
    }

    @ParameterizedTest
    @CsvSource("READ_OWN", "REPLACE_OWN")
    fun `missing or rejected proof never grants private access`(operation: OwnershipOperation) {
        denied(RoomLifecycleErrorCode.GUEST_SESSION_REQUIRED) { resolver.resolve(room, null, operation) }
        denied(RoomLifecycleErrorCode.GUEST_SESSION_INVALID) { resolver.resolve(room, RAW, operation) }
    }

    @ParameterizedTest
    @CsvSource("proof room", "loaded room", "loaded id", "account owned", "missing participant")
    fun `proof must bind the requested room to the persisted guest owned participant`(mismatch: String) {
        val anotherRoom = MeetingRoomId(UUID.randomUUID())
        val proof = GuestOwnershipProof(if (mismatch == "proof room") anotherRoom else room, guest.id, GuestProofLevel.VERIFIED_GUEST)
        `when`(proofs.resolve(room, RAW, false)).thenReturn(proof)
        val row =
            when (mismatch) {
                "loaded room" -> guest(anotherRoom)
                "loaded id" -> guest(room)
                "account owned" -> Participant.account(guest.id, room, principal.accountId, guest.displayName, ParticipantRole.MEMBER, NOW)
                "missing participant" -> null
                else -> guest
            }
        `when`(participants.findById(guest.id)).thenReturn(row)
        denied(RoomLifecycleErrorCode.GUEST_PROOF_INSUFFICIENT) { resolver.resolve(room, RAW, OwnershipOperation.READ_OWN) }
    }

    @ParameterizedTest
    @EnumSource(GuestProofLevel::class)
    fun `explicit claim requires verified proof even with authenticated room participation`(level: GuestProofLevel) {
        account = principal
        `when`(participants.findByRoomAndUser(room, principal.accountId)).thenReturn(member())
        proof(level, lock = true)
        if (level == GuestProofLevel.VERIFIED_GUEST) {
            assertEquals(guest, resolver.requireVerifiedGuestForClaim(room, RAW))
            assertEquals(guest, resolver.resolve(room, RAW, OwnershipOperation.CLAIM_ACCOUNT))
            verify(proofs, org.mockito.Mockito.times(2)).resolve(room, RAW, true)
        } else {
            denied(RoomLifecycleErrorCode.GUEST_PROOF_INSUFFICIENT) { resolver.requireVerifiedGuestForClaim(room, RAW) }
            denied(RoomLifecycleErrorCode.GUEST_PROOF_INSUFFICIENT) { resolver.resolve(room, RAW, OwnershipOperation.CLAIM_ACCOUNT) }
        }
        verify(participants, never()).findByRoomAndUser(room, principal.accountId)
    }

    @Test
    fun `claim without account fails before the proof is consulted`() {
        proof(GuestProofLevel.VERIFIED_GUEST, lock = true)
        denied(RoomLifecycleErrorCode.ACCOUNT_REQUIRED) { resolver.requireVerifiedGuestForClaim(room, RAW) }
        verifyNoInteractions(proofs)
    }

    private fun proof(
        level: GuestProofLevel,
        lock: Boolean = false,
    ) {
        `when`(proofs.resolve(room, RAW, lock)).thenReturn(GuestOwnershipProof(room, guest.id, level))
    }

    private fun member(role: ParticipantRole = ParticipantRole.MEMBER) =
        Participant.account(
            ParticipantId(UUID.randomUUID()),
            room,
            principal.accountId,
            ParticipantDisplayName.of("account member"),
            role,
            NOW,
        )

    private fun guest(room: MeetingRoomId) =
        Participant.member(
            ParticipantId(UUID.randomUUID()),
            room,
            GuestSessionId(UUID.randomUUID()),
            ParticipantDisplayName.of("guest"),
            NOW,
        )

    private fun denied(
        code: RoomLifecycleErrorCode,
        action: () -> Unit,
    ) {
        assertEquals(code, assertThrows<RoomLifecycleException> { action() }.code)
    }

    companion object {
        private const val RAW = "synthetic-guest-proof"
        private val NOW = Instant.parse("2026-10-17T12:00:00Z")
    }
}
