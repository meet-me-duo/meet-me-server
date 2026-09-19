package com.meetme.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties("meetme.gemini")
data class GeminiProperties(
    val apiKey: String = "",
    val model: String = "gemini-3.8-flash",
    val maxResponseBytes: Int = 262_144,
) {
    init {
        require(model == "gemini-3.8-flash") { "The accepted Gemini model must remain pinned" }
        require(maxResponseBytes == 262_144) { "Gemini response limit must be 256 KiB" }
    }
}

@Configuration
@EnableConfigurationProperties(GeminiProperties::class)
class GeminiConfiguration
