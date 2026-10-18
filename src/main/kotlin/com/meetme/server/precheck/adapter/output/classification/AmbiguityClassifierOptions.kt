package com.meetme.server.precheck.adapter.output.classification

import java.net.URI

/** Explicit synthetic options only; no environment binding or production confidence policy. */
data class AmbiguityClassifierOptions(
    val endpoint: URI = URI("https://api.openai.com/v1/decisions"),
    val enabled: Boolean = false,
    val minimumConfidence: Double? = null,
)
