package com.meetme.server.config

import com.meetme.server.meetingroom.adapter.output.security.SecureInviteCodeGenerator
import com.meetme.server.meetingroom.application.port.output.InviteCodeGenerator
import com.meetme.server.participant.adapter.output.security.GuestCredentialService
import com.meetme.server.shared.adapter.output.security.SystemIdGenerator
import com.meetme.server.shared.application.port.output.IdGenerator
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.security.SecureRandom
import java.time.Clock

@ConfigurationProperties("meetme.anonymous")
data class AnonymousAccessProperties(
    val allowedOrigins: List<String> = listOf("https://app.meet-me.co.kr"),
    val cookieSecure: Boolean = true,
) {
    init {
        require(allowedOrigins.isNotEmpty()) { "At least one anonymous API origin is required" }
        allowedOrigins.forEach { origin ->
            require(origin != "*") { "Wildcard anonymous API origins are forbidden" }
            val uri = runCatching { URI(origin) }.getOrElse { throw IllegalArgumentException("Invalid anonymous API origin") }
            require(uri.scheme in setOf("http", "https") && uri.host != null) { "Anonymous API origins require an HTTP(S) scheme and host" }
            require(uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null) {
                "Anonymous API origins must not contain user info, path, query, or fragment"
            }
            require(uri.toString() == origin) { "Anonymous API origins must use a canonical exact form" }
        }
    }
}

@Configuration
@EnableConfigurationProperties(AnonymousAccessProperties::class)
class AnonymousAccessConfiguration {
    @Bean
    fun systemClock(): Clock = Clock.systemUTC()

    @Bean
    fun secureRandom(): SecureRandom = SecureRandom()

    @Bean
    fun guestCredentialService(secureRandom: SecureRandom) = GuestCredentialService(secureRandom)

    @Bean
    fun inviteCodeGenerator(secureRandom: SecureRandom): InviteCodeGenerator = SecureInviteCodeGenerator(secureRandom)

    @Bean
    fun idGenerator(): IdGenerator = SystemIdGenerator()
}
