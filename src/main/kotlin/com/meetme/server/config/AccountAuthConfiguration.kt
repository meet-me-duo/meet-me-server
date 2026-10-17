package com.meetme.server.config

import com.meetme.server.account.adapter.input.web.RequestCurrentAccountAdapter
import com.meetme.server.account.adapter.output.oauth.OidcClient
import com.meetme.server.account.adapter.output.oauth.OidcOAuthProviderAdapter
import com.meetme.server.account.adapter.output.persistence.JdbcAccountRepository
import com.meetme.server.account.adapter.output.redis.RedisOAuthStateAdapter
import com.meetme.server.account.adapter.output.redis.RedisRefreshSessionAdapter
import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.application.port.output.AccountRepository
import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.account.application.port.output.OAuthProviderPort
import com.meetme.server.account.application.port.output.OAuthStatePort
import com.meetme.server.account.application.port.output.RefreshSessionPort
import com.meetme.server.account.application.service.AccountIdentityService
import com.meetme.server.account.application.service.OAuthLoginService
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.AuthFailure
import com.meetme.server.account.domain.OAuthProvider
import com.meetme.server.account.domain.ProviderIdentity
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.util.Base64

@ConfigurationProperties("meetme.auth")
data class AccountAuthProperties(
    val signingKeyId: String = "",
    val signingPrivateKeyPath: String = "",
    val verificationJwksPath: String = "",
    val issuer: String = "https://api.meet-me.co.kr",
    val audience: String = "meetme-web",
    val webCallbackUri: String = "https://app.meet-me.co.kr/auth/callback",
    val google: OAuthClientProperties = OAuthClientProperties(redirectUri = "https://api.meet-me.co.kr/api/auth/google/callback"),
    val kakao: OAuthClientProperties = OAuthClientProperties(redirectUri = "https://api.meet-me.co.kr/api/auth/kakao/callback"),
) {
    fun signingConfigured(): Boolean = signingKeyId.isNotBlank() && signingPrivateKeyPath.isNotBlank() && verificationJwksPath.isNotBlank()
}

data class OAuthClientProperties(
    val clientId: String = "",
    val clientSecret: String = "",
    val redirectUri: String = "",
) {
    override fun toString(): String = "OAuthClientProperties(configured=${clientId.isNotBlank()})"
}

@Configuration
@EnableConfigurationProperties(AccountAuthProperties::class)
class AccountAuthConfiguration {
    @Bean
    fun accountRepository(
        jdbc: JdbcTemplate,
        clock: Clock,
    ): AccountRepository = AccountIdentityService(JdbcAccountRepository(jdbc, clock))

    @Bean
    fun refreshSessionPort(
        redis: StringRedisTemplate,
        clock: Clock,
    ): RefreshSessionPort = RedisRefreshSessionAdapter(redis, clock)

    @Bean
    fun oauthStatePort(
        redis: StringRedisTemplate,
        clock: Clock,
    ): OAuthStatePort = RedisOAuthStateAdapter(redis, clock)

    @Bean
    fun accessTokenPort(
        properties: AccountAuthProperties,
        clock: Clock,
    ): AccessTokenPort {
        val settings = listOf(properties.signingKeyId, properties.signingPrivateKeyPath, properties.verificationJwksPath)
        if (settings.all(String::isBlank)) {
            return object : AccessTokenPort {
                override fun issue(principal: AccountPrincipal): String = throw AuthFailure("AUTH_UNAVAILABLE")

                override fun verify(token: String): AccountPrincipal = throw AuthFailure("AUTH_UNAVAILABLE")
            }
        }
        require(properties.signingConfigured()) { "Incomplete auth signing configuration" }
        try {
            val pemPath = Path.of(properties.signingPrivateKeyPath)
            val jwksPath = Path.of(properties.verificationJwksPath)
            require(Files.size(pemPath) <= 16_384 && Files.size(jwksPath) <= 65_536)
            val pem =
                Files
                    .readString(pemPath)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace(Regex("\\s"), "")
            val privateKey =
                KeyFactory
                    .getInstance(
                        "RSA",
                    ).generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem))) as RSAPrivateKey
            val jwks = JWKSet.parse(Files.readString(jwksPath))
            require(jwks.keys.isNotEmpty() && jwks.keys.all { it is RSAKey && !it.isPrivate && !it.keyID.isNullOrBlank() })
            require(
                jwks.keys
                    .map { it.keyID }
                    .toSet()
                    .size == jwks.keys.size,
            )
            val publicKeys = jwks.keys.associate { it.keyID to (it as RSAKey).toRSAPublicKey() }
            require(privateKey.modulus == publicKeys[properties.signingKeyId]?.modulus)
            return Rs256AccessTokenAdapter(privateKey, publicKeys, properties.signingKeyId, clock, properties.issuer, properties.audience)
        } catch (_: Exception) {
            throw IllegalStateException("Auth signing configuration is invalid")
        }
    }

    @Bean
    fun oauthProviderPort(
        properties: AccountAuthProperties,
        origins: AnonymousAccessProperties,
        clock: Clock,
    ): OAuthProviderPort {
        if (!properties.signingConfigured()) {
            return object : OAuthProviderPort {
                override fun authorizationUri(
                    provider: OAuthProvider,
                    state: String,
                    codeChallenge: String,
                    nonce: String,
                ): String = throw AuthFailure("AUTH_UNAVAILABLE")

                override fun exchange(
                    provider: OAuthProvider,
                    code: String,
                    codeVerifier: String,
                    expectedNonce: String,
                ): ProviderIdentity = throw AuthFailure("AUTH_UNAVAILABLE")
            }
        }
        val callback = URI.create(properties.webCallbackUri)
        require(callback.path == "/auth/callback" && callback.userInfo == null && callback.rawQuery == null && callback.rawFragment == null)
        require("${callback.scheme}://${callback.rawAuthority}" in origins.allowedOrigins) {
            "Auth callback must use an explicitly allowed frontend origin"
        }
        return OidcOAuthProviderAdapter(
            mapOf(
                OAuthProvider.GOOGLE to
                    OidcClient(properties.google.clientId, properties.google.clientSecret, properties.google.redirectUri),
                OAuthProvider.KAKAO to OidcClient(properties.kakao.clientId, properties.kakao.clientSecret, properties.kakao.redirectUri),
            ),
            clock,
        )
    }

    @Bean
    fun currentAccountPort(access: AccessTokenPort): CurrentAccountPort = RequestCurrentAccountAdapter(access)

    @Bean
    fun oauthLoginService(
        state: OAuthStatePort,
        provider: OAuthProviderPort,
        accounts: AccountRepository,
        refresh: RefreshSessionPort,
        clock: Clock,
    ) = OAuthLoginService(state, provider, accounts, refresh, clock)
}

@Configuration
class AccountBearerWebConfiguration(
    private val current: CurrentAccountPort,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry
            .addInterceptor(
                object : HandlerInterceptor {
                    override fun preHandle(
                        request: HttpServletRequest,
                        response: HttpServletResponse,
                        handler: Any,
                    ): Boolean {
                        current.currentAccount()
                        return true
                    }
                },
            ).addPathPatterns("/api/**")
            .order(-100)
    }
}
