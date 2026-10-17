package com.meetme.server.meetingroom.adapter.input.web

import com.fasterxml.jackson.annotation.JsonProperty
import com.meetme.server.meetingroom.application.port.input.RoomOwnershipUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class RoomOwnershipController(
    private val ownership: RoomOwnershipUseCase,
) {
    @PostMapping("/api/rooms/{inviteCode}/claim")
    @Operation(summary = "유효한 게스트 참여를 로그인 계정에 명시적으로 연결")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "동일 참여자 보존 후 연결 성공"),
        ApiResponse(
            responseCode = "401",
            description = "계정 또는 게스트 증명 누락/무효",
            content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Origin 또는 게스트 소유 증명 범위 부족",
            content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "방 없음",
            content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "이미 계정 참여 존재",
            content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "인증 검증 키 사용 불가",
            content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
        ),
    )
    fun claim(
        @PathVariable inviteCode: String,
        @CookieValue(name = com.meetme.server.shared.adapter.input.web.GuestCookie.NAME, required = false) credential: String?,
    ): RoomResponse = RoomResponse.from(ownership.claim(inviteCode, credential))

    @GetMapping("/api/account/rooms")
    @Operation(summary = "현재 계정이 주최자인 보관 방 목록")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "30일 보관 범위 내 본인 방"),
        ApiResponse(
            responseCode = "401",
            description = "로그인 필요",
            content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "인증 검증 키 사용 불가",
            content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
        ),
    )
    fun accountRooms(): ResponseEntity<AccountRoomsResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            AccountRoomsResponse(
                ownership.accountRooms().rooms.map {
                    AccountRoomResponse(it.inviteCode, it.purpose, it.createdAt, it.expiresAt)
                },
            ),
        )
}

@Schema(description = "본인 주최 방 보관 목록")
data class AccountRoomsResponse(
    @field:Schema(description = "만료되지 않은 본인 주최 방") val rooms: List<AccountRoomResponse>,
)

@Schema(description = "30일 보관 정책의 본인 주최 방")
data class AccountRoomResponse(
    @field:JsonProperty("invite_code") @field:Schema(description = "방 초대 코드") val inviteCode: String,
    @field:Schema(description = "모임 목적") val purpose: String,
    @field:JsonProperty("created_at") @field:Schema(description = "방 생성 시각") val createdAt: Instant,
    @field:JsonProperty("expires_at") @field:Schema(description = "마감된 방은 마감 시각, 수집 중 방은 생성 시각에서 30일 후 정리 기준 시각") val expiresAt: Instant,
)
