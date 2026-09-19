package com.meetme.server.adapter.input.web

import com.meetme.server.application.port.input.RetryAnalysisUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/rooms/{inviteCode}/analysis")
class AnalysisController(
    private val retryAnalysis: RetryAnalysisUseCase,
) {
    @PostMapping("/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "주최자 세션으로 지연된 고정 배치 재분석 요청")
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "재분석 요청 접수"),
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
            description = "분석 지연 상태가 아님",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun retry(
        @PathVariable inviteCode: String,
        @CookieValue(name = RoomLifecycleController.GUEST_COOKIE, required = false) credential: String?,
    ): RoomResponse = RoomResponse.from(retryAnalysis.retry(inviteCode, credential))
}
