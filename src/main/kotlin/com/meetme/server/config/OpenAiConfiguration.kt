package com.meetme.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("meetme.openai")
data class OpenAiProperties(
    val apiKey: String = "",
    val model: String = "gpt-6-luna",
    val maxResponseBytes: Int = 262_144,
) {
    init {
        require(model == "gpt-6-luna") { "The accepted fallback model must remain pinned" }
        require(maxResponseBytes == 262_144) { "OpenAI response limit must be 256 KiB" }
    }
}

@Configuration
@EnableConfigurationProperties(OpenAiProperties::class)
class OpenAiConfiguration
