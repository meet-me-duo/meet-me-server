package com.meetme.server.shared.adapter.input.web.operations

import com.meetme.server.shared.application.service.operations.OperatorQueryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/operations")
@ApiResponses(
    ApiResponse(
        responseCode = "400",
        description = "고정 형식의 잘못된 조회 조건",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = OperatorProblemResponse::class))],
    ),
    ApiResponse(
        responseCode = "401",
        description = "검증된 회원 인증 없음",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = OperatorProblemResponse::class))],
    ),
    ApiResponse(
        responseCode = "403",
        description = "기본 접근 거부 또는 명시적 운영자 allowlist 미포함",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = OperatorProblemResponse::class))],
    ),
    ApiResponse(
        responseCode = "503",
        description = "조회 또는 감사 저장 불가. 보호 데이터 미반환",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = OperatorProblemResponse::class))],
    ),
)
class OperatorQueryController(
    private val service: OperatorQueryService,
) {
    @GetMapping("/rooms")
    @Operation(summary = "명시적 운영자 전용 방 요약 페이지 조회")
    @ApiResponse(responseCode = "200", description = "원문 없는 현재 방 요약")
    fun listRooms(
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) cursor: String?,
        request: HttpServletRequest,
    ): OperatorRoomPageResponse =
        audited(request) {
            val page = service.listRooms(limit, cursor)
            OperatorRoomPageResponse(page.items.map(RoomSummaryResponse::from), page.nextCursor)
        }

    @GetMapping("/rooms/{roomId}")
    @Operation(summary = "현재 방 설정과 과거 버전을 포함한 구조화 실행 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "방 상태와 실행 이력 페이지"),
        ApiResponse(
            responseCode = "404",
            description = "운영자 인가 후에도 조회 대상 없음",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = OperatorProblemResponse::class))],
        ),
    )
    fun roomDetail(
        @PathVariable roomId: String,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) cursor: String?,
        request: HttpServletRequest,
    ): OperatorRoomDetailResponse =
        audited(request) {
            val detail = service.roomDetail(roomId, limit, cursor)
            OperatorRoomDetailResponse(
                RoomSummaryResponse.from(detail.room),
                detail.invocations.map(InvocationSummaryResponse::from),
                detail.nextCursor,
                RevisionCountsResponse.from(detail.revisionCounts),
                detail.legacyAttemptCount,
            )
        }

    @GetMapping("/invocations/{invocationId}")
    @Operation(summary = "한 영속 구조화 실행의 승인 시도와 사용량 확인 범위 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "공급자별 알려진 값과 미확인 범위. 로컬 호출은 수신·청구 건수가 아님"),
        ApiResponse(
            responseCode = "404",
            description = "운영자 인가 후에도 조회 대상 없음",
            content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = OperatorProblemResponse::class))],
        ),
    )
    fun invocationDetail(
        @PathVariable invocationId: String,
        request: HttpServletRequest,
    ): OperatorInvocationDetailResponse =
        audited(request) {
            val detail = service.invocationDetail(invocationId)
            OperatorInvocationDetailResponse(
                detail.roomId,
                InvocationSummaryResponse.from(detail.invocation),
                detail.attempts.map(AttemptSummaryResponse::from),
                detail.usageByProvider.map(ProviderUsageResponse::from),
            )
        }

    private fun <T> audited(
        request: HttpServletRequest,
        action: () -> T,
    ): T =
        try {
            action()
        } finally {
            // Each service path attempted a durable audit and fails closed if that attempt failed.
            request.setAttribute(OperatorNoStoreFilter.AUDIT_HANDLED_ATTRIBUTE, true)
        }
}
