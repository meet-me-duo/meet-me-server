package com.meetme.server.account.adapter.input.web

import com.meetme.server.account.application.port.input.AccountProfileUseCase
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.account.application.port.output.RefreshSessionPort
import com.meetme.server.account.application.service.OAuthLoginService
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.AuthEntropy
import com.meetme.server.account.domain.AuthFailure
import com.meetme.server.account.domain.OAuthProvider
import com.meetme.server.account.domain.RefreshRotation
import com.meetme.server.config.AccountAuthProperties
import com.meetme.server.config.AnonymousAccessProperties
import com.meetme.server.config.OperatorQueryProperties
import com.meetme.server.shared.adapter.input.web.ApiProblemSchema
import com.meetme.server.shared.application.service.operations.OperatorAccessPolicy
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.annotation.JsonDeserialize
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections

@Schema(description = "현재 계정의 화면 접근 권한. 실제 API 인가를 대체하지 않는다")
data class AccountCapabilitiesResponse(
    @field:Schema(description = "운영 조회가 활성화되고 현재 계정이 명시적 운영자 허용 목록에 포함된 경우 true")
    val canViewOperations: Boolean,
)

@Schema(description = "로그인 계정. 이메일과 공급자 토큰은 포함하지 않는다")
data class AccountResponse(
    @field:Schema(description = "계정 UUID") val id: String,
    @field:Schema(description = "계정 표시 이름") val displayName: String,
    @field:Schema(description = "현재 계정에 대해 서버가 확인한 화면 접근 권한")
    val capabilities: AccountCapabilitiesResponse,
) {
    companion object {
        fun from(
            principal: AccountPrincipal,
            canViewOperations: Boolean,
        ) = AccountResponse(principal.accountId.toString(), principal.displayName, AccountCapabilitiesResponse(canViewOperations))
    }
}

@Schema(description = "본인 계정의 표시 이름 변경. 모임별 이름과 저장된 분석 결과는 별도 유지")
data class UpdateAccountDisplayNameRequest(
    @field:Schema(description = "앞뒤 공백 제거 후 Unicode code point 1~50자. 공백만 또는 제어문자 입력 불가", example = "모임친구")
    @field:JsonDeserialize(using = AccountDisplayNameDeserializer::class)
    val displayName: String,
)

