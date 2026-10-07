package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.shared.adapter.input.web.GuestCookie
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.util.UUID

// Independent issue #92 public-contract fixtures. Only synthetic DB data; schedulers and paid parsing are disabled.
@SpringBootTest
@AutoConfigureMockMvc
abstract class CorrectionRoundPostgresFixture {
    @Autowired protected lateinit var mvc: MockMvc

    @Autowired protected lateinit var jdbc: JdbcTemplate

    @Autowired protected lateinit var mapper: ObjectMapper

    @Autowired protected lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun cleanCorrectionDatabase() {
        jdbc.execute(
            "TRUNCATE structured_submission_results, normalized_places, final_confirmations, candidate_participants, " +
                "candidate_time_ranges, candidates, coordination_attempts, outbox_events, coordination_runs, " +
                "submission_batch_items, submission_batches, manual_availability_intervals, submission_versions, " +
                "submission_heads, participants, meeting_rooms, guest_browser_sessions CASCADE",
        )
    }

    protected fun completedRoom(): CompletedCorrectionFixture {
        val created =
            mvc
                .perform(
                    post("/api/rooms")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"purpose":"synthetic correction","meeting_mode":"REMOTE","host_display_name":"host",
                             "manual_only":true,"search_start_date":"2026-10-07","search_end_date":"2026-10-12"}
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        val code = document(created).getValue("invite_code").toString()
        val host = CorrectionSession(code, cookieValue(created))
        val joined =
            mvc
                .perform(
                    post("/api/rooms/{code}/participants", code)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"display_name":"member"}"""),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        val member = CorrectionSession(code, cookieValue(joined))
        mvc.perform(save(host, "H0 original")).andExpect(status().isOk)
        mvc.perform(save(member, "M0 original")).andExpect(status().isOk)
        mvc
            .perform(
                post("/api/rooms/{code}/close", code)
                    .header("Origin", ORIGIN)
                    .cookie(host.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"confirm_early":true}"""),
            ).andExpect(status().isOk)
        val roomId =
            requireNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code = ?", UUID::class.java, code))
        val runId =
            requireNotNull(jdbc.queryForObject("SELECT id FROM coordination_runs WHERE room_id = ?", UUID::class.java, roomId))
        completePartial(runId)
        return CompletedCorrectionFixture(host, member, roomId, runId)
    }

    protected fun completePartial(runId: UUID) {
        jdbc.update(
            "UPDATE coordination_runs SET status = 'COMPLETED', candidate_quality = 'PARTIAL', " +
                "version = version + 1 WHERE id = ?",
            runId,
        )
        jdbc.update(
            "UPDATE outbox_events SET status = 'PROCESSED', published_at = now(), processed_at = now(), " +
                "processing_lease_until = NULL WHERE aggregate_id = ?",
            runId,
        )
    }

    protected fun reopen(
        fixture: CompletedCorrectionFixture,
        requestId: UUID = UUID.randomUUID(),
        generation: Long = 0,
        source: UUID = fixture.sourceRun,
        actor: CorrectionSession = fixture.host,
    ): MockHttpServletRequestBuilder =
        post("/api/rooms/{code}/reopen", actor.code)
            .header("Origin", ORIGIN)
            .cookie(actor.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"request_id":"$requestId","source_analysis_id":"$source","expected_generation":$generation}
                """.trimIndent(),
            )

    protected fun openRound(
        fixture: CompletedCorrectionFixture,
        requestId: UUID = UUID.randomUUID(),
        generation: Long = 0,
        source: UUID = fixture.sourceRun,
    ): UUID {
        val response =
            mvc
                .perform(reopen(fixture, requestId, generation, source))
                .andExpect(status().isOk)
                .andReturn()
                .response
        return UUID.fromString(child(document(response), "round").getValue("id").toString())
    }

    protected fun analyze(
        fixture: CompletedCorrectionFixture,
        roundId: UUID,
        requestId: UUID = UUID.randomUUID(),
        force: Boolean = false,
        actor: CorrectionSession = fixture.host,
    ): MockHttpServletRequestBuilder =
        post("/api/rooms/{code}/analysis", actor.code)
            .header("Origin", ORIGIN)
            .cookie(actor.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"revision_round_id":"$roundId","request_id":"$requestId","force_reparse":$force}
                """.trimIndent(),
            )

    protected fun save(
        actor: CorrectionSession,
        text: String,
        roundId: UUID? = null,
        expectedRevision: Int? = null,
    ): MockHttpServletRequestBuilder {
        val body = linkedMapOf<String, Any>("raw_text" to text)
        roundId?.let { body["revision_round_id"] = it }
        expectedRevision?.let { body["expected_revision"] = it }
        return put("/api/rooms/{code}/submission", actor.code)
            .header("Origin", ORIGIN)
            .header(HttpHeaders.ACCEPT_LANGUAGE, "ko-KR")
            .cookie(actor.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(body))
    }

    protected fun owner(actor: CorrectionSession): Map<String, Any?> =
        document(
            mvc
                .perform(get("/api/rooms/{code}/submission", actor.code).cookie(actor.cookie()))
                .andExpect(status().isOk)
                .andReturn()
                .response,
        )

    protected fun room(actor: CorrectionSession): Map<String, Any?> =
        document(
            mvc
                .perform(get("/api/rooms/{code}", actor.code).cookie(actor.cookie()))
                .andExpect(status().isOk)
                .andReturn()
                .response,
        )

    protected fun activeRun(actor: CorrectionSession): UUID = UUID.fromString(room(actor).getValue("analysis_id").toString())

    protected fun workCounts(): Map<String, Int> =
        listOf("submission_batches", "coordination_runs", "outbox_events", "coordination_attempts")
            .associateWith { requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $it", Int::class.java)) }

    protected fun versionIds(): List<UUID> =
        jdbc.queryForList("SELECT id FROM submission_versions ORDER BY id", UUID::class.java).map { requireNotNull(it) }

    protected fun closedAt(fixture: CompletedCorrectionFixture): String =
        requireNotNull(jdbc.queryForObject("SELECT closed_at::text FROM meeting_rooms WHERE id = ?", String::class.java, fixture.roomId))

    @Suppress("UNCHECKED_CAST")
    protected fun document(response: MockHttpServletResponse): Map<String, Any?> =
        mapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    protected fun child(
        document: Map<String, Any?>,
        field: String,
    ): Map<String, Any?> = document.getValue(field) as Map<String, Any?>

    private fun cookieValue(response: MockHttpServletResponse): String =
        requireNotNull(response.getHeader(HttpHeaders.SET_COOKIE)).substringAfter("meet_me_guest=").substringBefore(';')

    protected data class CorrectionSession(
        val code: String,
        val credential: String,
    ) {
        fun cookie() = Cookie(GuestCookie.NAME, credential)
    }

    protected data class CompletedCorrectionFixture(
        val host: CorrectionSession,
        val member: CorrectionSession,
        val roomId: UUID,
        val sourceRun: UUID,
    )

    companion object {
        const val ORIGIN = "https://app.meet-me.co.kr"

        fun registerCorrectionDatabase(
            registry: DynamicPropertyRegistry,
            postgres: PostgreSQLContainer,
        ) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("meetme.anonymous.allowed-origins") { ORIGIN }
            registry.add("meetme.reliability.enabled") { false }
            registry.add("meetme.data-retention.enabled") { false }
            registry.add("meetme.rate-limit.enabled") { false }
            registry.add("meetme.gemini.api-key") { "" }
        }
    }
}
