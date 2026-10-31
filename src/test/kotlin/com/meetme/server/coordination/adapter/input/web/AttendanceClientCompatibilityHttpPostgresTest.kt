package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ADR-066 compatibility: real HTTP, matcher and PostgreSQL; synthetic inputs and no provider calls. */
class AttendanceClientCompatibilityHttpPostgresTest : RecommendationPostgresFixture() {
    @Autowired private lateinit var structured: StructuredSubmissionRepository

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Test
    fun `ACC01 candidates reject every unsupported capability without filtering mixed attendance`() {
        val fixture = conditionalRoom()
        val before = storedState(fixture)
        val supported = read(candidates(fixture), SUPPORT)
        assertMixed(items(supported, "candidates"))
        for (support in UNSUPPORTED) updateRequired(candidates(fixture), support)
        assertEquals(before, storedState(fixture))
    }

    @Test
    fun `ACC02 primary recommendations reject every unsupported capability without hiding false variants`() {
        val fixture = conditionalRoom()
        val supported = primary(fixture)
        assertEquals("diverse-time-v1", supported["protocol"])
        assertMixed(items(supported, "options").flatMap { items(it, "variants") })
        for (support in UNSUPPORTED) updateRequired(primaryRequest(fixture), support)
    }

    @Test
    fun `ACC03 alternatives reject every unsupported capability and retain the complete supported page`() {
        val fixture = conditionalRoom(days = 5)
        val page = read(alternatives(fixture, limit = 100), SUPPORT)
        assertTrue(items(page, "options").isNotEmpty(), "The test must exercise actual alternatives")
        assertMixed(items(page, "options").flatMap { items(it, "variants") })
        for (support in UNSUPPORTED) updateRequired(alternatives(fixture, limit = 100), support)
        assertEquals(page, read(alternatives(fixture, limit = 100), SUPPORT))
    }

    @Test
    fun `ACC04 unsupported typed confirmation is rejected before any durable write`() {
        val fixture = conditionalRoom()
        val option = items(primary(fixture), "options").first()
        val variant = conditionalVariant(option)
        val before = storedState(fixture)
        for (support in UNSUPPORTED) {
            updateRequired(selection(fixture, option, variant), support)
            assertEquals(before, storedState(fixture), "Compatibility rejection must precede confirmation and room version writes")
        }
    }

    @Test
    fun `ACC05 unsupported legacy confirmation is rejected before any durable write`() {
        val fixture = completedRoom()
        val candidate = addLegacyCandidate(fixture, conditional = true)
        val before = storedState(fixture)
        for (support in UNSUPPORTED) {
            updateRequired(legacySelection(fixture, candidate), support)
            assertEquals(before, storedState(fixture))
        }
    }

    @Test
    fun `ACC06 typed result rejects unsupported readers after a supported conditional selection`() {
        val fixture = conditionalRoom()
        val option = items(primary(fixture), "options").first()
        val chosen = read(selection(fixture, option, conditionalVariant(option)), SUPPORT)
        val before = storedState(fixture)
        for (support in UNSUPPORTED) updateRequired(resultRequest(fixture), support)
        assertEquals(chosen, read(resultRequest(fixture), SUPPORT))
        assertEquals(before, storedState(fixture))
    }

    @Test
    fun `ACC07 legacy result rejects unsupported readers without changing the historical protocol`() {
        val fixture = completedRoom()
        val candidate = addLegacyCandidate(fixture, conditional = true)
        val chosen = read(legacySelection(fixture, candidate), SUPPORT)
        val before = storedState(fixture)
        for (support in UNSUPPORTED) updateRequired(resultRequest(fixture), support)
        assertEquals(chosen, read(resultRequest(fixture), SUPPORT))
        assertEquals(null, room(fixture.host)["recommendation_protocol"])
        assertEquals(before, storedState(fixture))
    }

