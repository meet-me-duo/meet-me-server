package com.meetme.server.account.adapter.output.persistence

import com.meetme.server.account.application.port.input.AccountProfileUseCase
import com.meetme.server.account.application.port.output.AccountProfileRepository
import com.meetme.server.account.application.port.output.AccountRepository
import com.meetme.server.account.application.service.AccountIdentityService
import com.meetme.server.account.application.service.AccountProfileService
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.AuthFailure
import com.meetme.server.account.domain.OAuthProvider
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Issue228 / FR-ACCOUNT-PROFILE: actual PostgreSQL is authoritative for account names. */
class AccountProfilePersistenceTest {
    private val profiles = context.getBean("profiles", AccountProfileUseCase::class.java)
    private val identities = context.getBean("identities", AccountRepository::class.java)
    private val jdbc = context.getBean(JdbcTemplate::class.java)

    @BeforeEach
    fun resetSyntheticProfiles() {
        jdbc.execute("DROP TABLE IF EXISTS participants, account_identities, account_users CASCADE")
        // These columns and constraints are the accepted V11 account ownership contract.
        jdbc.execute(
            "CREATE TABLE account_users(id uuid PRIMARY KEY, display_name varchar(50) NOT NULL, created_at timestamptz NOT NULL, CHECK(length(trim(display_name)) BETWEEN 1 AND 50))",
        )
        jdbc.execute(
            "CREATE TABLE account_identities(provider varchar(16) NOT NULL, subject varchar(255) NOT NULL, user_id uuid NOT NULL REFERENCES account_users(id), PRIMARY KEY(provider,subject))",
        )
        jdbc.execute(
            "CREATE TABLE participants(id uuid PRIMARY KEY, room_id uuid NOT NULL, user_id uuid REFERENCES account_users(id), display_name varchar(50) NOT NULL)",
        )
        jdbc.update(
            "INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,?),(?,?,?)",
            FIRST,
            "Kakao 회원",
            Timestamp.from(CREATED),
            SECOND,
            "다른 회원",
            Timestamp.from(CREATED),
        )
        jdbc.update(
            "INSERT INTO account_identities(provider,subject,user_id) VALUES (?,?,?),(?,?,?)",
            "KAKAO",
            "first-synthetic-subject",
            FIRST,
            "GOOGLE",
            "second-synthetic-subject",
            SECOND,
        )
        jdbc.update(
            "INSERT INTO participants(id,room_id,user_id,display_name) VALUES (?,?,?,?),(?,?,?,?)",
            FIRST_PARTICIPANT,
            ROOM,
            FIRST,
            "참가 당시 이름",
            SECOND_PARTICIPANT,
            ROOM,
            SECOND,
            "다른 참가 이름",
        )
    }

    @Test
    fun `P228-PG01 rename changes only the selected account name and preserves ownership snapshots`() {
        val beforeIdentities = jdbc.queryForList("SELECT * FROM account_identities ORDER BY provider,subject")
        val beforeParticipants = jdbc.queryForList("SELECT * FROM participants ORDER BY id")
        val name = "O'Connor; --"

        val updated = profiles.updateDisplayName(FIRST, "  $name  ")

        assertEquals(AccountPrincipal(FIRST, name), updated)
        assertEquals(name, storedName(FIRST))
        assertEquals("다른 회원", storedName(SECOND))
        assertEquals(2L, countAccounts())
        assertEquals(beforeIdentities, jdbc.queryForList("SELECT * FROM account_identities ORDER BY provider,subject"))
        assertEquals(beforeParticipants, jdbc.queryForList("SELECT * FROM participants ORDER BY id"))
        assertEquals(
            listOf(CREATED, CREATED),
            jdbc.query("SELECT created_at FROM account_users ORDER BY id", { row, _ -> row.getTimestamp(1).toInstant() }),
        )
    }

