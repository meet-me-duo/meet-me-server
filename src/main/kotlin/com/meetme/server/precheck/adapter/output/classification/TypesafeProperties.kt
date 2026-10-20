package com.meetme.server.precheck.adapter.output.classification

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "meetme.typesafe")
data class TypesafeProperties(
    val apiKey: String = "",
)
