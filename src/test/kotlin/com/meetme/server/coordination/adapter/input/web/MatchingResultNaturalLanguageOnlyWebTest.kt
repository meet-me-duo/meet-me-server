package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.application.service.NaturalLanguageOnlyFixture
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.MeetingCandidate
import com.meetme.server.coordination.domain.matching.PlanType
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.adapter.input.web.ApiExceptionHandler
import com.meetme.server.shared.adapter.input.web.GuestCookie
import com.meetme.server.shared.domain.CandidateId
import com.meetme.server.submission.domain.StructuredSubmissionResult
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.support.StaticMessageSource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import java.util.UUID
import kotlin.test.assertEquals

class MatchingResultNaturalLanguageOnlyWebTest {
    private lateinit var fixture: NaturalLanguageOnlyFixture
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        fixture = NaturalLanguageOnlyFixture(listOf("natural", null))
        fixture.results(listOf(fixture.window(9, 12)))
        // New matching persists this result before completing. A historical completion without it
        // must not be reclassified by read-only result endpoints; it is covered separately below.
        fixture.structured +=
            StructuredSubmissionResult(fixture.submissions[1].latest.id, emptyList(), "LEGACY_MANUAL_ONLY_UNSUPPORTED")
        fixture.run = fixture.run.complete(CandidateQuality.PARTIAL, emptyList())
        val service = fixture.resultService()
        mvc =
            MockMvcBuilders
                .standaloneSetup(MatchingResultController(service, service, service, service))
                .defaultRequest<StandaloneMockMvcBuilder>(get("/").header("Accept-Language", "ko"))
                .setControllerAdvice(ApiExceptionHandler(StaticMessageSource()))
                .build()
    }

    @Test
    fun `NO_MATCH candidate view counts archived input as unapplied without leaking identity or raw input`() {
        mvc
            .perform(get("/api/rooms/{code}/candidates", CODE).cookie(Cookie(GuestCookie.NAME, "member")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.quality").value("PARTIAL"))
            .andExpect(jsonPath("$.candidates").isEmpty)
            .andExpect(jsonPath("$.applied_submissions").value(1))
            .andExpect(jsonPath("$.total_submissions").value(2))
            .andExpect(jsonPath("$.unapplied_inputs").value(1))
            .andExpect(jsonPath("$.raw_text").doesNotExist())
            .andExpect(jsonPath("$.participant_display_name").doesNotExist())
    }

    @Test
    fun `HOST can read nullable archived raw text and explicit reason even with no candidates`() {
        mvc
            .perform(get("/api/rooms/{code}/candidates/unapplied-inputs", CODE).cookie(Cookie(GuestCookie.NAME, "host")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].participant_display_name").value("participant 2"))
            .andExpect(jsonPath("$[0].raw_text").value(nullValue()))
            .andExpect(jsonPath("$[0].reason").value("LEGACY_MANUAL_ONLY_UNSUPPORTED"))
    }

    @Test
    fun `member and unauthenticated viewers cannot read archived unsupported participant details`() {
        mvc
            .perform(get("/api/rooms/{code}/candidates/unapplied-inputs", CODE).cookie(Cookie(GuestCookie.NAME, "member")))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("HOST_PERMISSION_REQUIRED"))
        mvc
            .perform(get("/api/rooms/{code}/candidates/unapplied-inputs", CODE))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("GUEST_SESSION_REQUIRED"))
    }

    @ParameterizedTest(name = "historical completion confirmed={0}")
    @ValueSource(booleans = [false, true])
    fun `past completed and confirmed results retain original candidates quality counts and stored state`(confirmed: Boolean) {
        // Before Issue #83, slot-only input was applied and had no unsupported structured row.
        fixture.results(listOf(fixture.window(18, 20)))
        val candidate =
            MeetingCandidate(
                id = CandidateId(UUID.randomUUID()),
                rank = 1,
                timeRanges = listOf(fixture.utc(18, 20)),
                planType = PlanType.A,
                meetingMode = MeetingMode.REMOTE,
                participantIds = fixture.submissions.map { it.participantId },
                totalParticipants = 2,
            )
        val completed =
            CoordinationRun
                .queued(fixture.run.id, fixture.batch)
                .startMatching()
                .complete(CandidateQuality.COMPLETE, listOf(candidate))
        fixture.run = if (confirmed) completed.confirm(candidate.id, NaturalLanguageOnlyFixture.NOW) else completed
        val beforeRun = fixture.run
        val beforeStructured = fixture.structured.toList()

        fixture.process()
        mvc
            .perform(get("/api/rooms/{code}/candidates", CODE).cookie(Cookie(GuestCookie.NAME, "member")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.quality").value("COMPLETE"))
            .andExpect(jsonPath("$.applied_submissions").value(2))
            .andExpect(jsonPath("$.total_submissions").value(2))
            .andExpect(jsonPath("$.unapplied_inputs").value(0))
            .andExpect(jsonPath("$.candidates.length()").value(1))
            .andExpect(jsonPath("$.candidates[0].candidate_id").value(candidate.id.value.toString()))
            .andExpect(jsonPath("$.candidates[0].plan_type").value("A"))
            .andExpect(jsonPath("$.candidates[0].attendance_count").value(2))
            .andExpect(jsonPath("$.candidates[0].total_participants").value(2))
            .andExpect(jsonPath("$.candidates[0].time_ranges.length()").value(1))
            .andExpect(jsonPath("$.candidates[0].time_ranges[0].start_at").value("2026-09-21T09:00:00Z"))
            .andExpect(jsonPath("$.candidates[0].time_ranges[0].end_at").value("2026-09-21T11:00:00Z"))
        mvc
            .perform(get("/api/rooms/{code}/candidates/unapplied-inputs", CODE).cookie(Cookie(GuestCookie.NAME, "host")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isEmpty)
        if (confirmed) {
            mvc
                .perform(get("/api/rooms/{code}/result", CODE).cookie(Cookie(GuestCookie.NAME, "member")))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.candidate.candidate_id").value(candidate.id.value.toString()))
                .andExpect(jsonPath("$.candidate.attendance_count").value(2))
                .andExpect(jsonPath("$.candidate.time_ranges[0].start_at").value("2026-09-21T09:00:00Z"))
                .andExpect(jsonPath("$.candidate.time_ranges[0].end_at").value("2026-09-21T11:00:00Z"))
                .andExpect(jsonPath("$.confirmed_at").value(NaturalLanguageOnlyFixture.NOW.toString()))
        }
        assertEquals(beforeRun, fixture.run)
        assertEquals(beforeStructured, fixture.structured)
    }

    companion object {
        private const val CODE = "abcdefghijklmnopqrstuv"
    }
}
