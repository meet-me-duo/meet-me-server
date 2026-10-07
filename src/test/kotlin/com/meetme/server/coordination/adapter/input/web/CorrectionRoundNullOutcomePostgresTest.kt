package com.meetme.server.coordination.adapter.input.web

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertFailsWith

// Issue #92 persisted idempotency requires a non-null QUEUED/REUSED outcome for every consumed round.
class CorrectionRoundNullOutcomePostgresTest : CorrectionRoundPostgresFixture() {
    @Test
    fun `consumed round cannot lose persisted outcome through SQL NULL semantics`() {
        val fixture = completedRoom()
        val roundId = openRound(fixture)
        mvc.perform(analyze(fixture, roundId)).andExpect(status().isOk)

        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("UPDATE input_revision_rounds SET outcome = NULL WHERE id = ?", roundId)
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
