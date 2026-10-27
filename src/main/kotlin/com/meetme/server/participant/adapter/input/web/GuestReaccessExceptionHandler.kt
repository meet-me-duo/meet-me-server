package com.meetme.server.participant.adapter.input.web

import com.meetme.server.participant.application.port.output.GuestReaccessLimitCode
import com.meetme.server.participant.application.port.output.GuestReaccessLimitException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GuestReaccessExceptionHandler {
    @ExceptionHandler(GuestReaccessLimitException::class)
    fun limit(failure: GuestReaccessLimitException): ResponseEntity<ProblemDetail> {
        val status =
            if (failure.code ==
                GuestReaccessLimitCode.TOO_MANY_ATTEMPTS
            ) {
                HttpStatus.TOO_MANY_REQUESTS
            } else {
                HttpStatus.SERVICE_UNAVAILABLE
            }
        val response = ResponseEntity.status(status)
        failure.retryAfter?.let { response.header(HttpHeaders.RETRY_AFTER, ((it.toMillis() + 999) / 1000).coerceAtLeast(1).toString()) }
        return response.body(
            ProblemDetail.forStatusAndDetail(status, "재접근을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.").apply {
                title = status.reasonPhrase
                setProperty("code", failure.code.name)
            },
        )
    }
}
