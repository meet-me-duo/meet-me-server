package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.adapter.input.web.MatchingResultController
import com.meetme.server.coordination.application.port.input.MatchingResultErrorCode
import com.meetme.server.coordination.application.port.input.MatchingResultException
import com.meetme.server.shared.adapter.input.web.ApiExceptionHandler
import com.meetme.server.shared.adapter.input.web.GuestCookie
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.support.StaticMessageSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Test isolation only: a new fixture must not borrow another test's request or change real HTTP actor selection. */
class NaturalLanguageFixtureRequestIsolationTest {
    @Test
    fun `NFI01 a borrowed host request cannot override the new fixture member viewer`() {
        preservingRequestAttributes {
            val foreign =
                ServletRequestAttributes(
                    MockHttpServletRequest("GET", "/synthetic-foreign-test-request").apply {
                        setCookies(Cookie(GuestCookie.NAME, "host"))
                    },
                )
            try {
                RequestContextHolder.setRequestAttributes(foreign)
                assertSame(foreign, RequestContextHolder.getRequestAttributes())
                val fixture = fixture()
                val service = fixture.resultService()
                fixture.viewAsMember()

                val denied =
                    assertThrows<MatchingResultException> {
                        service.getUnappliedInputs(fixture.room.inviteCode.value, "member")
                    }
                assertEquals(MatchingResultErrorCode.HOST_PERMISSION_REQUIRED, denied.code)
            } finally {
                foreign.requestCompleted()
            }
        }
    }

    @Test
    fun `NFI02 own MockMvc requests still allow host and deny member after member viewer selection`() {
        preservingRequestAttributes {
            val fixture = fixture()
            val service = fixture.resultService()
            fixture.viewAsMember()
            val mvc =
                MockMvcBuilders
                    .standaloneSetup(MatchingResultController(service, service, service, service))
                    .setControllerAdvice(ApiExceptionHandler(StaticMessageSource()))
                    .build()
            val endpoint = "/api/rooms/{code}/candidates/unapplied-inputs"

            mvc
                .perform(get(endpoint, fixture.room.inviteCode.value).cookie(Cookie(GuestCookie.NAME, "host")))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reason").value("LEGACY_MANUAL_ONLY_UNSUPPORTED"))
            mvc
                .perform(get(endpoint, fixture.room.inviteCode.value).cookie(Cookie(GuestCookie.NAME, "member")))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("HOST_PERMISSION_REQUIRED"))
        }
    }

    private fun fixture() =
        NaturalLanguageOnlyFixture(listOf("synthetic natural availability", null)).apply {
            results(listOf(window(9, 12)))
            process()
        }

    private fun preservingRequestAttributes(block: () -> Unit) {
        val previous = RequestContextHolder.getRequestAttributes()
        try {
            RequestContextHolder.resetRequestAttributes()
            block()
        } finally {
            RequestContextHolder.resetRequestAttributes()
            previous?.let { RequestContextHolder.setRequestAttributes(it) }
        }
    }
}
