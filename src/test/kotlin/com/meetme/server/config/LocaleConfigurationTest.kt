package com.meetme.server.config

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.util.Locale
import kotlin.test.assertEquals

class LocaleConfigurationTest {
    private val localeResolver = LocaleConfiguration().localeResolver()

    @Test
    fun `Accept-Language가 없으면 ko-KR을 사용한다`() {
        val request = MockHttpServletRequest()

        assertEquals(Locale.forLanguageTag("ko-KR"), localeResolver.resolveLocale(request))
    }

    @Test
    fun `지원하는 ko-KR을 선택한다`() {
        val request =
            MockHttpServletRequest().apply {
                addHeader("Accept-Language", "en-US;q=0.8, ko-KR;q=0.9")
            }

        assertEquals(Locale.forLanguageTag("ko-KR"), localeResolver.resolveLocale(request))
    }

    @Test
    fun `지원하지 않는 언어는 ko-KR로 대체한다`() {
        val request =
            MockHttpServletRequest().apply {
                addHeader("Accept-Language", "en-US")
            }

        assertEquals(Locale.forLanguageTag("ko-KR"), localeResolver.resolveLocale(request))
    }
}
