package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.config.ApprovedAiOperationPolicy
import com.meetme.server.config.ApprovedAiRuntimePolicy
import com.meetme.server.config.ManagedAiRuntimeSelection
import com.meetme.server.shared.adapter.output.persistence.JdbcDurableAiBudgetRepository
import com.meetme.server.shared.application.port.output.AiBudgetAuthorityRegistryPort
import com.meetme.server.shared.application.port.output.AiBudgetBillRequest
import com.meetme.server.shared.application.port.output.AiBudgetExecutionFencePort
import com.meetme.server.shared.application.port.output.AiBudgetUnit
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiRoomBudgetState
import com.meetme.server.shared.application.port.output.AiStoredBudgetCall
import com.meetme.server.shared.application.port.output.AiVerifiedCompleteBill
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.shared.application.port.output.DurableAiBudgetRepository
import com.meetme.server.shared.application.service.DurableAiBudgetService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import org.springframework.aop.support.AopUtils
import org.springframework.beans.BeansException
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile
import org.springframework.core.env.MapPropertySource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ADR055 composition: production file loader, registry, startup listener and actual Spring/PG transaction proxy. */
class ReviewedAiBillStartupWiringTest : DurableBudgetPostgresFixture() {
    @TempDir
    lateinit var directory: Path

    private val mapper = JsonMapper.builder().build()
    private val unit = AiBudgetUnit("TEST_SUBUNIT", 0)

    @Test
    fun `BW01 ready event resolves reviewed file through real authority and transactional settle proxy`() {
        val room = ready()
        val call = dispatched(room)
        val properties = receiptFile(call, 7)
        val tracking = TrackingRepository(JdbcDurableAiBudgetRepository(jdbc))
        context(enabled = true, properties, tracking).use { context ->
            context.refresh()
            val budget = context.getBean(DurableAiBudgetPort::class.java)
            assertTrue(AopUtils.isAopProxy(budget), "Startup consumption must receive Spring's transactional budget proxy")
            val authority = context.getBean(AiBudgetAuthorityRegistryPort::class.java)
            assertIs<ConfiguredAiBudgetAuthorityRegistry>(authority)
            assertEquals(
                AiVerifiedCompleteBill(call.quote.quoteFingerprint, hash("reviewed-charge:${call.id}:7"), bi(7), unit),
                authority.completeBill(AiBudgetBillRequest(call.id, call.quote.quoteFingerprint, "bill-${call.id}")),
                "The actual bean must receive the loaded catalog rather than its empty constructor default",
            )
            assertEquals(bi(30), snapshot(room).held, "Context creation alone is not the ready-event consumption trigger")
            assertEquals(0, tracking.lookups.get())
            publishReady(context)
            assertEquals(bi(7), snapshot(room).settled)
            assertEquals(BigInteger.ZERO, snapshot(room).held)
            assertTrue(tracking.lockTransactions.isNotEmpty(), "The real financial path must acquire the existing room lock")
            assertTrue(tracking.lockTransactions.all { it }, "Every settlement room lock must run inside an actual Spring transaction")
            assertEquals(hash("synthetic-policy"), policyFingerprint(room))
            assertEquals(hash("synthetic-policy"), assertNotNull(context.getBean(ManagedAiRuntimeSelection::class.java).policy).fingerprint)
            publishReady(context)
            assertEquals(bi(7), snapshot(room).settled, "A duplicate ready event must be an idempotent accounting replay")
            assertEquals(1, billEvents(call.id))
        }
    }

    @Test
    fun `BW02 disabled selection performs no settlement lookup even when a valid reviewed file exists`() {
        val room = ready()
        val call = dispatched(room)
        val tracking = TrackingRepository(JdbcDurableAiBudgetRepository(jdbc))
        context(enabled = false, receiptFile(call, 7), tracking).use { context ->
            context.refresh()
            assertIs<DenyAiBudgetAuthorityRegistry>(context.getBean(AiBudgetAuthorityRegistryPort::class.java))
            publishReady(context)
            assertEquals(0, tracking.lookups.get())
            assertTrue(tracking.lockTransactions.isEmpty())
            assertEquals(bi(30), snapshot(room).held)
            assertEquals(BigInteger.ZERO, snapshot(room).settled)
            assertEquals(0, billEvents(call.id))
        }
    }

