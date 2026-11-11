package com.meetme.server.account.application.service

import com.meetme.server.account.application.port.input.AccountProfileUseCase
import com.meetme.server.account.application.port.output.AccountProfileRepository
import com.meetme.server.account.domain.AccountDisplayName
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.AuthFailure
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

open class AccountProfileService(
    private val repository: AccountProfileRepository,
) : AccountProfileUseCase {
    @Transactional(readOnly = true)
    override fun get(accountId: UUID): AccountPrincipal = repository.findById(accountId) ?: throw AuthFailure("AUTH_ACCESS_INVALID")

    @Transactional
    override fun updateDisplayName(
        accountId: UUID,
        displayName: String,
    ): AccountPrincipal =
        repository.updateDisplayName(accountId, AccountDisplayName.of(displayName)) ?: throw AuthFailure("AUTH_ACCESS_INVALID")
}
