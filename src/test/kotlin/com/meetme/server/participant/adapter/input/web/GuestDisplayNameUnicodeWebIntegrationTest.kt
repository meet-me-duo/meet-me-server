package com.meetme.server.participant.adapter.input.web

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.shared.application.port.output.RateLimitDecision
import com.meetme.server.shared.application.port.output.RateLimitPort
import com.meetme.server.shared.testsupport.AccountPrincipalTestFixture
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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import kotlin.test.assertEquals

/** HTTP validation must preserve the agreed trimmed 1..50 Unicode code-point name contract. */
@SpringBootTest(properties = ["meetme.reliability.enabled=false", "meetme.data-retention.enabled=false"])
@AutoConfigureMockMvc
@Import(GuestDisplayNameUnicodeWebIntegrationTest.UnicodeNameRateLimitConfiguration::class)
class GuestDisplayNameUnicodeWebIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var account: CurrentAccountPort

    private lateinit var accounts: AccountPrincipalTestFixture
    private val json = ObjectMapper()

    @BeforeEach
    fun cleanDatabase() {
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
        accounts = AccountPrincipalTestFixture(jdbc, account)
        accounts.install()
    }

    @Test
    fun `108 U01 non BMP 이름 50코드 포인트는 UTF16 길이에 관계없이 참여할 수 있다`() {
        val invite = room()
        val name = "😀".repeat(50)

        mvc
            .perform(join(invite, name))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.viewer.display_name").value(name))
            .andExpect(header().exists(HttpHeaders.SET_COOKIE))

        assertEquals(name, jdbc.queryForObject("SELECT display_name FROM participants WHERE role='MEMBER'", String::class.java))
        assertEquals(2, count("participants"))
        assertEquals(1, count("guest_browser_sessions"))
        assertEquals(1, count("guest_participant_passwords"))
    }

    @Test
    fun `108 U02 non BMP 이름 51코드 포인트 거부는 참여 세션 해시와 쿠키를 만들지 않는다`() {
        val invite = room()
        val before = snapshot()

        mvc
            .perform(join(invite, "😀".repeat(51)))
            .andExpect(status().isBadRequest)
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))

        assertEquals(before, snapshot())
    }

    @Test
    fun `108 U03 이름의 앞뒤 공백을 제거한 뒤 50코드 포인트를 허용하고 공백뿐인 이름은 거부한다`() {
        val invite = room()
        val trimmedName = "😀".repeat(50)

        mvc
            .perform(join(invite, " \t$trimmedName\n "))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.viewer.display_name").value(trimmedName))
            .andExpect(header().exists(HttpHeaders.SET_COOKIE))
        assertEquals(trimmedName, jdbc.queryForObject("SELECT display_name FROM participants WHERE role='MEMBER'", String::class.java))
        val beforeInvalidName = snapshot()

        mvc
            .perform(join(invite, " \t\n "))
            .andExpect(status().isBadRequest)
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))

        assertEquals(beforeInvalidName, snapshot())
    }

    private fun room(): String {
        val response =
            mvc
                .perform(
                    post("/api/rooms")
                        .header("Origin", ORIGIN)
                        .cookie(accounts.newAccount("주최자"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"purpose":"이름 경계 검증","meeting_mode":"EITHER","manual_only":true}"""),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        return json.readTree(response.contentAsByteArray).path("invite_code").stringValue()
    }

    private fun join(
        invite: String,
        name: String,
    ) = post("/api/rooms/$invite/participants")
        .header("Origin", ORIGIN)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(mapOf("display_name" to name, "password" to "unicode-name-password-108")))

    private fun count(table: String): Int = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private fun snapshot(): Map<String, Int> =
        listOf("participants", "guest_browser_sessions", "guest_participant_passwords", "guest_reaccess_grants", "submission_heads")
            .associateWith(::count)

    @TestConfiguration(proxyBeanMethods = false)
    class UnicodeNameRateLimitConfiguration {
        @Bean
        @Primary
        fun unicodeNameRateLimiter(): RateLimitPort = RateLimitPort { _, _, _ -> RateLimitDecision(true, Duration.ZERO) }
    }

    companion object {
        private const val ORIGIN = "https://app.meet-me.co.kr"
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
