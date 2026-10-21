package com.meetme.server.precheck.adapter.output.classification

import org.springframework.boot.context.properties.ConfigurationProperties

/** Explicit opt-in alone does not supply a confidence policy, verified quote, or live API evidence. */
@ConfigurationProperties(prefix = "meetme.precheck.classifier")
data class AmbiguityClassifierRuntimeProperties(
    val enabled: Boolean = false,
    val minimumConfidence: Double? = null,
)
