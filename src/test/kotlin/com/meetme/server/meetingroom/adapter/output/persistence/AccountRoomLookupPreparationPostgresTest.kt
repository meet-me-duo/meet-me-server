package com.meetme.server.meetingroom.adapter.output.persistence

import com.meetme.server.meetingroom.application.port.input.AccountRoomCursor
import com.meetme.server.meetingroom.application.port.input.AccountRoomsQuery
import com.meetme.server.participant.domain.ParticipantRole
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Issue161: real PGJDBC preparation behavior and unchanged account-history port semantics. */
class AccountRoomLookupPreparationPostgresTest {
    private lateinit var pool: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var repository: JdbcAccountRoomArchiveRepository
    private lateinit var fixtures: Fixtures

    @BeforeEach
    fun prepare() {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .load()
            .migrate()
        val setup = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
        setup.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
        fixtures = seed(setup)
        pool =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 1
                    minimumIdle = 1
                    connectionTimeout = 10000
                },
            )
        jdbc = JdbcTemplate(pool)
        repository = JdbcAccountRoomArchiveRepository(jdbc)
    }

    @AfterEach
    fun closePool() {
        if (::pool.isInitialized) pool.close()
    }

    @Test
    fun `account lookup repetition never populates named server cache while an unrelated statement still prepares`() {
        val backend = scalar("SELECT pg_backend_pid()")
        assertEquals(5, connectionThreshold())
        assertEquals("auto", scalar("SELECT current_setting('plan_cache_mode')"))
        val queries =
            listOf(
                AccountRoomsQuery(),
                AccountRoomsQuery(role = ParticipantRole.HOST),
                AccountRoomsQuery(role = ParticipantRole.MEMBER),
                AccountRoomsQuery(createdFrom = DAY_START, createdBefore = DAY_END),
                AccountRoomsQuery(query = "100%_plan\\done"),
                AccountRoomsQuery(query = "팀"),
                AccountRoomsQuery(limit = 2, cursor = AccountRoomCursor(RECENT, id(1))),
            )
        repeat(20) { iteration ->
            for (query in queries) {
                repository.findByAccount(if (iteration % 2 == 0) fixtures.accountA else fixtures.accountB, CUTOFF, query)
            }
            assertEquals(
                iteration,
                jdbc.queryForObject("/* issue161 independent preparation control */ SELECT ?::integer", Int::class.java, iteration),
            )
        }
        assertEquals(backend, scalar("SELECT pg_backend_pid()"), "Pool1 must keep the physical backend used by the observations")
        assertEquals(5, connectionThreshold(), "Account lookup policy must not change the connection default")
        assertEquals("auto", scalar("SELECT current_setting('plan_cache_mode')"), "No session-wide custom-plan policy")
        val prepared = namedStatements()
        assertTrue(
            prepared.any { it.sql.contains("issue161 independent preparation control") && !it.fromSql },
            "The unrelated repeated statement must still use PGJDBC named preparation",
        )
        assertFalse(
            prepared.any { it.isAccountHistoryLookup() },
            "Repeated account history queries, including role/search/date/cursor variants, must not use named server preparation",
        )
    }

    @Test
    fun `mixed account role filters preserve retained rows own roles literal search creation range and keyset order`() {
        val expectedAll = listOf(id(1), id(2), id(3), id(5), id(6), id(4))
        repeat(12) { iteration ->
            val roles =
                if (iteration % 2 ==
                    0
                ) {
                    listOf(ParticipantRole.HOST, ParticipantRole.MEMBER)
                } else {
                    listOf(ParticipantRole.MEMBER, ParticipantRole.HOST)
                }
            for (role in roles) {
                val rows = repository.findByAccount(fixtures.accountA, CUTOFF, AccountRoomsQuery(role = role))
                assertEquals(
                    if (role ==
                        ParticipantRole.HOST
                    ) {
                        listOf(id(1), id(5))
                    } else {
                        listOf(id(2), id(3), id(6), id(4))
                    },
                    rows.map { it.roomId },
                )
                assertTrue(rows.all { it.role == role }, "Filtered projection must equal the role proved by the existing predicate")
                assertEquals(listOf(id(9)), repository.findByAccount(fixtures.accountB, CUTOFF, AccountRoomsQuery()).map { it.roomId })
            }
            val all = repository.findByAccount(fixtures.accountA, CUTOFF, AccountRoomsQuery())
            assertEquals(expectedAll, all.map { it.roomId })
            assertEquals(
                listOf(
                    ParticipantRole.HOST,
                    ParticipantRole.MEMBER,
                    ParticipantRole.MEMBER,
                    ParticipantRole.HOST,
                    ParticipantRole.MEMBER,
                    ParticipantRole.MEMBER,
                ),
                all.map {
                    it.role
                },
            )
            assertEquals(emptyList(), repository.findByAccount(fixtures.emptyAccount, CUTOFF, AccountRoomsQuery()))
            assertEquals(
                listOf(
                    id(1),
                    id(2),
                ),
                repository
                    .findByAccount(
                        fixtures.accountA,
                        CUTOFF,
                        AccountRoomsQuery(createdFrom = DAY_START, createdBefore = DAY_END),
                    ).map {
                        it.roomId
                    },
            )
            assertEquals(
                listOf(
                    id(1),
                ),
                repository.findByAccount(fixtures.accountA, CUTOFF, AccountRoomsQuery(query = "100%_plan\\done")).map {
                    it.roomId
                },
            )
            assertEquals(
                listOf(id(1), id(2)),
                repository.findByAccount(fixtures.accountA, CUTOFF, AccountRoomsQuery(query = "팀")).map { it.roomId },
            )
            assertEquals(emptyList(), repository.findByAccount(fixtures.accountA, CUTOFF, AccountRoomsQuery(query = "' OR 1=1 --")))
            assertEquals(
                listOf(
                    id(6),
                    id(4),
                ),
                repository.findByAccount(fixtures.accountA, CUTOFF, AccountRoomsQuery(createdBefore = CUTOFF)).map {
                    it.roomId
                },
            )
            val afterFirstTie =
                repository.findByAccount(
                    fixtures.accountA,
                    CUTOFF,
                    AccountRoomsQuery(limit = 2, cursor = AccountRoomCursor(RECENT, id(1))),
                )
            assertEquals(
                listOf(id(2), id(3), id(5)),
                afterFirstTie.map { it.roomId },
                "Port keeps LIMIT+1 and mixed-direction tie ordering",
            )
            assertEquals(NOW, all.first { it.roomId == id(5) }.expiresAt)
            assertEquals(NOW, all.first { it.roomId == id(6) }.expiresAt)
            assertEquals(NOW.plus(Duration.ofDays(29)), all.first { it.roomId == id(4) }.expiresAt)
        }
        val seen = mutableListOf<UUID>()
        var cursor: AccountRoomCursor? = null
        do {
            val rows = repository.findByAccount(fixtures.accountA, CUTOFF, AccountRoomsQuery(limit = 2, cursor = cursor))
            val page = rows.take(2)
            seen += page.map { it.roomId }
            cursor = if (rows.size > 2) page.last().let { AccountRoomCursor(it.createdAt, it.roomId) } else null
        } while (cursor != null)
        assertEquals(expectedAll, seen)
        assertEquals(seen.size, seen.toSet().size)
        assertEquals("1", scalar("SELECT count(*)::text FROM participants WHERE room_id='${id(10)}' AND user_id IS NULL"))
        assertEquals("10", scalar("SELECT count(*)::text FROM meeting_rooms"), "Read optimization must not delete expired or guest records")
    }

    private fun connectionThreshold(): Int =
        pool.connection.use { connection ->
            val pgConnection = Class.forName("org.postgresql.PGConnection")
            pgConnection.getMethod("getPrepareThreshold").invoke(connection.unwrap(pgConnection)) as Int
        }

    private fun scalar(sql: String): String? =
        pool.connection.use { c ->
            c.createStatement().use { s -> s.executeQuery(sql).use { r -> if (r.next()) r.getString(1) else null } }
        }

    private fun namedStatements(): List<NamedStatement> =
        pool.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT statement,from_sql FROM pg_prepared_statements").use { r ->
                    buildList { while (r.next()) add(NamedStatement(r.getString(1), r.getBoolean(2))) }
                }
            }
        }

    private fun seed(setup: JdbcTemplate): Fixtures {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val empty = UUID.randomUUID()
        for (account in listOf(
            a,
            b,
            empty,
        )) {
            setup.update(
                "INSERT INTO account_users(id,display_name,created_at) VALUES (?,'same synthetic profile',?)",
                account,
                Timestamp.from(NOW),
            )
        }
        val guest = UUID.randomUUID()
        setup.update(
            "INSERT INTO guest_browser_sessions(id,credential_digest,created_at,expires_at) VALUES (?,'synthetic lookup guest',?,?)",
            guest,
            Timestamp.from(NOW),
            Timestamp.from(NOW.plusSeconds(86400)),
        )
        room(setup, 1, a, ParticipantRole.HOST, RECENT, "팀 회의 100%_PLAN\\done")
        room(setup, 2, a, ParticipantRole.MEMBER, RECENT, "팀 스터디 100xxPLAN\\done")
        room(setup, 3, a, ParticipantRole.MEMBER, NOW.minus(Duration.ofDays(2)), "지난 참여")
        room(setup, 4, a, ParticipantRole.MEMBER, NOW.minus(Duration.ofDays(60)), "오래된 생성 최근 마감", NOW.minus(Duration.ofDays(1)))
        room(setup, 5, a, ParticipantRole.HOST, CUTOFF, "수집 경계")
        room(setup, 6, a, ParticipantRole.MEMBER, CUTOFF.minus(Duration.ofDays(10)), "마감 경계", CUTOFF)
        room(setup, 7, a, ParticipantRole.HOST, CUTOFF.minusNanos(1000), "만료 수집")
        room(setup, 8, a, ParticipantRole.MEMBER, NOW.minus(Duration.ofDays(60)), "만료 마감", CUTOFF.minusNanos(1000))
        room(setup, 9, b, ParticipantRole.HOST, NOW.minusSeconds(3600), "다른 계정")
        room(setup, 10, null, ParticipantRole.HOST, NOW.minusSeconds(3600), "게스트 기록", guestId = guest)
        return Fixtures(a, b, empty)
    }

    private fun room(
        setup: JdbcTemplate,
        number: Int,
        account: UUID?,
        role: ParticipantRole,
        createdAt: Instant,
        purpose: String,
        closedAt: Instant? = null,
        guestId: UUID? = null,
    ) {
        val room = id(number)
        setup.update(
            """INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
            search_range_source,manual_only,collection_status,closure_reason,closed_at,created_at)
            VALUES (?,?,?,'EITHER','Asia/Seoul','2026-10-28','2026-11-10','DEFAULTED',true,?,?,?,?)""",
            room,
            "lookupfixture" + number.toString().padStart(9, '0'),
            purpose,
            if (closedAt == null) "COLLECTING" else "CLOSED",
            if (closedAt == null) null else "MANUAL",
            closedAt?.let(Timestamp::from),
            Timestamp.from(createdAt),
        )
        setup.update(
            "INSERT INTO participants(id,room_id,guest_session_id,user_id,role,display_name,joined_at) VALUES (?,?,?,?,?,'synthetic lookup participant',?)",
            UUID.randomUUID(),
            room,
            guestId,
            account,
            role.name,
            Timestamp.from(NOW),
        )
    }

    private data class NamedStatement(
        val sql: String,
        val fromSql: Boolean,
    ) {
        fun isAccountHistoryLookup(): Boolean {
            val words = sql.lowercase()
            return Regex("\\bmeeting_rooms\\b").containsMatchIn(words) && Regex("\\bparticipants\\b").containsMatchIn(words)
        }
    }

    private data class Fixtures(
        val accountA: UUID,
        val accountB: UUID,
        val emptyAccount: UUID,
    )

    companion object {
        private val NOW = Instant.parse("2026-10-28T12:00:00Z")
        private val CUTOFF = NOW.minus(Duration.ofDays(30))
        private val RECENT = NOW.minus(Duration.ofDays(1))
        private val DAY_START = Instant.parse("2026-10-26T15:00:00Z")
        private val DAY_END = Instant.parse("2026-10-27T15:00:00Z")
        private val postgres = PostgreSQLContainer("postgres:18-alpine")

        init {
            postgres.start()
        }

        private fun id(number: Int): UUID = UUID.fromString("00000000-0000-0000-0000-${number.toString().padStart(12, '0')}")

        @JvmStatic @AfterAll
        fun stopContainer() {
            postgres.stop()
        }
    }
}
