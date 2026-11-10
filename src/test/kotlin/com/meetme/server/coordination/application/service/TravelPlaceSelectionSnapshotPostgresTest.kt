package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.adapter.output.persistence.JdbcFinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.analysis.FrozenFinalAnalysisRoute
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslStructuralRepository
import com.meetme.server.submission.application.port.output.ScheduleDslStructuralRepository
import com.meetme.server.submission.domain.dsl.PublicPlaceConstraintDescription
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.SchedulePlaceConstraintKind
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import com.meetme.server.support.FinalAnalysisRouteTestFixture
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.TravelPlaceTestFixture as F

/** Real reader AOP and PostgreSQL repositories; the decorator records DB state and delegates every operation. */
@SpringBootTest
@Import(TravelPlaceSelectionSnapshotPostgresTest.SnapshotConfiguration::class)
class TravelPlaceSelectionSnapshotPostgresTest {
    @Autowired private lateinit var reader: ScheduleDslStoredReadService

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var envelopes: ObservedStructuralRepository

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var transactions: PlatformTransactionManager

    private lateinit var route: FrozenFinalAnalysisRoute
    private lateinit var declaration: ScheduleDslStructuralEnvelope
    private val runId = CoordinationRunId(H.ir.run)
    private val version = 2L
    private val expected =
        listOf(PublicPlaceConstraintDescription(F.firstParticipant, SchedulePlaceConstraintKind.TRAVEL_TIME_LIMIT, null, null, null))

    @BeforeEach
    fun seedRealFrozenSourceRouteAndEnvelope() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        H.seed(jdbc, mapper, F.input())
        jdbc.update("UPDATE meeting_rooms SET meeting_mode=? WHERE id=?", F.input().meetingMode.name, H.ir.room)
        val choice =
            FinalAnalysisRouteChoice.DslStructuralV5(
                IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", F.policy.version),
                F.policy,
                F.snapshot().context,
                F.input().meetingMode,
                "ko-time-terms-1",
            )
        route = routes.freeze(H.ir.run, 1, choice)
        declaration = F.parse(objectMapper = mapper)
        envelopes.publish(declaration, 1, FinalAnalysisRouteTestFixture.selectedAt)
        jdbc.update("UPDATE coordination_runs SET status='COMPLETED',version=?,candidate_quality='PARTIAL' WHERE id=?", version, H.ir.run)
        assertEquals(10, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
        assertTrue(AopUtils.isAopProxy(reader))
        envelopes.observations.clear()
    }

    @ParameterizedTest
    @ValueSource(ints = [TransactionDefinition.ISOLATION_READ_COMMITTED, TransactionDefinition.ISOLATION_REPEATABLE_READ])
    fun `TPS01 selection preload uses a new actual readonly RR snapshot while the locked writer resumes and can write`(isolation: Int) {
        val before = sourceRows()
        transaction(readOnly = false, isolation).executeWithoutResult { status ->
            lockRoomAndRun()
            val outer = observe(jdbc)
            assertEquals("off", outer.readOnly)
            repeat(2) { assertEquals(expected, reader.readPlaceDescriptionsForSelection(runId, version, route.fingerprint())) }
            assertEquals(2, envelopes.observations.size)
            envelopes.observations.forEach { inner ->
                assertNotEquals(outer.transactionId, inner.transactionId)
                assertEquals("on", inner.readOnly)
                assertEquals("repeatable read", inner.isolation)
            }
            assertEquals(
                2,
                envelopes.observations
                    .map { it.transactionId }
                    .toSet()
                    .size,
            )
            assertEquals(outer, observe(jdbc))
            jdbc.update("UPDATE coordination_runs SET version=? WHERE id=?", version + 1, H.ir.run)
            assertEquals(version + 1, currentVersion())
            status.setRollbackOnly()
        }
        assertEquals(version, currentVersion())
        assertEquals(before, sourceRows())
    }

    @ParameterizedTest
    @ValueSource(ints = [TransactionDefinition.ISOLATION_READ_COMMITTED, TransactionDefinition.ISOLATION_REPEATABLE_READ])
    fun `TPS02 a post write uncommitted version cannot be passed off as the committed frozen selection snapshot`(isolation: Int) {
        val before = sourceRows()
        transaction(readOnly = false, isolation).executeWithoutResult { status ->
            lockRoomAndRun()
            val outer = observe(jdbc)
            jdbc.update("UPDATE coordination_runs SET version=? WHERE id=?", version + 1, H.ir.run)
            val failure =
                assertFailsWith<IllegalStateException> {
                    reader.readPlaceDescriptionsForSelection(runId, version + 1, route.fingerprint())
                }
            assertEquals("Stale stored DSL execution", failure.message)
            assertEquals(version + 1, currentVersion())
            assertEquals(outer, observe(jdbc))
            status.setRollbackOnly()
        }
        assertEquals(version, currentVersion())
        assertEquals(before, sourceRows())
    }

    @ParameterizedTest
    @ValueSource(ints = [TransactionDefinition.ISOLATION_READ_COMMITTED, TransactionDefinition.ISOLATION_REPEATABLE_READ])
    fun `TPS03 existing GET description reader still rejects a surrounding writer instead of weakening its readonly guard`(isolation: Int) {
        val before = sourceRows()
        transaction(readOnly = false, isolation).executeWithoutResult { status ->
            assertFailsWith<RuntimeException> { reader.readPlaceDescriptions(runId, version, route.fingerprint()) }
            assertTrue(envelopes.observations.isEmpty())
            status.setRollbackOnly()
        }
        assertEquals(before, sourceRows())
    }

