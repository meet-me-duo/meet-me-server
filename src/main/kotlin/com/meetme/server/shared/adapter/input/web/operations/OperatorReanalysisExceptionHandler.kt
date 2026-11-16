package com.meetme.server.shared.adapter.input.web.operations

import com.meetme.server.account.domain.AuthFailure
import com.meetme.server.shared.application.port.input.operations.OperatorQueryError
import com.meetme.server.shared.application.port.input.operations.OperatorQueryException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

internal object OperatorReanalysisProblems {
    const val ERROR_ATTRIBUTE = "meetme.operations.reanalysisError"
    const val AUTHENTICATION_FAILED_ATTRIBUTE = "meetme.operations.reanalysisAuthenticationFailed"

    fun body(code: OperatorQueryError): OperatorProblemResponse {
        val detail =
            when (code) {
                OperatorQueryError.OPERATOR_INVALID_QUERY -> "재분석 요청값을 확인해 주세요."
                OperatorQueryError.OPERATOR_UNAUTHENTICATED -> "운영자 인증이 필요합니다."
                OperatorQueryError.OPERATOR_FORBIDDEN -> "운영자 권한 또는 요청 출처를 확인해 주세요."
                OperatorQueryError.OPERATOR_NOT_FOUND -> "대상 모임을 찾을 수 없습니다."
                OperatorQueryError.OPERATOR_REANALYSIS_CONFLICT -> "모임의 현재 상태를 새로 확인해 주세요."
                OperatorQueryError.OPERATOR_REANALYSIS_UNSUPPORTED_INPUT -> "이 모임의 저장 입력은 현재 분석 로직에서 지원하지 않습니다."
                OperatorQueryError.OPERATOR_REANALYSIS_RATE_LIMITED -> "요청이 많습니다. 안내된 시간 후 다시 시도해 주세요."
                else -> "재분석 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."
            }
        return OperatorProblemResponse(code.name, HttpStatus.valueOf(code.status).reasonPhrase, code.status, detail)
    }

    fun json(code: OperatorQueryError): String {
        val value = body(code)
        return "{\"code\":\"" + value.code + "\",\"title\":\"" + value.title + "\",\"status\":" +
            value.status + ",\"detail\":\"" + value.detail + "\"}"
    }
}

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [OperatorReanalysisController::class])
class OperatorReanalysisExceptionHandler {
    @ExceptionHandler(AuthFailure::class)
    fun authenticationFailure(
        exception: AuthFailure,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorProblemResponse> {
        request.setAttribute(OperatorReanalysisProblems.AUTHENTICATION_FAILED_ATTRIBUTE, true)
        return failure(
            OperatorQueryException(
                if (exception.code in setOf("AUTH_ACCESS_INVALID", "AUTH_ACCESS_REQUIRED")) {
                    OperatorQueryError.OPERATOR_UNAUTHENTICATED
                } else {
                    OperatorQueryError.OPERATOR_UNAVAILABLE
                },
            ),
            request,
        )
    }

    @ExceptionHandler(OperatorQueryException::class)
    fun failure(
        exception: OperatorQueryException,
        request: HttpServletRequest,
    ): ResponseEntity<OperatorProblemResponse> {
        request.setAttribute(OperatorReanalysisProblems.ERROR_ATTRIBUTE, exception.code)
        val builder =
            ResponseEntity
                .status(
                    exception.code.status,
                ).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore())
        if (exception.code == OperatorQueryError.OPERATOR_REANALYSIS_RATE_LIMITED) {
            builder.header("Retry-After", (exception.retryAfterSeconds ?: 1).coerceAtLeast(1).toString())
        }
        return builder.body(OperatorReanalysisProblems.body(exception.code))
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(request: HttpServletRequest): ResponseEntity<OperatorProblemResponse> =
        failure(OperatorQueryException(OperatorQueryError.OPERATOR_UNAVAILABLE), request)
}
