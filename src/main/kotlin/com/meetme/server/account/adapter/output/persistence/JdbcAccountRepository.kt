package com.meetme.server.account.adapter.output.persistence

import com.meetme.server.account.application.port.output.AccountRepository
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.AuthFailure
import com.meetme.server.account.domain.OAuthProvider
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

class JdbcAccountRepository(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) : AccountRepository {
    override fun findOrCreateIdentity(
        provider: OAuthProvider,
        subject: String,
        displayName: String,
    ): AccountPrincipal {
        val name = displayName.trim()
        if (subject.isBlank() ||
            subject.length > 255 ||
            '\u0000' in subject ||
            name.codePointCount(0, name.length) !in 1..50 ||
            name.isBlank()
        ) {
            throw AuthFailure("AUTH_OAUTH_INVALID")
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw AuthFailure("AUTH_UNAVAILABLE")
        try {
            // Serialize first-login creation only for the same verified provider subject.
            val digest = MessageDigest.getInstance("SHA-256").digest("${provider.name}:$subject".toByteArray(Charsets.UTF_8))
            val lockId = ByteBuffer.wrap(digest).long
            jdbc.query("SELECT pg_advisory_xact_lock(?)", { _, _ -> Unit }, lockId)
            val existing =
                jdbc
                    .query(
                        "SELECT u.id,u.display_name FROM account_identities i JOIN account_users u ON u.id=i.user_id WHERE i.provider=? AND i.subject=?",
                        { row, _ -> AccountPrincipal(row.getObject("id", UUID::class.java), row.getString("display_name")) },
                        provider.name,
                        subject,
                    ).singleOrNull()
            if (existing != null) return existing
            val principal = AccountPrincipal(UUID.randomUUID(), name)
            jdbc.update(
                "INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,?)",
                principal.accountId,
                name,
                Timestamp.from(clock.instant()),
            )
            jdbc.update(
                "INSERT INTO account_identities(provider,subject,user_id) VALUES (?,?,?)",
                provider.name,
                subject,
                principal.accountId,
            )
            return principal
        } catch (_: DataAccessException) {
            throw AuthFailure("AUTH_UNAVAILABLE")
        }
    }
}
