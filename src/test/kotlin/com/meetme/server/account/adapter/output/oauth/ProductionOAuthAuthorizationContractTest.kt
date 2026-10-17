package com.meetme.server.account.adapter.output.oauth

import com.meetme.server.account.domain.OAuthProvider
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// Google OIDC authorization scope uses spaces; Kakao Get authorization code requires comma-separated consent IDs.
// https://developers.kakao.com/docs/en/kakaologin/rest-api#get-authorization-code
// Tests exercise unmodified production endpoint defaults without any provider request or actual credentials.
class ProductionOAuthAuthorizationContractTest {
    @Test
    fun `Google default authorization requests only space separated openid and profile scopes`() {
        val uri = authorization(OAuthProvider.GOOGLE)
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth", uri.toString().substringBefore('?'))
        assertEquals("openid profile", query(uri)["scope"])
    }

    @Test
    fun `Kakao default authorization requests only comma separated openid and nickname consent IDs`() {
        val uri = authorization(OAuthProvider.KAKAO)
        assertEquals("https://kauth.kakao.com/oauth/authorize", uri.toString().substringBefore('?'))
        assertEquals("openid,profile_nickname", query(uri)["scope"])
    }

    private fun authorization(provider: OAuthProvider): URI {
        val adapter =
            OidcOAuthProviderAdapter(
                OAuthProvider.entries.associateWith {
                    OidcClient("synthetic-client", "synthetic-secret", "https://api.synthetic.example/api/auth/callback")
                },
                Clock.systemUTC(),
            )
        val uri = URI(adapter.authorizationUri(provider, "synthetic-state", "synthetic-challenge", "synthetic-nonce"))
        val fields = query(uri)
        assertEquals("code", fields["response_type"])
        assertEquals("synthetic-client", fields["client_id"])
        assertEquals("https://api.synthetic.example/api/auth/callback", fields["redirect_uri"])
        assertEquals("synthetic-state", fields["state"])
        assertEquals("synthetic-nonce", fields["nonce"])
        assertEquals("S256", fields["code_challenge_method"])
        assertEquals("synthetic-challenge", fields["code_challenge"])
        assertFalse(fields.containsKey("client_secret"))
        assertFalse(requireNotNull(fields["scope"]).contains("email", ignoreCase = true))
        assertFalse(requireNotNull(fields["scope"]).contains("calendar", ignoreCase = true))
        return uri
    }

    private fun query(uri: URI): Map<String, String> =
        requireNotNull(uri.rawQuery).split('&').associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], UTF_8) to URLDecoder.decode(pair.getOrElse(1) { "" }, UTF_8)
        }
}
