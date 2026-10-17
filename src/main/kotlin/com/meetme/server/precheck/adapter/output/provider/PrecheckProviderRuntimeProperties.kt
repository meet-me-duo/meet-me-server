package com.meetme.server.precheck.adapter.output.provider

import okhttp3.OkHttpClient
import org.springframework.boot.context.properties.ConfigurationProperties

/** An intentional opt-in, distinct from the fixed documented API capability profile. */
@ConfigurationProperties("meetme.precheck.provider")
data class PrecheckProviderRuntimeProperties(
    val enabled: Boolean = false,
)

/** Test substitution seam; the endpoint is never an application configuration property. */
class PrecheckProviderHttpTransport(
    val client: OkHttpClient = OkHttpClient(),
)