    @Test
    fun `ACC08 supported typed selection preserves false explanation chosen time and rejects old client replay`() {
        val fixture = conditionalRoom()
        val beforePending = pendingRows(fixture)
        val option = items(primary(fixture), "options").first()
        val variant = conditionalVariant(option)
        val start = Instant.parse(child(option, "time_range").getValue("start_at").toString()).plusSeconds(900).toString()
        val end = Instant.parse(child(option, "time_range").getValue("end_at").toString()).minusSeconds(900).toString()
        val selected = read(selection(fixture, option, variant, start = start, end = end), SUPPORT)
        assertConditional(child(selected, "candidate"))
        assertEquals(Instant.parse(start), Instant.parse(child(selected, "selection").getValue("start_at").toString()))
        assertEquals(Instant.parse(end), Instant.parse(child(selected, "selection").getValue("end_at").toString()))
        assertEquals("diverse-time-v1", child(selected, "selection")["protocol"])
        val committed = storedState(fixture)
        for (support in UNSUPPORTED) {
            updateRequired(selection(fixture, option, variant, start = start, end = end), support)
            assertEquals(committed, storedState(fixture), "An identical replay must not bypass compatibility")
        }
        assertEquals(selected, read(selection(fixture, option, variant, start = start, end = end), SUPPORT))
        assertEquals(selected, read(resultRequest(fixture), SUPPORT))
        assertEquals(beforePending, pendingRows(fixture))
        assertEquals(committed, storedState(fixture))
        assertEquals("diverse-time-v1", jdbc.queryForObject("SELECT protocol FROM recommendation_analyses", String::class.java))
    }

    @Test
    fun `ACC09 supported legacy selection preserves false and blocks an unsupported identical replay`() {
        val fixture = completedRoom()
        val candidate = addLegacyCandidate(fixture, conditional = true)
        val beforePending = pendingRows(fixture)
        val selected = read(legacySelection(fixture, candidate), SUPPORT)
        assertConditional(child(selected, "candidate"))
        val committed = storedState(fixture)
        for (support in UNSUPPORTED) updateRequired(legacySelection(fixture, candidate), support)
        assertEquals(selected, read(legacySelection(fixture, candidate), SUPPORT))
        assertEquals(selected, read(resultRequest(fixture), SUPPORT))
        assertEquals(beforePending, pendingRows(fixture))
        assertEquals(committed, storedState(fixture))
    }

    @Test
    fun `ACC10 genuinely confirmed typed attendance remains usable by old clients across reads and selection`() {
        val fixture = recommendedRoom(days = 5)
        // A normal EITHER projection contains only genuinely confirmed variants when no travel restriction exists.
        for (support in UNSUPPORTED) {
            val view = read(primaryRequest(fixture), support)
            val option = items(view, "options").first()
            val variant = items(option, "variants").first()
            assertTrue(items(view, "options").flatMap { items(it, "variants") }.all { it["attendance_confirmed"] == true })
            assertTrue(items(read(candidates(fixture), support), "candidates").all { it["attendance_confirmed"] == true })
            assertTrue(items(read(alternatives(fixture, limit = 100), support), "options").isNotEmpty())
            val selected = read(selection(fixture, option, variant), support)
            assertEquals(true, child(selected, "candidate")["attendance_confirmed"])
            assertEquals(selected, read(selection(fixture, option, variant), support))
            assertEquals(selected, read(resultRequest(fixture), support))
        }
    }

