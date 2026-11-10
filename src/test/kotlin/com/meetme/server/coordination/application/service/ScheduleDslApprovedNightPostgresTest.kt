package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslApprovedTermsResponseParser
import com.meetme.server.coordination.domain.dsl.ScheduleDslInputPolicy
import com.meetme.server.coordination.domain.matching.DeterministicCandidateMatcher
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslApprovedTermsRepository
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTerm
import com.meetme.server.support.FinalAnalysisRouteTestFixture
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ApprovedTimeTermFullTableFixture as T

@SpringBootTest
class ScheduleDslApprovedNightPostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @ParameterizedTest
    @ValueSource(strings = ["심야", "한밤중"])
    fun `ATPG01 alias night survives parser PostgreSQL fresh restore and actual two participant intersection`(lexeme: String) {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        val original = F.input("🙂 2027-01-08 $lexeme 가능.")
        val input =
            original.copy(
                inputs =
                    original.inputs.mapIndexed { index, item ->
                        if (index == 0) item else item.copy(rawText = "2027-01-08 21:00부터 다음날 05:00까지 가능.")
                    },
            )
        F.seed(jdbc, mapper, input)
        val originalProposal = F.proposal(input, terms = listOf(T.ref(input, ApprovedKoreanTimeTerm.LATE_NIGHT, lexeme)))
        val peer = F.claim(input, "peer", scope = F.scope("2027-01-08T21:00", "2027-01-09T05:00"), index = 1)
        val proposal =
            originalProposal.copy(
                base =
                    originalProposal.base.copy(
                        claims =
                            originalProposal.base.claims.copy(
                                results =
                                    originalProposal.base.claims.results.map { result ->
                                        if (result.submissionVersionId == F.ir.second) result.copy(conditions = listOf(peer)) else result
                                    },
                            ),
                    ),
            )
        val parsed =
            StrictScheduleDslApprovedTermsResponseParser(mapper).parse(
                F.json(F.document(proposal)),
                F.snapshot(input),
                "schedule-dsl-response-3",
                "schedule-ir-product-4",
                SCHEDULE_AST_VERSION,
                "ko-time-terms-1",
            )
        JdbcScheduleDslApprovedTermsRepository(jdbc, mapper).publish(parsed, 1, FinalAnalysisRouteTestFixture.selectedAt)
        val restored = assertNotNull(JdbcScheduleDslApprovedTermsRepository(jdbc, mapper).find(F.snapshot(input).context))
        assertEquals(parsed, restored)
        assertEquals(ApprovedKoreanTimeTerm.LATE_NIGHT, restored.termApplications.single().term)
        assertEquals(
            IrTemporalResolution.NON_PRECISE,
            restored.declaration.claims.results
                .first()
                .conditions
                .single()
                .temporalResolution,
        )
        val prepared = ScheduleDslInputPolicy.projectWithPreferredAvailability(restored.calculationProjection, F.policy)
        val expected = InstantTimeRange(Instant.parse("2027-01-08T13:00:00Z"), Instant.parse("2027-01-08T19:00:00Z"))
        assertTrue(
            DeterministicCandidateMatcher.generate(MeetingMode.REMOTE, prepared.participants).candidates.any {
                it.participantIds.size == 2 && it.timeRanges == listOf(expected)
            },
        )
        assertEquals(5, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM schedule_ir_inputs", Int::class.java))
    }

    companion object {
        private val postgres = mappedPortReadyPostgres().apply { start() }

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.flyway.enabled") { true }
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("meetme.reliability.enabled") { false }
            registry.add("meetme.data-retention.enabled") { false }
            registry.add("meetme.rate-limit.enabled") { false }
        }

        @JvmStatic @AfterAll
        fun stop() {
            postgres.stop()
        }
    }
}
