package com.meetme.server.account.adapter.input.web

import com.meetme.server.account.domain.AuthFailure
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.MessageSource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.util.Locale

@RestControllerAdvice
class AuthExceptionHandler(
    private val messages: MessageSource,
) {
    @ExceptionHandler(AuthFailure::class)
    fun auth(
        failure: AuthFailure,
        request: HttpServletRequest,
        locale: Locale,
    ): ResponseEntity<ProblemDetail> {
        val status =
            when (failure.code) {
                "AUTH_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE
                "AUTH_ORIGIN_NOT_ALLOWED" -> HttpStatus.FORBIDDEN
                else -> HttpStatus.UNAUTHORIZED
            }
        val problem =
            ProblemDetail.forStatusAndDetail(
                status,
                messages.getMessage("api.error.${failure.code}", null, "Authentication failed", locale),
            )
        problem.title = status.reasonPhrase
        problem.type = URI.create("https://api.meet-me.co.kr/problems/${failure.code.lowercase().replace('_', '-')}")
        problem.instance = URI.create(request.requestURI)
        problem.setProperty("code", failure.code)
        return ResponseEntity
            .status(
                status,
            ).header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("Referrer-Policy", "no-referrer")
            .body(problem)
    }
}
