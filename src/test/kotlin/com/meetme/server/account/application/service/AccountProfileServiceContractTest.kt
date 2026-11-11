package com.meetme.server.account.application.service

import com.meetme.server.account.application.port.output.AccountProfileRepository
import com.meetme.server.account.domain.AccountDisplayName
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.AuthFailure
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Issue228: PostgreSQL account identity remains authoritative; names never select an owner. */
class AccountProfileServiceContractTest {
    @Test
    fun `PS01 reads the requested persisted profile without synthesizing or caching a name`() {
        val repository = Profiles()
        val service = AccountProfileService(repository)
        assertEquals(AccountPrincipal(FIRST, "첫 이름"), service.get(FIRST))
        repository.rows[FIRST] = AccountPrincipal(FIRST, "새 이름")
        assertEquals(AccountPrincipal(FIRST, "새 이름"), service.get(FIRST))
        assertEquals(listOf(FIRST, FIRST), repository.reads)
        assertTrue(repository.writes.isEmpty())
    }

    @Test
    fun `PS02 updates only the specified account using normalized name and permits duplicates`() {
        val repository = Profiles()
        val service = AccountProfileService(repository)
        assertEquals(AccountPrincipal(FIRST, "다른 이름"), service.updateDisplayName(FIRST, "  다른 이름  "))
        assertEquals(AccountPrincipal(SECOND, "다른 이름"), repository.rows[SECOND])
        assertEquals(listOf(FIRST to "다른 이름"), repository.writes)
        assertEquals(AccountPrincipal(FIRST, "다른 이름"), service.get(FIRST))
    }

    @Test
    fun `PS03 validation precedes persistence and rejected input cannot change either account`() {
        val repository = Profiles()
        val service = AccountProfileService(repository)
        val before = repository.rows.toMap()
        listOf("", "   ", "😀".repeat(51), "private\nname").forEach {
            assertFailsWith<IllegalArgumentException> { service.updateDisplayName(FIRST, it) }
        }
        assertEquals(before, repository.rows)
        assertTrue(repository.writes.isEmpty())
    }

    @Test
    fun `PS04 missing account is unauthorized for both reads and writes and never upserted`() {
        val repository = Profiles()
        val service = AccountProfileService(repository)
        val missing = UUID.fromString("77777777-7777-4777-8777-777777777777")
        assertEquals("AUTH_ACCESS_INVALID", assertFailsWith<AuthFailure> { service.get(missing) }.code)
        assertEquals("AUTH_ACCESS_INVALID", assertFailsWith<AuthFailure> { service.updateDisplayName(missing, "New") }.code)
        assertEquals(setOf(FIRST, SECOND), repository.rows.keys)
    }

    @Test
    fun `PS05 repository outage is not converted into success or a cached account`() {
        val repository = Profiles().apply { failure = AuthFailure("AUTH_UNAVAILABLE") }
        val service = AccountProfileService(repository)
        assertEquals("AUTH_UNAVAILABLE", assertFailsWith<AuthFailure> { service.get(FIRST) }.code)
        assertEquals("AUTH_UNAVAILABLE", assertFailsWith<AuthFailure> { service.updateDisplayName(FIRST, "New") }.code)
        assertEquals(AccountPrincipal(FIRST, "첫 이름"), repository.rows[FIRST])
    }

    @Test
    fun `PS06 repeated same-name update keeps identity and result stable`() {
        val repository = Profiles()
        val service = AccountProfileService(repository)
        repeat(2) { assertEquals(AccountPrincipal(FIRST, "첫 이름"), service.updateDisplayName(FIRST, "첫 이름")) }
        assertEquals(setOf(FIRST, SECOND), repository.rows.keys)
        assertEquals(AccountPrincipal(SECOND, "다른 이름"), repository.rows[SECOND])
    }

    private class Profiles : AccountProfileRepository {
        val rows = mutableMapOf(FIRST to AccountPrincipal(FIRST, "첫 이름"), SECOND to AccountPrincipal(SECOND, "다른 이름"))
        val reads = mutableListOf<UUID>()
        val writes = mutableListOf<Pair<UUID, String>>()
        var failure: AuthFailure? = null

        override fun findById(accountId: UUID): AccountPrincipal? {
            failure?.let { throw it }
            reads += accountId
            return rows[accountId]
        }

        override fun updateDisplayName(
            accountId: UUID,
            displayName: AccountDisplayName,
        ): AccountPrincipal? {
            failure?.let { throw it }
            writes += accountId to displayName.value
            if (accountId !in rows) return null
            return AccountPrincipal(accountId, displayName.value).also { rows[accountId] = it }
        }
    }

    private companion object {
        val FIRST: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val SECOND: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}
