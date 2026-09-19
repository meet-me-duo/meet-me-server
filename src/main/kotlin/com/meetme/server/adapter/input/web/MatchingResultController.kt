package com.meetme.server.adapter.input.web

import com.meetme.server.application.port.input.ConfirmCandidateUseCase
import com.meetme.server.application.port.input.GetCandidatesUseCase
import com.meetme.server.application.port.input.GetConfirmedResultUseCase
import com.meetme.server.application.port.input.GetUnappliedInputsUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.Locale
import java.util.UUID

@RestController
@RequestMapping("/api/rooms/{inviteCode}", produces = [MediaType.APPLICATION_JSON_VALUE])
class MatchingResultController(
    private val getCandidates: GetCandidatesUseCase,
    private val getUnappliedInputs: GetUnappliedInputsUseCase,
    private val confirmCandidate: ConfirmCandidateUseCase,
    private val getConfirmedResult: GetConfirmedResultUseCase,
) {
    @GetMapping("/candidates")
    @Operation(summary = "결정론적 모임 후보 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "후보 조회 성공"),
        ApiResponse(
            responseCode = "401",
            description = "익명 세션 누락 또는 무효",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "방 참여자 아님",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "방 없음",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "후보 준비 전",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun candidates(
        @PathVariable inviteCode: String,
        @CookieValue(name = RoomLifecycleController.GUEST_COOKIE, required = false) credential: String?,
        locale: Locale,
    ): CandidateListResponse = CandidateListResponse.from(getCandidates.getCandidates(inviteCode, credential, locale))

    @GetMapping("/candidates/unapplied-inputs")
    @Operation(summary = "주최자용 미반영 입력 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "미반영 입력 조회 성공"),
        ApiResponse(
            responseCode = "401",
            description = "익명 세션 누락 또는 무효",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "주최자 권한 없음",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "방 없음",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "부분 결과 준비 전",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun unappliedInputs(
        @PathVariable inviteCode: String,
        @CookieValue(name = RoomLifecycleController.GUEST_COOKIE, required = false) credential: String?,
    ): List<UnappliedInputResponse> = getUnappliedInputs.getUnappliedInputs(inviteCode, credential).map(UnappliedInputResponse::from)

    @PostMapping("/candidates/{candidateId}/confirmation")
    @Operation(summary = "주최자 후보 확정")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "후보 확정 또는 동일 후보 멱등 재확인"),
        ApiResponse(
            responseCode = "401",
            description = "익명 세션 누락 또는 무효",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "주최자 권한 없음",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "방 또는 후보 없음",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "다른 후보가 이미 확정됨",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun confirm(
        @PathVariable inviteCode: String,
        @PathVariable candidateId: UUID,
        @CookieValue(name = RoomLifecycleController.GUEST_COOKIE, required = false) credential: String?,
        locale: Locale,
    ): ConfirmedResultResponse = ConfirmedResultResponse.from(confirmCandidate.confirm(inviteCode, candidateId, credential, locale))

    @GetMapping("/result")
    @Operation(summary = "확정 결과 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "확정 결과 조회 성공"),
        ApiResponse(
            responseCode = "401",
            description = "익명 세션 누락 또는 무효",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "방 참여자 아님",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "방 또는 확정 결과 없음",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun result(
        @PathVariable inviteCode: String,
        @CookieValue(name = RoomLifecycleController.GUEST_COOKIE, required = false) credential: String?,
        locale: Locale,
    ): ConfirmedResultResponse = ConfirmedResultResponse.from(getConfirmedResult.getConfirmed(inviteCode, credential, locale))
}
