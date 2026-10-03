package com.meetme.server.submission.adapter.input.web

import com.meetme.server.coordination.application.service.MatchingProcessor
import com.meetme.server.shared.adapter.input.web.GuestCookie
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@SpringBootTest
@AutoConfigureMockMvc
class SubmissionNaturalLanguageOnlyIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var matcher: MatchingProcessor

    @Autowired private lateinit var structured: StructuredSubmissionRepository

    @BeforeEach
    fun cleanDatabase() {
        jdbc.execute(
            "TRUNCATE structured_submission_results, normalized_places, final_confirmations, candidate_participants, " +
                "candidate_time_ranges, candidates, coordination_attempts, outbox_events, coordination_runs, " +
                "submission_batch_items, submission_batches, manual_availability_intervals, submission_versions, " +
                "submission_heads, participants, meeting_rooms, guest_browser_sessions CASCADE",
        )
    }

    @ParameterizedTest(name = "invalid body case {index}: {1} without mutation")
    @MethodSource("invalidBodies")
    fun `invalid requests cannot revise stored input or close room`(
        body: String,
        code: String,
    ) {
        val host = room(expected = 2)
        mvc.perform(save(host, """{"raw_text":"original"}""")).andExpect(status().isOk)
        val member = join(host)
        val before = snapshot()

        mvc.perform(save(host, body)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value(code))
        assertEquals(before, snapshot())
        mvc.perform(save(member, body)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value(code))
        assertEquals(before, snapshot())
        mvc.perform(own(host)).andExpect(status().isOk).andExpect(jsonPath("$.revision").value(1))
        assertEquals("COLLECTING", jdbc.queryForObject("SELECT collection_status FROM meeting_rooms", String::class.java))
        assertEquals(1, count("submission_heads"))
        assertEquals(0, count("outbox_events"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", ",\"manual_available_times\":[]", ",\"manual_available_times\":null"])
    fun `omitted empty and null shim accept natural text and always return empty deprecated array`(shim: String) {
        val host = room()
        mvc
            .perform(save(host, """{"raw_text":"  place only  "$shim}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.raw_text").value("place only"))
            .andExpect(jsonPath("$.manual_available_times").isEmpty)
            .andExpect(jsonPath("$.revision").value(1))
            .andExpect(jsonPath("$.locale").value("ko-KR"))
            .andExpect(jsonPath("$.created_at").exists())
            .andExpect(jsonPath("$.editable").value(true))
        mvc.perform(own(host)).andExpect(status().isOk).andExpect(jsonPath("$.manual_available_times").isEmpty)
        assertEquals(0, count("manual_availability_intervals"))
    }

    @ParameterizedTest(name = "ECMAScript trim U+{0}")
    @ValueSource(
        ints = [
            9, 10, 11, 12, 13, 32, 160, 5760, 8192, 8193, 8194, 8195, 8196, 8197, 8198, 8199,
            8200, 8201, 8202, 8232, 8233, 8239, 8287, 12288, 65279,
        ],
    )
    fun `exact ECMAScript boundary characters trim but internal whitespace remains`(codePoint: Int) {
        val host = room()
        val whitespace = codePoint.toChar().toString()
        val internal = "A${whitespace}B  C\tD"
        mvc
            .perform(save(host, """{"raw_text":${jsonString(whitespace + internal + whitespace)}}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.raw_text").value(internal))
        val before = snapshot()
        mvc
            .perform(save(host, """{"raw_text":${jsonString(whitespace.repeat(3))}}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("SUBMISSION_INPUT_REQUIRED"))
        assertEquals(before, snapshot())
    }

    @ParameterizedTest
    @ValueSource(ints = [133, 28])
    fun `U0085 and U001C are meaningful boundary characters and single codepoint inputs`(codePoint: Int) {
        val host = room()
        val character = codePoint.toChar().toString()
        mvc
            .perform(save(host, """{"raw_text":${jsonString(character)}}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.raw_text").value(character))
        mvc
            .perform(save(host, """{"raw_text":${jsonString(character + " text " + character)}}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.raw_text").value(character + " text " + character))
    }

    @Test
    fun `length applies after exact trim and uses supplementary Unicode codepoints without truncation`() {
        val host = room()
        val accepted = "😀".repeat(500)
        mvc
            .perform(save(host, """{"raw_text":${jsonString("\uFEFF" + accepted + "\uFEFF")}}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.raw_text").value(accepted))
        val before = snapshot()
        mvc
            .perform(save(host, """{"raw_text":${jsonString("😀".repeat(501))}}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("SUBMISSION_TEXT_TOO_LONG"))
        assertEquals(before, snapshot())
    }

    @Test
    fun `aggregate 10000 codepoints uses latest revisions and overflow leaves all state unchanged`() {
        val host = room()
        val text = "😀".repeat(500)
        mvc.perform(save(host, """{"raw_text":${jsonString(text)}}""")).andExpect(status().isOk)
        repeat(19) {
            mvc.perform(save(join(host), """{"raw_text":${jsonString(text)}}""")).andExpect(status().isOk)
        }
        val overflow = join(host)
        val before = snapshot()
        mvc
            .perform(save(overflow, """{"raw_text":"x"}"""))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SUBMISSION_BATCH_TEXT_LIMIT_EXCEEDED"))
        assertEquals(before, snapshot())
        mvc.perform(save(host, """{"raw_text":${jsonString(text)}}""")).andExpect(status().isOk)
        mvc.perform(save(host, """{"raw_text":"x"}""")).andExpect(status().isOk)
        mvc.perform(save(overflow, """{"raw_text":${jsonString("😀".repeat(499))}}""")).andExpect(status().isOk)
        assertEquals(21, count("submission_heads"))
        assertEquals(0, count("manual_availability_intervals"))
    }

    @Test
    fun `collecting archived slot-only input is nullable on GET and revisable without deleting old rows`() {
        val host = room()
        mvc.perform(save(host, """{"raw_text":"synthetic seed"}""")).andExpect(status().isOk)
        val oldId = archiveLatest(host)
        val oldRows = jdbc.queryForList("SELECT * FROM manual_availability_intervals WHERE submission_version_id = ?", oldId)
        mvc
            .perform(own(host))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.raw_text").value(nullValue()))
            .andExpect(jsonPath("$.manual_available_times").isEmpty)
            .andExpect(jsonPath("$.revision").value(1))
        mvc
            .perform(save(host, """{"raw_text":"new natural condition"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.revision").value(2))
            .andExpect(jsonPath("$.raw_text").value("new natural condition"))
            .andExpect(jsonPath("$.manual_available_times").isEmpty)
        assertEquals(oldRows, jdbc.queryForList("SELECT * FROM manual_availability_intervals WHERE submission_version_id = ?", oldId))
        assertEquals(2, count("submission_versions"))
        assertEquals(1, count("submission_heads"))
        assertEquals(1, count("manual_availability_intervals"))
        assertEquals(null, jdbc.queryForObject("SELECT raw_text FROM submission_versions WHERE id = ?", String::class.java, oldId))
    }

    @Test
    fun `legacy mixed batch exposes partial counts and warnings with Plan C and preserves confirmation`() {
        val host = room()
        val naturalMember = join(host)
        val legacy = join(host)
        listOf(host, naturalMember, legacy).forEach {
            mvc.perform(save(it, """{"raw_text":"synthetic natural"}""")).andExpect(status().isOk)
        }
        val legacyId = archiveLatest(legacy)
        closeAndMatch(host, listOf(legacyId))
        mvc
            .perform(get("/api/rooms/{code}", host.code).cookie(host.cookie()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.public_status").value("READY_WITH_WARNINGS"))
        val candidates =
            mvc
                .perform(get("/api/rooms/{code}/candidates", host.code).cookie(naturalMember.cookie()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.quality").value("PARTIAL"))
                .andExpect(jsonPath("$.applied_submissions").value(2))
                .andExpect(jsonPath("$.total_submissions").value(3))
                .andExpect(jsonPath("$.unapplied_inputs").value(1))
                .andExpect(jsonPath("$.candidates[0].plan_type").value("C"))
                .andExpect(jsonPath("$.candidates[0].attendance_count").value(2))
                .andExpect(jsonPath("$.candidates[0].total_participants").value(3))
                .andExpect(jsonPath("$.candidates[0].participant_ids").doesNotExist())
                .andReturn()
                .response.contentAsString
        assertFalse(candidates.contains("synthetic natural"))
        assertFalse(candidates.contains("LEGACY_MANUAL_ONLY_UNSUPPORTED"))
        assertLegacyPrivacy(host, legacy)
        val id = requireNotNull(jdbc.queryForObject("SELECT id FROM candidates", UUID::class.java))
        repeat(2) {
            mvc
                .perform(
                    post("/api/rooms/{code}/candidates/{id}/confirmation", host.code, id)
                        .header("Origin", ORIGIN)
                        .cookie(host.cookie()),
                ).andExpect(status().isOk)
        }
        val before = snapshot()
        matcher.process(batchId())
        assertEquals(before, snapshot())
        mvc
            .perform(get("/api/rooms/{code}/result", host.code).cookie(legacy.cookie()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.candidate.candidate_id").value(id.toString()))
            .andExpect(jsonPath("$.raw_text").doesNotExist())
        assertEquals(3, count("submission_batch_items"))
        assertEquals(1, count("manual_availability_intervals"))
    }

    @Test
    fun `one natural plus legacy finishes NO_MATCH with host-only unapplied endpoint available`() {
        val host = room()
        val legacy = join(host)
        listOf(host, legacy).forEach {
            mvc.perform(save(it, """{"raw_text":"synthetic natural"}""")).andExpect(status().isOk)
        }
        closeAndMatch(host, listOf(archiveLatest(legacy)))
        mvc
            .perform(get("/api/rooms/{code}", host.code).cookie(host.cookie()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.public_status").value("NO_MATCH"))
        mvc
            .perform(get("/api/rooms/{code}/candidates", host.code).cookie(legacy.cookie()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.candidates").isEmpty)
            .andExpect(jsonPath("$.quality").value("PARTIAL"))
            .andExpect(jsonPath("$.applied_submissions").value(1))
            .andExpect(jsonPath("$.total_submissions").value(2))
            .andExpect(jsonPath("$.unapplied_inputs").value(1))
        assertLegacyPrivacy(host, legacy)
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM coordination_runs", String::class.java))
        assertEquals(2, count("submission_batch_items"))
    }

    @Test
    fun `OpenAPI declares required natural text and deprecated empty manual shim`() {
        mvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.components.schemas.SaveSubmissionRequest.required[?(@ == 'raw_text')]").isNotEmpty)
            .andExpect(jsonPath("$.components.schemas.SaveSubmissionRequest.properties.raw_text.maxLength").value(500))
            .andExpect(jsonPath("$.components.schemas.SaveSubmissionRequest.properties.manual_available_times.deprecated").value(true))
            .andExpect(jsonPath("$.components.schemas.SaveSubmissionRequest.properties.manual_available_times.maxItems").value(0))
            .andExpect(
                jsonPath("$.components.schemas.SaveSubmissionRequest.properties.manual_available_times.type")
                    .value(containsInAnyOrder("array", "null")),
            ).andExpect(jsonPath("$.components.schemas.SaveSubmissionRequest.properties.manual_available_times.items").isEmpty)
            .andExpect(jsonPath("$.components.schemas.SaveSubmissionRequest.properties.manual_available_times['\$ref']").doesNotExist())
            .andExpect(jsonPath("$.components.schemas.SubmissionResponse.properties.manual_available_times.deprecated").value(true))
    }

    private fun closeAndMatch(
        host: Session,
        legacyIds: List<UUID>,
    ) {
        mvc
            .perform(
                post("/api/rooms/{code}/close", host.code)
                    .header("Origin", ORIGIN)
                    .cookie(host.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"confirm_early":true}"""),
            ).andExpect(status().isOk)
        val ids = jdbc.queryForList("SELECT submission_version_id FROM submission_batch_items", UUID::class.java)
        structured.replaceForBatch(
            batchId(),
            ids.filterNot { it in legacyIds }.map { id ->
                StructuredSubmissionResult(
                    SubmissionVersionId(requireNotNull(id)),
                    listOf(
                        StructuredCondition.TimeWindow(TimePolarity.AVAILABLE, DATE, null, LocalTime.of(9, 0), LocalTime.of(12, 0)),
                    ),
                    null,
                )
            },
            Instant.now(),
        )
        jdbc.update("UPDATE coordination_runs SET status = 'MATCHING', version = version + 1")
        matcher.process(batchId())
    }

    private fun assertLegacyPrivacy(
        host: Session,
        legacy: Session,
    ) {
        mvc
            .perform(get("/api/rooms/{code}/candidates/unapplied-inputs", host.code).cookie(host.cookie()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].raw_text").value(nullValue()))
            .andExpect(jsonPath("$[0].reason").value("LEGACY_MANUAL_ONLY_UNSUPPORTED"))
        mvc
            .perform(get("/api/rooms/{code}/candidates/unapplied-inputs", host.code).cookie(legacy.cookie()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("HOST_PERMISSION_REQUIRED"))
        mvc
            .perform(get("/api/rooms/{code}/candidates/unapplied-inputs", host.code))
            .andExpect(status().isUnauthorized)
    }

    private fun archiveLatest(owner: Session): UUID {
        val id =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT h.latest_version_id FROM submission_heads h JOIN participants p ON p.id = h.participant_id " +
                        "JOIN guest_browser_sessions g ON g.id = p.guest_session_id WHERE g.credential_digest = ?",
                    UUID::class.java,
                    credentialDigest(owner),
                ),
            )
        jdbc.update("UPDATE submission_versions SET raw_text = NULL WHERE id = ?", id)
        jdbc.update(
            "INSERT INTO manual_availability_intervals " +
                "(id, submission_version_id, interval_order, interval_kind, local_date, start_time, end_time) " +
                "VALUES (?, ?, 0, 'DATED', ?, '18:00', '20:00')",
            UUID.randomUUID(),
            id,
            java.sql.Date.valueOf(DATE),
        )
        return id
    }

    @Autowired private lateinit var credentials: com.meetme.server.participant.application.port.output.GuestCredentialPort

    private fun credentialDigest(owner: Session) = credentials.digest(owner.credential)

    private fun batchId() = SubmissionBatchId(requireNotNull(jdbc.queryForObject("SELECT id FROM submission_batches", UUID::class.java)))

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)

    private fun snapshot() =
        listOf(
            "meeting_rooms",
            "participants",
            "submission_heads",
            "submission_versions",
            "manual_availability_intervals",
            "submission_batches",
            "submission_batch_items",
            "coordination_runs",
            "outbox_events",
            "structured_submission_results",
            "candidates",
            "candidate_time_ranges",
            "final_confirmations",
        ).associateWith { table ->
            jdbc.queryForObject(
                "SELECT coalesce(jsonb_agg(row_data ORDER BY row_data::text), '[]'::jsonb)::text " +
                    "FROM (SELECT to_jsonb(t) AS row_data FROM $table t) rows",
                String::class.java,
            )
        }

    private fun room(expected: Int? = null): Session =
        session(
            mvc
                .perform(
                    post("/api/rooms")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"purpose":"synthetic","meeting_mode":"REMOTE","host_display_name":"host",
                            "expected_participants":${expected ?: "null"},"manual_only":${expected == null},
                            "search_start_date":"2026-09-21","search_end_date":"2026-09-23"}
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response,
        )

    private fun join(host: Session): Session =
        session(
            mvc
                .perform(
                    post("/api/rooms/{code}/participants", host.code)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"display_name":"member"}"""),
                ).andExpect(status().isCreated)
                .andReturn()
                .response,
            host.code,
        )

    private fun session(
        response: org.springframework.mock.web.MockHttpServletResponse,
        code: String? = null,
    ) = Session(
        code ?: response.contentAsString.substringAfter("\"invite_code\":\"").substringBefore('"'),
        requireNotNull(response.getHeader(HttpHeaders.SET_COOKIE)).substringAfter("meet_me_guest=").substringBefore(';'),
    )

    private fun save(
        owner: Session,
        body: String,
    ) = put("/api/rooms/{code}/submission", owner.code)
        .header("Origin", ORIGIN)
        .header(HttpHeaders.ACCEPT_LANGUAGE, "ko-KR")
        .cookie(owner.cookie())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)

    private fun own(owner: Session) = get("/api/rooms/{code}/submission", owner.code).cookie(owner.cookie())

    private data class Session(
        val code: String,
        val credential: String,
    ) {
        fun cookie() = Cookie(GuestCookie.NAME, credential)
    }

    companion object {
        private const val ORIGIN = "https://app.meet-me.co.kr"
        private val DATE = LocalDate.of(2026, 9, 21)
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("meetme.anonymous.allowed-origins") { ORIGIN }
        }

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()

        @JvmStatic
        fun invalidBodies(): Stream<Arguments> =
            Stream.of(
                Arguments.of("{}", "SUBMISSION_INPUT_REQUIRED"),
                Arguments.of("""{"raw_text":null}""", "SUBMISSION_INPUT_REQUIRED"),
                Arguments.of("""{"raw_text":""}""", "SUBMISSION_INPUT_REQUIRED"),
                Arguments.of("""{"raw_text":"   "}""", "SUBMISSION_INPUT_REQUIRED"),
                Arguments.of("""{"raw_text":"${"x".repeat(501)}"}""", "SUBMISSION_TEXT_TOO_LONG"),
                *listOf(
                    "[{}]",
                    "[null]",
                    "[1]",
                    "[\"slot\"]",
                    "[[]]",
                    "[{\"kind\":\"INVALID\"}]",
                    "[{\"kind\":\"WEEKLY\",\"day_of_week\":\"MONDAY\",\"start_time\":\"18:00\",\"end_time\":\"20:00\"}]",
                ).map {
                    Arguments.of(
                        """{"raw_text":"valid","manual_available_times":$it}""",
                        "SUBMISSION_MANUAL_AVAILABILITY_UNSUPPORTED",
                    )
                }.toTypedArray(),
                *listOf("{}", "\"slot\"", "1", "true")
                    .map {
                        Arguments.of("""{"raw_text":"valid","manual_available_times":$it}""", "VALIDATION_FAILED")
                    }.toTypedArray(),
                *listOf("1", "true", "[]", "{}")
                    .map {
                        Arguments.of("""{"raw_text":$it}""", "VALIDATION_FAILED")
                    }.toTypedArray(),
                Arguments.of("""{"raw_text":"valid","unknown":null}""", "VALIDATION_FAILED"),
            )

        // JSON escaping only; this helper never decides which codepoints the server should trim.
        private fun jsonString(value: String): String =
            buildString {
                append('"')
                value.forEach { character ->
                    when (character) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        in '\u0000'..'\u001F' -> append("\\u" + character.code.toString(16).padStart(4, '0'))
                        else -> append(character)
                    }
                }
                append('"')
            }
    }
}
