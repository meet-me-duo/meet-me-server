package com.meetme.server.config

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.MessageSource
import java.util.Locale
import kotlin.test.assertEquals

@SpringBootTest
class MessageSourceConfigurationTest(
    @Autowired private val messageSource: MessageSource,
) {
    @Test
    fun `기본 메시지를 한국어로 조회한다`() {
        val message = messageSource.getMessage("common.error.internal", null, Locale.ENGLISH)

        assertEquals("서버 내부 오류가 발생했습니다.", message)
    }
}
