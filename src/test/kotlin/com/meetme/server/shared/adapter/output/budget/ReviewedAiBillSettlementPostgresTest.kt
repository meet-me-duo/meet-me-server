package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.config.ApprovedAiOperationPolicy
import com.meetme.server.config.ApprovedAiRuntimePolicy
import com.meetme.server.config.ManagedAiRuntimeSelection
import com.meetme.server.shared.adapter.output.persistence.JdbcDurableAiBudgetRepository
import com.meetme.server.shared.application.port.output.AiBudgetCallState
import com.meetme.server.shared.application.port.output.AiBudgetDispatchPermit
import com.meetme.server.shared.application.port.output.AiBudgetDispatchRequest
import com.meetme.server.shared.application.port.output.AiBudgetObservation
import com.meetme.server.shared.application.port.output.AiBudgetOutcome
import com.meetme.server.shared.application.port.output.AiBudgetReservation
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiBudgetSettlementRequest
import com.meetme.server.shared.application.port.output.AiBudgetUnit
import com.meetme.server.shared.application.port.output.AiBudgetUsage
import com.meetme.server.shared.application.port.output.AiCompleteBillEvidenceCatalogPort
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiReviewedCompleteBillEvidence
import com.meetme.server.shared.application.port.output.AiRoomBudgetSnapshot
import com.meetme.server.shared.application.port.output.AiRoomBudgetState
import com.meetme.server.shared.application.port.output.AiStoredBudgetCall
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.shared.application.port.output.DurableAiBudgetRepository
import com.meetme.server.shared.application.service.AiBudgetSettlementImportResult
import com.meetme.server.shared.application.service.AiBudgetSettlementImportService
import com.meetme.server.shared.application.service.DurableAiBudgetService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR055: reviewed synthetic TEST_SUBUNIT receipts, actual PostgreSQL locks/events and production settle path. */
class ReviewedAiBillSettlementPostgresTest : DurableBudgetPostgresFixture() {
    private val repository get() = JdbcDurableAiBudgetRepository(jdbc)
    private val unit = AiBudgetUnit("TEST_SUBUNIT", 0)

    @Test
    fun `BP01 imported complete bill releases only its own hold and records exact charge once`() {
        val room = ready()
        val call = dispatched(room)
        val evidence = receipt(call, 7)
        val importer = importer(listOf(evidence))
        assertEquals(AiBudgetSettlementImportResult(1, 0), assertDoesNotThrow { importer.importReviewedBills() })
        assertEquals(bi(7), snapshot(room).settled)
        assertEquals(BigInteger.ZERO, snapshot(room).held)
        assertEquals(AiRoomBudgetState.READY, snapshot(room).state)
        assertEquals(AiBudgetCallState.SETTLED, assertNotNull(repository.findCall(call.id)).state)
        assertEquals(1, billEvents(call.id))
    }

    @Test
    fun `BP02 startup replay and concurrent consumers cannot charge or release the same reservation twice`() {
        val room = ready()
        val call = dispatched(room)
        val evidence = receipt(call, 7)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = List(2) { Callable { importer(listOf(evidence)).importReviewedBills() } }
            val results = pool.invokeAll(tasks).map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(listOf(AiBudgetSettlementImportResult(1, 0), AiBudgetSettlementImportResult(1, 0)), results)
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertEquals(AiBudgetSettlementImportResult(1, 0), importer(listOf(evidence)).importReviewedBills())
        assertEquals(bi(7), snapshot(room).settled)
        assertEquals(BigInteger.ZERO, snapshot(room).held)
        assertEquals(1, billEvents(call.id))
    }

    @Test
    fun `BP03 late evidence settles UNKNOWN after deadline without needing current execution permission`() {
        val room = ready()
        val call = dispatched(room)
        assertTrue(
            adapter().observe(
                AiBudgetObservation(call.id, assertNotNull(call.dispatchToken), UUID.randomUUID(), AiBudgetOutcome.TIMED_OUT),
            ),
        )
        assertEquals(AiBudgetCallState.UNKNOWN, assertNotNull(repository.findCall(call.id)).state)
        fence.revoke(call.execution)
        clock.advance(Duration.ofDays(1))
        assertEquals(AiBudgetSettlementImportResult(1, 0), importer(listOf(receipt(call, 7))).importReviewedBills())
        assertEquals(bi(7), snapshot(room).settled)
        assertEquals(BigInteger.ZERO, snapshot(room).held)
        assertEquals(1, billEvents(call.id))
    }

    @Test
    fun `BP04 HTTP success and complete zero usage without reviewed billing evidence leave the full hold`() {
        val room = ready()
        val call = dispatched(room)
        assertTrue(
            adapter().observe(
                AiBudgetObservation(
                    call.id,
                    assertNotNull(call.dispatchToken),
                    UUID.randomUUID(),
                    AiBudgetOutcome.SUCCEEDED,
                    AiBudgetUsage(0, 0, 0, 0, true),
                ),
            ),
        )
        assertEquals(AiBudgetSettlementImportResult(0, 0), importer(emptyList()).importReviewedBills())
        assertEquals(BigInteger.ZERO, snapshot(room).settled)
        assertEquals(bi(30), snapshot(room).held)
        assertEquals(AiBudgetCallState.UNKNOWN, assertNotNull(repository.findCall(call.id)).state)
        assertEquals(0, billEvents(call.id))
    }

