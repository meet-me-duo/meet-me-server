package com.meetme.server.meetingroom.adapter.input.web

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.account.domain.AccountPrincipal
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FR-109D: public retention metadata uses the persisted creation anchor, never browser history. */
@SpringBootTest(properties = ["meetme.data-retention.enabled=false", "meetme.reliability.enabled=false"])
@AutoConfigureMockMvc
@Import(RoomExpiryWebIntegrationTest.ExpiryClockConfiguration::class)
class RoomExpiryWebIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @MockitoBean private lateinit var account: CurrentAccountPort
    private var caller: AccountPrincipal? = null

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
        caller = null
        `when`(account.currentAccount()).thenAnswer { caller }
    }

    @Test
    fun `RE01 create expiry matches persisted creation and subsequent detail and account history despite nanosecond clock`() {
        login()
        val created = create()
        val code = created["invite_code"].asText()
        val expected = persistedCreation(code).plus(RETENTION)
        assertEquals(expected, Instant.parse(created["expires_at"].asText()))
        expiry(mvc.perform(get("/api/rooms/$code")), expected)
        assertEquals(expected, historyExpiry(code))
        assertFalse(created.has("created_at"), "The additive detail contract needs only the retention anchor")
    }

    @Test
    fun `RE02 public account and guest reads and first and repeated joins preserve the same stored anchor`() {
        val host = login()
        val code = create()["invite_code"].asText()
        val expected = changeCreation(code, Instant.parse("2026-10-22T14:59:59.654321Z"))
        expiry(mvc.perform(get("/api/rooms/$code")), expected).andExpect(jsonPath("$.viewer.role").value("HOST"))
        caller = null
        expiry(mvc.perform(get("/api/rooms/$code")), expected)
            .andExpect(jsonPath("$.viewer.joined").value(false))
            .andExpect(jsonPath("$.viewer.context_id").isEmpty)
        expiry(mvc.perform(get("/api/rooms/$code").cookie(Cookie("meet_me_guest", "invalid-expiry-guest-proof"))), expected)
            .andExpect(jsonPath("$.viewer.joined").value(false))
            .andExpect(jsonPath("$.viewer.context_id").isEmpty)
        val guest =
            expiry(mvc.perform(join(code).content("""{"display_name":"expiry guest"}""")), expected, 201)
                .andExpect(jsonPath("$.viewer.role").value("MEMBER"))
                .andReturn()
                .response
        val raw = requireNotNull(guest.getHeader(HttpHeaders.SET_COOKIE)).substringAfter("meet_me_guest=").substringBefore(';')
        val cookie = Cookie("meet_me_guest", raw)
        expiry(mvc.perform(get("/api/rooms/$code").cookie(cookie)), expected)
            .andExpect(jsonPath("$.viewer.display_name").value("expiry guest"))
        expiry(mvc.perform(join(code).cookie(cookie).content("{}")), expected)
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
        val member = login()
        expiry(mvc.perform(join(code).content("{}")), expected, 201)
        expiry(mvc.perform(join(code).content("{}")), expected)
        assertEquals(expected, historyExpiry(code))
        caller = host
        assertEquals(expected, historyExpiry(code))
        expiry(mvc.perform(join(code).content("{}")), expected).andExpect(jsonPath("$.viewer.role").value("HOST"))
        caller = member
        expiry(mvc.perform(get("/api/rooms/$code")), expected).andExpect(jsonPath("$.viewer.role").value("MEMBER"))
    }

    @Test
    fun `RE03 first and repeated close use creation plus thirty days rather than deadline or closure`() {
        login()
        val code = create()["invite_code"].asText()
        val expected = changeCreation(code, Instant.parse("2026-10-19T02:03:04.123456Z"))
        jdbc.update(
            "UPDATE meeting_rooms SET manual_only=false,submission_deadline=? WHERE invite_code=?",
            Timestamp.from(NOW.plus(Duration.ofDays(2))),
            code,
        )
        repeat(2) {
            val closed =
                expiry(
                    mvc.perform(
                        post("/api/rooms/$code/close")
                            .header("Origin", ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"confirm_early":true}"""),
                    ),
                    expected,
                ).andExpect(jsonPath("$.collection_status").value("CLOSED"))
                    .andReturn()
                    .response.contentAsString
            val document = mapper.readTree(closed)
            assertFalse(Instant.parse(document["closed_at"].asText()) == expected)
            assertFalse(Instant.parse(document["submission_deadline"].asText()) == expected)
        }
        expiry(mvc.perform(get("/api/rooms/$code")), expected)
        assertEquals(expected, historyExpiry(code))
    }

    @Test
    fun `RE04 timezone DST and overdue rows do not change the UTC duration or existing public read access`() {
        login()
        val code = create()["invite_code"].asText()
        val expected = changeCreation(code, Instant.parse("2026-10-15T14:59:59.654321Z"))
        jdbc.update("UPDATE meeting_rooms SET time_zone_id='America/New_York' WHERE invite_code=?", code)
        expiry(mvc.perform(get("/api/rooms/$code")), expected)
            .andExpect(jsonPath("$.time_zone_id").value("America/New_York"))
        assertEquals(Instant.parse("2026-11-14T14:59:59.654321Z"), expected)
        val overdue = changeCreation(code, Instant.parse("2026-09-01T12:00:00Z"))
        caller = null
        expiry(mvc.perform(get("/api/rooms/$code")), overdue).andExpect(jsonPath("$.viewer.joined").value(false))
        assertTrue(overdue < NOW, "Metadata must not introduce a new expiry denial into GET")
    }

    @Test
    fun `RE05 hiding affects only account history while detail expiry and ownership remain unchanged`() {
        val host = login()
        val code = create()["invite_code"].asText()
        val expected = changeCreation(code, Instant.parse("2026-10-25T12:00:00Z"))
        assertEquals(expected, historyExpiry(code))
        mvc.perform(post("/api/account/rooms/$code/hide").header("Origin", ORIGIN)).andExpect(status().isNoContent)
        mvc.perform(get("/api/account/rooms")).andExpect(status().isOk).andExpect(jsonPath("$.rooms.length()").value(0))
        expiry(mvc.perform(get("/api/rooms/$code")), expected).andExpect(jsonPath("$.viewer.role").value("HOST"))
        login()
        expiry(mvc.perform(get("/api/rooms/$code")), expected)
            .andExpect(jsonPath("$.viewer.joined").value(false))
            .andExpect(jsonPath("$.viewer.context_id").isEmpty)
        mvc
            .perform(
                post("/api/rooms/$code/close")
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"confirm_early":true}"""),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("PARTICIPANT_REQUIRED"))
        caller = host
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM meeting_rooms WHERE invite_code=?", Int::class.java, code))
    }

    @Test
    fun `RE06 invalid and absent invite codes retain not found without retention metadata`() {
        for (code in listOf("invalid", "abcdefghijklmnopqrstuv")) {
            mvc
                .perform(get("/api/rooms/$code"))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"))
                .andExpect(jsonPath("$.expires_at").doesNotExist())
        }
    }

    @Test
    fun `RE07 native springdoc declares required nonnull date time expiry and exports the exact generated bytes`() {
        val response =
            mvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.components.schemas.RoomResponse.required").value(hasItem("expires_at")))
                .andExpect(jsonPath("$.components.schemas.RoomResponse.properties.expires_at.format").value("date-time"))
                .andReturn()
                .response
        val document = mapper.readTree(response.contentAsByteArray)
        val schema = document["components"]["schemas"]["RoomResponse"]["properties"]["expires_at"]
        assertEquals("string", schema["type"].asText())
        assertFalse(schema.path("nullable").asBoolean(false))
        assertTrue(schema["description"].asText().isNotBlank())
        val operations =
            listOf(
                Triple("/api/rooms", "post", listOf("201")),
                Triple("/api/rooms/{inviteCode}", "get", listOf("200")),
                Triple("/api/rooms/{inviteCode}/participants", "post", listOf("200", "201")),
                Triple("/api/rooms/{inviteCode}/close", "post", listOf("200")),
                Triple("/api/rooms/{inviteCode}/claim", "post", listOf("200")),
                Triple("/api/rooms/{inviteCode}/analysis/retry", "post", listOf("202")),
            )
        for ((path, method, codes) in operations) {
            for (code in codes) {
                val content = document["paths"][path][method]["responses"][code]["content"]
                val media = requireNotNull(content["application/json"] ?: content["*/*"])
                assertEquals(
                    "#/components/schemas/RoomResponse",
                    media["schema"]["\$ref"].asText(),
                )
            }
        }
        for (envelope in listOf("ReopenedInputResponse", "RevisionAnalysisResponse")) {
            assertEquals(
                "#/components/schemas/RoomResponse",
                document["components"]["schemas"][envelope]["properties"]["room"]["\$ref"].asText(),
            )
        }
        val target = Path.of(System.getenv("MEETME_ROOM_EXPIRY_OPENAPI_EXPORT") ?: "build/room-expiry.openapi.json")
        target.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.write(target, response.contentAsByteArray)
        assertTrue(Files.readAllBytes(target).contentEquals(response.contentAsByteArray))
    }

    private fun login(): AccountPrincipal {
        val principal = AccountPrincipal(UUID.randomUUID(), "expiry account")
        jdbc.update(
            "INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,?)",
            principal.accountId,
            principal.displayName,
            Timestamp.from(NOW),
        )
        caller = principal
        return principal
    }

    private fun create(): JsonNode =
        mapper.readTree(
            mvc
                .perform(
                    post("/api/rooms")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"purpose":"expiry contract","meeting_mode":"EITHER","manual_only":true}"""),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.expires_at").isString)
                .andReturn()
                .response.contentAsString,
        )

    private fun join(code: String) =
        post("/api/rooms/$code/participants")
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)

    private fun expiry(
        result: ResultActions,
        expected: Instant,
        httpStatus: Int = 200,
    ): ResultActions = result.andExpect(status().`is`(httpStatus)).andExpect(jsonPath("$.expires_at").value(expected.toString()))

    private fun persistedCreation(code: String): Instant =
        requireNotNull(
            jdbc.queryForObject("SELECT created_at FROM meeting_rooms WHERE invite_code=?", Timestamp::class.java, code),
        ).toInstant()

    private fun changeCreation(
        code: String,
        createdAt: Instant,
    ): Instant {
        jdbc.update("UPDATE meeting_rooms SET created_at=? WHERE invite_code=?", Timestamp.from(createdAt), code)
        return persistedCreation(code).plus(RETENTION)
    }

    private fun historyExpiry(code: String): Instant {
        val response =
            mvc
                .perform(get("/api/account/rooms"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return Instant.parse(mapper.readTree(response)["rooms"].single { it["invite_code"].asText() == code }["expires_at"].asText())
    }

    @TestConfiguration
    class ExpiryClockConfiguration {
        @Bean
        @Primary
        fun expiryClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    companion object {
        private const val ORIGIN = "https://app.meet-me.co.kr"
        private val NOW = Instant.parse("2026-10-31T12:34:56.123456789Z")
        private val RETENTION = Duration.ofDays(30)
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("meetme.anonymous.allowed-origins") { ORIGIN }
        }
    }
}
