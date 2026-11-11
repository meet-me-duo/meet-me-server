package com.meetme.server.account.application.port.input

import com.meetme.server.account.domain.AccountPrincipal
import java.util.UUID

interface AccountProfileUseCase {
    fun get(accountId: UUID): AccountPrincipal

    fun updateDisplayName(
        accountId: UUID,
        displayName: String,
    ): AccountPrincipal
}
