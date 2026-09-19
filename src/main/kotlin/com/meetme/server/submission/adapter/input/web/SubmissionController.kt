package com.meetme.server.submission.adapter.input.web

import com.meetme.server.submission.application.port.input.GetOwnSubmissionUseCase
import com.meetme.server.submission.application.port.input.SaveSubmissionCommand
import com.meetme.server.submission.application.port.input.SaveSubmissionUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.Locale

@RestController
@RequestMapping("/api/rooms/{inviteCode}/submission")
class SubmissionController(
    private val saveSubmission: SaveSubmissionUseCase,
    private val getSubmission: GetOwnSubmissionUseCase,
) {
    @PutMapping
    @Operation(summary = "현재 익명 참여자의 조건 제출 또는 수정")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "저장된 최신 제출"),
        ApiResponse(
            responseCode = "400",
            description = "입력 계약 위반",
            content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "익명 세션 누락 또는 무효",
            content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "방 참여자 아님",
            content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "방 없음",
            content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "입력 수집 종료 또는 배치 상한 초과",
            content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
        ),
    )
    fun save(
        @PathVariable inviteCode: String,
        @Valid @RequestBody request: SaveSubmissionRequest,
        @CookieValue(name = com.meetme.server.shared.adapter.input.web.GuestCookie.NAME, required = false) credential: String?,
        locale: Locale,
    ): SubmissionResponse =
        SubmissionResponse.from(
            saveSubmission.save(
                SaveSubmissionCommand(
                    inviteCode,
                    credential,
                    request.rawText,
                    request.manualAvailableTimes.map(ManualAvailabilityDto::toDomain),
                    locale,
                ),
            ),
        )

    @GetMapping
    @Operation(summary = "현재 익명 참여자의 최신 제출 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "저장된 최신 제출"),
        ApiResponse(
            responseCode = "401",
            description = "익명 세션 누락 또는 무효",
            content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "방 참여자 아님",
            content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "방 또는 제출 없음",
            content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
        ),
    )
    fun get(
        @PathVariable inviteCode: String,
        @CookieValue(name = com.meetme.server.shared.adapter.input.web.GuestCookie.NAME, required = false) credential: String?,
    ): SubmissionResponse = SubmissionResponse.from(getSubmission.get(inviteCode, credential))
}
