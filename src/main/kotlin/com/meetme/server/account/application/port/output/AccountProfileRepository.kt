package com.meetme.server.account.application.port.output

import com.meetme.server.account.domain.AccountDisplayName
import com.meetme.server.account.domain.AccountPrincipal
import java.util.UUID

interface AccountProfileRepository {
    fun findById(accountId: UUID): AccountPrincipal?

    fun updateDisplayName(
        accountId: UUID,
        displayName: AccountDisplayName,
    ): AccountPrincipal?
}