    @Test
    fun `ACC11 old verified legacy results remain readable and confirmable with absent or unknown support`() {
        val fixture = completedRoom()
        val candidate = addLegacyCandidate(fixture, conditional = false)
        for (support in UNSUPPORTED) {
            assertEquals(true, items(read(candidates(fixture), support), "candidates").single()["attendance_confirmed"])
            val selected = read(legacySelection(fixture, candidate), support)
            assertEquals(true, child(selected, "candidate")["attendance_confirmed"])
            assertEquals(selected, read(resultRequest(fixture), support))
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM final_confirmations", Int::class.java))
        assertEquals(null, room(fixture.host)["recommendation_protocol"])
    }

    @Test
    fun `ACC12 attendance support never replaces authentication participation host or origin validation`() {
        val fixture = conditionalRoom()
        val foreign = accounts.newAccount("unrelated synthetic account")
        val option = items(primary(fixture), "options").first()
        val variant = conditionalVariant(option)
        val before = storedState(fixture)
        for (support in listOf(null, SUPPORT)) {
            for (suffix in listOf("candidates", "recommendations", "result")) {
                unchangedError(get("/api/rooms/{code}/$suffix", fixture.host.code), 401, support)
                unchangedError(get("/api/rooms/{code}/$suffix", fixture.host.code).cookie(foreign), 403, support)
            }
            unchangedError(selection(fixture, option, variant, actor = fixture.member), 403, support)
            unchangedError(
                selection(fixture, option, variant).with { request ->
                    request.removeHeader("Origin")
                    request.addHeader("Origin", "https://untrusted.invalid")
                    request
                },
                403,
                support,
            )
            unchangedError(
                get("/api/rooms/{code}/recommendations/alternatives", fixture.host.code).param("analysis_id", fixture.sourceRun.toString()),
                401,
                support,
            )
        }
        assertEquals(before, storedState(fixture))
    }

    @Test
    fun `ACC13 stale invalid selection and cursor errors retain their original meaning`() {
        val fixture = conditionalRoom(days = 5)
        val option = items(primary(fixture), "options").first()
        val variant = conditionalVariant(option)
        val before = storedState(fixture)
        for (support in listOf(null, SUPPORT)) {
            unchangedError(selection(fixture, option, variant, analysis = UUID.randomUUID()), 409, support, "STALE_ANALYSIS")
            unchangedError(alternatives(fixture, analysis = UUID.randomUUID()), 409, support, "STALE_ANALYSIS")
            unchangedError(alternatives(fixture, cursor = "not-a-valid-cursor"), 400, support, "VALIDATION_FAILED")
            unchangedError(selection(fixture, option, variant, start = "2026-10-06T00:00:00Z"), 400, support, "VALIDATION_FAILED")
        }
        assertEquals(before, storedState(fixture))
    }

    @Test
    fun `ACC14 open correction round and typed protocol fences take precedence over compatibility`() {
        val fixture = conditionalRoom()
        val option = items(primary(fixture), "options").first()
        val variant = conditionalVariant(option)
        val candidate = items(read(candidates(fixture), SUPPORT), "candidates").first().getValue("candidate_id")
        unchangedError(legacySelection(fixture, UUID.fromString(candidate.toString())), 409, null, "RECOMMENDATION_SELECTION_REQUIRED")
        openRound(fixture)
        val before = storedState(fixture)
        for (support in listOf(null, SUPPORT)) {
            for (request in listOf(candidates(fixture), primaryRequest(fixture), alternatives(fixture), resultRequest(fixture))) {
                unchangedError(request, 409, support, "CANDIDATES_NOT_READY")
            }
            unchangedError(selection(fixture, option, variant), 409, support, "CANDIDATES_NOT_READY")
            unchangedError(legacySelection(fixture, UUID.fromString(candidate.toString())), 409, support, "CANDIDATES_NOT_READY")
        }
        assertEquals(before, storedState(fixture))
    }

    @Test
    fun `ACC15 selection conflict still wins over an old client attempt to choose another conditional time`() {
        val fixture = conditionalRoom(days = 5)
        val options = items(primary(fixture), "options")
        assertTrue(options.size >= 2)
        read(selection(fixture, options[0], conditionalVariant(options[0])), SUPPORT)
        val before = storedState(fixture)
        unchangedError(selection(fixture, options[1], conditionalVariant(options[1])), 409, null, "CANDIDATE_ALREADY_CONFIRMED")
        assertEquals(before, storedState(fixture))
    }

    @Test
    fun `ACC16 every supported result path returns no store including conditional confirmations`() {
        val fixture = conditionalRoom(days = 5)
        val option = items(primary(fixture), "options").first()
        for (request in listOf(candidates(fixture), primaryRequest(fixture), alternatives(fixture, limit = 100))) {
            val response = perform(request, SUPPORT)
            assertEquals(200, response.status)
            assertNoStore(response)
        }
        val confirmation = perform(selection(fixture, option, conditionalVariant(option)), SUPPORT)
        assertEquals(200, confirmation.status)
        assertNoStore(confirmation)
        val result = perform(resultRequest(fixture), SUPPORT)
        assertEquals(200, result.status)
        assertNoStore(result)
        val legacy = completedRoom()
        val candidate = addLegacyCandidate(legacy, conditional = true)
        val legacyConfirmation = perform(legacySelection(legacy, candidate), SUPPORT)
        assertEquals(200, legacyConfirmation.status)
        assertNoStore(legacyConfirmation)
    }

    @Test
    fun `ACC17 legacy authentication missing candidate and unconfirmed result errors remain intact`() {
        val fixture = completedRoom()
        val candidate = addLegacyCandidate(fixture, conditional = true)
        val before = storedState(fixture)
        for (support in listOf(null, SUPPORT)) {
            unchangedError(
                post("/api/rooms/{code}/candidates/{id}/confirmation", fixture.host.code, candidate).header("Origin", ORIGIN),
                401,
                support,
            )
            unchangedError(
                post("/api/rooms/{code}/candidates/{id}/confirmation", fixture.host.code, candidate)
                    .header("Origin", ORIGIN)
                    .cookie(fixture.member.cookie()),
                403,
                support,
                "HOST_PERMISSION_REQUIRED",
            )
            unchangedError(legacySelection(fixture, UUID.randomUUID()), 404, support, "CANDIDATE_NOT_FOUND")
            unchangedError(resultRequest(fixture), 404, support, "RESULT_NOT_CONFIRMED")
        }
        assertEquals(before, storedState(fixture))
    }

    private fun conditionalRoom(days: Int = 1): CompletedCorrectionFixture {
        val fixture = matchingRoom()
        prepareMatching(fixture.sourceRun, days = days)
        val run = requireNotNull(runs.findById(CoordinationRunId(fixture.sourceRun)))
        structured.replaceForBatch(
            run.batch.id,
            structured.findByBatch(run.batch.id).mapIndexed { index, result ->
                val conditions =
                    if (index == 0) result.conditions + StructuredCondition.TravelConstraint(PRIVATE_TRAVEL) else result.conditions
                result.copy(conditions = conditions)
            },
            Instant.parse("2026-10-07T05:00:00Z"),
        )
        process(fixture.sourceRun)
        return fixture
    }

    private fun addLegacyCandidate(
        fixture: CompletedCorrectionFixture,
        conditional: Boolean,
    ): UUID {
        jdbc.update("UPDATE meeting_rooms SET meeting_mode='IN_PERSON' WHERE id=?", fixture.roomId)
        val candidate = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO candidates(id, coordination_run_id, rank, plan_type, meeting_mode, attendance_count, total_participants) " +
                "VALUES (?, ?, 1, 'PLAN_A', 'IN_PERSON', 2, 2)",
            candidate,
            fixture.sourceRun,
        )
        jdbc.update(
            "INSERT INTO candidate_time_ranges(candidate_id, range_order, start_at, end_at) " +
                "VALUES (?, 0, '2026-10-07T10:00:00Z', '2026-10-07T11:00:00Z')",
            candidate,
        )
        val participants =
            jdbc.queryForList("SELECT id FROM participants WHERE room_id=? ORDER BY id::text", UUID::class.java, fixture.roomId)
        participants.forEachIndexed { index, participant ->
            jdbc.update(
                "INSERT INTO candidate_participants(candidate_id, participant_id, coordination_run_id, room_id, place_unverified) " +
                    "VALUES (?, ?, ?, ?, ?)",
                candidate,
                participant,
                fixture.sourceRun,
                fixture.roomId,
                conditional && index == 0,
            )
        }
        return candidate
    }

