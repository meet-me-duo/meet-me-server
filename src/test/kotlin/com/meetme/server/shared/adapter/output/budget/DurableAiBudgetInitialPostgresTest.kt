package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.shared.adapter.output.persistence.JdbcDurableAiBudgetRepository
import com.meetme.server.shared.application.port.output.AiBudgetAuthorityRegistryPort
import com.meetme.server.shared.application.port.output.AiBudgetBillRequest
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionFencePort
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetQuoteRequest
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiBudgetUnit
import com.meetme.server.shared.application.port.output.AiCurrentBudgetExecution
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiVerifiedBudgetQuote
import com.meetme.server.shared.application.port.output.AiVerifiedCompleteBill
import com.meetme.server.shared.application.port.output.AiVerifiedFinalReserve
import com.meetme.server.shared.application.service.DurableAiBudgetService
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DB01/DB20: real V16 room, missing budget/policy never lazily grants capacity. No V17 SQL fixture is assumed. */
class DurableAiBudgetInitialPostgresTest {
    private val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    private val jdbc = JdbcTemplate(dataSource)
    private val transaction = TransactionTemplate(DataSourceTransactionManager(dataSource))
    private val authority =
        object : AiBudgetAuthorityRegistryPort {
            override fun quote(request: AiBudgetQuoteRequest): AiVerifiedBudgetQuote? = null

            override fun completeBill(request: AiBudgetBillRequest): AiVerifiedCompleteBill? = null

            // Explicit synthetic zero floor preserves this fixture's unrelated existing contract.
            override fun protectedFinalReserve(request: AiBudgetQuoteRequest): AiVerifiedFinalReserve =
                AiVerifiedFinalReserve(request.policyFingerprint, BigInteger.ZERO, AiBudgetUnit("TEST_SUBUNIT", 0), NOW.plusSeconds(60))
        }
    private val fence =
        object : AiBudgetExecutionFencePort {
            override fun lockAndValidate(
                request: AiBudgetReserveRequest,
                at: Instant,
            ): AiCurrentBudgetExecution? {
                val room =
                    jdbc
                        .query(
                            "SELECT id FROM meeting_rooms WHERE id = ? FOR UPDATE",
                            { rs, _ -> rs.getObject("id", UUID::class.java) },
                            request.execution.roomId,
                        ).singleOrNull() ?: return null
                return AiCurrentBudgetExecution(room, NOW.plusSeconds(15))
            }
        }
    private val service =
        DurableAiBudgetService(
            JdbcDurableAiBudgetRepository(jdbc),
            Clock.fixed(NOW, ZoneOffset.UTC),
            fence,
            authority,
        )

    @BeforeEach
    fun resetV16Rooms() {
        // Intentionally only the pre-existing migration lineage is required for this initial RED cycle.
        Flyway
            .configure()
            .dataSource(dataSource)
            .load()
            .migrate()
        jdbc.execute("TRUNCATE meeting_rooms CASCADE")
    }

    @Test
    fun `DB01 nonexistent room reserve and snapshot deny without creating a scope`() {
        val room = UUID.randomUUID()
        val request = request(room)

        assertNull(assertDoesNotThrow { transaction.execute { service.reserve(request) } })
        assertNull(assertDoesNotThrow { transaction.execute { service.snapshot(room) } })
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM meeting_rooms", Int::class.java))
    }

    @Test
    fun `DB01 real room without any approved policy cannot reserve even with a valid synthetic fence`() {
        val room = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO meeting_rooms (
                id, invite_code, purpose, meeting_mode, time_zone_id, search_start_date, search_end_date,
                search_range_source, manual_only, collection_status, created_at, version
            ) VALUES (?, ?, 'Synthetic accounting test', 'EITHER', 'Asia/Seoul',
                DATE '2026-10-18', DATE '2026-10-20', 'HOST_SPECIFIED', true, 'COLLECTING', ?, 0)
            """.trimIndent(),
            room,
            room.toString().replace("-", "").take(22),
            NOW.atOffset(ZoneOffset.UTC),
        )

        assertNull(assertDoesNotThrow { transaction.execute { service.reserve(request(room)) } })
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM meeting_rooms WHERE id = ?", Int::class.java, room))
    }

    private fun request(room: UUID) =
        AiBudgetReserveRequest(
            AiBudgetExecution(
                room,
                AiBudgetExecutionKind.PRECHECK,
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                1,
                "1".repeat(64),
                "2".repeat(64),
            ),
            listOf(AiPhysicalCallSpec(AiPhysicalOperation.PRECHECK_CLASSIFICATION, 1, "3".repeat(64), "4".repeat(64))),
        )

    companion object {
        private val NOW = Instant.parse("2026-10-18T00:00:00Z")
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @AfterAll
        fun stopPostgres() {
            postgres.stop()
        }
    }
}
