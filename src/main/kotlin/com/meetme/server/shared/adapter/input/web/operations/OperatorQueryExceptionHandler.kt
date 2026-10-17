package com.meetme.server.shared.adapter.input.web.operations

import com.meetme.server.account.domain.AuthFailure
import com.meetme.server.shared.application.port.input.operations.OperatorQueryException
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@Schema(description = "고정 운영 조회 오류. 요청 경로와 임의 exception 값은 포함하지 않음")
data class OperatorProblemResponse(
    @field:Schema(description = "제한된 운영 조회 오류 코드") val code: String,
    @field:Schema(description = "고정 HTTP 상태 이름") val title: String,
    @field:Schema(description = "HTTP 상태 코드") val status: Int,
    @field:Schema(description = "원문과 내부 오류 정보를 포함하지 않는 안내") val detail: String,
)

internal object OperatorProblems {
    fun body(status: Int): OperatorProblemResponse {
        val code =
            when (status) {
                400 -> "OPERATOR_INVALID_QUERY"
                401 -> "OPERATOR_UNAUTHENTICATED"
                403 -> "OPERATOR_FORBIDDEN"
                404 -> "OPERATOR_NOT_FOUND"
                405 -> "OPERATOR_METHOD_NOT_ALLOWED"
                else -> "OPERATOR_UNAVAILABLE"
            }
        val detail =
            when (status) {
                400 -> "조회 조건을 확인해 주세요."
                401 -> "운영자 조회에는 검증된 회원 인증이 필요합니다."
                403 -> "운영자 조회 권한이 없습니다."
                404 -> "조회 대상을 찾을 수 없습니다."
                405 -> "읽기 전용 조회만 지원합니다."
                else -> "운영자 조회를 완료할 수 없습니다. 잠시 후 다시 시도해 주세요."
            }
        return OperatorProblemResponse(code, HttpStatus.valueOf(status).reasonPhrase, status, detail)
    }

    /** All strings are fixed constants above, never request or exception data. */
    fun json(status: Int): String {
        val value = body(status)
        return """{"code":"${value.code}","title":"${value.title}","status":${value.status},"detail":"${value.detail}"}"""
    }
}

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [OperatorQueryController::class])
class OperatorQueryExceptionHandler {
    @ExceptionHandler(AuthFailure::class)
    fun authenticationFailure(exception: AuthFailure): ResponseEntity<OperatorProblemResponse> =
        response(if (exception.code == "AUTH_ACCESS_INVALID") 401 else 503)

    @ExceptionHandler(OperatorQueryException::class)
    fun operatorFailure(exception: OperatorQueryException): ResponseEntity<OperatorProblemResponse> = response(exception.code.status)

    @ExceptionHandler(Exception::class)
    fun unexpected(
        @Suppress("UNUSED_PARAMETER") exception: Exception,
    ): ResponseEntity<OperatorProblemResponse> = response(503)

    private fun response(status: Int): ResponseEntity<OperatorProblemResponse> =
        ResponseEntity
            .status(
                status,
            ).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .cacheControl(CacheControl.noStore())
            .body(OperatorProblems.body(status))
}
