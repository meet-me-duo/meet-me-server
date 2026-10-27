package com.meetme.server.account.adapter.output.persistence

import com.meetme.server.account.application.port.output.AccountRepository
import com.meetme.server.account.application.service.AccountIdentityService
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
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** FR-002B, issue #109: provider subject is identity; creation is one atomic transaction. */
class AccountIdentityPersistenceTest {
    private val repository = context.getBean(AccountRepository::class.java)
    private val jdbc = context.getBean(JdbcTemplate::class.java)

    @BeforeEach
    fun resetIdentityFixture() {
        // The independent V10 -> V11 migration test owns migration compatibility.
        // This fixture defines the accepted public persistence schema independently.
        jdbc.execute("DROP TABLE IF EXISTS account_identities, account_users CASCADE")
        jdbc.execute("CREATE TABLE account_users(id uuid PRIMARY KEY, display_name varchar(50) NOT NULL, created_at timestamptz NOT NULL)")
        jdbc.execute(
            "CREATE TABLE account_identities(provider varchar(16) NOT NULL, subject varchar(255) NOT NULL, user_id uuid NOT NULL REFERENCES account_users(id), PRIMARY KEY(provider,subject))",
        )
    }

    @Test
    fun `same provider subject resolves existing account without profile based replacement`() {
        val original = repository.findOrCreateIdentity(OAuthProvider.GOOGLE, "stable-subject", "  기존 이름  ")
        val repeated = repository.findOrCreateIdentity(OAuthProvider.GOOGLE, "stable-subject", "changed profile")

        assertEquals(original, repeated)
        assertEquals("기존 이름", original.displayName)
        assertEquals(1L, count("account_users"))
        assertEquals(1L, count("account_identities"))
        assertEquals(NOW, jdbc.queryForObject("SELECT created_at FROM account_users", java.sql.Timestamp::class.java)?.toInstant())
    }

    @Test
    fun `equal subjects across providers and equal names never implicitly merge accounts`() {
        val google = repository.findOrCreateIdentity(OAuthProvider.GOOGLE, "same-subject", "same profile")
        val kakao = repository.findOrCreateIdentity(OAuthProvider.KAKAO, "same-subject", "same profile")
        val anotherGoogle = repository.findOrCreateIdentity(OAuthProvider.GOOGLE, "another-subject", "same profile")

        assertNotEquals(google.accountId, kakao.accountId)
        assertNotEquals(google.accountId, anotherGoogle.accountId)
        assertNotEquals(kakao.accountId, anotherGoogle.accountId)
        assertEquals(3L, count("account_users"))
        assertEquals(3L, count("account_identities"))
    }

    @Test
    fun `display names trim whitespace and accept one through fifty Unicode code points`() {
        val one = repository.findOrCreateIdentity(OAuthProvider.GOOGLE, "x".repeat(255), "  가  ")
        val fifty = repository.findOrCreateIdentity(OAuthProvider.KAKAO, "fifty", "  ${"😀".repeat(50)}  ")

        assertEquals("가", one.displayName)
        assertEquals("😀".repeat(50), fifty.displayName)
        assertEquals(50, fifty.displayName.codePointCount(0, fifty.displayName.length))
        assertEquals(2L, count("account_users"))
    }

    @Test
    fun `invalid identity or display name is rejected before any account is persisted`() {
        val invalid =
            listOf(
                "" to "valid",
                "   " to "valid",
                "x".repeat(256) to "valid",
                "empty-name" to "",
                "blank-name" to " \t\n ",
                "long-name" to "가".repeat(51),
                "long-unicode-name" to "😀".repeat(51),
            )

        invalid.forEach { (subject, name) ->
            val failure = assertThrows<AuthFailure> { repository.findOrCreateIdentity(OAuthProvider.GOOGLE, subject, name) }
            assertEquals("AUTH_OAUTH_INVALID", failure.code)
        }
        assertEquals(0L, count("account_users"))
        assertEquals(0L, count("account_identities"))
    }

    @Test
    fun `concurrent first login for same identity yields one account and no orphans`() {
        jdbc.execute(
            """
            CREATE OR REPLACE FUNCTION delay_account_insert() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN PERFORM pg_sleep(0.15); RETURN NEW; END ${'$'}${'$'};
            CREATE TRIGGER delay_account_insert BEFORE INSERT ON account_users
            FOR EACH ROW EXECUTE FUNCTION delay_account_insert();
            """.trimIndent(),
        )
        val workers = 12
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val futures =
                (1..workers).map {
                    executor.submit<AccountPrincipal> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        repository.findOrCreateIdentity(OAuthProvider.GOOGLE, "concurrent-subject", "Concurrent member")
                    }
                }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val principals = futures.map { it.get(20, TimeUnit.SECONDS) }

            assertEquals(1, principals.map { it.accountId }.toSet().size)
            assertEquals(setOf("Concurrent member"), principals.map { it.displayName }.toSet())
            assertEquals(1L, count("account_users"))
            assertEquals(1L, count("account_identities"))
        } finally {
            start.countDown()
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `identity insertion failure rolls back account creation and later retry succeeds`() {
        jdbc.execute(
            """
            CREATE OR REPLACE FUNCTION reject_identity_insert() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'isolated identity insert fault'; END ${'$'}${'$'};
            CREATE TRIGGER reject_identity_insert BEFORE INSERT ON account_identities
            FOR EACH ROW EXECUTE FUNCTION reject_identity_insert();
            """.trimIndent(),
        )

        assertThrows<RuntimeException> { repository.findOrCreateIdentity(OAuthProvider.KAKAO, "retry-subject", "member") }
        assertEquals(0L, count("account_users"))
        assertEquals(0L, count("account_identities"))

        jdbc.execute("DROP TRIGGER reject_identity_insert ON account_identities")
        val principal = repository.findOrCreateIdentity(OAuthProvider.KAKAO, "retry-subject", "member")
        assertEquals(principal, repository.findOrCreateIdentity(OAuthProvider.KAKAO, "retry-subject", "other profile"))
        assertEquals(1L, count("account_users"))
        assertEquals(1L, count("account_identities"))
    }

    private fun count(table: String): Long = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    class IdentityTestConfiguration {
        @Bean
        fun dataSource(): DataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)

        @Bean
        fun jdbc(dataSource: DataSource): JdbcTemplate = JdbcTemplate(dataSource)

        @Bean
        fun transactionManager(dataSource: DataSource): DataSourceTransactionManager = DataSourceTransactionManager(dataSource)

        @Bean
        fun accountRepository(jdbc: JdbcTemplate): AccountRepository =
            AccountIdentityService(JdbcAccountRepository(jdbc, Clock.fixed(NOW, ZoneOffset.UTC)))
    }

    companion object {
        private val NOW = Instant.parse("2026-10-17T12:00:00Z")
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }
        private val context = AnnotationConfigApplicationContext(IdentityTestConfiguration::class.java)

        @JvmStatic
        @AfterAll
        fun closeResources() {
            context.close()
            postgres.stop()
        }
    }
}
