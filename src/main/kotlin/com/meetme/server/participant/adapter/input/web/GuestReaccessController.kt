package com.meetme.server.participant.adapter.input.web

import com.fasterxml.jackson.annotation.JsonProperty
import com.meetme.server.config.AnonymousAccessProperties
import com.meetme.server.participant.application.port.input.GuestReaccessUseCase
import com.meetme.server.participant.application.port.output.GuestProofLevel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.Locale
import java.util.UUID

@RestController
@RequestMapping("/api/rooms/{inviteCode}/guest-records")
@ApiResponses(
    ApiResponse(
        responseCode = "400",
        description = "잘못된 입력",
        content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
    ),
    ApiResponse(
        responseCode = "401",
        description = "무효 인증 또는 만료된 세션",
        content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
    ),
    ApiResponse(
        responseCode = "403",
        description = "소유 증명 또는 Origin 거부",
        content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
    ),
    ApiResponse(
        responseCode = "404",
        description = "방 없음",
        content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
    ),
    ApiResponse(
        responseCode = "409",
        description = "참여 또는 제출 경합, 마감",
        content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
    ),
    ApiResponse(
        responseCode = "429",
        description = "암호 시도 제한; Retry-After 초",
        content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
    ),
    ApiResponse(
        responseCode = "503",
        description = "검증 또는 시도 제한 저장소 사용 불가",
        content = [Content(schema = Schema(implementation = com.meetme.server.shared.adapter.input.web.ApiProblemSchema::class))],
    ),
)
class GuestReaccessController(
    private val guests: GuestReaccessUseCase,
    private val properties: AnonymousAccessProperties,
) {
    @GetMapping
    @Operation(summary = "재접근할 게스트 기록 선택 목록; 같은 이름도 기록 번호로 구분")
    @ApiResponse(responseCode = "200", description = "공개 이름과 기록 번호만 반환")
    fun records(
        @PathVariable inviteCode: String,
    ): ResponseEntity<GuestRecordsResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            GuestRecordsResponse(guests.records(inviteCode).map { GuestRecordResponse(it.participantId.value, it.displayName) }),
        )

    @PostMapping("/reaccess")
    @Operation(summary = "선택 암호 검증 후 기기 세션에 제한된 게스트 재접근 부여; 초기화나 HOST 우회 없음")
    @ApiResponse(responseCode = "200", description = "VERIFIED_GUEST는 본인 읽기, REPLACE_ONLY는 빈 입력으로 대체만 가능")
    fun reaccess(
        @PathVariable inviteCode: String,
        @RequestBody body: GuestReaccessRequest,
        @CookieValue(name = com.meetme.server.shared.adapter.input.web.GuestCookie.NAME, required = false) credential: String?,
        request: HttpServletRequest,
    ): ResponseEntity<GuestReaccessResponse> {
        val result = guests.reaccess(inviteCode, body.participantId, body.password, credential, request.remoteAddr)
        val response = ResponseEntity.ok().cacheControl(CacheControl.noStore())
        result.newCredential?.let {
            response.header(
                HttpHeaders.SET_COOKIE,
                ResponseCookie
                    .from(com.meetme.server.shared.adapter.input.web.GuestCookie.NAME, it)
                    .httpOnly(true)
                    .secure(properties.cookieSecure)
                    .sameSite("Lax")
                    .path("/api")
                    .maxAge(Duration.ofDays(30))
                    .build()
                    .toString(),
            )
        }
        return response.body(GuestReaccessResponse(result.participantId, result.level))
    }

    @PutMapping("/{participantId}/submission")
    @Operation(summary = "암호 없는 기록을 원문 조회 없이 대체; 선택 당시 revision 경합이면409")
    @ApiResponse(responseCode = "200", description = "성공 여부만 반환; 기존 원문과 제출 버전은 반환하지 않음")
    fun replace(
        @PathVariable inviteCode: String,
        @PathVariable participantId: UUID,
        @RequestBody body: GuestReplacementRequest,
        @CookieValue(name = com.meetme.server.shared.adapter.input.web.GuestCookie.NAME, required = false) credential: String?,
        locale: Locale,
    ): ResponseEntity<GuestReplacementResponse> {
        guests.replace(inviteCode, participantId, body.rawText, credential, locale)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(GuestReplacementResponse(true))
    }
}

@Schema(description = "공개 게스트 선택 목록")
data class GuestRecordsResponse(
    @field:Schema(description = "계정 소유 기록을 제외한 공개 게스트 이름과 번호") val records: List<GuestRecordResponse>,
)

@Schema(description = "동명 기록을 구분하는 공개 선택 정보")
data class GuestRecordResponse(
    @field:JsonProperty("participant_id") @field:Schema(description = "방별 기록 번호; 권한 증명이 아님", format = "uuid") val participantId: UUID,
    @field:JsonProperty("display_name") @field:Schema(description = "중복 가능한 게스트 표시 이름") val displayName: String,
)

@Schema(description = "재접근 검증 요청")
data class GuestReaccessRequest(
    @field:JsonProperty("participant_id") @field:Schema(description = "목록에서 명시적으로 선택한 기록 번호", format = "uuid") val participantId: UUID,
    @field:Schema(
        description = "설정한 경우 필요한 암호. 공백과 대소문자를 보존하며 1~128 Unicode 코드 포인트",
        nullable = true,
        accessMode = Schema.AccessMode.WRITE_ONLY,
    ) val password: String? = null,
) {
    override fun toString(): String = "GuestReaccessRequest(redacted)"
}

@Schema(description = "쿠키에 부여된 재접근 범위; 자체 권한 증명으로 사용할 수 없음")
data class GuestReaccessResponse(
    @field:JsonProperty("participant_id") @field:Schema(description = "선택한 기록 번호", format = "uuid") val participantId: UUID,
    @field:Schema(description = "VERIFIED_GUEST: 본인 읽기/수정, REPLACE_ONLY: 원문 없는 대체 입력만") val level: GuestProofLevel,
)

@Schema(description = "기존 원문을 제공하지 않는 전체 대체 입력")
data class GuestReplacementRequest(
    @field:JsonProperty(
        "raw_text",
    ) @field:Schema(description = "새 전체 입력; ECMAScript trim 후 1~500 Unicode 코드 포인트", nullable = true) val rawText: String?,
) {
    override fun toString(): String = "GuestReplacementRequest(redacted)"
}

@Schema(description = "원문과 버전을 포함하지 않는 대체 성공 응답")
data class GuestReplacementResponse(
    @field:Schema(description = "대체 입력 저장 성공") val replaced: Boolean,
)
