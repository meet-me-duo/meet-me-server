package com.meetme.server.review.adapter.input.web

import com.meetme.server.review.application.service.ReviewErrorCode
import com.meetme.server.review.application.service.ReviewException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ReviewExceptionHandler {
    @ExceptionHandler(ReviewException::class)
    fun review(error: ReviewException): ResponseEntity<ProblemDetail> {
        val status = when (error.code) {
            ReviewErrorCode.REVIEW_PROMPT_NOT_FOUND -> HttpStatus.NOT_FOUND
            ReviewErrorCode.REVIEW_STORAGE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
            else -> HttpStatus.CONFLICT
        }
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(
            ProblemDetail.forStatusAndDetail(status, "리뷰 요청 상태가 바뀌었어요. 확정 결과는 계속 확인할 수 있어요.").apply {
                title = status.reasonPhrase
                setProperty("code", error.code.name)
            },
        )
    }
}
