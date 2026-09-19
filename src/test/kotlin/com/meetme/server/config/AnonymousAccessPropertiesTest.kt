package com.meetme.server.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class AnonymousAccessPropertiesTest {
    @Test
    fun `정확한 HTTP origin만 허용한다`() {
        assertEquals(
            listOf("https://app.meet-me.co.kr", "http://localhost:3000"),
            AnonymousAccessProperties(
                allowedOrigins = listOf("https://app.meet-me.co.kr", "http://localhost:3000"),
            ).allowedOrigins,
        )
    }

    @Test
    fun `빈 목록 wildcard와 경로가 있는 origin을 거부한다`() {
        assertThrows<IllegalArgumentException> { AnonymousAccessProperties(allowedOrigins = emptyList()) }
        assertThrows<IllegalArgumentException> { AnonymousAccessProperties(allowedOrigins = listOf("*")) }
        assertThrows<IllegalArgumentException> {
            AnonymousAccessProperties(allowedOrigins = listOf("https://app.meet-me.co.kr/path"))
        }
    }
}
