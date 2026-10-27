package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.shared.adapter.input.web.ApiExceptionHandler
import com.meetme.server.shared.application.port.output.NewRoomAiBudgetUnavailableException
import jakarta.servlet.ServletException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.support.StaticMessageSource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The actual advice must expose an empty existing HTTP500 boundary, without broadening unrelated IllegalState handling. */
class Issue131ManagedRuntimeErrorContractTest {
    private val mvc =
        MockMvcBuilders
            .standaloneSetup(SyntheticController())
            .setControllerAdvice(ApiExceptionHandler(StaticMessageSource()))
            .build()

    @Test
    fun `MB08 typed unavailable configuration yields HTTP500 empty body with no invented problem code`() {
        val response = mvc.perform(post("/synthetic/typed-budget-failure")).andReturn().response
        assertEquals(500, response.status)
        assertEquals("", response.contentAsString)
        assertEquals(0, response.contentAsByteArray.size)
    }

    @Test
    fun `MB08 unrelated illegal state is not newly caught by the configuration boundary`() {
        val failure = assertThrows<ServletException> { mvc.perform(post("/synthetic/unrelated-failure")) }
        assertIs<IllegalStateException>(failure.cause)
        assertEquals("synthetic-other-failure", failure.cause?.message)
    }

    @RestController
    class SyntheticController {
        @PostMapping("/synthetic/typed-budget-failure")
        fun typed(): String = throw NewRoomAiBudgetUnavailableException()

        @PostMapping("/synthetic/unrelated-failure")
        fun unrelated(): String = throw IllegalStateException("synthetic-other-failure")
    }
}
