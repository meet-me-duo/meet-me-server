package com.meetme.server.shared.adapter.output.account

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.account.domain.AuthFailure
import com.meetme.server.shared.application.port.input.operations.OperatorQueryError
import com.meetme.server.shared.application.port.input.operations.OperatorQueryException
import com.meetme.server.shared.application.port.output.operations.OperatorIdentityPort
import org.springframework.stereotype.Component
import java.util.UUID

/** Apply only together with the actual #109 common account contract. */
@Component
class CurrentAccountOperatorIdentityAdapter(
    private val currentAccount: CurrentAccountPort,
) : OperatorIdentityPort {
    override fun currentUserId(): UUID? =
        try {
            currentAccount.currentAccount()?.accountId
        } catch (failure: AuthFailure) {
            throw OperatorQueryException(
                when (failure.code) {
                    "AUTH_ACCESS_INVALID" -> OperatorQueryError.OPERATOR_UNAUTHENTICATED
                    else -> OperatorQueryError.OPERATOR_UNAVAILABLE
                },
            )
        }
}
