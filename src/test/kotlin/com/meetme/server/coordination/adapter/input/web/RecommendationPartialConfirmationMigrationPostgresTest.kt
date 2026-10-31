package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.adapter.output.persistence.HistoricalCoordinationRunRepository
import com.meetme.server.coordination.adapter.output.persistence.HistoricalRecommendationRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.RecommendationRepository
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.submission.adapter.output.persistence.HistoricalSubmissionRepository
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.komapper.jdbc.JdbcDatabase
import org.postgresql.util.PSQLException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** FR-011 / ADR-060: upgrading the attendee floor preserves historical selected and legacy data. */
@Import(RecommendationPartialConfirmationMigrationPostgresTest.HistoricalStoragePhase::class)
class RecommendationPartialConfirmationMigrationPostgresTest : RecommendationPostgresFixture() {
    @Autowired private lateinit var historicalSubmissions: HistoricalSubmissionRepository

    @Autowired private lateinit var historicalRuns: HistoricalCoordinationRunRepository

    @Autowired private lateinit var historicalRecommendations: HistoricalRecommendationRepository

    @Autowired private lateinit var partialStructured: StructuredSubmissionRepository

    @Autowired private lateinit var partialRuns: CoordinationRunRepository

    @Test
    fun `PC06 V17 to V18 preserves previous confirmations and permits pending two of five selection`() {
        assertEquals("17", currentVersion())
        val historical = fiveMemberRoom(3)
        val historicalOption = items(recommendations(historical.host), "options").single()
        val historicalResult =
            document(
                mvc
                    .perform(selection(historical, historicalOption))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        val legacy = completedRoom()
        val legacyCandidate = seedLegacyCandidate(legacy)
        mvc
            .perform(
                post("/api/rooms/{code}/candidates/{id}/confirmation", legacy.host.code, legacyCandidate)
                    .header("Origin", ORIGIN)
                    .cookie(legacy.host.cookie()),
            ).andExpect(status().isOk)
        val legacyResult = result(legacy.host)
        val pending = fiveMemberRoom(2)
        val pendingRecommendations = recommendations(pending.host)
        val pendingOption = items(pendingRecommendations, "options").single()
        assertEquals(2, (items(pendingOption, "variants").single().getValue("attendance_count") as Number).toInt())
        val before = snapshot()
        // Fixture preparation uses the explicit pre-V21 store, never a current-reader schema fallback.
        val oldFloor = assertFailsWith<DataIntegrityViolationException> { verifyPendingSelection(pending, pendingOption) }
        assertEquals("23514", (oldFloor.mostSpecificCause as PSQLException).sqlState)
        assertEquals(before, snapshot(), "The rejected historical probe must leave every business row unchanged")

        val upgrade = flyway("18")
        upgrade.migrate()
        assertEquals("18", currentVersion())
        upgrade.validate()
        assertEquals(before, snapshot(), "Migration must not recalculate published variants or rewrite historical business rows")
        verifyPendingSelection(pending, pendingOption)
        assertEquals(before, snapshot(), "The successful V18 guard probe is rolled back without creating a selection")

        // Current production readers require V22; only use them after the historical V18 proof is complete.
        val current = flyway()
        current.migrate()
        current.validate()
        assertEquals("22", currentVersion())
        historicalSubmissions.useCurrentSchema()
        historicalRecommendations.useCurrentSchema()
        historicalRuns.useCurrentSchema()
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM candidate_participants WHERE place_unverified", Int::class.java))
        assertEquals(
            0,
            jdbc.queryForObject("SELECT count(*) FROM recommendation_variant_participants WHERE place_unverified", Int::class.java),
        )
        assertEquals(historicalResult, result(historical.member))
        assertEquals(legacyResult, result(legacy.member))
        assertEquals(pendingRecommendations, recommendations(pending.host))
        val selected =
            document(
                mvc
                    .perform(selection(pending, pendingOption))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            )
        assertEquals(selected, result(pending.member))
        assertEquals(
            selected,
            document(
                mvc
                    .perform(selection(pending, pendingOption))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response,
            ),
        )
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM recommendation_selections", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM final_confirmations", Int::class.java))
    }

    private fun verifyPendingSelection(
        pending: CompletedCorrectionFixture,
        option: Map<String, Any?>,
    ) {
        val variant = items(option, "variants").single()
        val window = child(option, "time_range")
        jdbc.execute(
            ConnectionCallback<Unit> { connection ->
                val autoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    connection
                        .prepareStatement(
                            "INSERT INTO recommendation_selections(" +
                                "coordination_run_id,room_id,option_id,variant_id,start_at,end_at,confirmed_at) " +
                                "SELECT ?,?,?,?,?,?,b.fixed_at FROM submission_batches b " +
                                "JOIN coordination_runs r ON r.batch_id=b.id WHERE r.id=?",
                        ).use { statement ->
                            statement.setObject(1, pending.sourceRun)
                            statement.setObject(2, pending.roomId)
                            statement.setObject(3, UUID.fromString(option.getValue("option_id").toString()))
                            statement.setObject(4, UUID.fromString(variant.getValue("variant_id").toString()))
                            statement.setTimestamp(5, Timestamp.from(Instant.parse(window.getValue("start_at").toString())))
                            statement.setTimestamp(6, Timestamp.from(Instant.parse(window.getValue("end_at").toString())))
                            statement.setObject(7, pending.sourceRun)
                            assertEquals(
                                1,
                                statement.executeUpdate(),
                                "The same two-of-five selection must pass the V18 database guard",
                            )
                        }
                    connection.createStatement().use { it.execute("SET CONSTRAINTS ALL IMMEDIATE") }
                } finally {
                    connection.rollback()
                    connection.autoCommit = autoCommit
                }
            },
        )
    }

    @TestConfiguration(proxyBeanMethods = false)
    class HistoricalStoragePhase {
        @Bean @Primary
        fun historicalRecommendationRepository(
            jdbc: JdbcTemplate,
            @Qualifier("jdbcRecommendationRepository") current: RecommendationRepository,
        ): HistoricalRecommendationRepository = HistoricalRecommendationRepository(jdbc, current)

        @Bean @Primary
        fun historicalCoordinationRunRepository(
            database: JdbcDatabase,
            jdbc: JdbcTemplate,
            recommendations: HistoricalRecommendationRepository,
            @Qualifier("komapperCoordinationRunRepository") current: CoordinationRunRepository,
        ): HistoricalCoordinationRunRepository = HistoricalCoordinationRunRepository(database, jdbc, recommendations, current)

        @Bean @Primary
        fun historicalSubmissionRepository(
            database: JdbcDatabase,
            jdbc: JdbcTemplate,
            @Qualifier("komapperSubmissionRepository") current: SubmissionRepository,
        ): HistoricalSubmissionRepository = HistoricalSubmissionRepository(database, jdbc, current)
    }

    private fun snapshot(): Map<String, List<String>> =
        listOf(
            "meeting_rooms",
            "participants",
            "submission_heads",
            "submission_versions",
            "submission_batches",
            "submission_batch_items",
            "structured_submission_results",
            "coordination_runs",
            "candidates",
            "candidate_participants",
            "candidate_time_ranges",
            "final_confirmations",
            "recommendation_analyses",
            "recommendation_options",
            "recommendation_variants",
            "recommendation_variant_participants",
            "recommendation_selections",
        ).associateWith { table ->
            jdbc
                .queryForList("SELECT to_jsonb(t)::text FROM $table t ORDER BY to_jsonb(t)::text", String::class.java)
                .map { requireNotNull(it) }
        }

    private fun seedLegacyCandidate(fixture: CompletedCorrectionFixture): UUID {
        val candidate = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO candidates(id,coordination_run_id,rank,plan_type,meeting_mode,attendance_count,total_participants) " +
                "VALUES (?,?,1,'PLAN_B','REMOTE',2,2)",
            candidate,
            fixture.sourceRun,
        )
        jdbc.update(
            "INSERT INTO candidate_time_ranges(candidate_id,range_order,start_at,end_at) " +
                "VALUES (?,0,'2026-10-07T09:00:00Z','2026-10-07T12:00:00Z')",
            candidate,
        )
        jdbc.update(
            "INSERT INTO candidate_participants(candidate_id,participant_id,coordination_run_id,room_id) " +
                "SELECT ?,id,?,room_id FROM participants WHERE room_id = ?",
            candidate,
            fixture.sourceRun,
            fixture.roomId,
        )
        return candidate
    }

    private fun currentVersion(): String =
        flyway()
            .info()
            .current()
            .version.version

    private fun flyway(target: String? = null): Flyway {
        val configuration = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        target?.let(configuration::target)
        return configuration.load()
    }

    private fun fiveMemberRoom(availableCount: Int): CompletedCorrectionFixture {
        val displayNames = listOf("가온", "나래", "민수", "다온", "라온")
        val hostCookie = accounts.newAccount(displayNames.first())
        val created =
            mvc
                .perform(
                    post("/api/rooms")
                        .cookie(hostCookie)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"purpose":"synthetic partial recommendation","meeting_mode":"REMOTE","host_display_name":"가온","manual_only":true,"search_start_date":"2026-10-07","search_end_date":"2026-10-12"}""",
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        val code = document(created).getValue("invite_code").toString()
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='LEGACY' WHERE invite_code=?", code)
        val actors = mutableListOf(CorrectionSession(code, hostCookie.value))
        for (name in displayNames.drop(1)) {
            val cookie = accounts.newAccount(name)
            mvc
                .perform(
                    post("/api/rooms/{code}/participants", code)
                        .cookie(cookie)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(mapOf("display_name" to name))),
                ).andExpect(status().isCreated)
            actors += CorrectionSession(code, cookie.value)
        }
        actors.forEachIndexed { index, actor -> mvc.perform(save(actor, "synthetic private input $index")).andExpect(status().isOk) }
        mvc
            .perform(
                post("/api/rooms/{code}/close", code)
                    .cookie(actors.first().cookie())
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"confirm_early":true}"""),
            ).andExpect(status().isOk)
        val roomId = requireNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code=?", UUID::class.java, code))
        val runId = requireNotNull(jdbc.queryForObject("SELECT id FROM coordination_runs WHERE room_id=?", UUID::class.java, roomId))
        jdbc.update("UPDATE coordination_runs SET status='MATCHING',candidate_quality=NULL,version=version+1 WHERE id=?", runId)
        val run = requireNotNull(partialRuns.findById(CoordinationRunId(runId)))
        val available =
            listOf(
                StructuredCondition.TimeWindow(
                    TimePolarity.AVAILABLE,
                    LocalDate.of(2026, 10, 7),
                    null,
                    LocalTime.of(18, 0),
                    LocalTime.of(21, 0),
                ),
            )
        val unavailable =
            (0L..4L).map { day ->
                StructuredCondition.TimeWindow(
                    TimePolarity.UNAVAILABLE,
                    LocalDate.of(2026, 10, 7).plusDays(day),
                    null,
                    LocalTime.MIDNIGHT,
                    LocalTime.MIDNIGHT,
                    endsAtNextDayStart = true,
                )
            }
        partialStructured.replaceForBatch(
            run.batch.id,
            run.batch.submissionVersionIds.map { version ->
                val name =
                    requireNotNull(
                        jdbc.queryForObject(
                            "SELECT p.display_name FROM submission_versions v JOIN submission_heads h ON h.id=v.submission_id JOIN participants p ON p.id=h.participant_id WHERE v.id=?",
                            String::class.java,
                            version.value,
                        ),
                    )
                StructuredSubmissionResult(version, if (name in displayNames.take(availableCount)) available else unavailable, null)
            },
            Instant.parse("2026-10-07T05:00:00Z"),
        )
        process(runId)
        return CompletedCorrectionFixture(actors.first(), actors[1], roomId, runId)
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registerCorrectionDatabase(registry, postgres)
            registry.add("spring.flyway.target") { "17" }
        }

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
