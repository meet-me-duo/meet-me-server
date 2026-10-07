package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.application.port.input.RecommendationListView
import com.meetme.server.coordination.application.port.input.RecommendationUseCase
import com.meetme.server.coordination.application.port.input.SelectRecommendationCommand
import com.meetme.server.shared.adapter.input.web.ApiProblemSchema
import com.meetme.server.shared.adapter.input.web.GuestCookie
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.Locale
import java.util.UUID

@RestController
@RequestMapping("/api/rooms/{inviteCode}", produces = [MediaType.APPLICATION_JSON_VALUE])
class RecommendationController(
    private val useCase: RecommendationUseCase,
) {
    @GetMapping("/recommendations")
    @Operation(summary = "다양한 날짜·시간 추천 최대 세 안 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 또는 동일 선택 멱등 확정 성공"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 입력·cursor·범위·정밀도; PostgreSQL 지원 마이크로초까지",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "세션 누락 또는 무효",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "참여 또는 HOST 권한 없음",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "방 없음",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "분석 준비 전·수정 라운드 OPEN·STALE_ANALYSIS·다른 선택 확정 충돌",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun primary(
        @PathVariable inviteCode: String,
        @CookieValue(name = GuestCookie.NAME, required = false) credential: String?,
        locale: Locale,
    ): RecommendationListResponse = response(useCase.primary(inviteCode, credential, locale))

    @GetMapping("/recommendations/alternatives")
    @Operation(summary = "현재 분석의 다른 가능한 시간 전체 페이지 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 또는 동일 선택 멱등 확정 성공"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 입력·cursor·범위·정밀도; PostgreSQL 지원 마이크로초까지",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "세션 누락 또는 무효",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "참여 또는 HOST 권한 없음",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "방 없음",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "분석 준비 전·수정 라운드 OPEN·STALE_ANALYSIS·다른 선택 확정 충돌",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun alternatives(
        @PathVariable inviteCode: String,
        @RequestParam(name = "analysis_id") analysisId: UUID,
        @RequestParam(required = false) cursor: String?,
        @Parameter(description = "페이지 크기1~100", schema = Schema(minimum = "1", maximum = "100"))
        @RequestParam(defaultValue = "20") limit: Int,
        @CookieValue(name = GuestCookie.NAME, required = false) credential: String?,
        locale: Locale,
    ): RecommendationListResponse = response(useCase.alternatives(inviteCode, analysisId, cursor, limit, credential, locale))

    @PostMapping("/recommendations/{optionId}/confirmation", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "추천 창 안의 실제 모임 시작·종료 확정", description = "전체 선택 튜플만 멱등. legacy candidate 확정 URL과 별도")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 또는 동일 선택 멱등 확정 성공"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 입력·cursor·범위·정밀도; PostgreSQL 지원 마이크로초까지",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "세션 누락 또는 무효",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "참여 또는 HOST 권한 없음",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "방 없음",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "분석 준비 전·수정 라운드 OPEN·STALE_ANALYSIS·다른 선택 확정 충돌",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "429",
            description = "기존 주최자 명령 요청 제한 초과",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "기존 요청 제한 저장소 사용 불가",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun confirm(
        @PathVariable inviteCode: String,
        @PathVariable optionId: UUID,
        @RequestBody body: RecommendationConfirmationRequest,
        @CookieValue(name = GuestCookie.NAME, required = false) credential: String?,
        locale: Locale,
    ): ConfirmedResultResponse =
        ConfirmedResultResponse.from(
            useCase.confirm(
                SelectRecommendationCommand(
                    inviteCode,
                    optionId,
                    body.analysisId,
                    body.variantId,
                    body.startAt,
                    body.endAt,
                    credential,
                    locale,
                ),
            ),
        )

    private fun response(view: RecommendationListView) =
        RecommendationListResponse(
            view.protocol,
            view.analysisId,
            view.stateVersion,
            view.quality,
            view.totalOptions,
            view.options.map { option ->
                RecommendationOptionResponse(
                    option.optionId,
                    option.rank,
                    CandidateTimeRangeResponse(option.timeRange.startInclusive, option.timeRange.endExclusive),
                    option.variants.map { variant ->
                        RecommendationVariantResponse(
                            variant.variantId,
                            RecommendationMeetingMode.valueOf(variant.meetingMode.name),
                            variant.attendanceCount,
                            variant.totalParticipants,
                            variant.partialAttendance,
                            variant.place?.let(CandidatePlaceResponse::from),
                        )
                    },
                    option.summary,
                )
            },
            view.hasAlternatives,
            view.nextCursor,
        )
}
