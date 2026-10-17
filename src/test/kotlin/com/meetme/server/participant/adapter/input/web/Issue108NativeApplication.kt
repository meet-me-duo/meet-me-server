package com.meetme.server.participant.adapter.input.web

import com.meetme.server.MeetMeServerApplication
import com.meetme.server.account.adapter.output.oauth.OidcClient
import com.meetme.server.account.adapter.output.oauth.OidcEndpoints
import com.meetme.server.account.adapter.output.oauth.OidcOAuthProviderAdapter
import com.meetme.server.account.application.port.output.OAuthProviderPort
import com.meetme.server.account.domain.OAuthProvider
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock

/** Explicit test-classpath entrypoint. Never imported by the production application. */
object Issue108NativeApplication {
    @JvmStatic
    fun main(args: Array<String>) {
        SpringApplicationBuilder(MeetMeServerApplication::class.java, NativeOAuthConfiguration::class.java).run(*args)
    }
}

@TestConfiguration(proxyBeanMethods = false)
class NativeOAuthConfiguration {
    @Bean
    @Primary
    fun isolatedGuestNativeOAuth(clock: Clock): OAuthProviderPort =
        OidcOAuthProviderAdapter(
            OAuthProvider.entries.associateWith { provider ->
                val name = provider.name.lowercase()
                OidcClient("synthetic-$name-108", "synthetic-local-only", "http://localhost:8080/api/auth/$name/callback")
            },
            clock,
            endpoints =
                OAuthProvider.entries.associateWith { provider ->
                    val base = "http://localhost:9091/${provider.name.lowercase()}"
                    OidcEndpoints("$base/authorize", "$base/token", "$base/jwks", base, "openid profile")
                },
            allowLoopbackTestEndpoints = true,
        )
}