    @Test
    fun `TPS04 existing GET description reader joins the actual outer readonly RR source snapshot`() {
        val before = sourceRows()
        transaction(readOnly = true, TransactionDefinition.ISOLATION_REPEATABLE_READ).executeWithoutResult {
            val outer = observe(jdbc)
            assertEquals(expected, reader.readPlaceDescriptions(runId, version, route.fingerprint()))
            assertEquals(listOf(outer), envelopes.observations)
            assertEquals("on", outer.readOnly)
            assertEquals("repeatable read", outer.isolation)
            assertEquals(outer, observe(jdbc))
        }
        assertEquals(before, sourceRows())
    }

    @Test
    fun `TPS05 selection snapshot pins the real route fingerprint and unchanged native source without publishing`() {
        val before = sourceRows()
        assertEquals(expected, reader.readPlaceDescriptionsForSelection(runId, version, route.fingerprint()))
        assertFailsWith<RuntimeException> { reader.readPlaceDescriptionsForSelection(runId, version, "0".repeat(64)) }
        assertEquals(before, sourceRows())
        assertEquals(declaration, envelopes.find(F.snapshot().context))
        assertEquals(F.firstParticipant, expected.single().participantId)
        assertNull(expected.single().publicPlaceName)
        jdbc.update("UPDATE submission_versions SET raw_text=? WHERE id=?", F.RAW.replace(F.NAME, "가상붉은역"), F.first)
        val changed = sourceRows()
        assertFailsWith<RuntimeException> { reader.readPlaceDescriptionsForSelection(runId, version, route.fingerprint()) }
        assertEquals(changed, sourceRows())
    }

    @Test
    fun `TPS06 ordinary GET still rejects a readonly RC outer transaction`() {
        transaction(readOnly = true, TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult { status ->
            assertFailsWith<RuntimeException> { reader.readPlaceDescriptions(runId, version, route.fingerprint()) }
            assertTrue(envelopes.observations.isEmpty())
            status.setRollbackOnly()
        }
    }

    private fun transaction(
        readOnly: Boolean,
        isolation: Int,
    ) = TransactionTemplate(transactions).apply {
        isReadOnly = readOnly
        isolationLevel = isolation
    }

    private fun lockRoomAndRun() {
        assertNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE id=? FOR UPDATE", java.util.UUID::class.java, H.ir.room))
        assertNotNull(jdbc.queryForObject("SELECT id FROM coordination_runs WHERE id=? FOR UPDATE", java.util.UUID::class.java, H.ir.run))
    }

    private fun currentVersion() = jdbc.queryForObject("SELECT version FROM coordination_runs WHERE id=?", Long::class.java, H.ir.run)

    private fun sourceRows() =
        listOf(
            "meeting_rooms",
            "coordination_runs",
            "final_analysis_routes",
            "submission_versions",
            "schedule_ir_envelopes",
            "schedule_ir_inputs",
        ).associateWith { FinalAnalysisRouteTestFixture.rows(jdbc, it) }

    data class Observation(
        val transactionId: Long,
        val readOnly: String,
        val isolation: String,
    )

    class ObservedStructuralRepository(
        jdbc: JdbcTemplate,
        mapper: ObjectMapper,
    ) : ScheduleDslStructuralRepository {
        private val database = jdbc
        private val delegate = JdbcScheduleDslStructuralRepository(jdbc, mapper)
        val observations = mutableListOf<Observation>()

        override fun publish(
            envelope: ScheduleDslStructuralEnvelope,
            expectedRunVersion: Long,
            processedAt: Instant,
        ) = delegate.publish(envelope, expectedRunVersion, processedAt)

        override fun find(context: ScheduleIrContext): ScheduleDslStructuralEnvelope? {
            observations += observe(database)
            return delegate.find(context)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class SnapshotConfiguration {
        @Bean
        fun snapshotRoutes(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
        ): FinalAnalysisRouteRegistry =
            JdbcFinalAnalysisRouteRegistry(jdbc, mapper, Clock.fixed(FinalAnalysisRouteTestFixture.selectedAt, ZoneOffset.UTC))

        @Bean
        fun snapshotEnvelopes(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
        ) = ObservedStructuralRepository(jdbc, mapper)
    }

    companion object {
        private val postgres = mappedPortReadyPostgres().apply { start() }

        private fun observe(jdbc: JdbcTemplate) =
            Observation(
                requireNotNull(jdbc.queryForObject("SELECT txid_current()", Long::class.java)),
                requireNotNull(jdbc.queryForObject("SHOW transaction_read_only", String::class.java)),
                requireNotNull(jdbc.queryForObject("SHOW transaction_isolation", String::class.java)),
            )

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("meetme.reliability.enabled") { false }
            registry.add("meetme.data-retention.enabled") { false }
            registry.add("meetme.rate-limit.enabled") { false }
            registry.add("meetme.gemini.api-key") { "" }
            registry.add("meetme.openai.api-key") { "" }
            registry.add("meetme.typesafe.api-key") { "" }
        }

        @JvmStatic @AfterAll
        fun stop() {
            postgres.stop()
        }
    }
}
