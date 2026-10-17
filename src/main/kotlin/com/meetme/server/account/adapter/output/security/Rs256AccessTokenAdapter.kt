package com.meetme.server.account.adapter.output.security

import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.AuthFailure
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.util.UUID

class Rs256AccessTokenAdapter(
    private val privateKey: PrivateKey,
    private val verificationKeys: Map<String, PublicKey>,
    private val signingKeyId: String,
    private val clock: Clock,
    private val issuer: String,
    private val audience: String,
) : AccessTokenPort {
    init {
        require(privateKey is RSAPrivateKey && privateKey.modulus.bitLength() >= 2048)
        require(signingKeyId.isNotBlank() && verificationKeys[signingKeyId] is RSAPublicKey)
        require(verificationKeys.values.all { it is RSAPublicKey && it.modulus.bitLength() >= 2048 })
        require(issuer.isNotBlank() && audience.isNotBlank())
    }

    override fun issue(principal: AccountPrincipal): String {
        val now = clock.instant().epochSecond
        val payload =
            mapOf(
                "iss" to issuer,
                "aud" to audience,
                "sub" to principal.accountId.toString(),
                "name" to principal.displayName,
                "iat" to now,
                "exp" to now + 900,
                "jti" to UUID.randomUUID().toString(),
            )
        return JWSObject(
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(signingKeyId)
                .build(),
            Payload(payload),
        ).apply { sign(RSASSASigner(privateKey as RSAPrivateKey)) }
            .serialize()
    }

    override fun verify(token: String): AccountPrincipal {
        try {
            if (token.length !in 1..8192) throw AuthFailure("AUTH_ACCESS_INVALID")
            val jwt = SignedJWT.parse(token)
            val key = verificationKeys[jwt.header.keyID] as? RSAPublicKey ?: throw AuthFailure("AUTH_ACCESS_INVALID")
            if (jwt.header.algorithm != JWSAlgorithm.RS256 || !jwt.verify(RSASSAVerifier(key))) throw AuthFailure("AUTH_ACCESS_INVALID")
            val claims = jwt.jwtClaimsSet
            val now = clock.instant()
            val issued = claims.issueTime?.toInstant() ?: throw AuthFailure("AUTH_ACCESS_INVALID")
            val expires = claims.expirationTime?.toInstant() ?: throw AuthFailure("AUTH_ACCESS_INVALID")
            if (claims.issuer != issuer ||
                claims.audience != listOf(audience) ||
                !expires.isAfter(now) ||
                issued.isAfter(now) ||
                !expires.isAfter(issued) ||
                expires.isAfter(issued.plusSeconds(900)) ||
                claims.notBeforeTime?.toInstant()?.isAfter(now) == true
            ) {
                throw AuthFailure("AUTH_ACCESS_INVALID")
            }
            val name = claims.getStringClaim("name") ?: throw AuthFailure("AUTH_ACCESS_INVALID")
            if (name.isBlank() || name.codePointCount(0, name.length) > 50) throw AuthFailure("AUTH_ACCESS_INVALID")
            return AccountPrincipal(UUID.fromString(claims.subject), name)
        } catch (failure: AuthFailure) {
            throw failure
        } catch (_: Exception) {
            throw AuthFailure("AUTH_ACCESS_INVALID")
        }
    }
}
