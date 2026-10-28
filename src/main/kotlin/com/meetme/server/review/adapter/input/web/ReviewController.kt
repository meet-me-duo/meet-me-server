package com.meetme.server.review.adapter.input.web

import com.meetme.server.review.application.service.ReviewReceipt
import com.meetme.server.review.application.service.ReviewService
import com.meetme.server.review.domain.ReviewAnswer
import com.meetme.server.review.domain.ReviewPromptState
import com.meetme.server.shared.adapter.input.web.ApiProblemSchema
import com.meetme.server.shared.adapter.input.web.GuestCookie
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ClaimReviewRequest(@field:Schema(description = "현재 화면의 재시도에만 재사용하는 UUID") val request_id: UUID)

class SubmitReviewRequest(
    @field:ArraySchema(minItems = 1, maxItems = 5, uniqueItems = true, schema = Schema(type = "integer", allowableValues = ["1", "2", "3", "4", "5"], description = "1 만족은 배타. 2~5 복수 선택"))
    val choices: List<Int>,
    @field:Schema(description = "5 기타 선택 시에만, 선택 입력. 최대 2000 Unicode 코드포인트", nullable = true)
    val other_text: String? = null,
) {
    override fun toString(): String = "SubmitReviewRequest(redacted)"
}

data class CloseReviewRequest(@field:Schema(description = "체크 후 닫기만 사용자 전체 7일 숨김") val hide_for_week: Boolean?)

data class ReviewResponse(
    @field:Schema(description = "현재 화면에서 표시할 요청. 표시할 수 없으면 null", nullable = true)
    val prompt_id: UUID?,
    @field:Schema(description = "PENDING만 표시. 종료 응답에는 자유 입력이나 선택을 포함하지 않음")
    val state: ReviewPromptState,
) {
    companion object {
        fun from(receipt: ReviewReceipt): ReviewResponse = ReviewResponse(receipt.promptId, receipt.state)
    }
}

@RestController
@RequestMapping("/api/rooms/{inviteCode}/review-prompt")
@ApiResponses(
    ApiResponse(responseCode = "200", description = "본인 요청 상태. 응답은 no-store"),
    ApiResponse(responseCode = "400", description = "입력 형식/선택 조합 오류", content = [Content(schema = Schema(implementation = ApiProblemSchema::class))]),
    ApiResponse(responseCode = "401", description = "본인 세션 없음/만료", content = [Content(schema = Schema(implementation = ApiProblemSchema::class))]),
    ApiResponse(responseCode = "403", description = "본인 참여 증명 또는 Origin 부족", content = [Content(schema = Schema(implementation = ApiProblemSchema::class))]),
    ApiResponse(responseCode = "404", description = "방 또는 본인 요청 없음", content = [Content(schema = Schema(implementation = ApiProblemSchema::class))]),
    ApiResponse(responseCode = "409", description = "미확정, 이미 종료 또는 다른 모임의 정책 갱신", content = [Content(schema = Schema(implementation = ApiProblemSchema::class))]),
    ApiResponse(responseCode = "503", description = "리뷰 저장 일시 실패. 같은 요청으로 재시도", content = [Content(schema = Schema(implementation = ApiProblemSchema::class))]),
)
class ReviewController(private val reviews: ReviewService) {
    @PostMapping
    @Operation(summary = "확정 결과 본인 리뷰 요청을 한 번 선점", description = "같은 화면의 request_id 재시도만 동일 pending 반환. 새 화면/기기는 같은 모임을 다시 요청하지 않음")
    fun claim(
        @PathVariable inviteCode: String,
        @CookieValue(name = GuestCookie.NAME, required = false) credential: String?,
        @RequestBody body: ClaimReviewRequest,
    ): ResponseEntity<ReviewResponse> = response(reviews.claim(inviteCode, credential, body.request_id))

    @PostMapping("/{promptId}/submission")
    @Operation(summary = "본인 리뷰 제출", description = "같은 답변 재시도는 멱등. 만족은 영구 중단, 다른 답변은 제출 후 7일 숨김. 답변 조회 API 없음")
    fun submit(
        @PathVariable inviteCode: String,
        @PathVariable promptId: UUID,
        @CookieValue(name = GuestCookie.NAME, required = false) credential: String?,
        @RequestBody body: SubmitReviewRequest,
    ): ResponseEntity<ReviewResponse> = response(reviews.submit(inviteCode, credential, promptId, ReviewAnswer.of(body.choices, body.other_text)))

    @PostMapping("/{promptId}/close")
    @Operation(summary = "본인 리뷰 미제출 닫기", description = "자유 입력을 보내지 않음. 체크한 경우만 서버 시각 기준 7일 숨김. 재시도는 기한을 연장하지 않음")
    fun close(
        @PathVariable inviteCode: String,
        @PathVariable promptId: UUID,
        @CookieValue(name = GuestCookie.NAME, required = false) credential: String?,
        @RequestBody body: CloseReviewRequest,
    ): ResponseEntity<ReviewResponse> = response(reviews.close(inviteCode, credential, promptId, requireNotNull(body.hide_for_week)))

    private fun response(receipt: ReviewReceipt): ResponseEntity<ReviewResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ReviewResponse.from(receipt))
}