    @Test
    fun `BP05 never dispatched missing and deleted reservations cannot be settled or recreated`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val reserved = reserve(request).calls.single()
        val record = assertNotNull(repository.findCall(reserved.reservationId))
        assertNull(record.dispatchToken)
        val rows = listOf(receipt(record, 7), receipt(record, 7).copy(reservationId = UUID.randomUUID(), evidenceReference = "missing"))
        assertEquals(AiBudgetSettlementImportResult(0, 2), importer(rows).importReviewedBills())
        assertEquals(bi(30), snapshot(room).held)
        assertEquals(0, billEvents(record.id))
        jdbc.update("DELETE FROM meeting_rooms WHERE id=?", room)
        assertEquals(AiBudgetSettlementImportResult(0, 2), importer(rows).importReviewedBills())
        assertNull(repository.findRoom(room))
        assertNull(repository.findCall(record.id))
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_room_budgets WHERE room_id=?", Int::class.java, room))
    }

    @Test
    fun `BP06 quote or unit mismatch retains reservation rather than trusting a matching reservation id`() {
        val room = ready()
        val call = dispatched(room)
        for (wrong in listOf(
            receipt(call, 7).copy(quoteFingerprint = hash("other-quote")),
            receipt(call, 7).copy(unit = AiBudgetUnit("OTHER_TEST_UNIT", 0)),
        )) {
            assertEquals(AiBudgetSettlementImportResult(0, 1), importer(listOf(wrong)).importReviewedBills())
        }
        assertEquals(BigInteger.ZERO, snapshot(room).settled)
        assertEquals(bi(30), snapshot(room).held)
        assertEquals(0, billEvents(call.id))
    }

    @Test
    fun `BP07 a complete overquote bill remains known and freezes admission instead of clipping to reserve`() {
        val room = ready()
        val call = dispatched(room)
        assertEquals(AiBudgetSettlementImportResult(1, 0), importer(listOf(receipt(call, 31))).importReviewedBills())
        assertEquals(bi(31), snapshot(room).settled)
        assertEquals(BigInteger.ZERO, snapshot(room).held)
        assertEquals(AiRoomBudgetState.FROZEN, snapshot(room).state)
        val amount =
            jdbc.queryForObject(
                "SELECT bill_amount FROM ai_call_observations WHERE reservation_id=? AND kind='BILL'",
                BigDecimal::class.java,
                call.id,
            )
        assertEquals(bi(31), assertNotNull(amount).toBigIntegerExact())
    }

    @Test
    fun `BP08 later conflicting approved charge freezes room without overwriting the first settlement`() {
        val room = ready()
        val call = dispatched(room)
        val first = receipt(call, 7)
        assertEquals(AiBudgetSettlementImportResult(1, 0), importer(listOf(first)).importReviewedBills())
        val conflict =
            first.copy(
                amount = bi(8),
                evidenceFingerprint = hash("different-reviewed-charge"),
                evidenceReference = "revised-bill",
                reviewReference = "revised-review",
            )
        assertEquals(AiBudgetSettlementImportResult(0, 1), importer(listOf(conflict)).importReviewedBills())
        assertEquals(bi(7), snapshot(room).settled)
        assertEquals(BigInteger.ZERO, snapshot(room).held)
        assertEquals(AiRoomBudgetState.FROZEN, snapshot(room).state)
        assertEquals(2, billEvents(call.id))
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_call_observations WHERE reservation_id=? AND kind='BILL' AND failure='ACCOUNTING'",
                Int::class.java,
                call.id,
            ),
        )
    }

    @Test
    fun `BP09 deletion between catalog lookup and transactional settle cannot bootstrap an empty ledger`() {
        val room = ready()
        val call = dispatched(room)
        val base = repository
        val deleting =
            object : DurableAiBudgetRepository by base {
                override fun findCall(
                    id: UUID,
                    lock: Boolean,
                ): AiStoredBudgetCall? =
                    base.findCall(id, lock).also { if (it != null && !lock) jdbc.update("DELETE FROM meeting_rooms WHERE id=?", room) }
            }
        assertEquals(AiBudgetSettlementImportResult(0, 1), importer(listOf(receipt(call, 7)), deleting).importReviewedBills())
        assertNull(repository.findRoom(room))
        assertNull(repository.findCall(call.id))
        assertEquals(0, billEvents(call.id))
    }

    @Test
    fun `BP10 aggregate NUMERIC38 overflow preserves the known second charge and its unresolved hold`() {
        val maximum = BigInteger.TEN.pow(38) - BigInteger.ONE
        val room = ready(maximum)
        val first = dispatched(room, 1)
        val second = dispatched(room, 1)
        val prior = maximum - bi(10)
        val rows = listOf(receipt(first, prior), receipt(second, 20))
        assertEquals(AiBudgetSettlementImportResult(1, 1), importer(rows).importReviewedBills())
        assertEquals(prior, snapshot(room).settled)
        assertEquals(bi(1), snapshot(room).held)
        assertEquals(AiRoomBudgetState.FROZEN, snapshot(room).state)
        val event =
            jdbc
                .queryForList(
                    "SELECT bill_amount,failure FROM ai_call_observations WHERE reservation_id=? AND kind='BILL'",
                    second.id,
                ).single()
        assertEquals(bi(20), assertNotNull(event["bill_amount"] as? BigDecimal).toBigIntegerExact())
        assertEquals("ACCOUNTING", event["failure"])
    }

    @Test
    fun `BP11 separate receipt import cannot reconfigure policy and new below-cap admission remains possible`() {
        val room = ready()
        val call = dispatched(room)
        val policyBefore = jdbc.queryForObject("SELECT policy_fingerprint FROM ai_room_budgets WHERE room_id=?", String::class.java, room)
        assertEquals(AiBudgetSettlementImportResult(1, 0), importer(listOf(receipt(call, 7))).importReviewedBills())
        assertEquals(
            policyBefore,
            jdbc.queryForObject("SELECT policy_fingerprint FROM ai_room_budgets WHERE room_id=?", String::class.java, room),
        )
        val next = request(room)
        authorize(next, 10)
        assertNotNull(adapter().reserve(next))
        assertEquals(bi(7), snapshot(room).settled)
        assertEquals(bi(10), snapshot(room).held)
        assertEquals(AiRoomBudgetState.READY, snapshot(room).state)
    }

    @Test
    fun `BP12 a bounded row read failure is unresolved and cannot prevent an independent valid charge`() {
        val room = ready()
        val call = dispatched(room)
        val missing = UUID.randomUUID()
        val base = repository
        val failing =
            object : DurableAiBudgetRepository by base {
                override fun findCall(
                    id: UUID,
                    lock: Boolean,
                ): AiStoredBudgetCall? {
                    if (id == missing) throw IllegalStateException("synthetic-sensitive-read-error")
                    return base.findCall(id, lock)
                }
            }
        val rows = listOf(receipt(call, 1).copy(reservationId = missing), receipt(call, 7))
        assertEquals(AiBudgetSettlementImportResult(1, 1), assertDoesNotThrow { importer(rows, failing).importReviewedBills() })
        assertEquals(bi(7), snapshot(room).settled)
        assertEquals(BigInteger.ZERO, snapshot(room).held)
    }

    private fun dispatched(
        room: UUID,
        maximum: Int = 30,
    ): AiStoredBudgetCall {
        val request = request(room)
        authorize(request, maximum)
        val call = reserve(request).calls.single()
        claim(request, call)
        return assertNotNull(repository.findCall(call.reservationId))
    }

    private fun receipt(
        call: AiStoredBudgetCall,
        amount: Int,
    ) = receipt(call, bi(amount))

    private fun receipt(
        call: AiStoredBudgetCall,
        amount: BigInteger,
    ) = AiReviewedCompleteBillEvidence(
        call.id,
        call.quote.quoteFingerprint,
        "bill-${call.id}",
        hash("bill:${call.id}:$amount"),
        amount,
        unit,
        "synthetic-reviewed-charge",
    )

    private fun importer(
        rows: List<AiReviewedCompleteBillEvidence>,
        reads: DurableAiBudgetRepository = repository,
    ): AiBudgetSettlementImportService {
        val catalog = AiCompleteBillEvidenceCatalogPort { rows }
        return AiBudgetSettlementImportService(catalog, reads, productionBudget(catalog))
    }

    private fun productionBudget(catalog: AiCompleteBillEvidenceCatalogPort): DurableAiBudgetPort {
        val policy =
            ApprovedAiRuntimePolicy(
                hash("synthetic-policy"),
                unit,
                bi(100),
                0.5,
                AiPhysicalOperation.entries.associateWith {
                    ApprovedAiOperationPolicy(hash("synthetic-profile"), hash("synthetic-price-evidence"), bi(30), NOW.plusSeconds(60))
                },
            )
        val authority = ConfiguredAiBudgetAuthorityRegistry(ManagedAiRuntimeSelection(true, policy), clock, catalog)
        val service = DurableAiBudgetService(repository, clock, fence, authority)
        return object : DurableAiBudgetPort {
            override fun reserve(request: AiBudgetReserveRequest): AiBudgetReservation? = transaction.execute { service.reserve(request) }

            override fun claim(request: AiBudgetDispatchRequest): AiBudgetDispatchPermit? = transaction.execute { service.claim(request) }

            override fun observe(observation: AiBudgetObservation): Boolean = transaction.execute { service.observe(observation) }!!

            override fun settle(request: AiBudgetSettlementRequest): Boolean = transaction.execute { service.settle(request) }!!

            override fun snapshot(roomId: UUID): AiRoomBudgetSnapshot? = transaction.execute { service.snapshot(roomId) }
        }
    }

    private fun billEvents(id: UUID) =
        jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_observations WHERE reservation_id=? AND kind='BILL'", Int::class.java, id)
}
