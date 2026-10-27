package com.meetme.server.precheck.adapter.output.provider

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("meetme.precheck.decision-routing")
data class PrecheckDecisionRoutingProperties(
    val enabled: Boolean = false,
)
