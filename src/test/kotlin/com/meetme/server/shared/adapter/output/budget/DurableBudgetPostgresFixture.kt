package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.shared.adapter.output.persistence.JdbcDurableAiBudgetRepository
import com.meetme.server.shared.application.port.output.AiBudgetAuthorityRegistryPort
import com.meetme.server.shared.application.port.output.AiBudgetBillRequest
import com.meetme.server.shared.application.port.output.AiBudgetDispatchPermit
import com.meetme.server.shared.application.port.output.AiBudgetDispatchRequest
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionFencePort
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetObservation
import com.meetme.server.shared.application.port.output.AiBudgetQuoteRequest
import com.meetme.server.shared.application.port.output.AiBudgetReservation
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiBudgetSettlementRequest
import com.meetme.server.shared.application.port.output.AiBudgetUnit
import com.meetme.server.shared.application.port.output.AiCurrentBudgetExecution
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiReservedPhysicalCall
import com.meetme.server.shared.application.port.output.AiRoomBudgetSnapshot
import com.meetme.server.shared.application.port.output.AiVerifiedBudgetQuote
import com.meetme.server.shared.application.port.output.AiVerifiedCompleteBill
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.shared.application.service.DurableAiBudgetService
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertNotNull

/** Test-only authority/coverage: these arbitrary TEST_SUBUNIT receipts are NOT production billing proof. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class DurableBudgetPostgresFixture {
    private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }
    protected val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    protected val jdbc = JdbcTemplate(dataSource)
    protected val transaction = TransactionTemplate(DataSourceTransactionManager(dataSource))
    protected val clock = MutableBudgetClock(NOW)
    protected val fence = SyntheticFence(jdbc)
    private val authority = SyntheticAuthority()
    private val quoteByCall = ConcurrentHashMap<AiPhysicalCallSpec, AiVerifiedBudgetQuote>()

    @BeforeEach
    fun resetBudgetFixture() {
        Flyway
            .configure()
            .dataSource(dataSource)
            .load()
            .migrate()
        jdbc.execute("TRUNCATE meeting_rooms CASCADE")
        clock.set(NOW)
        authority.quotes.clear()
        authority.bills.clear()
        quoteByCall.clear()
        fence.clear()
    }

    @AfterAll
    fun stopBudgetFixture() {
        postgres.stop()
    }

    protected fun adapter(): DurableAiBudgetPort {
        val service = DurableAiBudgetService(JdbcDurableAiBudgetRepository(jdbc), clock, fence, authority)
        return object : DurableAiBudgetPort {
            override fun reserve(request: AiBudgetReserveRequest): AiBudgetReservation? = transaction.execute { service.reserve(request) }

            override fun claim(request: AiBudgetDispatchRequest): AiBudgetDispatchPermit? = transaction.execute { service.claim(request) }

            override fun observe(observation: AiBudgetObservation): Boolean = transaction.execute { service.observe(observation) }!!

            override fun settle(request: AiBudgetSettlementRequest): Boolean = transaction.execute { service.settle(request) }!!

            override fun snapshot(roomId: UUID): AiRoomBudgetSnapshot? = transaction.execute { service.snapshot(roomId) }
        }
    }

    protected fun room(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO meeting_rooms (
                id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
                search_range_source,manual_only,collection_status,created_at,version
            ) VALUES (?,?,'Synthetic accounting test','EITHER','Asia/Seoul',DATE '2026-10-18',DATE '2026-10-20',
                'HOST_SPECIFIED',true,'COLLECTING',?,0)
            """.trimIndent(),
            id,
            id.toString().replace("-", "").take(22),
            NOW.atOffset(ZoneOffset.UTC),
        )
        return id
    }

    protected fun ready(cap: BigInteger = bi(100)): UUID {
        val id = room()
        val epoch = UUID.randomUUID()
        // Explicit synthetic coverage+policy fixture; migration date alone must never confer this authority.
        jdbc.update(
            """
            INSERT INTO ai_room_budgets (
                room_id,state,epoch_id,unit_code,unit_scale,cap,settled,held,version,policy_fingerprint,coverage_started_at
            ) VALUES (?,'READY',?,'TEST_SUBUNIT',0,?,0,0,1,?,?)
            ON CONFLICT (room_id) DO UPDATE SET state='READY',epoch_id=EXCLUDED.epoch_id,unit_code=EXCLUDED.unit_code,
                unit_scale=0,cap=EXCLUDED.cap,settled=0,held=0,policy_fingerprint=EXCLUDED.policy_fingerprint,
                coverage_started_at=EXCLUDED.coverage_started_at
            """.trimIndent(),
            id,
            epoch,
            java.math.BigDecimal(cap),
            hash("synthetic-policy"),
            NOW.atOffset(ZoneOffset.UTC),
        )
        fence.epochs[id] = epoch
        return id
    }

    protected fun request(
        room: UUID,
        operation: AiPhysicalOperation = AiPhysicalOperation.PRECHECK_CLASSIFICATION,
    ): AiBudgetReserveRequest {
        val final = operation == AiPhysicalOperation.FINAL_PRIMARY || operation == AiPhysicalOperation.FINAL_FALLBACK
        val execution =
            AiBudgetExecution(
                room,
                if (final) AiBudgetExecutionKind.FINAL_ANALYSIS else AiBudgetExecutionKind.PRECHECK,
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                1,
                hash("source"),
                hash("context"),
            )
        return AiBudgetReserveRequest(
            execution,
            listOf(
                AiPhysicalCallSpec(
                    operation,
                    1,
                    hash(UUID.randomUUID().toString()),
                    hash("synthetic-profile"),
                    if (final)UUID.randomUUID() else null,
                ),
            ),
        )
    }

    protected fun authorize(
        request: AiBudgetReserveRequest,
        maximum: Int,
    ) = authorize(request, bi(maximum))

    protected fun authorize(
        request: AiBudgetReserveRequest,
        maximum: BigInteger,
    ) {
        fence.allow(request.execution)
        request.calls.forEach { call ->
            val quote =
                AiVerifiedBudgetQuote(
                    hash("quote:${request.execution.executionId}:$call:$maximum"),
                    call.profileFingerprint,
                    call.wholePayloadFingerprint,
                    hash("synthetic-policy"),
                    hash("synthetic-evidence"),
                    maximum,
                    UNIT,
                    NOW.plusSeconds(60),
                )
            authority.quotes[call] = quote
            quoteByCall[call] = quote
        }
    }

    protected fun alterQuote(
        call: AiPhysicalCallSpec,
        transform: (AiVerifiedBudgetQuote) -> AiVerifiedBudgetQuote,
    ) {
        val current = assertNotNull(authority.quotes[call])
        val changed = transform(current)
        authority.quotes[call] = changed
        quoteByCall[call] = changed
    }

    protected fun reserve(request: AiBudgetReserveRequest): AiBudgetReservation =
        assertNotNull(
            assertDoesNotThrow {
                adapter().reserve(request)
            },
            "Synthetic tracked policy and exact vetted quote should admit below-cap exposure",
        )

    protected fun claim(
        request: AiBudgetReserveRequest,
        call: AiReservedPhysicalCall,
    ): AiBudgetDispatchPermit =
        assertNotNull(
            assertDoesNotThrow {
                adapter().claim(AiBudgetDispatchRequest(call.reservationId, request.execution, call.call))
            },
        )

    protected fun snapshot(room: UUID): AiRoomBudgetSnapshot = assertNotNull(assertDoesNotThrow { adapter().snapshot(room) })

    protected fun bill(
        call: AiReservedPhysicalCall,
        amount: Int,
        reference: String,
    ) = bill(call, bi(amount), reference)

    protected fun bill(
        call: AiReservedPhysicalCall,
        amount: BigInteger,
        reference: String,
    ) {
        val quote = assertNotNull(quoteByCall[call.call])
        authority.bills[reference] = AiVerifiedCompleteBill(quote.quoteFingerprint, hash("bill:$reference"), amount, UNIT)
    }

    protected fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    protected fun bi(amount: Int): BigInteger = BigInteger.valueOf(amount.toLong())

    protected class SyntheticFence(
        private val jdbc: JdbcTemplate,
    ) : AiBudgetExecutionFencePort {
        val epochs = ConcurrentHashMap<UUID, UUID>()
        private val admitted = ConcurrentHashMap<UUID, AiBudgetExecution>()
        var deadline: Instant = NOW.plusSeconds(15)

        fun clear() {
            epochs.clear()
            admitted.clear()
            deadline = NOW.plusSeconds(15)
        }

        fun allow(execution: AiBudgetExecution) {
            admitted[execution.executionId] = execution
        }

        fun revoke(execution: AiBudgetExecution) {
            admitted.remove(execution.executionId)
        }

        override fun lockAndValidate(
            request: AiBudgetReserveRequest,
            at: Instant,
        ): AiCurrentBudgetExecution? {
            jdbc
                .query(
                    "SELECT id FROM meeting_rooms WHERE id = ? FOR UPDATE",
                    { rs, _ -> rs.getObject(1, UUID::class.java) },
                    request.execution.roomId,
                ).singleOrNull()
                ?: return null
            if (admitted[request.execution.executionId] != request.execution || !at.isBefore(deadline)) return null
            return AiCurrentBudgetExecution(epochs[request.execution.roomId] ?: return null, deadline)
        }
    }

    protected class MutableBudgetClock(
        initial: Instant,
    ) : Clock() {
        private val current = AtomicReference(initial)

        fun set(at: Instant) {
            current.set(at)
        }

        fun advance(duration: Duration) {
            current.updateAndGet { it.plus(duration) }
        }

        override fun instant(): Instant = current.get()

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    private class SyntheticAuthority : AiBudgetAuthorityRegistryPort {
        val quotes = ConcurrentHashMap<AiPhysicalCallSpec, AiVerifiedBudgetQuote>()
        val bills = ConcurrentHashMap<String, AiVerifiedCompleteBill>()

        override fun quote(request: AiBudgetQuoteRequest): AiVerifiedBudgetQuote? = quotes[request.call]

        override fun completeBill(request: AiBudgetBillRequest): AiVerifiedCompleteBill? = bills[request.evidenceReference]
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-10-18T00:00:00Z")
        private val UNIT = AiBudgetUnit("TEST_SUBUNIT", 0)
    }
}
