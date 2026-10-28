package com.meetme.server.review.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class ReviewOwnerKind { ACCOUNT, GUEST }

data class ReviewOwner(
    val kind: ReviewOwnerKind,
    val id: UUID,
) {
    val key: String get() = "$kind:$id"
}

class ReviewAnswer private constructor(
    val choices: Set<Int>,
    val otherText: String?,
) {
    val satisfied: Boolean get() = choices == setOf(1)

    override fun equals(other: Any?): Boolean = other is ReviewAnswer && choices == other.choices && otherText == other.otherText

    override fun hashCode(): Int = 31 * choices.hashCode() + (otherText?.hashCode() ?: 0)

    override fun toString(): String = "ReviewAnswer(redacted)"

    companion object {
        const val MAX_TEXT_CODE_POINTS = 2000

        fun of(
            choices: List<Int>,
            otherText: String?,
        ): ReviewAnswer {
            require(choices.isNotEmpty() && choices.size <= 5 && choices.distinct().size == choices.size)
            require(choices.all { it in 1..5 } && (1 !in choices || choices.size == 1))
            require(otherText == null || 5 in choices)
            require(otherText == null || otherText.codePointCount(0, otherText.length) <= MAX_TEXT_CODE_POINTS)
            require(otherText == null || '\u0000' !in otherText)
            return ReviewAnswer(choices.toSet(), otherText?.takeUnless { it.isBlank() })
        }
    }
}

data class ReviewPolicy(
    val satisfiedAt: Instant? = null,
    val hiddenUntil: Instant? = null,
) {
    fun eligible(now: Instant): Boolean = satisfiedAt == null && (hiddenUntil == null || now >= hiddenUntil)

    fun submitted(
        answer: ReviewAnswer,
        now: Instant,
    ): ReviewPolicy = if (answer.satisfied) copy(satisfiedAt = satisfiedAt ?: now) else hide(now)

    fun hide(now: Instant): ReviewPolicy = copy(hiddenUntil = maxOf(hiddenUntil ?: Instant.MIN, now.plus(WEEK)))

    companion object {
        val WEEK: Duration = Duration.ofDays(7)
    }
}

enum class ReviewPromptState { PENDING, SUBMITTED, CLOSED, SUPPRESSED }

data class ReviewPrompt(
    val id: UUID,
    val participantId: UUID,
    val roomId: UUID,
    val owner: ReviewOwner,
    val requestId: UUID,
    val state: ReviewPromptState,
    val createdAt: Instant,
    val answer: ReviewAnswer? = null,
    val completedAt: Instant? = null,
    val hideForWeek: Boolean = false,
)
