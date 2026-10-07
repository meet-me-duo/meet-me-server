package com.meetme.server.coordination.adapter.input.web

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import kotlin.test.assertFalse

// Actual application request logs, including correction success and authentication rejection; synthetic data only.
@ExtendWith(OutputCaptureExtension::class)
class CorrectionRoundLoggingPostgresTest : CorrectionRoundPostgresFixture() {
    @Test
    fun `correction HTTP requests never log own raw text room code or guest credentials`(output: CapturedOutput) {
        val fixture = completedRoom()
        val roundId = openRound(fixture)
        val raw = "private-synthetic-raw-${UUID.randomUUID()}"
        mvc.perform(save(fixture.host, raw, roundId, 1)).andExpect(status().isOk)
        mvc.perform(analyze(fixture, roundId)).andExpect(status().isAccepted)
        mvc
            .perform(
                post("/api/rooms/{code}/reopen", fixture.host.code)
                    .cookie(fixture.host.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"request_id":"${UUID.randomUUID()}","source_analysis_id":"${fixture.sourceRun}","expected_generation":1}""",
                    ),
            ).andExpect(status().isForbidden)
        for (privateValue in listOf(
            raw,
            "H0 original",
            "M0 original",
            fixture.host.code,
            fixture.host.credential,
            fixture.member.credential,
        )) {
            assertFalse(output.all.contains(privateValue), "Application request logs must omit synthetic private input and credentials")
        }
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = CorrectionRoundPostgresFixture.registerCorrectionDatabase(registry, postgres)

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
