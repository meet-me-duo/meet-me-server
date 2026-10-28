package com.meetme.server.meetingroom.adapter.input.web

import com.fasterxml.jackson.annotation.JsonProperty
import com.meetme.server.meetingroom.application.port.input.RoomOwnershipUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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
import org.springframework.web.bind.annotation.RequestParam
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
    @Operation(
        summary = "현재 계정의 주최·참여 모임 목록",
        description = "역할 생략은 전체. 생성일은 KST 양끝 포함 날짜이며 생성 시각 DESC, 방 ID ASC 커서 페이지를 사용한다. 기존 30일 보관 정책을 적용한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "30일 보관 범위 내 본인 방"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 역할·생성일·기간·검색 길이·페이지 크기·커서 (VALIDATION_FAILED)",
            content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
        ),
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
    fun accountRooms(
        @RequestParam(required = false)
        @Parameter(description = "생략 시 전체, HOST는 주최, MEMBER는 참여", schema = Schema(allowableValues = ["HOST", "MEMBER"]))
        role: String?,
        @RequestParam(name = "created_from", required = false)
        @Parameter(description = "KST 모임 생성일 시작(포함), YYYY-MM-DD, 0001~9998년", schema = Schema(type = "string", format = "date"))
        createdFrom: String?,
        @RequestParam(name = "created_to", required = false)
        @Parameter(description = "KST 모임 생성일 종료(포함), 시작일 이상, YYYY-MM-DD", schema = Schema(type = "string", format = "date"))
        createdTo: String?,
        @RequestParam(required = false)
        @Parameter(description = "모임 이름의 대소문자 무시 부분 검색. 앞뒤 공백 제거, 최대 100 Unicode code point, 빈 값은 전체. %, _, 역슬래시는 문자 그대로 검색")
        query: String?,
        @RequestParam(required = false)
        @Parameter(
            description = "페이지 크기 1~100, 기본 20",
            schema = Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"),
        )
        limit: String?,
        @RequestParam(required = false)
        @Parameter(description = "이전 응답의 next_cursor. 첫 페이지와 필터·검색 변경 시 생략", schema = Schema(maxLength = 200))
        cursor: String?,
    ): ResponseEntity<AccountRoomsResponse> {
        val result = ownership.accountRooms(AccountRoomParameters.query(role, createdFrom, createdTo, query, limit, cursor))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            AccountRoomsResponse(
                rooms =
                    result.rooms.map {
                        AccountRoomResponse(it.inviteCode, it.purpose, it.createdAt, it.expiresAt, it.role.name)
                    },
                hasMore = result.nextCursor != null,
                nextCursor = result.nextCursor?.let(AccountRoomParameters::encodeCursor),
            ),
        )
    }
}

@Schema(description = "계정 본인의 주최·참여 모임 목록. 생성 시각 DESC, 방 ID ASC 커서 정렬")
data class AccountRoomsResponse(
    @field:Schema(description = "계정 소유권과 기존 30일 보관 범위를 만족하는 모임") val rooms: List<AccountRoomResponse>,
    @field:JsonProperty("has_more") @field:Schema(description = "다음 페이지 존재 여부") val hasMore: Boolean,
    @field:JsonProperty("next_cursor")
    @field:Schema(description = "다음 페이지 커서. has_more가 false면 null. 필터 변경 시 초기화", nullable = true)
    val nextCursor: String?,
)

@Schema(description = "기존 30일 보관 정책의 본인 주최 또는 참여 모임")
data class AccountRoomResponse(
    @field:JsonProperty("invite_code") @field:Schema(description = "방 초대 코드") val inviteCode: String,
    @field:Schema(description = "모임 이름(목적)") val purpose: String,
    @field:JsonProperty("created_at") @field:Schema(description = "방 생성 시각, UTC ISO-8601") val createdAt: Instant,
    @field:JsonProperty("expires_at") @field:Schema(description = "마감된 방은 마감 시각, 수집 중 방은 생성 시각에서 30일 후 정리 기준 시각. 정확한 경계는 포함") val expiresAt:
        Instant,
    @field:Schema(description = "현재 계정 본인의 모임 역할", allowableValues = ["HOST", "MEMBER"]) val role: String,
)