    @Test
    fun `P228-PG02 committed name survives a fresh connection and a new service context`() {
        profiles.updateDisplayName(FIRST, "영구 이름")
        val freshJdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
        assertEquals("영구 이름", freshJdbc.queryForObject("SELECT display_name FROM account_users WHERE id=?", String::class.java, FIRST))

        AnnotationConfigApplicationContext(ProfileTestConfiguration::class.java).use { restarted ->
            val reloaded = restarted.getBean("profiles", AccountProfileUseCase::class.java).get(FIRST)
            assertEquals(AccountPrincipal(FIRST, "영구 이름"), reloaded)
        }
    }

    @Test
    fun `P228-PG03 subsequent provider login retains the manually renamed account`() {
        profiles.updateDisplayName(FIRST, "직접 정한 이름")

        val relogin = identities.findOrCreateIdentity(OAuthProvider.KAKAO, "first-synthetic-subject", "공급자 프로필 이름")

        assertEquals(AccountPrincipal(FIRST, "직접 정한 이름"), relogin)
        assertEquals(2L, countAccounts())
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM account_identities", Long::class.java))
    }

    @Test
    fun `P228-PG04 trimmed fifty-codepoint Unicode names and equal names remain valid`() {
        val name = "😀".repeat(50)
        assertEquals(name, profiles.updateDisplayName(FIRST, "  $name  ").displayName)
        assertEquals(name, profiles.updateDisplayName(SECOND, name).displayName)
        assertEquals(name, storedName(FIRST))
        assertEquals(name, storedName(SECOND))
        assertEquals(2L, countAccounts())
    }

    @Test
    fun `P228-PG05 reading an absent profile fails closed without creating an account`() {
        val failure = assertThrows<AuthFailure> { profiles.get(ABSENT) }
        assertEquals("AUTH_ACCESS_INVALID", failure.code)
        assertEquals(2L, countAccounts())
    }

    @Test
    fun `P228-PG06 renaming an absent profile fails closed without inserting a replacement`() {
        val failure = assertThrows<AuthFailure> { profiles.updateDisplayName(ABSENT, "새 이름") }
        assertEquals("AUTH_ACCESS_INVALID", failure.code)
        assertEquals(2L, countAccounts())
        assertEquals("Kakao 회원", storedName(FIRST))
        assertEquals("다른 회원", storedName(SECOND))
    }

    @Test
    fun `P228-PG07 invalid names never alter any stored profile`() {
        for (name in listOf("", "   ", "가".repeat(51), "😀".repeat(51), "안\u0000녕", "안\n녕")) {
            assertThrows<IllegalArgumentException> { profiles.updateDisplayName(FIRST, name) }
            assertEquals("Kakao 회원", storedName(FIRST))
            assertEquals("다른 회원", storedName(SECOND))
        }
    }

    @Test
    fun `P228-PG08 database update failure preserves names and later retry can succeed`() {
        jdbc.execute(
            """
            CREATE OR REPLACE FUNCTION reject_profile_update() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'synthetic account update fault'; END ${'$'}${'$'};
            CREATE TRIGGER reject_profile_update BEFORE UPDATE ON account_users
            FOR EACH ROW EXECUTE FUNCTION reject_profile_update();
            """.trimIndent(),
        )

        val failure = assertThrows<AuthFailure> { profiles.updateDisplayName(FIRST, "반영되면 안 됨") }
        assertEquals("AUTH_UNAVAILABLE", failure.code)
        assertEquals("Kakao 회원", storedName(FIRST))
        assertEquals("다른 회원", storedName(SECOND))

        jdbc.execute("DROP TRIGGER reject_profile_update ON account_users")
        assertEquals(AccountPrincipal(FIRST, "재시도 이름"), profiles.updateDisplayName(FIRST, "재시도 이름"))
    }

    @Test
    fun `P228-PG09 caller transaction rollback also rolls back the profile mutation`() {
        val transactions = TransactionTemplate(context.getBean(DataSourceTransactionManager::class.java))
        assertThrows<RollbackMarker> {
            transactions.executeWithoutResult {
                assertEquals(AccountPrincipal(FIRST, "취소될 이름"), profiles.updateDisplayName(FIRST, "취소될 이름"))
                throw RollbackMarker()
            }
        }
        assertEquals("Kakao 회원", storedName(FIRST))
        assertEquals("다른 회원", storedName(SECOND))
    }

