package com.meetme.server.submission.adapter.input.web

import com.meetme.server.shared.adapter.input.web.ApiExceptionHandler
import com.meetme.server.shared.domain.time.DatedTimeRange
import com.meetme.server.shared.domain.time.LocalTimeRange
import com.meetme.server.submission.application.port.input.GetOwnSubmissionUseCase
import com.meetme.server.submission.application.port.input.SaveSubmissionCommand
import com.meetme.server.submission.application.port.input.SaveSubmissionUseCase
import com.meetme.server.submission.application.port.input.SubmissionView
import com.meetme.server.submission.domain.ManualAvailability
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.support.StaticMessageSource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.stream.Stream
import kotlin.test.assertEquals

// Exercise JSON shape before the application boundary; text semantics and atomicity use PostgreSQL tests.
class SubmissionNaturalLanguageOnlyWebTest {
    private lateinit var port: CapturingPort
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        port = CapturingPort()
        mvc =
            MockMvcBuilders
                .standaloneSetup(SubmissionController(port, port))
                .setControllerAdvice(ApiExceptionHandler(StaticMessageSource()))
                .build()
    }

    @ParameterizedTest(name = "unsupported array case {index}")
    @MethodSource("unsupportedArrays")
    fun `every nonempty array is rejected before slot conversion or save use case`(array: String) {
        mvc
            .perform(
                put("/api/rooms/{code}/submission", CODE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"raw_text":"natural","manual_available_times":$array}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("SUBMISSION_MANUAL_AVAILABILITY_UNSUPPORTED"))
        assertEquals(0, port.saves)
    }

    @ParameterizedTest(name = "wrong JSON shape case {index}")
    @MethodSource("wrongShapes")
    fun `raw text wrong types manual wrong types and unknown fields reject before use case`(body: String) {
        mvc
            .perform(put("/api/rooms/{code}/submission", CODE).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        assertEquals(0, port.saves)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", ",\"manual_available_times\":[]", ",\"manual_available_times\":null"])
    fun `natural input allows absent empty and null compatibility shim`(shim: String) {
        mvc
            .perform(
                put("/api/rooms/{code}/submission", CODE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"raw_text":"natural"$shim}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.raw_text").value("natural"))
            .andExpect(jsonPath("$.manual_available_times").isEmpty)
        assertEquals(1, port.saves)
    }

    @Test
    fun `GET keeps nullable archived text but never exposes archived manual slots`() {
        port.archived =
            SubmissionView(
                7,
                null,
                listOf(
                    ManualAvailability.Dated(
                        DatedTimeRange(
                            LocalDate.of(2026, 9, 21),
                            LocalTimeRange.of(LocalTime.of(18, 0), LocalTime.of(20, 0)),
                        ),
                    ),
                ),
                "ko-KR",
                NOW,
                true,
            )
        mvc
            .perform(get("/api/rooms/{code}/submission", CODE))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.revision").value(7))
            .andExpect(jsonPath("$.raw_text").value(nullValue()))
            .andExpect(jsonPath("$.manual_available_times").isEmpty)
            .andExpect(jsonPath("$.locale").value("ko-KR"))
            .andExpect(jsonPath("$.created_at").exists())
            .andExpect(jsonPath("$.editable").value(true))
    }

    private class CapturingPort :
        SaveSubmissionUseCase,
        GetOwnSubmissionUseCase {
        var saves = 0
        var archived = SubmissionView(1, "natural", emptyList(), "ko-KR", NOW, true)

        override fun save(command: SaveSubmissionCommand): SubmissionView {
            saves++
            return archived.copy(rawText = command.rawText)
        }

        override fun get(
            inviteCode: String,
            rawCredential: String?,
        ): SubmissionView = archived
    }

    companion object {
        private const val CODE = "abcdefghijklmnopqrstuv"
        private val NOW = Instant.parse("2026-09-19T00:00:00Z")

        @JvmStatic
        fun unsupportedArrays(): Stream<Arguments> =
            listOf(
                "[{}]",
                "[null]",
                "[1]",
                "[true]",
                "[\"slot\"]",
                "[[]]",
                "[{\"kind\":\"INVALID\"}]",
                """[{"kind":"WEEKLY","day_of_week":"MONDAY","start_time":"18:00","end_time":"20:00"}]""",
                """[{"kind":"DATED","date":"1900-01-01","start_time":"bad","end_time":"24:00"}]""",
                "[" + "null,".repeat(256) + "null]",
            ).stream().map { Arguments.of(it) }

        @JvmStatic
        fun wrongShapes(): Stream<Arguments> =
            (
                listOf("1", "1.25", "true", "false", "[]", "{}").map { """{"raw_text":$it}""" } +
                    listOf("1", "true", "\"slot\"", "{}").map { """{"raw_text":"natural","manual_available_times":$it}""" } +
                    listOf("""{"raw_text":"natural","calendar_blocked_times":[]}""")
            ).stream().map { Arguments.of(it) }
    }
}
