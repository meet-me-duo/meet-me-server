package com.meetme.server.review.application.port.output

import com.meetme.server.review.domain.ReviewOwner
import com.meetme.server.review.domain.ReviewPolicy
import com.meetme.server.review.domain.ReviewPrompt
import java.util.UUID

interface ReviewRepository {
    fun lockPolicy(owner: ReviewOwner): ReviewPolicy

    fun savePolicy(
        owner: ReviewOwner,
        policy: ReviewPolicy,
    )

    fun findPrompt(participantId: UUID): ReviewPrompt?

    fun insertPrompt(prompt: ReviewPrompt)

    fun updatePrompt(prompt: ReviewPrompt)
}
