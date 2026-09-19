package com.meetme.server.submission.adapter.input.web

import com.meetme.server.meetingroom.application.service.CollectionClosureService
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class SubmissionWebIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun cleanDatabase() {
        jdbcTemplate.execute(
            "TRUNCATE structured_submission_results, final_confirmations, candidate_time_ranges, candidates, " +
                "coordination_attempts, outbox_events, coordination_runs, submission_batch_items, submission_batches, " +
                "manual_availability_intervals, submission_versions, submission_heads, participants, " +
                "meeting_rooms, guest_browser_sessions CASCADE",
        )
    }

    @Test
    fun `본인 제출을 저장 수정 조회하고 주간 가능 시간을 병합한다`() {
        val room = createRoom(expectedParticipants = null, manualOnly = true)

        mockMvc
            .perform(save(room, """{"raw_text":null,"manual_available_times":[]}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("SUBMISSION_INPUT_REQUIRED"))
        assertEquals(0, count("submission_versions"))

        mockMvc
            .perform(
                save(
                    room,
                    """
                    {
                      "raw_text":"  월요일 저녁에 학교 근처  ",
                      "manual_available_times":[
                        {"kind":"WEEKLY","day_of_week":"MONDAY","start_time":"19:00","end_time":"21:00"},
                        {"kind":"WEEKLY","day_of_week":"MONDAY","start_time":"18:00","end_time":"20:00"}
                      ]
                    }
                    """.trimIndent(),
                ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.revision").value(1))
            .andExpect(jsonPath("$.raw_text").value("월요일 저녁에 학교 근처"))
            .andExpect(jsonPath("$.manual_available_times.length()").value(1))
            .andExpect(jsonPath("$.manual_available_times[0].start_time").value("18:00:00"))
            .andExpect(jsonPath("$.manual_available_times[0].end_time").value("21:00:00"))

        mockMvc
            .perform(save(room, """{"raw_text":"화요일 가능","manual_available_times":[]}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.revision").value(2))
        mockMvc
            .perform(
                get("/api/rooms/{inviteCode}/submission", room.inviteCode)
                    .cookie(Cookie(com.meetme.server.shared.adapter.input.web.GuestCookie.NAME, room.credential)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.revision").value(2))
            .andExpect(jsonPath("$.raw_text").value("화요일 가능"))
        assertEquals(2, count("submission_versions"))
        assertEquals(1, count("submission_heads"))
    }

    @Test
    fun `두 번째 고유 제출이 예상 인원에 도달하면 최신 배치와 참조형 Outbox를 원자적으로 만든다`() {
        val host = createRoom(expectedParticipants = 2, manualOnly = false)
        mockMvc
            .perform(save(host, """{"raw_text":"화요일 저녁","manual_available_times":[]}"""))
            .andExpect(status().isOk)
        val member = join(host.inviteCode, "참여자")
        mockMvc
            .perform(save(member, """{"raw_text":"수요일 학교 앞","manual_available_times":[]}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.editable").value(false))

        assertEquals("CLOSED", jdbcTemplate.queryForObject("SELECT collection_status FROM meeting_rooms", String::class.java))
        assertEquals(2, count("submission_batch_items"))
        assertEquals("QUEUED", jdbcTemplate.queryForObject("SELECT status FROM coordination_runs", String::class.java))
        val payload = jdbcTemplate.queryForObject("SELECT payload::text FROM outbox_events", String::class.java).orEmpty()
        assertFalse(payload.contains("화요일"))
        assertFalse(payload.contains("수요일"))
        assertEquals(true, payload.contains("submission_batch_id"))
    }

    @Test
    fun `전원이 수동 시간만 제출하면 Gemini 없이 매칭 Outbox로 전이한다`() {
        val host = createRoom(expectedParticipants = 2, manualOnly = false)
        val manual =
            """{"manual_available_times":[{"kind":"WEEKLY","day_of_week":"MONDAY","start_time":"18:00","end_time":"20:00"}]}"""
        mockMvc.perform(save(host, manual)).andExpect(status().isOk)
        val member = join(host.inviteCode, "참여자")
        mockMvc.perform(save(member, manual)).andExpect(status().isOk)

        assertEquals("MATCHING", jdbcTemplate.queryForObject("SELECT status FROM coordination_runs", String::class.java))
        assertEquals(1, count("outbox_events"))
        assertEquals(
            CollectionClosureService.MATCHING_REQUESTED,
            jdbcTemplate.queryForObject("SELECT event_type FROM outbox_events", String::class.java),
        )
    }

    @Test
    fun `MVP 제출 API는 Calendar 필드를 수락하지 않고 다른 참여자의 제출을 노출하지 않는다`() {
        val host = createRoom(expectedParticipants = null, manualOnly = true)
        mockMvc
            .perform(
                save(host, """{"raw_text":"화요일","manual_available_times":[],"calendar_blocked_times":[]}"""),
            ).andExpect(status().isBadRequest)
        mockMvc
            .perform(save(host, """{"raw_text":"화요일","manual_available_times":[]}"""))
            .andExpect(status().isOk)
        val member = join(host.inviteCode, "참여자")
        mockMvc
            .perform(
                get("/api/rooms/{inviteCode}/submission", host.inviteCode)
                    .cookie(Cookie(com.meetme.server.shared.adapter.input.web.GuestCookie.NAME, member.credential)),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("SUBMISSION_NOT_FOUND"))
    }

    @Test
    fun `OpenAPI는 제출 조회 수정과 재분석 계약을 노출한다`() {
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/api/rooms/{inviteCode}/submission'].put").exists())
            .andExpect(jsonPath("$.paths['/api/rooms/{inviteCode}/submission'].get").exists())
            .andExpect(jsonPath("$.paths['/api/rooms/{inviteCode}/analysis/retry'].post").exists())
            .andExpect(jsonPath("$.components.schemas.SaveSubmissionRequest").exists())
            .andExpect(jsonPath("$.components.schemas.SubmissionResponse").exists())
    }

    @Test
    fun `HOST 재분석 요청은 같은 고정 배치에 대기 이벤트 하나만 만든다`() {
        val host = createRoom(expectedParticipants = 2, manualOnly = false)
        mockMvc
            .perform(save(host, """{"raw_text":"화요일 저녁","manual_available_times":[]}"""))
            .andExpect(status().isOk)
        val member = join(host.inviteCode, "참여자")
        mockMvc
            .perform(save(member, """{"raw_text":"수요일 저녁","manual_available_times":[]}"""))
            .andExpect(status().isOk)
        jdbcTemplate.update("UPDATE outbox_events SET status = 'PUBLISHED', published_at = now()")
        jdbcTemplate.update("UPDATE coordination_runs SET status = 'ANALYSIS_DELAYED', version = version + 1")

        repeat(2) {
            mockMvc
                .perform(
                    post("/api/rooms/{inviteCode}/analysis/retry", host.inviteCode)
                        .header("Origin", ALLOWED_ORIGIN)
                        .cookie(Cookie(com.meetme.server.shared.adapter.input.web.GuestCookie.NAME, host.credential)),
                ).andExpect(status().isAccepted)
        }

        assertEquals("QUEUED", jdbcTemplate.queryForObject("SELECT status FROM coordination_runs", String::class.java))
        assertEquals(
            1,
            jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events WHERE status = 'PENDING'", Int::class.java),
        )
        assertEquals(1, count("submission_batches"))
    }

    @Test
    fun `N번째와 후속 제출이 동시에 도착하면 목표 인원까지만 접수한다`() {
        val host = createRoom(expectedParticipants = 2, manualOnly = false)
        val first = join(host.inviteCode, "참여자1")
        val second = join(host.inviteCode, "참여자2")
        mockMvc
            .perform(save(host, """{"raw_text":"월요일","manual_available_times":[]}"""))
            .andExpect(status().isOk)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val statuses =
                listOf(first, second).map { participant ->
                    executor.submit<Int> {
                        ready.countDown()
                        start.await()
                        mockMvc
                            .perform(save(participant, """{"raw_text":"화요일","manual_available_times":[]}"""))
                            .andReturn()
                            .response.status
                    }
                }
            ready.await()
            start.countDown()
            assertEquals(listOf(200, 409), statuses.map { it.get() }.sorted())
            assertEquals(2, count("submission_heads"))
            assertEquals("CLOSED", jdbcTemplate.queryForObject("SELECT collection_status FROM meeting_rooms", String::class.java))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `수정과 마감 경합은 커밋된 최신 revision만 배치에 고정한다`() {
        val host = createRoom(expectedParticipants = 3, manualOnly = false)
        val member = join(host.inviteCode, "참여자")
        mockMvc
            .perform(save(host, """{"raw_text":"월요일","manual_available_times":[]}"""))
            .andExpect(status().isOk)
        mockMvc
            .perform(save(member, """{"raw_text":"화요일","manual_available_times":[]}"""))
            .andExpect(status().isOk)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val revise =
                executor.submit<Int> {
                    ready.countDown()
                    start.await()
                    mockMvc
                        .perform(save(host, """{"raw_text":"수정된 월요일","manual_available_times":[]}"""))
                        .andReturn()
                        .response.status
                }
            val close =
                executor.submit<Int> {
                    ready.countDown()
                    start.await()
                    mockMvc
                        .perform(
                            post("/api/rooms/{inviteCode}/close", host.inviteCode)
                                .header("Origin", ALLOWED_ORIGIN)
                                .cookie(Cookie(com.meetme.server.shared.adapter.input.web.GuestCookie.NAME, host.credential))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""{"confirm_early":true}"""),
                        ).andReturn()
                        .response.status
                }
            ready.await()
            start.countDown()
            assertEquals(200, close.get())
            assertEquals(true, revise.get() in setOf(200, 409))
            val mismatches =
                jdbcTemplate.queryForObject(
                    """
                    SELECT count(*) FROM submission_batch_items bi
                    JOIN submission_versions v ON v.id = bi.submission_version_id
                    JOIN submission_heads h ON h.id = v.submission_id
                    WHERE bi.submission_version_id <> h.latest_version_id
                    """.trimIndent(),
                    Int::class.java,
                )
            assertEquals(0, mismatches)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `한 방의 익명 참여자는 주최자를 포함해 50명으로 제한한다`() {
        val host = createRoom(expectedParticipants = null, manualOnly = true)
        val roomId =
            jdbcTemplate.queryForObject(
                "SELECT id FROM meeting_rooms WHERE invite_code = ?",
                UUID::class.java,
                host.inviteCode,
            )
        repeat(49) { index ->
            val sessionId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO guest_browser_sessions (id, credential_digest, expires_at, created_at)
                VALUES (?, ?, now() + INTERVAL '30 days', now())
                """.trimIndent(),
                sessionId,
                UUID.randomUUID().toString().replace("-", ""),
            )
            jdbcTemplate.update(
                """
                INSERT INTO participants (id, room_id, guest_session_id, display_name, role, joined_at)
                VALUES (?, ?, ?, ?, 'MEMBER', now())
                """.trimIndent(),
                UUID.randomUUID(),
                roomId,
                sessionId,
                "참여자$index",
            )
        }

        mockMvc
            .perform(
                post("/api/rooms/{inviteCode}/participants", host.inviteCode)
                    .header("Origin", ALLOWED_ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"display_name":"51번째"}"""),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("ROOM_PARTICIPANT_LIMIT_REACHED"))
        assertEquals(50, count("participants"))
    }

    @Test
    fun `방 전체 최신 자연어 합계는 10000 코드 포인트를 넘겨 저장하지 않는다`() {
        val host = createRoom(expectedParticipants = null, manualOnly = true)
        val text = "가".repeat(500)
        mockMvc
            .perform(save(host, """{"raw_text":"$text","manual_available_times":[]}"""))
            .andExpect(status().isOk)
        repeat(19) { index ->
            val member = join(host.inviteCode, "배치$index")
            mockMvc
                .perform(save(member, """{"raw_text":"$text","manual_available_times":[]}"""))
                .andExpect(status().isOk)
        }
        val overflow = join(host.inviteCode, "초과")

        mockMvc
            .perform(save(overflow, """{"raw_text":"나","manual_available_times":[]}"""))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SUBMISSION_BATCH_TEXT_LIMIT_EXCEEDED"))
        assertEquals(20, count("submission_heads"))
    }

    @Test
    fun `민감한 제출 원문은 애플리케이션 로그와 Outbox에 남지 않는다`(output: CapturedOutput) {
        val room = createRoom(expectedParticipants = 2, manualOnly = false)
        val sentinel = "민감원문-로그금지-7d14"
        mockMvc
            .perform(save(room, """{"raw_text":"$sentinel","manual_available_times":[]}"""))
            .andExpect(status().isOk)
        val member = join(room.inviteCode, "참여자")
        mockMvc
            .perform(save(member, """{"raw_text":"화요일","manual_available_times":[]}"""))
            .andExpect(status().isOk)

        val payload = jdbcTemplate.queryForObject("SELECT payload::text FROM outbox_events", String::class.java).orEmpty()
        assertFalse(output.out.contains(sentinel))
        assertFalse(output.err.contains(sentinel))
        assertFalse(payload.contains(sentinel))
    }

    private fun save(
        room: RoomSession,
        body: String,
    ) = put("/api/rooms/{inviteCode}/submission", room.inviteCode)
        .header("Origin", ALLOWED_ORIGIN)
        .cookie(Cookie(com.meetme.server.shared.adapter.input.web.GuestCookie.NAME, room.credential))
        .header(HttpHeaders.ACCEPT_LANGUAGE, "ko-KR")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)

    private fun createRoom(
        expectedParticipants: Int?,
        manualOnly: Boolean,
    ): RoomSession {
        val result =
            mockMvc
                .perform(
                    post("/api/rooms")
                        .header("Origin", ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"purpose":"제출 테스트","meeting_mode":"EITHER",
                             "host_display_name":"주최자","expected_participants":${expectedParticipants ?: "null"},
                             "manual_only":$manualOnly}
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
        return RoomSession(
            result.response.contentAsString
                .substringAfter("\"invite_code\":\"")
                .substringBefore('"'),
            requireNotNull(result.response.getHeader(HttpHeaders.SET_COOKIE)).substringAfter("meet_me_guest=").substringBefore(';'),
        )
    }

    private fun join(
        inviteCode: String,
        name: String,
    ): RoomSession {
        val result =
            mockMvc
                .perform(
                    post("/api/rooms/{inviteCode}/participants", inviteCode)
                        .header("Origin", ALLOWED_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"display_name":"$name"}"""),
                ).andExpect(status().isCreated)
                .andReturn()
        return RoomSession(
            inviteCode,
            requireNotNull(result.response.getHeader(HttpHeaders.SET_COOKIE)).substringAfter("meet_me_guest=").substringBefore(';'),
        )
    }

    private fun count(table: String): Int = requireNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private data class RoomSession(
        val inviteCode: String,
        val credential: String,
    )

    companion object {
        private const val ALLOWED_ORIGIN = "https://app.meet-me.co.kr"
        private val postgres = PostgreSQLContainer("postgres:18-alpine")

        init {
            postgres.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("meetme.anonymous.allowed-origins") { ALLOWED_ORIGIN }
            registry.add("meetme.anonymous.cookie-secure") { true }
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
