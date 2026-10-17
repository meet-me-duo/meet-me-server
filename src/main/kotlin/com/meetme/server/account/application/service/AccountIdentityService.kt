package com.meetme.server.account.application.service

import com.meetme.server.account.application.port.output.AccountRepository
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.OAuthProvider
import org.springframework.transaction.annotation.Transactional

open class AccountIdentityService(
    private val repository: AccountRepository,
) : AccountRepository {
    @Transactional
    override fun findOrCreateIdentity(
        provider: OAuthProvider,
        subject: String,
        displayName: String,
    ): AccountPrincipal = repository.findOrCreateIdentity(provider, subject, displayName)
}
