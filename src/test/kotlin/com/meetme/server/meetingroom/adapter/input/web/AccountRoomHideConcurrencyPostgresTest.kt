package com.meetme.server.meetingroom.adapter.input.web

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.account.domain.AccountPrincipal
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #197: independent timestamp-idempotency and real room-to-participant lock ordering oracles. */
@SpringBootTest
@AutoConfigureMockMvc
class AccountRoomHideConcurrencyPostgresTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var dataSource: DataSource

    @MockitoBean private lateinit var account: CurrentAccountPort
    private val caller = ThreadLocal<AccountPrincipal?>()

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
        caller.remove()
        `when`(account.currentAccount()).thenAnswer { caller.get() }
    }

    @Test
    fun `repeat hide preserves the original stored timestamp instead of refreshing it`() {
        val fixture = fixture()
        caller.set(fixture.owner)
        mvc.perform(hide(fixture)).andExpect(status().isNoContent)
        val original = Instant.parse("2026-01-01T00:00:00Z")
        jdbc.update(
            "UPDATE participants SET account_archive_hidden_at=? WHERE id=?",
            Timestamp.from(original),
            fixture.participant,
        )

        mvc.perform(hide(fixture)).andExpect(status().isNoContent)

        assertEquals(
            original,
            jdbc
                .queryForObject(
                    "SELECT account_archive_hidden_at FROM participants WHERE id=?",
                    Timestamp::class.java,
                    fixture.participant,
                )?.toInstant(),
            "An idempotent repeat must retain the original hide instant",
        )
    }

    @Test
    fun `hide holds the real room lock while waiting for its participant so cleanup can skip it`() {
        val fixture = fixture()
        val executor = Executors.newSingleThreadExecutor()
        dataSource.connection.use { blocker ->
            blocker.autoCommit = false
            val blockerPid =
                blocker.createStatement().use { statement ->
                    statement.executeQuery("SELECT pg_backend_pid()").use { result ->
                        result.next()
                        result.getInt(1)
                    }
                }
            blocker.prepareStatement("SELECT id FROM participants WHERE id=? FOR UPDATE").use { statement ->
                statement.setObject(1, fixture.participant)
                statement.executeQuery().use { result -> check(result.next()) }
            }
            val hiding =
                executor.submit<Int> {
                    caller.set(fixture.owner)
                    try {
                        mvc
                            .perform(hide(fixture))
                            .andReturn()
                            .response.status
                    } finally {
                        caller.remove()
                    }
                }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var waiting = false
                while (!hiding.isDone && System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname=current_database() " +
                            "AND ? = ANY(pg_blocking_pids(pid)))",
                        Boolean::class.java,
                        blockerPid,
                    ) == true
                    if (waiting) break
                    Thread.sleep(20)
                }
                assertTrue(waiting, "The real hide transaction must reach the blocked participant write")
                assertEquals(
                    emptyList(),
                    jdbc.query(
                        "SELECT id FROM meeting_rooms WHERE id=? FOR UPDATE SKIP LOCKED",
                        { result, _ -> result.getObject(1, UUID::class.java) },
                        fixture.room,
                    ),
                    "Hide must already hold the room lock before attempting the participant write",
                )
            } finally {
                blocker.rollback()
                executor.shutdown()
                assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
            }
            assertEquals(204, hiding.get(1, TimeUnit.SECONDS))
        }
    }

    private fun fixture(): Fixture {
        val owner = AccountPrincipal(UUID.randomUUID(), "synthetic owner")
        val room = UUID.randomUUID()
        val participant = UUID.randomUUID()
        val invite =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteArray(16).also { java.security.SecureRandom().nextBytes(it) },
            )
        jdbc.update("INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,now())", owner.accountId, owner.displayName)
        jdbc.update(
            """
            INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
                search_range_source,manual_only,collection_status,created_at)
            VALUES (?,?,'synthetic lock test','REMOTE','Asia/Seoul','2026-10-17','2026-10-31','DEFAULTED',true,'COLLECTING',now())
            """.trimIndent(),
            room,
            invite,
        )
        jdbc.update(
            "INSERT INTO participants(id,room_id,user_id,role,display_name,joined_at) VALUES (?,?,?,'MEMBER','synthetic owner',now())",
            participant,
            room,
            owner.accountId,
        )
        return Fixture(room, participant, invite, owner)
    }

    private fun hide(fixture: Fixture) = post("/api/account/rooms/${fixture.invite}/hide").header("Origin", ORIGIN)

    private data class Fixture(
        val room: UUID,
        val participant: UUID,
        val invite: String,
        val owner: AccountPrincipal,
    )

    companion object {
        private const val ORIGIN = "https://app.meet-me.co.kr"
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