    private fun candidates(fixture: CompletedCorrectionFixture) =
        get("/api/rooms/{code}/candidates", fixture.host.code).cookie(fixture.host.cookie())

    private fun primaryRequest(fixture: CompletedCorrectionFixture) =
        get("/api/rooms/{code}/recommendations", fixture.host.code).cookie(fixture.host.cookie())

    private fun primary(fixture: CompletedCorrectionFixture) = read(primaryRequest(fixture), SUPPORT)

    private fun resultRequest(fixture: CompletedCorrectionFixture) =
        get("/api/rooms/{code}/result", fixture.host.code).cookie(fixture.host.cookie())

    private fun legacySelection(
        fixture: CompletedCorrectionFixture,
        candidate: UUID,
    ) = post("/api/rooms/{code}/candidates/{id}/confirmation", fixture.host.code, candidate)
        .header("Origin", ORIGIN)
        .cookie(fixture.host.cookie())

    private fun read(
        request: MockHttpServletRequestBuilder,
        support: String? = null,
    ): Map<String, Any?> {
        val response = perform(request, support)
        assertEquals(200, response.status, response.contentAsString)
        return document(response)
    }

    private fun updateRequired(
        request: MockHttpServletRequestBuilder,
        support: String?,
    ) {
        val response = perform(request, support)
        assertEquals(409, response.status, "Unsupported client must not receive or confirm conditional attendance")
        val problem = document(response)
        assertEquals("CLIENT_UPDATE_REQUIRED", problem["code"])
        assertTrue(problem["detail"].toString().contains("새로고침"), "The existing error message must guide an old tab to refresh")
        assertTrue(
            listOf("candidate", "candidates", "options", "selection").none { it in problem },
            "409 must not carry the unsafe payload",
        )
        assertNoStore(response)
    }

