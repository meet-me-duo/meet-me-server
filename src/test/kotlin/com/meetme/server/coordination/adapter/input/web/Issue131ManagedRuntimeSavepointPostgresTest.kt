package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.shared.application.port.output.NewRoomAiBudgetPort
import com.meetme.server.shared.application.port.output.NewRoomAiBudgetUnavailableException
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real manager savepoints and public INSERT/initialize behavior; no access to private receipt representation.
 * Legacy financial-component assertions are retained; production admission is verified by Issue144 graphs.
 */
@Import(LegacyMonetaryComponentTestConfiguration::class, DurableAnalysisInvocationPostgresTest.ControlledTime::class)
class Issue131ManagedRuntimeSavepointPostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var rooms: MeetingRoomRepository

    @Autowired private lateinit var bootstrap: NewRoomAiBudgetPort

    @Test
    fun `RB03 INSERT rolled back to savepoint cannot authorize raw same-ID insertion later in that transaction`() {
        val room = room()
        tx().executeWithoutResult { status ->
            val point = status.createSavepoint()
            assertTrue(rooms.insertIfInviteAvailable(room))
            status.rollbackToSavepoint(point)
            status.releaseSavepoint(point)
            assertEquals(0, count(room))
            rawInsert(room)
            assertTrue(denied(room), "Rolled-back INSERT authority must not survive its database savepoint")
            status.setRollbackOnly()
        }
        assertEquals(0, count(room))
    }

    @Test
    fun `RB03 INSERT before savepoint remains eligible when only later INSERT work is rolled back`() {
        val outer = room()
        val later = room()
        assertDoesNotThrow {
            tx().executeWithoutResult { status ->
                assertTrue(rooms.insertIfInviteAvailable(outer))
                val point = status.createSavepoint()
                assertTrue(rooms.insertIfInviteAvailable(later))
                status.rollbackToSavepoint(point)
                status.releaseSavepoint(point)
                assertEquals(0, count(later))
                bootstrap.initialize(outer.id.value, outer.createdAt)
            }
        }
        assertEquals("READY", row(outer)["state"])
        assertEquals(0, count(later))
    }

    private fun denied(room: MeetingRoom): Boolean =
        try {
            bootstrap.initialize(room.id.value, room.createdAt)
            false
        } catch (_: NewRoomAiBudgetUnavailableException) {
            true
        }

    private fun tx(requiresNew: Boolean = false): TransactionTemplate =
        TransactionTemplate(transactionManager).apply {
            if (requiresNew) propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    private fun room(): MeetingRoom {
        val id = UUID.randomUUID()
        return MeetingRoom.create(
            MeetingRoomId(id),
            InviteCode.of(id.toString().replace("-", "").take(22)),
            "synthetic INSERT",
            MeetingMode.REMOTE,
            MeetingTimeZone.of("Asia/Seoul"),
            SearchDateRange.explicit(LocalDate.parse("2026-10-07"), LocalDate.parse("2026-10-12")),
            ClosurePolicy.of(expectedParticipants = 2),
            NOW,
        )
    }

    private fun rawInsert(room: MeetingRoom) {
        jdbc.update(
            "INSERT INTO meeting_rooms(id,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,search_range_source,expected_participants,manual_only,collection_status,created_at,invite_code) VALUES (?,'raw synthetic','REMOTE','Asia/Seoul','2026-10-07','2026-10-12','HOST_SPECIFIED',2,false,'COLLECTING',?,?)",
            room.id.value,
            room.createdAt.atOffset(ZoneOffset.UTC),
            room.inviteCode.value,
        )
    }

    private fun row(room: MeetingRoom): Map<String, Any?> = jdbc.queryForMap("SELECT * FROM ai_room_budgets WHERE room_id=?", room.id.value)

    private fun count(room: MeetingRoom): Int =
        assertNotNull(jdbc.queryForObject("SELECT count(*) FROM meeting_rooms WHERE id=?", Int::class.java, room.id.value))

    companion object {
        private val NOW = Instant.parse("2026-10-07T00:00:00Z")
        private val profiles =
            listOf(
                "c8d01d4a04aa077e0488400dc3b4791753b8428db48c6712aa78f903608a4dfc",
                "658a65563b34d43f854cc47dcd93d274d23789745e718b5f7ef49e1b59e8e307",
                "86e4593b78ec903e1c9b4bbbffe204d2b8343e1752d2b6353703ce3fb9b65714",
                "0a3fc94ba00d368b4e7c9a41d0b231faab5058aa893dc11082ef786fc0a08d04",
                "9c5021916c80178f58d235e55bbbad4889875560b710a505fbde5dedd1a824ab",
            )
        private val operations =
            listOf("PRECHECK_CLASSIFICATION", "PRECHECK_COUNT", "PRECHECK_GENERATION", "FINAL_PRIMARY", "FINAL_FALLBACK")
        private val directory = Files.createTempDirectory("issue131-savepoint-policy-")
        private val rows =
            operations
                .mapIndexed { index, name ->
                    """{"operation":"$name","profileFingerprint":"${profiles[index]}","evidenceFingerprint":"${hash(
                        "synthetic:$name",
                    )}","maximumAmount":"${index + 3}","validUntil":"2026-10-07T01:00:00Z"}"""
                }.joinToString(",")
        private val json =
            """{"protocol":"meetme-managed-ai-policy-v1","state":"CONFIGURED",""" +
                """"unit":{"code":"TEST_SUBUNIT","scale":0},"roomCap":"1000","minimumConfidence":0.73,"operations":[$rows]}"""
        private val policy = Files.writeString(directory.resolve("policy.json"), json)
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        private fun hash(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
                "%02x".format(it)
            }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registerCorrectionDatabase(registry, postgres)
            registry.add("meetme.precheck.provider.enabled") { true }
            registry.add("meetme.precheck.decision-routing.enabled") { true }
            registry.add("meetme.precheck.classifier.enabled") { true }
            registry.add("meetme.precheck.classifier.minimum-confidence") { 0.73 }
            registry.add("meetme.ai.budget.enabled") { true }
            registry.add("meetme.ai.budget.policy-path") { policy.toString() }
            registry.add("meetme.ai.budget.policy-sha256") { hash(json) }
        }

        @JvmStatic @AfterAll
        fun stopFixture() = postgres.stop()
    }
}