@Schema(description = "15분 Access Token. 브라우저 메모리에만 보관한다")
data class AuthResponse(
    @field:Schema(description = "RS256 Bearer Access Token") val accessToken: String,
    @field:Schema(description = "Access Token 만료 시각") val expiresAt: Instant,
    @field:Schema(description = "로그인 계정") val account: AccountResponse,
)

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val login: OAuthLoginService,
    private val refresh: RefreshSessionPort,
    private val access: AccessTokenPort,
    private val current: CurrentAccountPort,
    private val properties: AccountAuthProperties,
    private val origins: AnonymousAccessProperties,
    private val clock: Clock,
    private val profiles: AccountProfileUseCase,
    private val operatorAccess: OperatorAccessPolicy = OperatorAccessPolicy(OperatorQueryProperties()),
) {
    @PutMapping("/me")
    @Operation(summary = "현재 로그인 계정의 표시 이름 변경")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "최신 계정 정보. 인증 토큰은 새로 발급하지 않음"),
        ApiResponse(
            responseCode = "400",
            description = "올바르지 않은 표시 이름 또는 요청",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "유효한 본인 계정 인증 없음",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "정확한 Origin 불일치",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "계정 저장소 사용 불가",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun updateMe(
        @RequestBody body: UpdateAccountDisplayNameRequest,
        request: HttpServletRequest,
    ): ResponseEntity<AccountResponse> {
        origin(request)
        val account = current.currentAccount() ?: throw AuthFailure("AUTH_ACCESS_REQUIRED")
        val updated = profiles.updateDisplayName(account.accountId, body.displayName)
        return headers(HttpStatus.OK).body(AccountResponse.from(updated, operatorAccess.canViewOperations(updated.accountId)))
    }

    @GetMapping("/{provider}/start")
    @Operation(summary = "Google 또는 Kakao 로그인 시작")
    @ApiResponses(
        ApiResponse(responseCode = "302", description = "공급자 인증 화면 이동"),
        ApiResponse(
            responseCode = "401",
            description = "유효하지 않은 로그인 요청",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "공급자 또는 서명 키 설정/저장소 사용 불가",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun start(
        @PathVariable provider: String,
    ): ResponseEntity<Void> {
        val binding = AuthEntropy.token()
        val uri = login.begin(provider(provider), binding)
        return headers(
            HttpStatus.FOUND,
        ).location(URI.create(uri)).header(HttpHeaders.SET_COOKIE, cookie(BROWSER_COOKIE, binding, Duration.ofMinutes(5))).build()
    }

    @GetMapping("/{provider}/callback")
    @Operation(summary = "로그인 검증 후 토큰 없는 고정 웹 콜백으로 이동")
    @ApiResponses(
        ApiResponse(responseCode = "302", description = "Refresh 쿠키 발급과 고정 웹 이동"),
        ApiResponse(
            responseCode = "401",
            description = "공급자 거절 또는 state/브라우저/ID Token 검증 실패",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "인증 공급자/저장소 사용 불가",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun callback(
        @PathVariable provider: String,
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @CookieValue(name = "meetme_oauth_browser", required = false) binding: String?,
        request: HttpServletRequest? = null,
    ): ResponseEntity<Void> {
        if (request?.cookies?.count { it.name == BROWSER_COOKIE }?.let { it > 1 } == true) throw AuthFailure("AUTH_OAUTH_INVALID")
        val session = login.callback(provider(provider), code ?: "", state ?: "", binding ?: "")
        return headers(HttpStatus.FOUND)
            .location(URI.create(properties.webCallbackUri))
            .header(HttpHeaders.SET_COOKIE, refreshCookie(session), cookie(BROWSER_COOKIE, "", Duration.ZERO))
            .build()
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh 회전과 15분 Access Token 발급")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Access와 계정 반환. Refresh는 Set-Cookie로만 전달"),
        ApiResponse(
            responseCode = "401",
            description = "Refresh 누락/만료/재사용",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "정확한 Origin 불일치",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "인증 저장소/서명 키 사용 불가",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun refresh(request: HttpServletRequest): ResponseEntity<AuthResponse> {
        origin(request)
        val token = credential(request) ?: throw AuthFailure("AUTH_REFRESH_INVALID")
        val session = refresh.rotate(token)
        val principal = profiles.get(session.principal.accountId)
        val accessToken = access.issue(principal)
        return headers(HttpStatus.OK)
            .header(HttpHeaders.SET_COOKIE, refreshCookie(session))
            .body(
                AuthResponse(
                    accessToken,
                    clock.instant().truncatedTo(ChronoUnit.SECONDS).plusSeconds(900),
                    AccountResponse.from(principal, operatorAccess.canViewOperations(principal.accountId)),
                ),
            )
    }

    @GetMapping("/me")
    @Operation(summary = "Bearer Access Token 계정 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "현재 계정"),
        ApiResponse(
            responseCode = "401",
            description = "Access 누락/검증 실패",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "계정 저장소 또는 검증 키 사용 불가",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun me(): ResponseEntity<AccountResponse> {
        val authenticated = current.currentAccount() ?: throw AuthFailure("AUTH_ACCESS_REQUIRED")
        val principal = profiles.get(authenticated.accountId)
        return headers(HttpStatus.OK).body(
            AccountResponse.from(principal, operatorAccess.canViewOperations(principal.accountId)),
        )
    }

    @PostMapping("/logout")
    @Operation(summary = "현재 기기 Refresh 패밀리 로그아웃. Access는 최대 15분 유효")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "현재 패밀리 폐기와 쿠키 만료"),
        ApiResponse(
            responseCode = "401",
            description = "잘못된 Refresh 형식",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "정확한 Origin 불일치",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "인증 저장소 사용 불가",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun logout(request: HttpServletRequest): ResponseEntity<Void> {
        origin(request)
        credential(request)?.let(refresh::revoke)
        return headers(HttpStatus.NO_CONTENT).header(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, "", Duration.ZERO)).build()
    }

    @PostMapping("/logout-all")
    @Operation(summary = "현재 로그인 계정의 모든 기기 로그아웃. Access는 최대 15분 유효")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "현재 계정의 모든 Refresh 패밀리 폐기와 쿠키 만료"),
        ApiResponse(
            responseCode = "401",
            description = "Access 누락/검증 실패",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "정확한 Origin 불일치",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "인증 저장소 사용 불가",
            content = [Content(schema = Schema(implementation = ApiProblemSchema::class))],
        ),
    )
    fun logoutAll(request: HttpServletRequest): ResponseEntity<Void> {
        origin(request)
        val account = current.currentAccount() ?: throw AuthFailure("AUTH_ACCESS_REQUIRED")
        refresh.revokeAll(account.accountId)
        return headers(HttpStatus.NO_CONTENT).header(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, "", Duration.ZERO)).build()
    }

    private fun origin(request: HttpServletRequest) {
        val received = Collections.list(request.getHeaders(HttpHeaders.ORIGIN))
        if (received.size != 1 || received.single() !in origins.allowedOrigins) throw AuthFailure("AUTH_ORIGIN_NOT_ALLOWED")
    }

    private fun credential(request: HttpServletRequest): String? {
        val cookies = request.cookies?.filter { it.name == REFRESH_COOKIE } ?: return null
        if (cookies.size > 1) throw AuthFailure("AUTH_REFRESH_INVALID")
        return cookies.singleOrNull()?.value?.takeIf(String::isNotBlank)
    }

    private fun refreshCookie(session: RefreshRotation): String {
        val remaining = minOf(Duration.ofDays(14), Duration.between(clock.instant(), minOf(session.expiresAt, session.absoluteExpiresAt)))
        if (remaining.isNegative || remaining.isZero) throw AuthFailure("AUTH_REFRESH_INVALID")
        return cookie(REFRESH_COOKIE, session.refreshToken, remaining)
    }

    private fun cookie(
        name: String,
        value: String,
        age: Duration,
    ) = ResponseCookie
        .from(name, value)
        .secure(true)
        .httpOnly(true)
        .sameSite("Lax")
        .path("/api/auth")
        .maxAge(age)
        .build()
        .toString()

    private fun headers(status: HttpStatus): ResponseEntity.BodyBuilder =
        ResponseEntity
            .status(status)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.PRAGMA, "no-cache")
            .header("Referrer-Policy", "no-referrer")

    private fun provider(value: String) =
        when (value) {
            "google" -> OAuthProvider.GOOGLE
            "kakao" -> OAuthProvider.KAKAO
            else -> throw AuthFailure("AUTH_OAUTH_INVALID")
        }

    private companion object {
        const val REFRESH_COOKIE = "meetme_refresh"
        const val BROWSER_COOKIE = "meetme_oauth_browser"
    }
}