    @Test
    fun `BW03 absent optional catalog starts with no database lookup and no inferred charge`() {
        val room = ready()
        val call = dispatched(room)
        val tracking = TrackingRepository(JdbcDurableAiBudgetRepository(jdbc))
        context(enabled = true, AiCompleteBillEvidenceProperties(), tracking).use { context ->
            assertDoesNotThrow { context.refresh() }
            publishReady(context)
            assertEquals(0, tracking.lookups.get())
            assertTrue(tracking.lockTransactions.isEmpty())
            assertEquals(bi(30), snapshot(room).held)
            assertEquals(BigInteger.ZERO, snapshot(room).settled)
            assertEquals(0, billEvents(call.id))
        }
    }

    @Test
    fun `BW04 invalid configured billing artifact fails context startup before any financial mutation`() {
        val room = ready()
        val call = dispatched(room)
        val bytes = """{"protocol":"meetme-complete-bills-v1","bills":[],"synthetic-sensitive-evidence":"must-not-leak"}""".toByteArray()
        val path = Files.createTempFile(directory, "invalid-reviewed-bills-", ".json").also { Files.write(it, bytes) }
        val properties = AiCompleteBillEvidenceProperties(path.toString(), digest(bytes))
        val tracking = TrackingRepository(JdbcDurableAiBudgetRepository(jdbc))
        context(enabled = true, properties, tracking).use { context ->
            val failure = assertFailsWith<BeansException> { context.refresh() }
            val causes = generateSequence<Throwable>(failure) { it.cause }.take(12).joinToString(" ") { it.message.orEmpty() }
            assertTrue(!causes.contains("synthetic-sensitive-evidence") && !causes.contains("must-not-leak"))
            assertEquals(0, tracking.lookups.get())
            assertTrue(tracking.lockTransactions.isEmpty())
            assertEquals(bi(30), snapshot(room).held)
            assertEquals(BigInteger.ZERO, snapshot(room).settled)
            assertEquals(0, billEvents(call.id))
        }
    }

    @Test
    fun `BW05 replacing separately reviewed billing artifact preserves existing room policy and both charges`() {
        val room = ready()
        val first = dispatched(room)
        val second = dispatched(room)
        val before = policyFingerprint(room)
        val firstProperties = receiptFile(first, 7)
        val secondProperties = receiptFile(second, 5)
        assertTrue(firstProperties.sha256 != secondProperties.sha256)
        for (properties in listOf(firstProperties, secondProperties)) {
            context(enabled = true, properties, TrackingRepository(JdbcDurableAiBudgetRepository(jdbc))).use { context ->
                context.refresh()
                publishReady(context)
                assertEquals(before, policyFingerprint(room), "A bill catalog digest cannot replace a room's admission policy fingerprint")
                assertEquals(before, assertNotNull(context.getBean(ManagedAiRuntimeSelection::class.java).policy).fingerprint)
            }
        }
        assertEquals(bi(12), snapshot(room).settled)
        assertEquals(BigInteger.ZERO, snapshot(room).held)
        assertEquals(AiRoomBudgetState.READY, snapshot(room).state)
        assertEquals(1, billEvents(first.id))
        assertEquals(1, billEvents(second.id))
    }

