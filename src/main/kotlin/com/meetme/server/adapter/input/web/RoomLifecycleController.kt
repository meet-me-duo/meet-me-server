package com.meetme.server.adapter.input.web

import com.meetme.server.application.port.input.CloseRoomCommand
import com.meetme.server.application.port.input.CloseRoomUseCase
import com.meetme.server.application.port.input.CreateRoomCommand
import com.meetme.server.application.port.input.CreateRoomUseCase
import com.meetme.server.application.port.input.GetRoomUseCase
import com.meetme.server.application.port.input.JoinRoomCommand
import com.meetme.server.application.port.input.JoinRoomUseCase
import com.meetme.server.config.AnonymousAccessProperties
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Duration

@RestController
@RequestMapping("/api/rooms")
class RoomLifecycleController(
    private val createRoom: CreateRoomUseCase,
    private val getRoom: GetRoomUseCase,
    private val joinRoom: JoinRoomUseCase,
    private val closeRoom: CloseRoomUseCase,
    private val properties: AnonymousAccessProperties,
) {
    @PostMapping
    @Operation(summary = "로그인 없이 방과 주최자 참여 생성")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "방 생성 성공"),
        ApiResponse(
            responseCode = "400",
            description = "입력 검증 실패",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun create(
        @Valid @RequestBody request: CreateRoomRequest,
        @CookieValue(name = GUEST_COOKIE, required = false) credential: String?,
    ): ResponseEntity<RoomResponse> {
        val result =
            createRoom.create(
                CreateRoomCommand(
                    credential,
                    request.hostDisplayName,
                    request.purpose,
                    request.durationMinutes,
                    request.meetingMode,
                    request.expectedParticipants,
                    request.submissionDeadline,
                    request.manualOnly,
                    request.searchStartDate,
                    request.searchEndDate,
                ),
            )
        return responseBuilder(HttpStatus.CREATED, result.newCredential)
            .location(URI.create("/api/rooms/${result.room.inviteCode}"))
            .body(RoomResponse.from(result.room))
    }

    @GetMapping("/{inviteCode}")
    @Operation(summary = "초대 코드로 공개 방 정보 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "방 조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "방 없음",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun get(
        @PathVariable inviteCode: String,
        @CookieValue(name = GUEST_COOKIE, required = false) credential: String?,
    ): RoomResponse = RoomResponse.from(getRoom.get(inviteCode, credential))

    @PostMapping("/{inviteCode}/participants")
    @Operation(summary = "익명 세션으로 방 참여")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "새 참여 생성"),
        ApiResponse(responseCode = "200", description = "기존 참여 반환"),
        ApiResponse(
            responseCode = "400",
            description = "입력 검증 실패",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "방 없음",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "이미 닫힌 방",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun join(
        @PathVariable inviteCode: String,
        @Valid @RequestBody request: JoinRoomRequest,
        @CookieValue(name = GUEST_COOKIE, required = false) credential: String?,
    ): ResponseEntity<RoomResponse> {
        val result = joinRoom.join(JoinRoomCommand(inviteCode, credential, request.displayName))
        return responseBuilder(
            if (result.participantCreated) HttpStatus.CREATED else HttpStatus.OK,
            result.newCredential,
        ).body(RoomResponse.from(result.room))
    }

    @PostMapping("/{inviteCode}/close")
    @Operation(summary = "주최자 세션으로 입력 수집 수동 마감")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "마감 성공 또는 기존 마감 결과"),
        ApiResponse(
            responseCode = "401",
            description = "게스트 세션 누락 또는 무효",
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
            description = "조기 마감 재확인 필요",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun close(
        @PathVariable inviteCode: String,
        @RequestBody request: CloseRoomRequest,
        @CookieValue(name = GUEST_COOKIE, required = false) credential: String?,
    ): RoomResponse = RoomResponse.from(closeRoom.close(CloseRoomCommand(inviteCode, credential, request.confirmEarly)))

    private fun responseBuilder(
        status: HttpStatus,
        newCredential: String?,
    ): ResponseEntity.BodyBuilder {
        val builder = ResponseEntity.status(status)
        if (newCredential != null) {
            val cookie =
                ResponseCookie
                    .from(GUEST_COOKIE, newCredential)
                    .httpOnly(true)
                    .secure(properties.cookieSecure)
                    .sameSite("Lax")
                    .path("/api")
                    .maxAge(Duration.ofDays(30))
                    .build()
            builder.header(HttpHeaders.SET_COOKIE, cookie.toString())
        }
        builder.contentType(MediaType.APPLICATION_JSON)
        return builder
    }

    companion object {
        const val GUEST_COOKIE = "meet_me_guest"
    }
}
