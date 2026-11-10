package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.application.service.PrecheckProcessingService
import com.meetme.server.precheck.domain.PrecheckStatus
import com.meetme.server.support.mappedPortReadyPostgres
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Same-clock native candidate -> real claim/publication -> PG automatic close. CLEAR is synthetic, with no provider call. */
@Import(DefaultAnalysisPrecheckClockPostgresTest.Configuration::class)
class DefaultAnalysisPrecheckClockPostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var clock: SourceClock

    @Autowired private lateinit var processing: PrecheckProcessingService

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @BeforeEach fun resetSourceClock() = clock.set(BOUNDARY.minusSeconds(60))

    @ParameterizedTest
    @CsvSource(
        "PRE_SUBMIT_V1,123456499",
        "PRE_SUBMIT_V1,123456501",
        "PRE_SUBMIT_V1,999999999",
        "PRE_SUBMIT_V2,123456499",
        "PRE_SUBMIT_V2,123456501",
        "PRE_SUBMIT_V2,999999999",
    )
    fun `DPC01 approval at the same pre-midnight clock preserves source anchors and closes a coherent V5 batch`(
        protocol: String,
        nanos: Int,
    ) {
        val hostCookie = accounts.newAccount("precheck clock host")
        val created =
            mvc
                .perform(
                    post("/api/rooms")
                        .cookie(hostCookie)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"purpose":"synthetic precheck clock","meeting_mode":"REMOTE","expected_participants":2,
                   "search_start_date":"2027-01-08","search_end_date":"2027-01-12"}""",
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        val code = document(created).getValue("invite_code").toString()
        val room =
            UUID.fromString(
                requireNotNull(
                    jdbc.queryForObject(
                        "SELECT id::text FROM meeting_rooms WHERE invite_code=?",
                        String::class.java,
                        code,
                    ),
                ),
            )
        // New synthetic rooms choose each historical protocol explicitly; no existing production row is migrated.
        jdbc.update("UPDATE meeting_rooms SET submission_protocol=? WHERE id=?", protocol, room)
        val memberCookie = accounts.newAccount("precheck clock member")
        mvc
            .perform(
                post("/api/rooms/$code/participants")
                    .cookie(memberCookie)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isCreated)
        val requestAt = BOUNDARY.plusNanos(nanos.toLong())
        clock.set(requestAt)
        val originals =
            listOf(
                "2027-01-08 19:00~21:00 synthetic first original",
                "2027-01-08 19:00~21:00 synthetic second original",
            )
        val checks = listOf(candidate(code, hostCookie, originals[0]), candidate(code, memberCookie, originals[1]))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM submission_versions", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM coordination_runs", Int::class.java))

        checks.forEachIndexed { index, check ->
            val claim = assertNotNull(processing.claim(check), "Fresh native check must remain claimable at the same clock")
            assertEquals(originals[index], claim.check.candidate.rawText)
            val result = runCatching { processing.publish(claim, PrecheckProviderResult(PrecheckVerdict.CLEAR), null) }
            assertTrue(
                result.isSuccess,
                "Valid same-clock $protocol approval at $requestAt failed; candidate=${claim.check.candidate.createdAt}, " +
                    "reference=${claim.check.candidate.referenceAt}; ${result.exceptionOrNull()?.stackTraceToString()}",
            )
            assertEquals(PrecheckStatus.CLEAR, result.getOrThrow())
        }
        assertEquals("CLOSED", jdbc.queryForObject("SELECT collection_status FROM meeting_rooms WHERE id=?", String::class.java, room))
        assertEquals(
            "EXPECTED_PARTICIPANTS",
            jdbc.queryForObject("SELECT closure_reason FROM meeting_rooms WHERE id=?", String::class.java, room),
        )
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM submission_versions", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM coordination_runs", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM analysis_invocations", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM coordination_attempts", Int::class.java))

        val run =
            UUID.fromString(
                requireNotNull(jdbc.queryForObject("SELECT active_run_id::text FROM meeting_rooms WHERE id=?", String::class.java, room)),
            )
        val route = assertNotNull(routes.find(run))
        val choice = route.choice as FinalAnalysisRouteChoice.DslStructuralV5
        assertEquals("schedule-dsl-evidence-response-5", choice.identity.responseContractVersion)
        assertEquals(run, route.runId)
        assertEquals(room, route.roomId)
        assertEquals(run, choice.context.runId)
        assertEquals(route.batchId, choice.context.batchId)
        assertEquals(route.batchId, jdbc.queryForObject("SELECT batch_id FROM coordination_runs WHERE id=?", UUID::class.java, run))
        val batchIds =
            jdbc
                .queryForList(
                    "SELECT submission_version_id FROM submission_batch_items WHERE batch_id=?",
                    UUID::class.java,
                    route.batchId,
                ).toSet()
        assertEquals(2, batchIds.size)
        assertEquals(
            batchIds,
            choice.context.inputs
                .map { it.submissionVersionId }
                .toSet(),
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM submission_batch_items b JOIN submission_versions v ON v.id=b.submission_version_id " +
                    "JOIN submission_batches s ON s.id=b.batch_id WHERE b.batch_id=? AND v.created_at>s.fixed_at",
                Int::class.java,
                route.batchId,
            ),
        )
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=?", Int::class.java, run))
        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT count(*) FROM precheck_checks " + "WHERE status='CLEAR' AND retry_count=0 AND generation=1",
                Int::class.java,
            ),
        )
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM precheck_checks WHERE question IS NOT NULL", Int::class.java))

        val expectedLocalDate = requestAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
        checks.forEachIndexed { index, check ->
            val row =
                jdbc.queryForMap(
                    "SELECT c.raw_text,c.created_at candidate_at," +
                        "c.interpretation_reference_at reference_at,c.input_evidence candidate_evidence," +
                        "v.raw_text accepted_text,v.created_at accepted_at,v.input_evidence accepted_evidence " +
                        "FROM precheck_checks k JOIN precheck_candidates c ON c.id=k.candidate_id " +
                        "JOIN submission_versions v ON v.id=c.accepted_version_id WHERE k.id=?",
                    check,
                )
            val candidateAt = (row.getValue("candidate_at") as java.sql.Timestamp).toInstant()
            val acceptedAt = (row.getValue("accepted_at") as java.sql.Timestamp).toInstant()
            val referenceAt = (row.getValue("reference_at") as java.sql.Timestamp).toInstant()
            assertEquals(originals[index], row["raw_text"])
            assertEquals(originals[index], row["accepted_text"])
            assertEquals(candidateAt, acceptedAt)
            assertEquals(candidateAt, referenceAt)
            assertEquals(expectedLocalDate, candidateAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate())
            assertEquals(expectedLocalDate, referenceAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate())
            if (protocol == "PRE_SUBMIT_V2") {
                val candidateEvidence = mapper.readTree(row.getValue("candidate_evidence").toString())
                val acceptedEvidence = mapper.readTree(row.getValue("accepted_evidence").toString())
                assertEquals(referenceAt, Instant.parse(candidateEvidence.path("referenceAt").asText()))
                assertEquals(referenceAt, Instant.parse(acceptedEvidence.path("referenceAt").asText()))
                assertEquals("CONFIRMED", acceptedEvidence.path("interpretation").asText())
                assertTrue(acceptedEvidence.path("supplement").isNull || acceptedEvidence.path("supplement").isMissingNode)
            } else {
                assertNull(row["candidate_evidence"])
                assertNull(row["accepted_evidence"])
            }
        }
    }

    private fun candidate(
        code: String,
        cookie: Cookie,
        raw: String,
    ): UUID {
        val result =
            mvc
                .perform(
                    post("/api/rooms/$code/submission/checks")
                        .cookie(cookie)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            mapper.writeValueAsString(
                                mapOf("request_id" to UUID.randomUUID(), "expected_revision" to 0, "raw_text" to raw),
                            ),
                        ),
                ).andExpect(status().isAccepted)
                .andReturn()
                .response
        val outcome = document(result)
        return UUID.fromString(child(outcome, "operation_outcome").getValue("check_id").toString())
    }

    class SourceClock : Clock() {
        private val value = AtomicReference(BOUNDARY.minusSeconds(60))

        fun set(at: Instant) {
            value.set(at)
        }

        override fun instant(): Instant = value.get()

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Configuration {
        @Bean @Primary
        fun precheckSourceClock() = SourceClock()
    }

    companion object {
        private val BOUNDARY = Instant.parse("2027-01-01T14:59:59Z")
        private val postgres = mappedPortReadyPostgres().apply { start() }

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registerCorrectionDatabase(registry, postgres)
            registry.add("meetme.analysis.default-contract") { "DSL_STRUCTURAL_V5" }
            for (flag in listOf(
                "union-preferred-availability",
                "server-evidence-references",
                "search-relative-dates",
                "calendar-month-facts",
                "indexed-evidence-selections",
                "weekly-schedule-rules",
            )) {
                registry.add("meetme.experiment.$flag") { true }
            }
        }

        @JvmStatic @AfterAll
        fun stop() = postgres.stop()
    }
}
