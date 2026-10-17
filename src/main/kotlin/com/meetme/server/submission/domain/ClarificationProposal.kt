package com.meetme.server.submission.domain

/** A single unresolved distinction; options replace the complete input, preserving every other constraint. */
data class ClarificationProposal(
    val excerpt: String,
    val question: String,
    val optionA: String,
    val optionB: String,
)