    private fun context(
        enabled: Boolean,
        properties: AiCompleteBillEvidenceProperties,
        tracking: TrackingRepository,
    ): AnnotationConfigApplicationContext =
        AnnotationConfigApplicationContext().apply {
            environment.setActiveProfiles("reviewed-bills-wiring-test")
            environment.propertySources.addFirst(
                MapPropertySource(
                    "synthetic-reviewed-billing",
                    mapOf(
                        "meetme.ai.budget.billing.path" to properties.path,
                        "meetme.ai.budget.billing.sha256" to properties.sha256,
                    ),
                ),
            )
            register(
                TransactionBindings::class.java,
                AiCompleteBillEvidenceConfiguration::class.java,
                AiBudgetAuthorityConfiguration::class.java,
            )
            registerBean(DataSource::class.java, Supplier { dataSource })
            registerBean(Clock::class.java, Supplier { clock })
            registerBean(AiBudgetExecutionFencePort::class.java, Supplier { fence })
            registerBean(DurableAiBudgetRepository::class.java, Supplier { tracking })
            registerBean(
                ManagedAiRuntimeSelection::class.java,
                Supplier { ManagedAiRuntimeSelection(enabled, if (enabled) policy() else null) },
            )
        }

    private fun publishReady(context: AnnotationConfigApplicationContext) {
        context.publishEvent(
            ApplicationReadyEvent(SpringApplication(TransactionBindings::class.java), emptyArray(), context, Duration.ZERO),
        )
    }

    private fun dispatched(room: UUID): AiStoredBudgetCall {
        val request = request(room)
        authorize(request, 30)
        val call = reserve(request).calls.single()
        claim(request, call)
        return assertNotNull(JdbcDurableAiBudgetRepository(jdbc).findCall(call.reservationId))
    }

    private fun receiptFile(
        call: AiStoredBudgetCall,
        amount: Int,
    ): AiCompleteBillEvidenceProperties {
        val row =
            linkedMapOf(
                "reservationId" to call.id.toString(),
                "quoteFingerprint" to call.quote.quoteFingerprint,
                "evidenceReference" to "bill-${call.id}",
                "evidenceFingerprint" to hash("reviewed-charge:${call.id}:$amount"),
                "amount" to amount.toString(),
                "unit" to linkedMapOf("code" to unit.code, "scale" to unit.scale),
                "reviewReference" to "synthetic-complete-charge-review",
            )
        val bytes = mapper.writeValueAsBytes(linkedMapOf("protocol" to "meetme-complete-bills-v1", "bills" to listOf(row)))
        val file = Files.createTempFile(directory, "reviewed-bills-", ".json").also { Files.write(it, bytes) }
        return AiCompleteBillEvidenceProperties(file.toString(), digest(bytes))
    }

    private fun policy() =
        ApprovedAiRuntimePolicy(
            hash("synthetic-policy"),
            unit,
            bi(100),
            0.5,
            AiPhysicalOperation.entries.associateWith {
                ApprovedAiOperationPolicy(hash("synthetic-profile"), hash("synthetic-price-evidence"), bi(30), NOW.plusSeconds(60))
            },
        )

    private fun policyFingerprint(room: UUID) =
        jdbc.queryForObject("SELECT policy_fingerprint FROM ai_room_budgets WHERE room_id=?", String::class.java, room)

    private fun billEvents(id: UUID) =
        jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_observations WHERE reservation_id=? AND kind='BILL'", Int::class.java, id)

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class TrackingRepository(
        private val backing: DurableAiBudgetRepository,
    ) : DurableAiBudgetRepository by backing {
        val lookups = AtomicInteger()
        val lockTransactions = CopyOnWriteArrayList<Boolean>()

        override fun findCall(
            id: UUID,
            lock: Boolean,
        ): AiStoredBudgetCall? {
            lookups.incrementAndGet()
            return backing.findCall(id, lock)
        }

        override fun lockExistingRoom(roomId: UUID): Boolean {
            lockTransactions += TransactionSynchronizationManager.isActualTransactionActive()
            return backing.lockExistingRoom(roomId)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Profile("reviewed-bills-wiring-test")
    @EnableTransactionManagement(proxyTargetClass = false)
    class TransactionBindings {
        @Bean
        fun transactionManager(dataSource: DataSource) = DataSourceTransactionManager(dataSource)

        @Bean
        fun durableAiBudget(
            repository: DurableAiBudgetRepository,
            clock: Clock,
            fence: AiBudgetExecutionFencePort,
            authority: AiBudgetAuthorityRegistryPort,
        ): DurableAiBudgetService = DurableAiBudgetService(repository, clock, fence, authority)
    }
}