    @Test
    fun `P228-PG10 concurrent renames preserve response ownership and do not affect another account`() {
        val workers = 6
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val futures =
                (1..workers).map { number ->
                    val name = "동시 이름 $number"
                    executor.submit<AccountPrincipal> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        profiles.updateDisplayName(FIRST, name).also { assertEquals(AccountPrincipal(FIRST, name), it) }
                    }
                }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
            assertTrue(storedName(FIRST) in (1..workers).map { "동시 이름 $it" })
            assertEquals("다른 회원", storedName(SECOND))
            assertEquals(2L, countAccounts())
        } finally {
            start.countDown()
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `P228-PG11 read failure must not return a previously observed cached name`() {
        assertEquals(AccountPrincipal(FIRST, "Kakao 회원"), profiles.get(FIRST))
        jdbc.execute("ALTER TABLE account_users RENAME TO temporarily_unavailable_accounts")
        try {
            val failure = assertThrows<AuthFailure> { profiles.get(FIRST) }
            assertEquals("AUTH_UNAVAILABLE", failure.code)
        } finally {
            jdbc.execute("ALTER TABLE temporarily_unavailable_accounts RENAME TO account_users")
        }
        assertEquals("Kakao 회원", storedName(FIRST))
    }

    @Test
    fun `P228-PG12 existing service instances observe later committed profile changes`() {
        assertEquals(AccountPrincipal(FIRST, "Kakao 회원"), profiles.get(FIRST))
        val separateConnection = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
        separateConnection.update("UPDATE account_users SET display_name=? WHERE id=?", "다른 연결에서 변경", FIRST)
        assertEquals(AccountPrincipal(FIRST, "다른 연결에서 변경"), profiles.get(FIRST))
    }

    private fun storedName(accountId: UUID): String =
        jdbc.queryForObject("SELECT display_name FROM account_users WHERE id=?", String::class.java, accountId)!!

    private fun countAccounts(): Long = jdbc.queryForObject("SELECT count(*) FROM account_users", Long::class.java)!!

    private class RollbackMarker : RuntimeException()

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    class ProfileTestConfiguration {
        @Bean
        fun dataSource(): DataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)

        @Bean
        fun jdbc(dataSource: DataSource): JdbcTemplate = JdbcTemplate(dataSource)

        @Bean
        fun transactionManager(dataSource: DataSource): DataSourceTransactionManager = DataSourceTransactionManager(dataSource)

        @Bean
        fun storage(jdbc: JdbcTemplate): JdbcAccountRepository = JdbcAccountRepository(jdbc, Clock.fixed(CREATED, ZoneOffset.UTC))

        @Bean
        fun profiles(storage: JdbcAccountRepository): AccountProfileUseCase = AccountProfileService(storage as AccountProfileRepository)

        @Bean
        fun identities(storage: JdbcAccountRepository): AccountRepository = AccountIdentityService(storage)
    }

    companion object {
        private val CREATED = Instant.parse("2026-11-01T03:00:00Z")
        private val FIRST = UUID.fromString("10000000-0000-4000-8000-000000000001")
        private val SECOND = UUID.fromString("10000000-0000-4000-8000-000000000002")
        private val ABSENT = UUID.fromString("10000000-0000-4000-8000-000000000003")
        private val ROOM = UUID.fromString("20000000-0000-4000-8000-000000000001")
        private val FIRST_PARTICIPANT = UUID.fromString("30000000-0000-4000-8000-000000000001")
        private val SECOND_PARTICIPANT = UUID.fromString("30000000-0000-4000-8000-000000000002")
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }
        private val context = AnnotationConfigApplicationContext(ProfileTestConfiguration::class.java)

        @JvmStatic
        @AfterAll
        fun closeResources() {
            context.close()
            postgres.stop()
        }
    }
}
