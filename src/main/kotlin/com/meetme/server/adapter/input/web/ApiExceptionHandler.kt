package com.meetme.server.adapter.input.web

import com.meetme.server.application.port.input.MatchingResultErrorCode
import com.meetme.server.application.port.input.MatchingResultException
import com.meetme.server.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.application.port.input.RoomLifecycleException
import com.meetme.server.application.port.input.SubmissionErrorCode
import com.meetme.server.application.port.input.SubmissionException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.MessageSource
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.util.Locale

@RestControllerAdvice
class ApiExceptionHandler(
    private val messageSource: MessageSource,
) {
    @ExceptionHandler(MatchingResultException::class)
    fun matchingResult(
        exception: MatchingResultException,
        request: HttpServletRequest,
        locale: Locale,
    ): ResponseEntity<ProblemDetail> {
        val status = exception.code.status()
        return ResponseEntity.status(status).body(problem(status, exception.code.name, request, locale))
    }

    @ExceptionHandler(RoomLifecycleException::class)
    fun lifecycle(
        exception: RoomLifecycleException,
        request: HttpServletRequest,
        locale: Locale,
    ): ResponseEntity<ProblemDetail> {
        val status = exception.code.status()
        val problem = problem(status, exception.code.name, request, locale)
        exception.details.forEach(problem::setProperty)
        return ResponseEntity.status(status).body(problem)
    }

    @ExceptionHandler(SubmissionException::class)
    fun submission(
        exception: SubmissionException,
        request: HttpServletRequest,
        locale: Locale,
    ): ResponseEntity<ProblemDetail> {
        val status = exception.code.status()
        val problem = problem(status, exception.code.name, request, locale)
        exception.details.forEach(problem::setProperty)
        return ResponseEntity.status(status).body(problem)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
        locale: Locale,
    ): ResponseEntity<ProblemDetail> {
        val problem = problem(HttpStatus.BAD_REQUEST, RoomLifecycleErrorCode.VALIDATION_FAILED.name, request, locale)
        problem.setProperty(
            "field_errors",
            exception.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") },
        )
        return ResponseEntity.badRequest().body(problem)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun illegalArgument(
        @Suppress("UNUSED_PARAMETER") exception: IllegalArgumentException,
        request: HttpServletRequest,
        locale: Locale,
    ): ResponseEntity<ProblemDetail> =
        ResponseEntity.badRequest().body(
            problem(HttpStatus.BAD_REQUEST, RoomLifecycleErrorCode.VALIDATION_FAILED.name, request, locale),
        )

    private fun problem(
        status: HttpStatus,
        code: String,
        request: HttpServletRequest,
        locale: Locale,
    ): ProblemDetail =
        ProblemDetail
            .forStatusAndDetail(
                status,
                messageSource.getMessage("api.error.$code", null, code, locale),
            ).apply {
                title = status.reasonPhrase
                type = URI.create("https://api.meet-me.co.kr/problems/${code.lowercase().replace('_', '-')}")
                instance = URI.create(request.requestURI)
                setProperty("code", code)
            }
}

private fun MatchingResultErrorCode.status(): HttpStatus =
    when (this) {
        MatchingResultErrorCode.GUEST_SESSION_REQUIRED,
        MatchingResultErrorCode.GUEST_SESSION_INVALID,
        -> HttpStatus.UNAUTHORIZED
        MatchingResultErrorCode.PARTICIPANT_REQUIRED,
        MatchingResultErrorCode.HOST_PERMISSION_REQUIRED,
        -> HttpStatus.FORBIDDEN
        MatchingResultErrorCode.ROOM_NOT_FOUND,
        MatchingResultErrorCode.CANDIDATE_NOT_FOUND,
        MatchingResultErrorCode.RESULT_NOT_CONFIRMED,
        -> HttpStatus.NOT_FOUND
        MatchingResultErrorCode.CANDIDATES_NOT_READY,
        MatchingResultErrorCode.CANDIDATE_ALREADY_CONFIRMED,
        -> HttpStatus.CONFLICT
    }

private fun RoomLifecycleErrorCode.status(): HttpStatus =
    when (this) {
        RoomLifecycleErrorCode.GUEST_SESSION_REQUIRED,
        RoomLifecycleErrorCode.GUEST_SESSION_INVALID,
        -> HttpStatus.UNAUTHORIZED
        RoomLifecycleErrorCode.HOST_PERMISSION_REQUIRED -> HttpStatus.FORBIDDEN
        RoomLifecycleErrorCode.ROOM_NOT_FOUND -> HttpStatus.NOT_FOUND
        RoomLifecycleErrorCode.ROOM_CLOSED,
        RoomLifecycleErrorCode.EARLY_CLOSE_CONFIRMATION_REQUIRED,
        RoomLifecycleErrorCode.ROOM_PARTICIPANT_LIMIT_REACHED,
        -> HttpStatus.CONFLICT
        RoomLifecycleErrorCode.INVITE_CODE_GENERATION_FAILED -> HttpStatus.SERVICE_UNAVAILABLE
        RoomLifecycleErrorCode.VALIDATION_FAILED -> HttpStatus.BAD_REQUEST
        RoomLifecycleErrorCode.ORIGIN_NOT_ALLOWED -> HttpStatus.FORBIDDEN
    }

private fun SubmissionErrorCode.status(): HttpStatus =
    when (this) {
        SubmissionErrorCode.SUBMISSION_NOT_FOUND -> HttpStatus.NOT_FOUND
        SubmissionErrorCode.PARTICIPANT_REQUIRED -> HttpStatus.FORBIDDEN
        SubmissionErrorCode.SUBMISSION_BATCH_TEXT_LIMIT_EXCEEDED,
        SubmissionErrorCode.ANALYSIS_NOT_DELAYED,
        -> HttpStatus.CONFLICT
        SubmissionErrorCode.SUBMISSION_INPUT_REQUIRED,
        SubmissionErrorCode.SUBMISSION_TEXT_TOO_LONG,
        SubmissionErrorCode.SUBMISSION_TIME_RANGE_INVALID,
        SubmissionErrorCode.SUBMISSION_TIME_RANGE_MODE_MISMATCH,
        -> HttpStatus.BAD_REQUEST
    }
