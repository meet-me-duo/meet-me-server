package com.meetme.server.precheck.adapter.output.classification

import java.net.URI

/** Pinned wire identity only; defaults do not activate dispatch or supply a confidence policy. */
data class JevClassifierOptions(
    val endpoint: URI = URI("https://api.typesafe.ai/v1/systemone"),
    val enabled: Boolean = false,
    val model: String = "jev-1.14.0",
    val minimumConfidence: Double? = null,
    val requireMinimumConfidence: Boolean = true,
)
