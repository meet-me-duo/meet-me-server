package com.meetme.server.shared.adapter.input.web

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@ExtendWith(OutputCaptureExtension::class)
class CorrelationLoggingFilterTest {
    @Test
    fun `요청 로그는 서버 상관관계 ID와 경로 템플릿만 남기고 민감한 식별자를 제외한다`(output: CapturedOutput) {
        val inviteCode = "private-invite-code"
        val request = MockHttpServletRequest("PUT", "/api/rooms/$inviteCode/submission")
        request.addHeader("Cookie", "meet_me_guest=secret-credential")
        val response = MockHttpServletResponse()

        CorrelationLoggingFilter().doFilter(request, response, MockFilterChain())

        assertNotNull(response.getHeader(CorrelationLoggingFilter.HEADER))
        assertFalse(output.out.contains(inviteCode))
        assertFalse(output.out.contains("secret-credential"))
    }
}