    private fun unchangedError(
        request: MockHttpServletRequestBuilder,
        expectedStatus: Int,
        support: String?,
        code: String? = null,
    ) {
        val response = perform(request, support)
        assertEquals(expectedStatus, response.status, response.contentAsString)
        assertFalse(
            response.contentAsString.contains("CLIENT_UPDATE_REQUIRED"),
            "Capability declaration is neither authentication nor a replacement for an existing fence",
        )
        // Existing CORS rejection is a plain-text 403 rather than an application problem document.
        code?.let { assertEquals(it, document(response)["code"]) }
    }

    private fun perform(
        request: MockHttpServletRequestBuilder,
        support: String?,
    ): MockHttpServletResponse {
        support?.let { request.param("attendance_support", it) }
        return mvc.perform(request.header(HttpHeaders.ACCEPT_LANGUAGE, "ko-KR")).andReturn().response
    }

    private fun assertNoStore(response: MockHttpServletResponse) {
        assertTrue(
            response.getHeaders(HttpHeaders.CACHE_CONTROL).any { it.contains("no-store") },
            "200 and compatibility 409 responses must not be cached",
        )
    }

    private fun assertMixed(variants: List<Map<String, Any?>>) {
        assertEquals(
            setOf(true, false),
            variants.map { it["attendance_confirmed"] }.toSet(),
            "The complete mixed payload must be preserved for supported clients",
        )
        variants.filter { it["attendance_confirmed"] == false }.forEach(::assertConditional)
    }

    private fun conditionalVariant(option: Map<String, Any?>) = items(option, "variants").single { it["attendance_confirmed"] == false }

    private fun assertConditional(candidate: Map<String, Any?>) {
        assertEquals(false, candidate["attendance_confirmed"])
        assertEquals("IN_PERSON", candidate["meeting_mode"])
        val summary = candidate["summary"].toString()
        assertTrue(summary.contains("확인"), "The explanation must preserve the place verification limitation")
        assertFalse(summary.contains(PRIVATE_TRAVEL))
        assertFalse(summary.contains("모두 참여 가능"))
    }

    private fun pendingRows(fixture: CompletedCorrectionFixture) =
        listOf("candidate_participants", "recommendation_variant_participants").map { table ->
            jdbc.queryForList("SELECT * FROM $table WHERE coordination_run_id=? ORDER BY participant_id::text", fixture.sourceRun)
        }

    private fun storedState(fixture: CompletedCorrectionFixture): List<List<Map<String, Any?>>> =
        listOf(
            jdbc.queryForList("SELECT * FROM meeting_rooms WHERE id=?", fixture.roomId),
            jdbc.queryForList("SELECT * FROM coordination_runs WHERE id=?", fixture.sourceRun),
            jdbc.queryForList("SELECT * FROM final_confirmations WHERE coordination_run_id=?", fixture.sourceRun),
            jdbc.queryForList("SELECT * FROM recommendation_selections WHERE coordination_run_id=?", fixture.sourceRun),
        ) + pendingRows(fixture)

    companion object {
        private const val SUPPORT = "conditional-v1"
        private const val PRIVATE_TRAVEL = "합성 비공개 출발지에서 환승 없이 이동"
        private val UNSUPPORTED = listOf(null, "", "conditional-v2", "CONDITIONAL-V1", "true")
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
