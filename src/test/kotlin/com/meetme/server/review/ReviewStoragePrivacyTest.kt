package com.meetme.server.review

import com.meetme.server.review.adapter.output.persistence.JdbcReviewRepository
import com.meetme.server.review.adapter.input.web.SubmitReviewRequest
import com.meetme.server.review.application.service.ReviewErrorCode
import com.meetme.server.review.application.service.ReviewException
import com.meetme.server.review.domain.ReviewAnswer
import com.meetme.server.review.domain.ReviewOwner
import com.meetme.server.review.domain.ReviewOwnerKind
import com.meetme.server.review.domain.ReviewPolicy
import com.meetme.server.review.domain.ReviewPrompt
import com.meetme.server.review.domain.ReviewPromptState
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Independent RV privacy/cooldown checks; exceptions deliberately carry synthetic private data. */
class ReviewStoragePrivacyTest {
    @Test
    fun `RV15 database failing-row text is removed from public exception including cause`() {
        val repository = JdbcReviewRepository(FailingJdbc())
        val prompt = ReviewPrompt(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), OWNER, UUID.randomUUID(),
            ReviewPromptState.SUBMITTED, NOW, ReviewAnswer.of(listOf(5), PRIVATE), NOW)
        val failure = assertFailsWith<ReviewException> { repository.updatePrompt(prompt) }
        assertEquals(ReviewErrorCode.REVIEW_STORAGE_UNAVAILABLE, failure.code)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains(PRIVATE))
        assertTrue(failure.suppressed.isEmpty())
    }

    @Test
    fun `RV15 policy insertion storage errors are sanitized too`() {
        val failure = assertFailsWith<ReviewException> { JdbcReviewRepository(FailingJdbc()).lockPolicy(OWNER) }
        assertEquals(ReviewErrorCode.REVIEW_STORAGE_UNAVAILABLE, failure.code)
        assertNull(failure.cause)
        assertFalse(failure.stackTraceToString().contains(PRIVATE))
    }

    @Test
    fun `RV15 request and answer string forms never contain free text`() {
        assertFalse(SubmitReviewRequest(listOf(5), PRIVATE).toString().contains(PRIVATE))
        assertFalse(ReviewAnswer.of(listOf(5), PRIVATE).toString().contains(PRIVATE))
    }

    @Test
    fun `RV03 OTHER alone with null empty or whitespace is semantically equal`() {
        val nullAnswer = ReviewAnswer.of(listOf(5), null)
        assertEquals(nullAnswer, ReviewAnswer.of(listOf(5), ""))
        assertEquals(nullAnswer, ReviewAnswer.of(listOf(5), " \t\n"))
        assertEquals(ReviewAnswer.of(listOf(2, 3, 4, 5), PRIVATE), ReviewAnswer.of(listOf(5, 4, 2, 3), PRIVATE))
    }

    @Test
    fun `RV04 exactly 2000 codepoints accepts astral characters and rejects one extra or NUL`() {
        assertEquals("😀".repeat(2000), ReviewAnswer.of(listOf(5), "😀".repeat(2000)).otherText)
        assertFailsWith<IllegalArgumentException> { ReviewAnswer.of(listOf(5), "😀".repeat(2001)) }
        assertFailsWith<IllegalArgumentException> { ReviewAnswer.of(listOf(5), "private\u0000text") }
    }

    @Test
    fun `RV06 satisfied policy never becomes eligible after later hide or dissatisfaction`() {
        val satisfied = ReviewPolicy().submitted(ReviewAnswer.of(listOf(1), null), NOW)
        assertFalse(satisfied.eligible(NOW.plusSeconds(365L * 86400)))
        assertFalse(satisfied.hide(NOW).eligible(NOW.plusSeconds(365L * 86400)))
        assertFalse(satisfied.submitted(ReviewAnswer.of(listOf(2), null), NOW).eligible(NOW.plusSeconds(365L * 86400)))
    }

    @Test
    fun `RV07 one-week hide uses exact elapsed duration at the boundary`() {
        val policy = ReviewPolicy().submitted(ReviewAnswer.of(listOf(3), null), NOW)
        assertFalse(policy.eligible(NOW.plusSeconds(7L * 86400).minusNanos(1)))
        assertTrue(policy.eligible(NOW.plusSeconds(7L * 86400)))
        assertTrue(policy.eligible(NOW.plusSeconds(7L * 86400).plusNanos(1)))
    }

    private class FailingJdbc : JdbcTemplate() {
        override fun update(sql: String, vararg args: Any?): Int = throw DataIntegrityViolationException(PRIVATE)
    }

    companion object {
        private const val PRIVATE = "synthetic@example.test private-review-text"
        private val NOW = Instant.parse("2026-10-28T12:00:00Z")
        private val OWNER = ReviewOwner(ReviewOwnerKind.ACCOUNT, UUID.fromString("10000000-0000-0000-0000-000000000001"))
    }
}
