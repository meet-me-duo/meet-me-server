package com.meetme.server.participant.adapter.input.web

import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.shared.application.port.output.RateLimitDecision
import com.meetme.server.shared.application.port.output.RateLimitPort
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.security.KeyPairGenerator
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Independent #108 HTTP/PG acceptance tests; synthetic RSA identities, no external provider or Redis calls. */
@SpringBootTest(properties = ["meetme.reliability.enabled=false", "meetme.data-retention.enabled=false"])
@AutoConfigureMockMvc
@Import(GuestReaccessWebIntegrationTest.IsolatedSecurity::class)
class GuestReaccessWebIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var credentials: GuestCredentialPort

    @Autowired private lateinit var tokens: AccessTokenPort

    private val json = ObjectMapper()

    @BeforeEach
    fun cleanDatabase() {
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
        LIMITER.set(RateLimitPort { _, _, _ -> RateLimitDecision(true, Duration.ZERO) })
    }

    @Test
    fun `108 H01 이름만 참여한 원래 소유자는 자신의 원문을 읽고 암호는 저장하지 않는다`() {
        val room = room()
        val owner = join(room)
        save(room, owner.cookie, ORIGINAL)

        read(room, owner.cookie).andExpect(status().isOk).andExpect(jsonPath("$.raw_text").value(ORIGINAL))
        assertEquals(0, count("guest_participant_passwords"))
        assertEquals(owner.sessionId, ownerSession(owner.id))
    }

    @Test
    fun `108 H02 선택 암호는 PBKDF2 해시로 저장되고 응답과 저장 row에 원문 암호를 남기지 않는다`() {
        val room = room()
        val owner = join(room, password = PASSWORD)
        val stored = jdbc.queryForObject("SELECT row_to_json(p)::text FROM guest_participant_passwords p", String::class.java).orEmpty()

        assertTrue(stored.contains("pbkdf2-sha256"))
        assertFalse(stored.contains(PASSWORD))
        assertEquals(owner.sessionId, ownerSession(owner.id))
    }

    @Test
    fun `108 H03 같은 이름도 별도 participant 번호로 나열하며 목록은 게스트의 공개 선택 정보만 반환한다`() {
        val room = room()
        val first = join(room, name = "동명", password = PASSWORD)
        val second = join(room, name = "동명")
        save(room, first.cookie, ORIGINAL)
        val response =
            mvc
                .perform(get("/api/rooms/${room.invite}/guest-records"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val records = json.readTree(response.contentAsByteArray).path("records").toList()

        assertEquals(setOf(first.id, second.id), records.map { UUID.fromString(it.path("participant_id").stringValue()) }.toSet())
        assertEquals(listOf("동명", "동명"), records.map { it.path("display_name").stringValue() })
        assertNotEquals(first.id, second.id)
        assertFalse(response.contentAsString.contains(room.host.id.toString()))
        assertNoPrivateFields(response.contentAsString, first.cookie.value, first.sessionId.toString(), ORIGINAL, PASSWORD)
    }

    @Test
    fun `108 H04 회원은 이름 암호 입력을 면제하고 게스트 선택 목록에 추가하지 않는다`() {
        val room = room()
        val member = account("회원 프로필")
        mvc
            .perform(
                post("/api/rooms/${room.invite}/participants")
                    .header(HttpHeaders.AUTHORIZATION, member.bearer)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(mapOf("password" to "x".repeat(129)))),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.viewer.display_name").value("회원 프로필"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
        mvc
            .perform(
                get("/api/rooms/${room.invite}/guest-records"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.records.length()").value(0))
        assertEquals(0, count("guest_participant_passwords"))
        assertEquals(0, count("guest_browser_sessions"))
    }

    @Test
    fun `108 H05 기존 유효 세션의 재참여는 이름과 암호를 덮어쓰지 않는다`() {
        val room = room()
        val owner = join(room, password = PASSWORD)
        save(room, owner.cookie, ORIGINAL)
        mvc
            .perform(
                post("/api/rooms/${room.invite}/participants")
                    .header("Origin", ORIGIN)
                    .cookie(owner.cookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"display_name":"바꾼 이름","password":"new-password"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.viewer.display_name").value("게스트"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
        reaccess(room, owner.id, PASSWORD)
        deniedReaccess(room, owner.id, "new-password")
        read(room, owner.cookie).andExpect(status().isOk).andExpect(jsonPath("$.raw_text").value(ORIGINAL))
        assertEquals(2, count("participants"))
    }

    @Test
    fun `108 H06 정답 암호의 다른 기기 재접근은 읽기를 허용하며 원래 소유 세션을 유지한다`() {
        val room = room()
        val owner = join(room, password = PASSWORD)
        save(room, owner.cookie, ORIGINAL)
        val device = reaccess(room, owner.id, PASSWORD)

        assertNotEquals(owner.cookie.value, device.value)
        read(room, device).andExpect(status().isOk).andExpect(jsonPath("$.raw_text").value(ORIGINAL))
        read(room, owner.cookie).andExpect(status().isOk).andExpect(jsonPath("$.raw_text").value(ORIGINAL))
        assertEquals(owner.sessionId, ownerSession(owner.id))
        assertEquals(2, count("participants"))
    }

    @Test
    fun `108 H07 새 재접근 쿠키는 host only Secure HttpOnly이고 응답은 원문과 암호를 포함하지 않는다`() {
        val room = room()
        val owner = join(room, password = PASSWORD)
        save(room, owner.cookie, ORIGINAL)
        val result = performReaccess(room, owner.id, PASSWORD).andExpect(status().isOk).andReturn()
        val cookieHeader = requireNotNull(result.response.getHeader(HttpHeaders.SET_COOKIE))

        assertTrue(cookieHeader.contains("Secure"))
        assertTrue(cookieHeader.contains("HttpOnly"))
        assertTrue(cookieHeader.contains("SameSite=Lax"))
        assertTrue(cookieHeader.contains("Path=/api"))
        assertFalse(cookieHeader.contains("Domain=", ignoreCase = true))
        assertNoPrivateFields(result.response.contentAsString, owner.cookie.value, owner.sessionId.toString(), ORIGINAL, PASSWORD)
        assertFalse(result.response.contentAsString.contains(responseCookie(result).value))
    }

    @Test
    fun `108 H08 다른 방에서 이미 발급된 유효한 브라우저 세션은 재접근 때 회전하지 않는다`() {
        val target = room()
        val owner = join(target, password = PASSWORD)
        save(target, owner.cookie, ORIGINAL)
        val otherRoom = room()
        val device = join(otherRoom)
        performReaccess(target, owner.id, PASSWORD, device.cookie)
            .andExpect(status().isOk)
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
        read(target, device.cookie).andExpect(status().isOk).andExpect(jsonPath("$.raw_text").value(ORIGINAL))
        assertEquals(owner.sessionId, ownerSession(owner.id))
    }

    @Test
    fun `108 H09 암호가 있는 기록의 암호 누락은 일반 증명 부족으로 거부한다`() {
        val room = room()
        val owner = join(room, password = PASSWORD)
        deniedReaccess(room, owner.id, null)
        assertEquals(0, count("guest_reaccess_grants"))
        assertEquals(owner.sessionId, ownerSession(owner.id))
    }

    @Test
    fun `108 H10 틀린 암호는 원문이나 암호 존재 정보를 노출하지 않고 거부한다`() {
        val room = room()
        val owner = join(room, password = PASSWORD)
        save(room, owner.cookie, ORIGINAL)
        val failed = deniedReaccess(room, owner.id, "wrong-password")
        assertNoPrivateFields(failed.response.contentAsString, PASSWORD, ORIGINAL, owner.cookie.value, owner.sessionId.toString())
        assertEquals(0, count("guest_reaccess_grants"))
    }

    @Test
    fun `108 H11 존재하지 않는 participant도 같은 공개 증명 부족 오류를 반환한다`() {
        val room = room()
        deniedReaccess(room, UUID.randomUUID(), PASSWORD)
        assertEquals(0, count("guest_reaccess_grants"))
    }

    @Test
    fun `108 H12 다른 방의 participant 번호와 정답 암호로 현재 방에 접근할 수 없다`() {
        val firstRoom = room()
        val owner = join(firstRoom, password = PASSWORD)
        val secondRoom = room()
        deniedReaccess(secondRoom, owner.id, PASSWORD)
        assertEquals(0, count("guest_reaccess_grants"))
        assertEquals(owner.sessionId, ownerSession(owner.id))
    }

    @Test
    fun `108 H13 무암호 재접근은 REPLACE_ONLY이며 기존 원문과 회원 연결을 허용하지 않는다`() {
        val room = room()
        val owner = join(room)
        save(room, owner.cookie, ORIGINAL)
        val device = reaccess(room, owner.id, level = "REPLACE_ONLY")
        read(room, device).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("GUEST_PROOF_INSUFFICIENT"))
        val member = account()
        mvc
            .perform(claim(room, device, member))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("GUEST_PROOF_INSUFFICIENT"))
        assertEquals(owner.sessionId, ownerSession(owner.id))
        assertNull(ownerAccount(owner.id))
    }

    @Test
    fun `108 H14 무암호 대체 입력은 이전 원문 없는 성공 응답과 새 불변 버전만 만든다`() {
        val room = room()
        val owner = join(room)
        save(room, owner.cookie, ORIGINAL)
        val device = reaccess(room, owner.id, level = "REPLACE_ONLY")
        val replaced = mvc.perform(blindSave(room, owner.id, device, REPLACEMENT)).andExpect(status().isOk).andReturn()

        assertEquals(mapOf("replaced" to true), json.readValue(replaced.response.contentAsByteArray, Map::class.java))
        assertNoPrivateFields(replaced.response.contentAsString, ORIGINAL, REPLACEMENT, PASSWORD, owner.cookie.value)
        read(room, device).andExpect(status().isForbidden)
        read(room, owner.cookie).andExpect(status().isOk).andExpect(jsonPath("$.raw_text").value(REPLACEMENT))
        assertEquals(listOf(ORIGINAL, REPLACEMENT), versions(owner.id))
        assertEquals(1, count("submission_heads"))
        assertEquals(2, count("participants"))
        assertEquals(owner.sessionId, ownerSession(owner.id))
    }

    @Test
    fun `108 H15 일반 PUT 경로도 REPLACE_ONLY를 읽기 권한으로 승격하지 않는다`() {
        val room = room()
        val owner = join(room)
        save(room, owner.cookie, ORIGINAL)
        val device = reaccess(room, owner.id, level = "REPLACE_ONLY")
        mvc
            .perform(normalSave(room, device, REPLACEMENT))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("GUEST_PROOF_INSUFFICIENT"))
        read(room, device).andExpect(status().isForbidden)
        assertEquals(listOf(ORIGINAL), versions(owner.id))
    }

    @Test
    fun `108 H16 다른 participant 번호에 대체 입력을 보내면 거부하고 두 원문을 보존한다`() {
        val room = room()
        val first = join(room)
        val second = join(room, name = "다른 사람")
        save(room, first.cookie, ORIGINAL)
        save(room, second.cookie, "다른 사람의 원문")
        val device = reaccess(room, first.id, level = "REPLACE_ONLY")

        mvc.perform(blindSave(room, second.id, device, REPLACEMENT)).andExpect(status().isForbidden)
        assertEquals(listOf(ORIGINAL), versions(first.id))
        assertEquals(listOf("다른 사람의 원문"), versions(second.id))
    }

    @Test
    fun `108 H17 만료되거나 회수된 재접근 세션은 읽기와 대체 입력에 사용할 수 없다`() {
        val room = room()
        val owner = join(room, password = PASSWORD)
        save(room, owner.cookie, ORIGINAL)
        val expired = reaccess(room, owner.id, PASSWORD)
        jdbc.update(
            "UPDATE guest_browser_sessions SET created_at=?,expires_at=? WHERE credential_digest=?",
            Timestamp.from(NOW.minusSeconds(60)),
            Timestamp.from(NOW),
            credentials.digest(expired.value),
        )
        read(room, expired).andExpect(status().isUnauthorized)
        mvc.perform(blindSave(room, owner.id, expired, REPLACEMENT)).andExpect(status().isUnauthorized)
        val revoked = reaccess(room, owner.id, PASSWORD)
        jdbc.update("UPDATE guest_browser_sessions SET revoked_at=created_at WHERE credential_digest=?", credentials.digest(revoked.value))
        read(room, revoked).andExpect(status().isUnauthorized)
        mvc.perform(blindSave(room, owner.id, revoked, REPLACEMENT)).andExpect(status().isUnauthorized)
        assertEquals(listOf(ORIGINAL), versions(owner.id))
    }

    @Test
    fun `108 H18 guest HOST 기록의 정답 암호는 주최자 마감 명령을 허용하지 않는다`() {
        val room = room()
        val owner = join(room, password = PASSWORD)
        jdbc.update("UPDATE participants SET role='MEMBER' WHERE room_id=? AND role='HOST'", room.id)
        jdbc.update("UPDATE participants SET role='HOST' WHERE id=?", owner.id)
        val device = reaccess(room, owner.id, PASSWORD)
        mvc
            .perform(
                post("/api/rooms/${room.invite}/close")
                    .header("Origin", ORIGIN)
                    .cookie(device)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"confirm_early":true}"""),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("ACCOUNT_REQUIRED"))
        assertEquals("COLLECTING", collectionStatus(room))
        assertEquals(owner.sessionId, ownerSession(owner.id))
    }

    @Test
    fun `108 H19 허용 Origin이 없거나 비슷한 외부 Origin이면 재접근과 대체 입력을 거부한다`() {
        val room = room()
        val owner = join(room)
        save(room, owner.cookie, ORIGINAL)
        val device = reaccess(room, owner.id, level = "REPLACE_ONLY")
        listOf(null, "$ORIGIN.evil").forEach { origin ->
            val access = reaccessRequest(room, owner.id, null)
            val replacement = blindSave(room, owner.id, device, REPLACEMENT, origin = null)
            if (origin != null) {
                access.header("Origin", origin)
                replacement.header("Origin", origin)
            }
            mvc.perform(access).andExpect(status().isForbidden)
            mvc.perform(replacement).andExpect(status().isForbidden)
        }
        assertEquals(listOf(ORIGINAL), versions(owner.id))
    }

    @Test
    fun `108 H20 유효한 재접근 쿠키도 무효 Bearer를 guest로 우회하지 못한다`() {
        val room = room()
        val owner = join(room, password = PASSWORD)
        save(room, owner.cookie, ORIGINAL)
        val device = reaccess(room, owner.id, PASSWORD)
        mvc
            .perform(get("/api/rooms/${room.invite}/submission").cookie(device).header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
            .andExpect(status().isUnauthorized)
        mvc
            .perform(blindSave(room, owner.id, device, REPLACEMENT).header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
            .andExpect(status().isUnauthorized)
        assertEquals(listOf(ORIGINAL), versions(owner.id))
    }

    @Test
    fun `108 H21 마감된 방의 blind PUT은 원문과 head를 변경하지 않는다`() {
        val room = room()
        val owner = join(room)
        save(room, owner.cookie, ORIGINAL)
        val device = reaccess(room, owner.id, level = "REPLACE_ONLY")
        closeFixture(room)

        mvc.perform(blindSave(room, owner.id, device, REPLACEMENT)).andExpect(status().isConflict)
        assertEquals(listOf(ORIGINAL), versions(owner.id))
        assertEquals(0, count("outbox_events"))
    }

    @Test
    fun `108 H22 deadline 정확한 시점부터 blind PUT을 거부하고 원문을 보존한다`() {
        val room = room()
        val owner = join(room)
        save(room, owner.cookie, ORIGINAL)
        val device = reaccess(room, owner.id, level = "REPLACE_ONLY")
        jdbc.update(
            "UPDATE meeting_rooms SET manual_only=false,submission_deadline=?,created_at=? WHERE id=?",
            Timestamp.from(NOW),
            Timestamp.from(NOW.minusSeconds(60)),
            room.id,
        )

        mvc.perform(blindSave(room, owner.id, device, REPLACEMENT)).andExpect(status().isConflict)
        assertEquals(listOf(ORIGINAL), versions(owner.id))
    }

    @Test
    fun `108 H23 확정된 방의 blind PUT은 확정과 이전 제출을 보존한다`() {
        val room = room()
        val owner = join(room)
        save(room, owner.cookie, ORIGINAL)
        val device = reaccess(room, owner.id, level = "REPLACE_ONLY")
        confirmedFixture(room)

        mvc.perform(blindSave(room, owner.id, device, REPLACEMENT)).andExpect(status().isConflict)
        assertEquals(listOf(ORIGINAL), versions(owner.id))
        assertEquals(1, count("final_confirmations"))
    }

    @Test
    fun `108 H24 닫힌 방에서도 정답 암호는 읽기만 복구하며 수정은 재개하지 않는다`() {
        val room = room()
        val owner = join(room, password = PASSWORD)
        save(room, owner.cookie, ORIGINAL)
        closeFixture(room)
        val device = reaccess(room, owner.id, PASSWORD)

        read(room, device).andExpect(status().isOk).andExpect(jsonPath("$.raw_text").value(ORIGINAL))
        mvc.perform(normalSave(room, device, REPLACEMENT)).andExpect(status().isConflict)
        assertEquals(listOf(ORIGINAL), versions(owner.id))
        assertEquals("CLOSED", collectionStatus(room))
    }

    @Test
    fun `108 H25 같은 revision을 선택한 두 기기의 대체 입력은 하나만 수용하고 나머지는 충돌한다`() {
        val room = room()
        val owner = join(room)
        save(room, owner.cookie, ORIGINAL)
        val first = reaccess(room, owner.id, level = "REPLACE_ONLY")
        val second = reaccess(room, owner.id, level = "REPLACE_ONLY")
        val statuses =
            concurrently(
                {
                    mvc
                        .perform(blindSave(room, owner.id, first, "기기 A 입력"))
                        .andReturn()
                        .response.status
                },
                {
                    mvc
                        .perform(blindSave(room, owner.id, second, "기기 B 입력"))
                        .andReturn()
                        .response.status
                },
            )

        assertEquals(listOf(200, 409), statuses.sorted())
        val stored = versions(owner.id)
        assertEquals(2, stored.size)
        assertEquals(ORIGINAL, stored.first())
        assertTrue(stored.last() in setOf("기기 A 입력", "기기 B 입력"))
        assertEquals(1, count("submission_heads"))
        assertEquals(owner.sessionId, ownerSession(owner.id))
    }

    @Test
    fun `108 H26 암호 증명과 로그인 후 명시 연결만 소유자를 전환하고 과거 guest 접근을 폐기한다`() {
        val room = room()
        val owner = join(room, password = PASSWORD)
        save(room, owner.cookie, ORIGINAL)
        val device = reaccess(room, owner.id, PASSWORD)
        val member = account()
        assertNull(ownerAccount(owner.id))
        mvc.perform(claim(room, device, member)).andExpect(status().isOk)

        assertEquals(member.id, ownerAccount(owner.id))
        assertNull(ownerSession(owner.id))
        mvc
            .perform(get("/api/rooms/${room.invite}/submission").header(HttpHeaders.AUTHORIZATION, member.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.raw_text").value(ORIGINAL))
        read(room, owner.cookie).andExpect(status().isForbidden)
        read(room, device).andExpect(status().isForbidden)
        deniedReaccess(room, owner.id, PASSWORD)
        assertEquals(listOf(ORIGINAL), versions(owner.id))
    }

    @Test
    fun `108 H27 기존 회원 기록 충돌은 두 participant와 두 원문을 덮어쓰지 않는다`() {
        val room = room()
        val owner = join(room, password = PASSWORD)
        save(room, owner.cookie, ORIGINAL)
        val member = account()
        joinMember(room, member)
        mvc.perform(normalSave(room, null, "회원 기존 원문").header(HttpHeaders.AUTHORIZATION, member.bearer)).andExpect(status().isOk)
        val device = reaccess(room, owner.id, PASSWORD)
        mvc
            .perform(claim(room, device, member))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("PARTICIPANT_CONFLICT"))

        assertEquals(owner.sessionId, ownerSession(owner.id))
        assertNull(ownerAccount(owner.id))
        assertEquals(listOf(ORIGINAL), versions(owner.id))
        mvc
            .perform(get("/api/rooms/${room.invite}/submission").header(HttpHeaders.AUTHORIZATION, member.bearer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.raw_text").value("회원 기존 원문"))
        assertEquals(3, count("participants"))
    }

    @Test
    fun `108 H28 회원 연결과 대체 입력이 경합해도 소유권과 원문은 원자적으로 보존된다`() {
        val room = room()
        val owner = join(room)
        save(room, owner.cookie, ORIGINAL)
        val device = reaccess(room, owner.id, level = "REPLACE_ONLY")
        val member = account()
        val statuses =
            concurrently(
                {
                    mvc
                        .perform(claim(room, owner.cookie, member))
                        .andReturn()
                        .response.status
                },
                {
                    mvc
                        .perform(blindSave(room, owner.id, device, REPLACEMENT))
                        .andReturn()
                        .response.status
                },
            )

        assertEquals(200, statuses[0])
        assertTrue(statuses[1] in setOf(200, 403, 409))
        assertEquals(member.id, ownerAccount(owner.id))
        assertNull(ownerSession(owner.id))
        val stored = versions(owner.id)
        assertEquals(ORIGINAL, stored.first())
        assertEquals(if (statuses[1] == 200) listOf(ORIGINAL, REPLACEMENT) else listOf(ORIGINAL), stored)
        read(room, device).andExpect(status().isForbidden)
        assertEquals(2, count("participants"))
        assertEquals(1, count("submission_heads"))
    }

    @Test
    fun `108 H29 OpenAPI는 재접근 목록 검증 대체입력과 보안 거부 응답을 문서화한다`() {
        mvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/api/rooms/{inviteCode}/guest-records'].get").exists())
            .andExpect(jsonPath("$.paths['/api/rooms/{inviteCode}/guest-records/reaccess'].post.responses['403']").exists())
            .andExpect(jsonPath("$.paths['/api/rooms/{inviteCode}/guest-records/reaccess'].post.responses['429']").exists())
            .andExpect(jsonPath("$.paths['/api/rooms/{inviteCode}/guest-records/reaccess'].post.responses['503']").exists())
            .andExpect(
                jsonPath("$.paths['/api/rooms/{inviteCode}/guest-records/{participantId}/submission'].put.responses['409']").exists(),
            )
    }

    @Test
    fun `108 H30 기존 소유 세션은 암호를 다시 요구하지 않고 다른 소유 기록으로는 전환하지 않는다`() {
        val room = room()
        val first = join(room, password = PASSWORD)
        val second = join(room, name = "다른 기록", password = PASSWORD)
        save(room, first.cookie, ORIGINAL)
        listOf(null, "wrong-password").forEach { password ->
            performReaccess(room, first.id, password, first.cookie)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.level").value("VERIFIED_GUEST"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
        }
        performReaccess(room, second.id, PASSWORD, first.cookie).andExpect(status().isConflict)
        read(room, first.cookie).andExpect(status().isOk).andExpect(jsonPath("$.raw_text").value(ORIGINAL))
        assertEquals(first.sessionId, ownerSession(first.id))
        assertEquals(second.sessionId, ownerSession(second.id))
    }

    @Test
    fun `108 H31 HTTP 시도 한도는 429와 Retry After를 반환하고 grant와 새 쿠키를 만들지 않는다`() {
        val room = room()
        val owner = join(room, password = PASSWORD)
        LIMITER.set(RateLimitPort { _, _, _ -> RateLimitDecision(false, Duration.ofSeconds(17)) })

        val result =
            performReaccess(room, owner.id, PASSWORD)
                .andExpect(status().isTooManyRequests)
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "17"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn()

        assertEquals(0, count("guest_reaccess_grants"))
        assertEquals(1, count("guest_browser_sessions"))
        assertNoPrivateFields(result.response.contentAsString, PASSWORD, owner.cookie.value, owner.sessionId.toString())
    }

    @Test
    fun `108 H32 HTTP limiter 장애는 503 fail closed이며 내부 오류나 비밀 정보를 노출하지 않는다`() {
        val room = room()
        val owner = join(room, password = PASSWORD)
        LIMITER.set(RateLimitPort { _, _, _ -> throw IllegalStateException("private-limiter-error ${owner.cookie.value}") })

        val result =
            performReaccess(room, owner.id, PASSWORD)
                .andExpect(status().isServiceUnavailable)
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn()

        assertEquals(0, count("guest_reaccess_grants"))
        assertEquals(1, count("guest_browser_sessions"))
        assertNoPrivateFields(result.response.contentAsString, PASSWORD, owner.cookie.value, "private-limiter-error")
    }

    @Test
    fun `108 H33 이름만 신규 게스트도 deadline 정확한 시점부터 참여시키지 않는다`() {
        val room = room()
        reachedDeadlineFixture(room)

        mvc
            .perform(
                post("/api/rooms/${room.invite}/participants")
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"display_name":"마감 후 신규 게스트"}"""),
            ).andExpect(status().isConflict)
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
        assertUnchangedBeforeJoin()
    }

    @Test
    fun `108 H34 암호가 있는 신규 게스트도 deadline 정확한 시점부터 세션과 해시를 만들지 않는다`() {
        val room = room()
        reachedDeadlineFixture(room)

        mvc
            .perform(
                post("/api/rooms/${room.invite}/participants")
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(mapOf("display_name" to "마감 후 보호 게스트", "password" to PASSWORD))),
            ).andExpect(status().isConflict)
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
        assertUnchangedBeforeJoin()
    }

    @Test
    fun `108 H35 이름과 암호 입력이 면제된 회원도 deadline 정확한 시점부터 새로 참여할 수 없다`() {
        val room = room()
        val member = account()
        reachedDeadlineFixture(room)

        mvc
            .perform(
                post("/api/rooms/${room.invite}/participants")
                    .header(HttpHeaders.AUTHORIZATION, member.bearer)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isConflict)
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
        assertUnchangedBeforeJoin()
    }

    @Test
    fun `108 H36 Unicode 129개 암호 등록 실패는 participant 세션 해시를 모두 rollback하고 쿠키를 발급하지 않는다`() {
        val room = room()
        mvc
            .perform(
                post("/api/rooms/${room.invite}/participants")
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(mapOf("display_name" to "유효한 이름", "password" to "😀".repeat(129)))),
            ).andExpect(status().isBadRequest)
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
        assertUnchangedBeforeJoin()
    }

    @Test
    fun `108 H37 blind 입력은 기존 원문과 같아도 새 버전으로 snapshot을 소비하며 같은 grant 재사용은 충돌한다`() {
        val room = room()
        val owner = join(room)
        save(room, owner.cookie, ORIGINAL)
        val device = reaccess(room, owner.id, level = "REPLACE_ONLY")

        mvc
            .perform(blindSave(room, owner.id, device, ORIGINAL))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.replaced").value(true))
        assertEquals(listOf(ORIGINAL, ORIGINAL), versions(owner.id))
        mvc.perform(blindSave(room, owner.id, device, REPLACEMENT)).andExpect(status().isConflict)
        assertEquals(listOf(ORIGINAL, ORIGINAL), versions(owner.id))
        read(room, device).andExpect(status().isForbidden)
        assertEquals(owner.sessionId, ownerSession(owner.id))
    }

    private fun account(name: String = "합성 회원"): Account {
        val principal = AccountPrincipal(UUID.randomUUID(), name)
        jdbc.update("INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,?)", principal.accountId, name, Timestamp.from(NOW))
        return Account(principal.accountId, "Bearer ${tokens.issue(principal)}")
    }

    private fun room(): Room {
        val host = account("주최자")
        val response =
            mvc
                .perform(
                    post("/api/rooms")
                        .header(HttpHeaders.AUTHORIZATION, host.bearer)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"purpose":"통합 검증","meeting_mode":"EITHER","manual_only":true}"""),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        val invite = json.readTree(response.contentAsByteArray).path("invite_code").stringValue()
        // Historical direct-submission/correction oracle: migrated rooms retain LEGACY.
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='LEGACY' WHERE invite_code=?", invite)
        val id = requireNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code=?", UUID::class.java, invite))
        return Room(id, invite, host)
    }

    private fun join(
        room: Room,
        name: String = "게스트",
        password: String? = null,
    ): Guest {
        val payload = linkedMapOf<String, Any>("display_name" to name)
        if (password != null) payload["password"] = password
        val result =
            mvc
                .perform(
                    post("/api/rooms/${room.invite}/participants")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)),
                ).andExpect(status().isCreated)
                .andReturn()
        val cookie = responseCookie(result)
        assertNoPrivateFields(result.response.contentAsString, cookie.value, password.orEmpty().ifEmpty { "not-a-real-secret" })
        val participant =
            jdbc.queryForMap(
                "SELECT p.id,p.guest_session_id FROM participants p JOIN guest_browser_sessions s ON s.id=p.guest_session_id " +
                    "WHERE p.room_id=? AND s.credential_digest=?",
                room.id,
                credentials.digest(cookie.value),
            )
        return Guest(participant.getValue("id") as UUID, participant.getValue("guest_session_id") as UUID, cookie)
    }

    private fun joinMember(
        room: Room,
        member: Account,
    ) {
        mvc
            .perform(
                post("/api/rooms/${room.invite}/participants")
                    .header(HttpHeaders.AUTHORIZATION, member.bearer)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isCreated)
    }

    private fun save(
        room: Room,
        cookie: Cookie,
        rawText: String,
    ) {
        mvc.perform(normalSave(room, cookie, rawText)).andExpect(status().isOk)
    }

    private fun normalSave(
        room: Room,
        cookie: Cookie?,
        rawText: String,
    ): MockHttpServletRequestBuilder {
        val request =
            put("/api/rooms/${room.invite}/submission")
                .header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("raw_text" to rawText)))
        if (cookie != null) request.cookie(cookie)
        return request
    }

    private fun read(
        room: Room,
        cookie: Cookie,
    ) = mvc.perform(get("/api/rooms/${room.invite}/submission").cookie(cookie))

    private fun reaccess(
        room: Room,
        participant: UUID,
        password: String? = null,
        level: String = "VERIFIED_GUEST",
    ): Cookie {
        val result =
            performReaccess(room, participant, password)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.participant_id").value(participant.toString()))
                .andExpect(jsonPath("$.level").value(level))
                .andReturn()
        return responseCookie(result)
    }

    private fun performReaccess(
        room: Room,
        participant: UUID,
        password: String?,
        cookie: Cookie? = null,
    ) = mvc.perform(reaccessRequest(room, participant, password).header("Origin", ORIGIN).apply { if (cookie != null) this.cookie(cookie) })

    private fun reaccessRequest(
        room: Room,
        participant: UUID,
        password: String?,
    ): MockHttpServletRequestBuilder {
        val payload = linkedMapOf<String, Any>("participant_id" to participant.toString())
        if (password != null) payload["password"] = password
        return post("/api/rooms/${room.invite}/guest-records/reaccess")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(payload))
    }

    private fun deniedReaccess(
        room: Room,
        participant: UUID,
        password: String?,
    ): MvcResult =
        performReaccess(room, participant, password)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("GUEST_PROOF_INSUFFICIENT"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
            .andReturn()

    private fun blindSave(
        room: Room,
        participant: UUID,
        cookie: Cookie,
        rawText: String,
        origin: String? = ORIGIN,
    ): MockHttpServletRequestBuilder {
        val request =
            put("/api/rooms/${room.invite}/guest-records/$participant/submission")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("raw_text" to rawText)))
        if (origin != null) request.header("Origin", origin)
        return request
    }

    private fun claim(
        room: Room,
        cookie: Cookie,
        member: Account,
    ): MockHttpServletRequestBuilder =
        post("/api/rooms/${room.invite}/claim")
            .header("Origin", ORIGIN)
            .header(HttpHeaders.AUTHORIZATION, member.bearer)
            .cookie(cookie)

    private fun ownerSession(participant: UUID): UUID? =
        jdbc.queryForObject("SELECT guest_session_id FROM participants WHERE id=?", UUID::class.java, participant)

    private fun ownerAccount(participant: UUID): UUID? =
        jdbc.queryForObject("SELECT user_id FROM participants WHERE id=?", UUID::class.java, participant)

    private fun versions(participant: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT v.raw_text FROM submission_versions v JOIN submission_heads h ON h.id=v.submission_id " +
                    "WHERE h.participant_id=? ORDER BY v.revision",
                String::class.java,
                participant,
            ).map { requireNotNull(it) }

    private fun count(table: String): Int = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private fun collectionStatus(room: Room): String? =
        jdbc.queryForObject("SELECT collection_status FROM meeting_rooms WHERE id=?", String::class.java, room.id)

    private fun reachedDeadlineFixture(room: Room) {
        jdbc.update(
            "UPDATE meeting_rooms SET manual_only=false,submission_deadline=?,created_at=? WHERE id=?",
            Timestamp.from(NOW),
            Timestamp.from(NOW.minusSeconds(60)),
            room.id,
        )
    }

    private fun assertUnchangedBeforeJoin() {
        assertEquals(1, count("participants"))
        assertEquals(0, count("guest_browser_sessions"))
        assertEquals(0, count("guest_participant_passwords"))
        assertEquals(0, count("guest_reaccess_grants"))
        assertEquals(0, count("submission_heads"))
    }

    private fun closeFixture(room: Room) {
        jdbc.update(
            "UPDATE meeting_rooms SET collection_status='CLOSED',closure_reason='MANUAL',closed_at=? WHERE id=?",
            Timestamp.from(NOW),
            room.id,
        )
    }

    private fun confirmedFixture(room: Room) {
        mvc.perform(normalSave(room, null, "주최자 확정 조건").header(HttpHeaders.AUTHORIZATION, room.host.bearer)).andExpect(status().isOk)
        closeFixture(room)
        val batch = UUID.randomUUID()
        val run = UUID.randomUUID()
        val candidate = UUID.randomUUID()
        jdbc.update("INSERT INTO submission_batches(id,room_id,fixed_at) VALUES (?,?,?)", batch, room.id, Timestamp.from(NOW))
        jdbc.update(
            "INSERT INTO submission_batch_items(batch_id,submission_version_id) " +
                "SELECT ?,latest_version_id FROM submission_heads WHERE room_id=?",
            batch,
            room.id,
        )
        jdbc.update(
            "INSERT INTO coordination_runs(id,room_id,batch_id,status,candidate_quality,created_at) VALUES (?,?,?,'COMPLETED','COMPLETE',?)",
            run,
            room.id,
            batch,
            Timestamp.from(NOW),
        )
        jdbc.update("UPDATE meeting_rooms SET active_run_id=? WHERE id=?", run, room.id)
        jdbc.update(
            "INSERT INTO candidates(id,coordination_run_id,rank,plan_type,meeting_mode,attendance_count,total_participants) " +
                "VALUES (?,?,1,'PLAN_B','REMOTE',2,2)",
            candidate,
            run,
        )
        jdbc.update(
            "INSERT INTO candidate_time_ranges(candidate_id,range_order,start_at,end_at) VALUES (?,0,?,?)",
            candidate,
            Timestamp.from(NOW.plus(Duration.ofDays(1))),
            Timestamp.from(NOW.plus(Duration.ofDays(1)).plusSeconds(3600)),
        )
        jdbc.update(
            "INSERT INTO candidate_participants(candidate_id,participant_id,coordination_run_id,room_id) " +
                "SELECT ?,id,?,room_id FROM participants WHERE room_id=?",
            candidate,
            run,
            room.id,
        )
        jdbc.update(
            "INSERT INTO final_confirmations(coordination_run_id,candidate_id,confirmed_at) VALUES (?,?,?)",
            run,
            candidate,
            Timestamp.from(NOW),
        )
    }

    private fun concurrently(
        first: () -> Int,
        second: () -> Int,
    ): List<Int> {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks =
                listOf(first, second).map { action ->
                    executor.submit<Int> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        action()
                    }
                }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            return tasks.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    private fun responseCookie(result: MvcResult): Cookie {
        val header = requireNotNull(result.response.getHeaders(HttpHeaders.SET_COOKIE).firstOrNull { it.startsWith("meet_me_guest=") })
        return Cookie("meet_me_guest", header.substringAfter('=').substringBefore(';'))
    }

    private fun assertNoPrivateFields(
        body: String,
        vararg secrets: String,
    ) {
        listOf("password_hash", "guest_session_id", "credential_digest", "raw_text", "pbkdf2-sha256").forEach {
            assertFalse(body.contains(it), "Response must omit private fields")
        }
        secrets.forEach { assertFalse(body.contains(it), "Response must omit private values") }
    }

    private data class Account(
        val id: UUID,
        val bearer: String,
    )

    private data class Room(
        val id: UUID,
        val invite: String,
        val host: Account,
    )

    private data class Guest(
        val id: UUID,
        val sessionId: UUID,
        val cookie: Cookie,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class IsolatedSecurity {
        @Bean
        @Primary
        fun guestReaccessTestClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        @Bean
        @Primary
        fun guestReaccessTestTokens(): AccessTokenPort =
            Rs256AccessTokenAdapter(
                KEYS.private,
                mapOf("issue108" to KEYS.public),
                "issue108",
                Clock.fixed(NOW, ZoneOffset.UTC),
                ISSUER,
                AUDIENCE,
            )

        @Bean
        @Primary
        fun guestReaccessTestLimiter(): RateLimitPort = RateLimitPort { key, limit, window -> LIMITER.get().check(key, limit, window) }
    }

    companion object {
        private const val ORIGIN = "https://app.meet-me.co.kr"
        private const val ISSUER = "https://api.synthetic.example"
        private const val AUDIENCE = "meetme-web"
        private const val ORIGINAL = "공개하면 안 되는 기존 원문 108"
        private const val REPLACEMENT = "새로 작성한 대체 입력 108"
        private const val PASSWORD = "protect-guest-108"
        private val NOW = Instant.parse("2026-10-17T12:00:00Z")
        private val KEYS = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val LIMITER = AtomicReference(RateLimitPort { _, _, _ -> RateLimitDecision(true, Duration.ZERO) })
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("meetme.anonymous.allowed-origins") { ORIGIN }
            registry.add("meetme.anonymous.cookie-secure") { true }
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
