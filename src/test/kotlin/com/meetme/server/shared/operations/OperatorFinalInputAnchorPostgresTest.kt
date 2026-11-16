package com.meetme.server.shared.operations

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflinePipeline
import com.meetme.server.coordination.adapter.output.persistence.JdbcFinalAnalysisRouteRegistry
import com.meetme.server.shared.adapter.output.persistence.operations.JdbcOperationsQueryRepository
import com.meetme.server.shared.adapter.output.persistence.operations.JdbcOperatorDiagnosticsRepository
import com.meetme.server.shared.domain.InputEvidence
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F

/** Final-source time and personal precheck time are separate facts; absence cannot invent a legacy anchor. */
class OperatorFinalInputAnchorPostgresTest {
    private val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    private val jdbc = JdbcTemplate(source)
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val repository = JdbcOperatorDiagnosticsRepository(jdbc, mapper, JdbcOperationsQueryRepository(jdbc))
    private val candidateAnchor = F.ir.reference.plusSeconds(60)
    private val acceptedAt = F.ir.reference.plusSeconds(300)

    @BeforeEach
    fun nativeInputsWithDifferentPersonalAnchor() {
        Flyway
            .configure()
            .dataSource(source)
            .load()
            .migrate()
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions CASCADE")
        F.seed(jdbc, mapper)
        jdbc.update(
            """INSERT INTO precheck_candidates(id,room_id,participant_id,revision,raw_text,locale,created_at,
                interpretation_reference_at,owner_binding,accepted_version_id)
                VALUES (?,?,?,1,?,'ko-KR',?,?,'synthetic-anchor-owner',?)""",
            UUID(0, 24101),
            F.ir.room,
            F.ir.firstParticipant,
            F.q
                .input2()
                .inputs
                .first()
                .rawText,
            Timestamp.from(acceptedAt),
            Timestamp.from(candidateAnchor),
            F.ir.first,
        )
        jdbc.update(
            """INSERT INTO precheck_checks(id,candidate_id,status,version,generation,retry_count,deadline_at)
                VALUES (?,?,'CLEAR',1,1,0,?)""",
            UUID(0, 24102),
            UUID(0, 24101),
            Timestamp.from(acceptedAt.plusSeconds(30)),
        )
    }

    @Test
    fun `FIA01 explicit frozen evidence retains its original native reference independently of personal checks`() {
        jdbc.update(
            "UPDATE submission_versions SET input_evidence=CAST(? AS jsonb) WHERE id=?",
            mapper.writeValueAsString(InputEvidence(3, F.ir.reference)),
            F.ir.first,
        )
        val input = first(F.ir.run)
        assertEquals(F.ir.reference, input.referenceAt)
        assertEquals(acceptedAt, input.createdAt)
        assertEquals(candidateAnchor, input.checks.single().referenceAt)
    }

    @Test
    fun `FIA02 legacy frozen inputs without native evidence have an unknown final anchor`() {
        clearEvidence()
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='LEGACY' WHERE id=?", F.ir.room)
        val input = first(F.ir.run)
        assertNull(input.referenceAt)
        assertEquals(acceptedAt, input.createdAt)
        assertEquals(candidateAnchor, input.checks.single().referenceAt)
    }

    @Test
    fun `FIA03 current collection cannot claim a final anchor from candidate reference or input creation`() {
        clearEvidence()
        val input = first(null)
        assertNull(input.referenceAt)
        assertEquals(acceptedAt, input.createdAt)
        assertEquals(candidateAnchor, input.checks.single().referenceAt)
    }

    @Test
    fun `FIA04 selected native DSL binds evidence-less original input to immutable version creation`() {
        clearEvidence()
        val native = F.q.input1().let { it.copy(inputs = it.inputs.map { input -> input.copy(evidence = null) }) }
        val context = ScheduleDslOfflinePipeline.snapshot(native).context
        val choice = F.choice().copy(context = context)
        JdbcFinalAnalysisRouteRegistry(jdbc, mapper, Clock.fixed(F.selectedAt, ZoneOffset.UTC)).freeze(F.ir.run, 1, choice)
        val input = first(F.ir.run)
        assertEquals(acceptedAt, input.referenceAt)
        assertEquals(acceptedAt, input.createdAt)
        assertEquals(candidateAnchor, input.checks.single().referenceAt)
    }

    private fun clearEvidence() {
        jdbc.update("UPDATE submission_versions SET input_evidence=NULL")
    }

    private fun first(runId: UUID?) =
        assertNotNull(repository.diagnostics(F.ir.room, runId)).inputs.single { it.rawText == F.ir.FIRST_TEXT }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
