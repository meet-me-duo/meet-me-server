package com.meetme.server.meetingroom.adapter.input.web

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.meetingroom.application.port.input.CreateRoomCommand
import com.meetme.server.meetingroom.application.port.input.JoinRoomCommand
import com.meetme.server.meetingroom.application.service.RoomLifecycleService
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@SpringBootTest
@AutoConfigureMockMvc
class RoomLifecycleWebIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var lifecycleService: RoomLifecycleService

    @Autowired private lateinit var credentialPort: GuestCredentialPort

    @MockitoBean private lateinit var currentAccount: CurrentAccountPort
    private val caller = ThreadLocal<AccountPrincipal?>()

    @BeforeEach
    fun cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
        caller.remove()
        `when`(currentAccount.currentAccount()).thenAnswer { caller.get() }
    }

    @Test
    fun `방 생성은 계정 주최자와 공개 정보만 반환하고 같은 계정 재참여는 기존 HOST로 귀결된다`() {
        val created = createRoom(manualOnly = true).andReturn()
        val inviteCode = invite(created.response.contentAsString)
        assertFalse(created.response.contentAsString.contains("credential_digest"))
        assertFalse(created.response.contentAsString.contains("guest_session_id"))
        assertFalse(created.response.contentAsString.contains("\"id\""))
        assertFalse(created.response.contentAsString.contains("duration_minutes"))
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM guest_browser_sessions", Int::class.java))
        mockMvc
            .perform(
                post("/api/rooms/{inviteCode}/participants", inviteCode)
                    .header("Origin", ALLOWED_ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.viewer.role").value("HOST"))
            .andExpect(jsonPath("$.viewer.display_name").value("주최자"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
    }

    @Test
    fun `게스트 참여 쿠키는 30일 host-only 보안 속성과 해시 저장을 사용한다`() {
        val inviteCode = invite(createRoom(manualOnly = true).andReturn().response.contentAsString)
        caller.remove()
        val joined =
            guestJoin(inviteCode)
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=2592000")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Path=/api")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Secure")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("SameSite=Lax")))
                .andExpect(
                    header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Domain="))),
                ).andReturn()
        val raw = credential(requireNotNull(joined.response.getHeader(HttpHeaders.SET_COOKIE)))
        assertEquals(43, raw.length)
        assertFalse(joined.response.contentAsString.contains(raw))
        val stored = jdbcTemplate.queryForObject("SELECT credential_digest FROM guest_browser_sessions", String::class.java)
        assertEquals(credentialPort.digest(raw), stored)
        assertFalse(raw == stored)
    }

    @Test
    fun `허용 Origin이 없으면 ProblemDetail로 거부한다`() {
        login()
        mockMvc
            .perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content(createBody(true)))
            .andExpect(status().isForbidden)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"))
    }

    @Test
    fun `자동 조건 전 수동 마감은 409 재확인 후 HOST만 수행한다`() {
        val inviteCode = invite(createRoom(manualOnly = false, expectedParticipants = 3).andReturn().response.contentAsString)
        mockMvc
            .perform(close(inviteCode, false))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("EARLY_CLOSE_CONFIRMATION_REQUIRED"))
            .andExpect(jsonPath("$.submitted_participants").value(0))
        mockMvc
            .perform(close(inviteCode, true))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.collection_status").value("CLOSED"))
            .andExpect(jsonPath("$.closure_reason").value("MANUAL"))
            .andExpect(jsonPath("$.public_status").value("INSUFFICIENT_PARTICIPANTS"))
        mockMvc
            .perform(close(inviteCode, true))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.closure_reason").value("MANUAL"))
    }

    @Test
    fun `일반 회원 참여자는 주최자 마감을 수행할 수 없다`() {
        val inviteCode = invite(createRoom(manualOnly = true).andReturn().response.contentAsString)
        login("참여자")
        mockMvc
            .perform(
                post("/api/rooms/{inviteCode}/participants", inviteCode)
                    .header("Origin", ALLOWED_ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isCreated)
        mockMvc
            .perform(close(inviteCode, true))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("HOST_PERMISSION_REQUIRED"))
    }

    @Test
    fun `명시적 연결은 누락 위조 만료 회수 게스트 세션을 401로 거부한다`() {
        val inviteCode = invite(createRoom(manualOnly = true).andReturn().response.contentAsString)
        caller.remove()
        val joined = guestJoin(inviteCode).andReturn()
        val raw = credential(requireNotNull(joined.response.getHeader(HttpHeaders.SET_COOKIE)))
        login("연결 계정")
        mockMvc.perform(post("/api/rooms/$inviteCode/claim").header("Origin", ALLOWED_ORIGIN)).andExpect(status().isUnauthorized)
        mockMvc.perform(claim(inviteCode, "forged")).andExpect(status().isUnauthorized)
        jdbcTemplate.update(
            "UPDATE guest_browser_sessions SET expires_at=created_at+INTERVAL '1 microsecond' WHERE credential_digest=?",
            credentialPort.digest(raw),
        )
        mockMvc.perform(claim(inviteCode, raw)).andExpect(status().isUnauthorized)
        jdbcTemplate.update(
            "UPDATE guest_browser_sessions SET expires_at=created_at+INTERVAL '30 days',revoked_at=created_at WHERE credential_digest=?",
            credentialPort.digest(raw),
        )
        mockMvc.perform(claim(inviteCode, raw)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `같은 게스트 세션의 동시 참여는 참여자 한 명으로 수렴한다`() {
        val source = invite(createRoom(manualOnly = true).andReturn().response.contentAsString)
        caller.remove()
        val raw = credential(requireNotNull(guestJoin(source).andReturn().response.getHeader(HttpHeaders.SET_COOKIE)))
        val target = invite(createRoom(manualOnly = true).andReturn().response.contentAsString)
        caller.remove()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures =
                (1..2).map { index ->
                    executor.submit<Boolean> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        lifecycleService.join(JoinRoomCommand(target, raw, "동시 참여자 $index")).participantCreated
                    }
                }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val flags = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, flags.count { it })
            assertEquals(
                1,
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM participants p JOIN meeting_rooms r ON r.id=p.room_id WHERE r.invite_code=? AND p.role='MEMBER'",
                    Int::class.java,
                    target,
                ),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `방과 HOST 생성 도중 실패하면 부분 방과 참여자를 rollback한다`() {
        login()
        assertThrows<IllegalArgumentException> {
            lifecycleService.create(CreateRoomCommand(null, "무시되는 재입력", "   ", MeetingMode.EITHER, null, null, true, null, null))
        }
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM guest_browser_sessions", Int::class.java))
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM meeting_rooms", Int::class.java))
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM participants", Int::class.java))
    }

    @Test
    fun `OpenAPI는 네 방 endpoint와 오류 계약을 노출한다`() {
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/api/rooms'].post").exists())
            .andExpect(jsonPath("$.paths['/api/rooms/{inviteCode}'].get").exists())
            .andExpect(jsonPath("$.paths['/api/rooms/{inviteCode}/participants'].post").exists())
            .andExpect(jsonPath("$.paths['/api/rooms/{inviteCode}/close'].post").exists())
            .andExpect(jsonPath("$.components.schemas.ApiProblemSchema").exists())
            .andExpect(jsonPath("$.components.schemas.CreateRoomRequest.properties.duration_minutes").doesNotExist())
            .andExpect(jsonPath("$.components.schemas.RoomResponse.properties.duration_minutes").doesNotExist())
            .andExpect(jsonPath("$.components.schemas.RoomResponse.properties.public_status.enum").isArray)
            .andExpect(jsonPath("$.components.schemas.RoomResponse.properties.public_status.enum").value(hasItem("ANALYZING")))
            .andExpect(jsonPath("$.components.schemas.RoomResponse.properties.public_status.enum").value(hasItem("ANALYSIS_DELAYED")))
            .andExpect(jsonPath("$.components.schemas.RoomResponse.properties.public_status.enum").value(hasItem("READY_WITH_WARNINGS")))
    }

    @Test
    fun `OpenAPI는 후보 조회와 확정 결과의 snake case 시간 계약을 노출한다`() {
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/api/rooms/{inviteCode}/candidates'].get").exists())
            .andExpect(jsonPath("$.paths['/api/rooms/{inviteCode}/candidates/unapplied-inputs'].get").exists())
            .andExpect(jsonPath("$.paths['/api/rooms/{inviteCode}/candidates/{candidateId}/confirmation'].post").exists())
            .andExpect(jsonPath("$.paths['/api/rooms/{inviteCode}/result'].get").exists())
            .andExpect(jsonPath("$.components.schemas.CandidateTimeRangeResponse.properties.start_at").exists())
            .andExpect(jsonPath("$.components.schemas.CandidateTimeRangeResponse.properties.end_at").exists())
    }

    private fun login(name: String = "주최자") {
        val principal = AccountPrincipal(UUID.randomUUID(), name)
        jdbcTemplate.update("INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,now())", principal.accountId, name)
        caller.set(principal)
    }

    private fun createRoom(
        manualOnly: Boolean,
        expectedParticipants: Int? = null,
    ): org.springframework.test.web.servlet.ResultActions {
        login()
        return mockMvc
            .perform(
                post("/api/rooms")
                    .header("Origin", ALLOWED_ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(manualOnly, expectedParticipants)),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.invite_code").value(org.hamcrest.Matchers.matchesPattern("^[A-Za-z0-9_-]{22}$")))
            .andExpect(jsonPath("$.viewer.role").value("HOST"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
    }

    private fun createBody(
        manualOnly: Boolean,
        expectedParticipants: Int? = null,
    ) =
        """{"purpose":"프로젝트 회의","meeting_mode":"EITHER","expected_participants":${expectedParticipants ?: "null"},"manual_only":$manualOnly}"""

    private fun guestJoin(inviteCode: String) =
        mockMvc
            .perform(
                post("/api/rooms/$inviteCode/participants")
                    .header("Origin", ALLOWED_ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"display_name":"게스트"}"""),
            ).andExpect(status().isCreated)

    private fun invite(body: String) = body.substringAfter("\"invite_code\":\"").substringBefore('"')

    private fun credential(header: String) = header.substringAfter("meet_me_guest=").substringBefore(';')

    private fun close(
        inviteCode: String,
        confirm: Boolean,
    ) = post("/api/rooms/$inviteCode/close")
        .header("Origin", ALLOWED_ORIGIN)
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"confirm_early":$confirm}""")

    private fun claim(
        inviteCode: String,
        credential: String,
    ) = post("/api/rooms/$inviteCode/claim")
        .header("Origin", ALLOWED_ORIGIN)
        .cookie(Cookie("meet_me_guest", credential))

    companion object {
        private const val ALLOWED_ORIGIN = "https://app.meet-me.co.kr"
        private val postgres = PostgreSQLContainer("postgres:18-alpine")

        init {
            postgres.start()
        }

        @JvmStatic @AfterAll
        fun stopContainer() {
            postgres.stop()
        }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("meetme.anonymous.allowed-origins") { ALLOWED_ORIGIN }
            registry.add("meetme.anonymous.cookie-secure") { true }
        }
    }
}
