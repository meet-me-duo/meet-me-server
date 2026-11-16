package com.meetme.server.coordination.application.service

import com.meetme.server.config.OperatorQueryProperties
import com.meetme.server.coordination.application.port.input.OperatorReanalysisReceipt
import com.meetme.server.coordination.application.port.input.OperatorReanalysisRequest
import com.meetme.server.coordination.application.port.input.OperatorReanalysisUseCase
import com.meetme.server.coordination.application.port.output.OperatorReanalysisCommandPort
import com.meetme.server.shared.application.port.input.operations.OperationsCursorCodec
import com.meetme.server.shared.application.port.input.operations.OperatorQueryError
import com.meetme.server.shared.application.port.input.operations.OperatorQueryException
import com.meetme.server.shared.application.port.output.operations.AuditOutcome
import com.meetme.server.shared.application.port.output.operations.AuditRoute
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAudit
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAuditPort
import com.meetme.server.shared.application.port.output.operations.OperatorIdentityPort
import com.meetme.server.shared.application.service.operations.OperatorAccessPolicy
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

@Service
class OperatorReanalysisService(
    private val command: OperatorReanalysisCommandPort,
    private val audit: OperatorAccessAuditPort,
    properties: OperatorQueryProperties,
    private val clock: Clock,
    private val identity: OperatorIdentityPort? = null,
    private val operatorAccess: OperatorAccessPolicy = OperatorAccessPolicy(properties),
) : OperatorReanalysisUseCase {
    override fun request(
        rawRoomId: String,
        decode: () -> OperatorReanalysisRequest,
    ): OperatorReanalysisReceipt {
        val target = OperationsCursorCodec.uuidOrNull(rawRoomId)
        val actor =
            try {
                identity?.currentUserId()
            } catch (failure: OperatorQueryException) {
                val unauthenticated = failure.code == OperatorQueryError.OPERATOR_UNAUTHENTICATED
                append(target, null, if (unauthenticated) AuditOutcome.DENIED else AuditOutcome.UNAVAILABLE)
                throw OperatorQueryException(
                    if (unauthenticated) OperatorQueryError.OPERATOR_UNAUTHENTICATED else OperatorQueryError.OPERATOR_UNAVAILABLE,
                )
            } catch (_: Exception) {
                append(target, null, AuditOutcome.UNAVAILABLE)
                throw OperatorQueryException(OperatorQueryError.OPERATOR_UNAVAILABLE)
            }
        if (actor == null) {
            append(target, null, AuditOutcome.DENIED)
            throw OperatorQueryException(OperatorQueryError.OPERATOR_UNAUTHENTICATED)
        }
        if (!operatorAccess.canViewOperations(actor)) {
            append(target, actor, AuditOutcome.DENIED)
            throw OperatorQueryException(OperatorQueryError.OPERATOR_FORBIDDEN)
        }
        val request =
            try {
                if (target == null) throw OperatorQueryException(OperatorQueryError.OPERATOR_INVALID_QUERY)
                decode().also {
                    if (it.expectedStateVersion < 0) throw OperatorQueryException(OperatorQueryError.OPERATOR_INVALID_QUERY)
                }
            } catch (failure: OperatorQueryException) {
                val denied = failure.code == OperatorQueryError.OPERATOR_FORBIDDEN
                append(target, actor, if (denied) AuditOutcome.DENIED else AuditOutcome.INVALID_QUERY)
                throw OperatorQueryException(
                    if (denied) OperatorQueryError.OPERATOR_FORBIDDEN else OperatorQueryError.OPERATOR_INVALID_QUERY,
                )
            } catch (_: Exception) {
                append(target, actor, AuditOutcome.UNAVAILABLE)
                throw OperatorQueryException(OperatorQueryError.OPERATOR_UNAVAILABLE)
            }
        // This durable ALLOWED record is an access admission; only the atomic command row proves acceptance.
        append(target, actor, AuditOutcome.ALLOWED)
        return try {
            command.enqueue(actor, requireNotNull(target), request)
        } catch (failure: OperatorQueryException) {
            throw failure
        } catch (_: Exception) {
            throw OperatorQueryException(OperatorQueryError.OPERATOR_UNAVAILABLE)
        }
    }

    private fun append(
        target: UUID?,
        actor: UUID?,
        outcome: AuditOutcome,
    ) {
        try {
            audit.append(OperatorAccessAudit(UUID.randomUUID(), clock.instant(), actor, AuditRoute.ROOM_REANALYSIS, target, outcome))
        } catch (_: Exception) {
            throw OperatorQueryException(OperatorQueryError.OPERATOR_UNAVAILABLE)
        }
    }
}
