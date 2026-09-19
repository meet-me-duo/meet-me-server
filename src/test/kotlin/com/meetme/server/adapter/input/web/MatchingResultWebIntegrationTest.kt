package com.meetme.server.adapter.input.web

import com.meetme.server.application.port.input.CandidateListView
import com.meetme.server.application.port.input.CandidateView
import com.meetme.server.application.port.input.ConfirmCandidateUseCase
import com.meetme.server.application.port.input.ConfirmedResultView
import com.meetme.server.application.port.input.GetCandidatesUseCase
import com.meetme.server.application.port.input.GetConfirmedResultUseCase
import com.meetme.server.application.port.input.GetUnappliedInputsUseCase
import com.meetme.server.application.port.input.MatchingResultErrorCode
import com.meetme.server.application.port.input.MatchingResultException
import com.meetme.server.application.port.input.UnappliedInputView
import com.meetme.server.domain.coordination.CandidateQuality
import com.meetme.server.domain.matching.PlanType
import com.meetme.server.domain.meeting.MeetingMode
import com.meetme.server.domain.time.InstantTimeRange
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.support.StaticMessageSource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.lang.reflect.Modifier
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchingResultWebIntegrationTest {
    private lateinit var resultPort: StatefulResultPort
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        resultPort = StatefulResultPort()
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(
                    MatchingResultController(resultPort, resultPort, resultPort, resultPort),
                ).setControllerAdvice(ApiExceptionHandler(StaticMessageSource()))
                .build()
    }

    @Test
    fun `participant reads candidates without participant identities or unapplied raw text`() {
        mockMvc
            .perform(
                get("/api/rooms/{inviteCode}/candidates", INVITE_CODE)
                    .cookie(Cookie(RoomLifecycleController.GUEST_COOKIE, MEMBER_CREDENTIAL))
                    .header("Accept-Language", "ko-KR"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.quality").value("PARTIAL"))
            .andExpect(jsonPath("$.applied_submissions").value(1))
            .andExpect(jsonPath("$.total_submissions").value(2))
            .andExpect(jsonPath("$.unapplied_inputs").value(1))
            .andExpect(jsonPath("$.candidates[0].candidate_id").value(CANDIDATE_A.toString()))
            .andExpect(jsonPath("$.candidates[0].attendance_count").value(2))
            .andExpect(jsonPath("$.candidates[0].summary").value("9월 21일 18:00~19:00"))
            .andExpect(jsonPath("$.candidates[0].participant_ids").doesNotExist())
            .andExpect(jsonPath("$.candidates[0].raw_text").doesNotExist())
    }

    @Test
    fun `shared invite link without participant credential cannot read candidates`() {
        mockMvc
            .perform(get("/api/rooms/{inviteCode}/candidates", INVITE_CODE))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("GUEST_SESSION_REQUIRED"))
    }

    @Test
    fun `host alone can read unapplied participant raw input`() {
        mockMvc
            .perform(
                get("/api/rooms/{inviteCode}/candidates/unapplied-inputs", INVITE_CODE)
                    .cookie(Cookie(RoomLifecycleController.GUEST_COOKIE, HOST_CREDENTIAL)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].participant_display_name").value("민수"))
            .andExpect(jsonPath("$[0].raw_text").value("학교 근처"))
            .andExpect(jsonPath("$[0].reason").value("UNRESOLVED_PLACE"))

        mockMvc
            .perform(
                get("/api/rooms/{inviteCode}/candidates/unapplied-inputs", INVITE_CODE)
                    .cookie(Cookie(RoomLifecycleController.GUEST_COOKIE, MEMBER_CREDENTIAL)),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("HOST_PERMISSION_REQUIRED"))
    }

    @Test
    fun `same candidate confirmation is idempotent and a different candidate conflicts`() {
        repeat(2) {
            mockMvc
                .perform(
                    post("/api/rooms/{inviteCode}/candidates/{candidateId}/confirmation", INVITE_CODE, CANDIDATE_A)
                        .cookie(Cookie(RoomLifecycleController.GUEST_COOKIE, HOST_CREDENTIAL)),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.candidate.candidate_id").value(CANDIDATE_A.toString()))
        }

        mockMvc
            .perform(
                post("/api/rooms/{inviteCode}/candidates/{candidateId}/confirmation", INVITE_CODE, CANDIDATE_B)
                    .cookie(Cookie(RoomLifecycleController.GUEST_COOKIE, HOST_CREDENTIAL)),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CANDIDATE_ALREADY_CONFIRMED"))
    }

    @Test
    fun `concurrent different confirmations allow exactly one winner`() {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val statuses =
                listOf(CANDIDATE_A, CANDIDATE_B).map { candidateId ->
                    executor.submit<Int> {
                        ready.countDown()
                        start.await()
                        mockMvc
                            .perform(
                                post(
                                    "/api/rooms/{inviteCode}/candidates/{candidateId}/confirmation",
                                    INVITE_CODE,
                                    candidateId,
                                ).cookie(Cookie(RoomLifecycleController.GUEST_COOKIE, HOST_CREDENTIAL)),
                            ).andReturn()
                            .response.status
                    }
                }
            ready.await()
            start.countDown()
            assertEquals(listOf(200, 409), statuses.map { it.get() }.sorted())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `confirmed result is participant readable and excludes unapplied input`() {
        resultPort.confirm(INVITE_CODE, CANDIDATE_A, HOST_CREDENTIAL, Locale.KOREAN)

        mockMvc
            .perform(
                get("/api/rooms/{inviteCode}/result", INVITE_CODE)
                    .cookie(Cookie(RoomLifecycleController.GUEST_COOKIE, MEMBER_CREDENTIAL)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.candidate.candidate_id").value(CANDIDATE_A.toString()))
            .andExpect(jsonPath("$.raw_text").doesNotExist())
            .andExpect(jsonPath("$.candidate.raw_text").doesNotExist())
    }

    @Test
    fun `public result endpoints document success and failure responses and DTO fields`() {
        val responseCodes =
            MatchingResultController::class.java.declaredMethods
                .filter { it.name in setOf("candidates", "unappliedInputs", "confirm", "result") }
                .associate { method ->
                    method.name to
                        method
                            .getAnnotation(ApiResponses::class.java)
                            .value
                            .map { it.responseCode }
                            .toSet()
                }

        assertEquals(setOf("200", "401", "403", "404", "409"), responseCodes.getValue("candidates"))
        assertEquals(setOf("200", "401", "403", "404", "409"), responseCodes.getValue("unappliedInputs"))
        assertEquals(setOf("200", "401", "403", "404", "409"), responseCodes.getValue("confirm"))
        assertEquals(setOf("200", "401", "403", "404"), responseCodes.getValue("result"))
        listOf(
            CandidateListResponse::class.java,
            CandidateResponse::class.java,
            CandidateTimeRangeResponse::class.java,
            CandidatePlaceResponse::class.java,
            UnappliedInputResponse::class.java,
            ConfirmedResultResponse::class.java,
        ).forEach { type ->
            assertTrue(type.isAnnotationPresent(Schema::class.java))
            assertTrue(
                type.declaredFields
                    .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
                    .all { it.isAnnotationPresent(Schema::class.java) },
            )
        }
    }

    @Test
    fun `empty candidate response remains a successful no match representation`() {
        resultPort.candidates = resultPort.candidates.copy(candidates = emptyList())

        mockMvc
            .perform(
                get("/api/rooms/{inviteCode}/candidates", INVITE_CODE)
                    .cookie(Cookie(RoomLifecycleController.GUEST_COOKIE, MEMBER_CREDENTIAL)),
            ).andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.candidates").isEmpty)
    }

    private class StatefulResultPort :
        GetCandidatesUseCase,
        GetUnappliedInputsUseCase,
        ConfirmCandidateUseCase,
        GetConfirmedResultUseCase {
        var candidates =
            CandidateListView(
                CandidateQuality.PARTIAL,
                appliedSubmissions = 1,
                totalSubmissions = 2,
                unappliedInputs = 1,
                candidates = listOf(candidate(CANDIDATE_A), candidate(CANDIDATE_B, rank = 2)),
            )
        private var confirmed: UUID? = null

        override fun getCandidates(
            inviteCode: String,
            rawCredential: String?,
            locale: Locale,
        ): CandidateListView {
            requireParticipant(rawCredential)
            return candidates
        }

        override fun getUnappliedInputs(
            inviteCode: String,
            rawCredential: String?,
        ): List<UnappliedInputView> {
            if (rawCredential != HOST_CREDENTIAL) throw MatchingResultException(MatchingResultErrorCode.HOST_PERMISSION_REQUIRED)
            return listOf(UnappliedInputView("민수", "학교 근처", "UNRESOLVED_PLACE"))
        }

        @Synchronized
        override fun confirm(
            inviteCode: String,
            candidateId: UUID,
            rawCredential: String?,
            locale: Locale,
        ): ConfirmedResultView {
            if (rawCredential != HOST_CREDENTIAL) throw MatchingResultException(MatchingResultErrorCode.HOST_PERMISSION_REQUIRED)
            val existing = confirmed
            if (existing != null && existing != candidateId) {
                throw MatchingResultException(MatchingResultErrorCode.CANDIDATE_ALREADY_CONFIRMED)
            }
            val selected =
                candidates.candidates.firstOrNull { it.candidateId == candidateId }
                    ?: throw MatchingResultException(MatchingResultErrorCode.CANDIDATE_NOT_FOUND)
            confirmed = candidateId
            return ConfirmedResultView(selected, CONFIRMED_AT)
        }

        override fun getConfirmed(
            inviteCode: String,
            rawCredential: String?,
            locale: Locale,
        ): ConfirmedResultView {
            requireParticipant(rawCredential)
            val candidateId = confirmed ?: throw MatchingResultException(MatchingResultErrorCode.RESULT_NOT_CONFIRMED)
            return ConfirmedResultView(candidates.candidates.first { it.candidateId == candidateId }, CONFIRMED_AT)
        }

        private fun requireParticipant(rawCredential: String?) {
            if (rawCredential == null) {
                throw MatchingResultException(MatchingResultErrorCode.GUEST_SESSION_REQUIRED)
            }
            if (rawCredential !in setOf(HOST_CREDENTIAL, MEMBER_CREDENTIAL)) {
                throw MatchingResultException(MatchingResultErrorCode.PARTICIPANT_REQUIRED)
            }
        }
    }

    companion object {
        private const val INVITE_CODE = "abcdefghijklmnopqrstuv"
        private const val HOST_CREDENTIAL = "host-credential"
        private const val MEMBER_CREDENTIAL = "member-credential"
        private val CANDIDATE_A: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        private val CANDIDATE_B: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
        private val CONFIRMED_AT: Instant = Instant.parse("2026-09-19T10:00:00Z")

        private fun candidate(
            id: UUID,
            rank: Int = 1,
        ) = CandidateView(
            id,
            PlanType.A,
            MeetingMode.REMOTE,
            rank,
            attendanceCount = 2,
            totalParticipants = 2,
            timeRanges =
                listOf(
                    InstantTimeRange(
                        Instant.parse("2026-09-21T09:00:00Z"),
                        Instant.parse("2026-09-21T10:00:00Z"),
                    ),
                ),
            place = null,
            summary = "9월 21일 18:00~19:00",
        )
    }
}
