package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchResult
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.service.GeminiBatchProcessor
import com.meetme.server.meetingroom.application.service.DeadlineClosureScheduler
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

// Real room HTTP/PostgreSQL and real processors/matcher. Only the external LLM port is mocked; paid calls remain zero.
@Import(CorrectionMeetingScenarioPostgresTest.ClockConfiguration::class)
class CorrectionMeetingScenarioPostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var worker: GeminiBatchProcessor

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var deadline: DeadlineClosureScheduler

    @Autowired private lateinit var scenarioClock: ScenarioClock

    @MockitoBean private lateinit var parser: NaturalLanguageParserPort

    private val trace = linkedMapOf<String, Any?>()

    @BeforeEach
    fun prepareMockLlm() {
        scenarioClock.value = NOW
        `when`(parser.parse(anyValue())).thenAnswer {
            val request = it.getArgument<NaturalLanguageBatchRequest>(0)
            NaturalLanguageBatchResult(
                request.inputs.map { input ->
                    val hours =
                        when (input.rawText) {
                            H19_21 -> 19 to 21
                            H20_22 -> 20 to 22
                            H19_20 -> 19 to 20
                            H21_22 -> 21 to 22
                            AMBIGUOUS -> null
                            else -> error("Unspecified synthetic LLM fixture")
                        }
                    StructuredSubmissionResult(
                        SubmissionVersionId(UUID.fromString(input.inputRef)),
                        hours
                            ?.let { (start, end) ->
                                listOf(
                                    StructuredCondition.TimeWindow(
                                        TimePolarity.AVAILABLE,
                                        LocalDate.of(2026, 10, 8),
                                        null,
                                        LocalTime.of(start, 0),
                                        LocalTime.of(end, 0),
                                    ),
                                )
                            }.orEmpty(),
                        if (hours == null) "AMBIGUOUS_TIME_CONSTRAINT" else null,
                    )
                },
                ParserUsage(0, 0, 0),
            )
        }
    }

    @Test
    fun `two person count deadline closes once and produces actual two attendee interval`() =
        scenario("two-person-count") {
            val fixture = prepare(2, "COUNT", listOf(H19_21, H19_21))
            assertEquals("EXPECTED_PARTICIPANTS", room(fixture.host)["closure_reason"])
            process(fixture)
            expectCandidate(fixture, "COMPLETE", 19, 21, listOf("host", "member1"))
        }

    @Test
    fun `three person count deadline closes once and intersects all three actual attendees`() =
        scenario("three-person-count") {
            val fixture = prepare(3, "COUNT", listOf(H19_21, H19_21, H20_22))
            assertEquals("EXPECTED_PARTICIPANTS", room(fixture.host)["closure_reason"])
            process(fixture)
            expectCandidate(fixture, "COMPLETE", 20, 21, listOf("host", "member1", "member2"))
        }

    @Test
    fun `time deadline preserves two submitted inputs and closes by actual scheduler service`() =
        scenario("time-deadline") {
            val fixture = prepare(2, "DEADLINE", listOf(H19_21, H20_22))
            assertEquals("DEADLINE", room(fixture.host)["closure_reason"])
            process(fixture)
            expectCandidate(fixture, "COMPLETE", 20, 21, listOf("host", "member1"))
        }

    @Test
    fun `manual close freezes two inputs and produces the actual full interval`() =
        scenario("manual-close") {
            val fixture = prepare(2, "MANUAL", listOf(H19_21, H19_21))
            assertEquals("MANUAL", room(fixture.host)["closure_reason"])
            process(fixture)
            expectCandidate(fixture, "COMPLETE", 19, 21, listOf("host", "member1"))
        }

    @Test
    fun `NO_MATCH keeps own inputs through correction then reparses and protects confirmed result`() =
        scenario("no-match-correction") {
            val fixture = prepare(2, "MANUAL", listOf(H19_20, H21_22))
            process(fixture)
            assertEquals("NO_MATCH", room(fixture.host)["public_status"])
            assertEquals(emptyList<Any?>(), candidates(fixture)["candidates"])
            trace["original_result"] = candidates(fixture)
            val hostBefore = owner(fixture.host)
            val memberBefore = owner(fixture.member)
            val round = openRound(fixture)
            assertEquals(memberBefore["raw_text"], owner(fixture.member)["raw_text"])
            mvc.perform(save(fixture.member, H19_20, round, 1)).andExpect(status().isOk)
            trace["corrected_input"] = mapOf("name" to "member1", "raw" to H19_20)
            assertEquals(hostBefore["raw_text"], owner(fixture.host)["raw_text"])
            assertEquals(hostBefore["created_at"], owner(fixture.host)["created_at"])
            mvc.perform(analyze(fixture, round)).andExpect(status().isAccepted)
            process(fixture)
            expectCandidate(fixture, "COMPLETE", 19, 20, listOf("host", "member1"))
            confirmAndProtect(fixture)
        }

    @Test
    fun `PARTIAL preserves all three inputs and corrected third attendee joins reanalysis before confirmation`() =
        scenario("partial-correction") {
            val fixture = prepare(3, "MANUAL", listOf(H19_21, H19_21, AMBIGUOUS))
            process(fixture)
            expectCandidate(fixture, "PARTIAL", 19, 21, listOf("host", "member1"))
            trace["original_result"] = candidates(fixture)
            val third = requireNotNull(thirdSession)
            val before = owner(third)
            val round = openRound(fixture)
            assertEquals(before["raw_text"], owner(third)["raw_text"])
            assertEquals(H19_21, owner(fixture.host)["raw_text"])
            assertEquals(H19_21, owner(fixture.member)["raw_text"])
            mvc.perform(save(third, H19_21, round, 1)).andExpect(status().isOk)
            trace["corrected_input"] = mapOf("name" to "member2", "raw" to H19_21)
            mvc.perform(analyze(fixture, round)).andExpect(status().isAccepted)
            process(fixture)
            expectCandidate(fixture, "COMPLETE", 19, 21, listOf("host", "member1", "member2"))
            confirmAndProtect(fixture)
        }

    private var thirdSession: CorrectionSession? = null

    private fun prepare(
        count: Int,
        policy: String,
        inputs: List<String>,
    ): CompletedCorrectionFixture {
        trace["participant_count"] = count
        trace["closure_policy"] = policy
        trace["search_range"] = listOf("2026-10-07", "2026-10-12")
        trace["participant_inputs"] = inputs.mapIndexed { i, raw -> mapOf("name" to if (i == 0) "host" else "member$i", "raw" to raw) }
        val body =
            linkedMapOf<String, Any>(
                "purpose" to "synthetic meeting",
                "meeting_mode" to "REMOTE",
                "host_display_name" to "host",
                "search_start_date" to "2026-10-07",
                "search_end_date" to "2026-10-12",
            )
        when (policy) {
            "COUNT" -> body["expected_participants"] = count
            "DEADLINE" -> body["submission_deadline"] = NOW.plusSeconds(600).toString()
            else -> body["manual_only"] = true
        }
        val created =
            mvc
                .perform(
                    post("/api/rooms")
                        .header("Origin", CorrectionRoundPostgresFixture.ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        val code = document(created).getValue("invite_code").toString()

        fun credential(header: String?) = requireNotNull(header).substringAfter("meet_me_guest=").substringBefore(';')
        val sessions = mutableListOf(CorrectionSession(code, credential(created.getHeader(HttpHeaders.SET_COOKIE))))
        for (index in 1 until count) {
            val joined =
                mvc
                    .perform(
                        post("/api/rooms/{code}/participants", code)
                            .header("Origin", CorrectionRoundPostgresFixture.ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"display_name":"member$index"}"""),
                    ).andExpect(status().isCreated)
                    .andReturn()
                    .response
            sessions += CorrectionSession(code, credential(joined.getHeader(HttpHeaders.SET_COOKIE)))
        }
        sessions.zip(inputs).forEach { (actor, raw) -> mvc.perform(save(actor, raw)).andExpect(status().isOk) }
        if (policy == "DEADLINE") {
            assertEquals("COLLECTING", room(sessions[0])["collection_status"])
            scenarioClock.value = NOW.plusSeconds(601)
            deadline.closeDueRooms()
        } else if (policy == "MANUAL") {
            mvc
                .perform(
                    post("/api/rooms/{code}/close", code)
                        .header("Origin", CorrectionRoundPostgresFixture.ORIGIN)
                        .cookie(sessions[0].cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"confirm_early":true}"""),
                ).andExpect(status().isOk)
        }
        thirdSession = sessions.getOrNull(2)
        val roomId = requireNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code = ?", UUID::class.java, code))
        val run = activeRun(sessions[0])
        return CompletedCorrectionFixture(sessions[0], sessions[1], roomId, run)
    }

    private fun process(fixture: CompletedCorrectionFixture) {
        val current = requireNotNull(runs.findById(CoordinationRunId(activeRun(fixture.host))))
        worker.process(current.batch.id)
        trace["actual_room"] = room(fixture.host)
        trace["actual_saved_inputs"] =
            jdbc.queryForList(
                "SELECT p.display_name AS name,v.raw_text AS raw,v.revision,v.created_at::text AS created_at " +
                    "FROM submission_heads h JOIN submission_versions v ON v.id=h.latest_version_id " +
                    "JOIN participants p ON p.id=h.participant_id WHERE h.room_id=? ORDER BY p.display_name",
                fixture.roomId,
            )
    }

    private fun candidates(fixture: CompletedCorrectionFixture): Map<String, Any?> =
        document(
            mvc
                .perform(get("/api/rooms/{code}/candidates", fixture.host.code).cookie(fixture.host.cookie()))
                .andExpect(status().isOk)
                .andReturn()
                .response,
        )

    private fun expectCandidate(
        fixture: CompletedCorrectionFixture,
        quality: String,
        start: Int,
        end: Int,
        attendees: List<String>,
    ) {
        val result = candidates(fixture)
        val candidate = child(mapOf("first" to (result.getValue("candidates") as List<*>).single()), "first")
        val actualAttendees =
            jdbc
                .queryForList(
                    "SELECT p.display_name FROM candidate_participants c JOIN participants p ON p.id=c.participant_id " +
                        "WHERE c.candidate_id=? ORDER BY p.display_name",
                    String::class.java,
                    UUID.fromString(candidate["candidate_id"].toString()),
                ).map { requireNotNull(it) }
        val ranges =
            listOf(
                mapOf(
                    "start_at" to "2026-10-08T${(start - 9).toString().padStart(2, '0')}:00:00Z",
                    "end_at" to "2026-10-08T${(end - 9).toString().padStart(2, '0')}:00:00Z",
                ),
            )
        trace["expected"] = mapOf("quality" to quality, "utc_ranges" to ranges, "attendees" to attendees.sorted())
        trace["actual_candidates"] = result
        trace["actual_attendees"] = actualAttendees
        assertEquals(quality, result["quality"])
        assertEquals(ranges, candidate["time_ranges"])
        assertEquals(attendees.sorted(), actualAttendees)
        assertEquals(attendees.size, (candidate.getValue("attendance_count") as Number).toInt())
        assertEquals(trace["participant_count"], (candidate.getValue("total_participants") as Number).toInt())
    }

    private fun confirmAndProtect(fixture: CompletedCorrectionFixture) {
        val candidate = child(mapOf("first" to (candidates(fixture).getValue("candidates") as List<*>).single()), "first")
        mvc
            .perform(
                post("/api/rooms/{code}/candidates/{id}/confirmation", fixture.host.code, candidate["candidate_id"])
                    .header("Origin", CorrectionRoundPostgresFixture.ORIGIN)
                    .cookie(fixture.host.cookie()),
            ).andExpect(status().isOk)
        val source = activeRun(fixture.host)
        mvc.perform(reopen(fixture, generation = 1, source = source)).andExpect(status().isConflict)
        trace["confirmed_protection"] = "409"
    }

    private fun scenario(
        name: String,
        action: () -> Unit,
    ) {
        trace["scenario"] = name
        trace["llm"] = "EXPLICIT_MOCK_PORT; actual Gemini calls 0; real application processors and deterministic matcher"
        try {
            action()
            trace["status"] = "PASS"
        } catch (failure: Throwable) {
            trace["status"] = "FAIL"
            trace["failure_type"] = failure.javaClass.simpleName
            throw failure
        } finally {
            val directory =
                Path.of(
                    System.getenv(
                        "MEETME_ISSUE92_SCENARIO_REPORT_DIR",
                    ) ?: "/workspace/scratch/meet-me-bootstrap/issue-92-meeting-scenarios",
                )
            require(directory.isAbsolute && (directory.startsWith("/workspace") || directory.startsWith("/tmp")))
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("$name.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(trace))
        }
    }

    class ScenarioClock(
        var value: Instant = NOW,
    ) : Clock() {
        override fun instant(): Instant = value

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = Clock.fixed(value, zone)
    }

    @TestConfiguration
    class ClockConfiguration {
        @Bean
        @Primary
        fun scenarioClock() = ScenarioClock()
    }

    companion object {
        private val NOW = Instant.parse("2026-10-07T00:00:00Z")
        private const val H19_21 = "2026년10월8일 오후7시부터9시까지 가능해요"
        private const val H20_22 = "2026년10월8일 오후8시부터10시까지 가능해요"
        private const val H19_20 = "2026년10월8일 오후7시부터8시까지 가능해요"
        private const val H21_22 = "2026년10월8일 오후9시부터10시까지 가능해요"
        private const val AMBIGUOUS = "미확정 시간 입력"
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = CorrectionRoundPostgresFixture.registerCorrectionDatabase(registry, postgres)

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()

        @Suppress("UNCHECKED_CAST")
        private fun <T> anyValue(): T {
            Mockito.any<T>()
            return null as T
        }
    }
}
