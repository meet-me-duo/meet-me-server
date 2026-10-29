package com.meetme.server.shared.application.service.operations

import com.meetme.server.config.OperatorQueryProperties
import com.meetme.server.shared.application.port.input.operations.InvocationDetail
import com.meetme.server.shared.application.port.input.operations.OperationsCursor
import com.meetme.server.shared.application.port.input.operations.OperationsCursorCodec
import com.meetme.server.shared.application.port.input.operations.OperationsPage
import com.meetme.server.shared.application.port.input.operations.OperatorQueryError
import com.meetme.server.shared.application.port.input.operations.OperatorQueryException
import com.meetme.server.shared.application.port.input.operations.RoomDetail
import com.meetme.server.shared.application.port.input.operations.RoomSummary
import com.meetme.server.shared.application.port.output.operations.AuditOutcome
import com.meetme.server.shared.application.port.output.operations.AuditRoute
import com.meetme.server.shared.application.port.output.operations.OperationsQueryRepository
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAudit
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAuditPort
import com.meetme.server.shared.application.port.output.operations.OperatorIdentityPort
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Service
class OperatorQueryService(
    private val query: OperationsQueryRepository,
    private val audit: OperatorAccessAuditPort,
    properties: OperatorQueryProperties,
    private val clock: Clock,
    private val identity: OperatorIdentityPort? = null,
    private val operatorAccess: OperatorAccessPolicy = OperatorAccessPolicy(properties),
) {
    fun listRooms(
        limit: String? = null,
        cursor: String? = null,
    ): OperationsPage<RoomSummary> = access(AuditRoute.ROOM_LIST, null) { query.listRooms(pageSize(limit), pageCursor(cursor)) }

    fun roomDetail(
        roomId: String,
        limit: String? = null,
        cursor: String? = null,
    ): RoomDetail =
        access(AuditRoute.ROOM_DETAIL, roomId) {
            query.roomDetail(target(roomId), pageSize(limit), pageCursor(cursor))
        }

    fun invocationDetail(invocationId: String): InvocationDetail =
        access(AuditRoute.INVOCATION_DETAIL, invocationId) { query.invocationDetail(target(invocationId)) }

    private fun <T : Any> access(
        route: AuditRoute,
        rawTarget: String?,
        read: () -> T?,
    ): T {
        val targetId = OperationsCursorCodec.uuidOrNull(rawTarget)
        val actor =
            try {
                identity?.currentUserId()
            } catch (exception: OperatorQueryException) {
                val authenticationFailure = exception.code == OperatorQueryError.OPERATOR_UNAUTHENTICATED
                append(route, targetId, null, if (authenticationFailure) AuditOutcome.DENIED else AuditOutcome.UNAVAILABLE)
                throw OperatorQueryException(
                    if (authenticationFailure) OperatorQueryError.OPERATOR_UNAUTHENTICATED else OperatorQueryError.OPERATOR_UNAVAILABLE,
                )
            } catch (_: Exception) {
                append(route, targetId, null, AuditOutcome.UNAVAILABLE)
                throw OperatorQueryException(OperatorQueryError.OPERATOR_UNAVAILABLE)
            }
        if (actor == null) {
            append(route, targetId, null, AuditOutcome.DENIED)
            throw OperatorQueryException(OperatorQueryError.OPERATOR_UNAUTHENTICATED)
        }
        if (!operatorAccess.canViewOperations(actor)) {
            append(route, targetId, actor, AuditOutcome.DENIED)
            throw OperatorQueryException(OperatorQueryError.OPERATOR_FORBIDDEN)
        }
        val result =
            try {
                read()
            } catch (exception: OperatorQueryException) {
                val outcome =
                    when (exception.code) {
                        OperatorQueryError.OPERATOR_INVALID_QUERY -> AuditOutcome.INVALID_QUERY
                        OperatorQueryError.OPERATOR_NOT_FOUND -> AuditOutcome.NOT_FOUND
                        else -> AuditOutcome.UNAVAILABLE
                    }
                append(route, targetId, actor, outcome)
                throw exception
            } catch (_: Exception) {
                append(route, targetId, actor, AuditOutcome.UNAVAILABLE)
                throw OperatorQueryException(OperatorQueryError.OPERATOR_UNAVAILABLE)
            }
        if (result == null) {
            append(route, targetId, actor, AuditOutcome.NOT_FOUND)
            throw OperatorQueryException(OperatorQueryError.OPERATOR_NOT_FOUND)
        }
        append(route, targetId, actor, AuditOutcome.ALLOWED)
        return result
    }

    private fun append(
        route: AuditRoute,
        targetId: UUID?,
        actor: UUID?,
        outcome: AuditOutcome,
    ) {
        try {
            audit.append(OperatorAccessAudit(UUID.randomUUID(), clock.instant(), actor, route, targetId, outcome))
        } catch (_: Exception) {
            throw OperatorQueryException(OperatorQueryError.OPERATOR_UNAVAILABLE)
        }
    }

    private fun target(value: String): UUID =
        OperationsCursorCodec.uuidOrNull(value) ?: throw OperatorQueryException(OperatorQueryError.OPERATOR_INVALID_QUERY)

    private fun pageSize(value: String?): Int {
        if (value == null) return 20
        val size = value.takeIf { it.length <= 3 }?.toIntOrNull()
        if (size == null || size !in 1..100) throw OperatorQueryException(OperatorQueryError.OPERATOR_INVALID_QUERY)
        return size
    }

    private fun pageCursor(value: String?): OperationsCursor? {
        if (value == null) return null
        return try {
            OperationsCursorCodec.decode(value)
        } catch (_: Exception) {
            throw OperatorQueryException(OperatorQueryError.OPERATOR_INVALID_QUERY)
        }
    }
}

@Service
class OperatorAuditRetentionService(
    private val audit: OperatorAccessAuditPort,
    private val properties: OperatorQueryProperties,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${meetme.operations.audit-cleanup-fixed-delay:PT1H}")
    fun cleanup(): Int {
        if (!properties.cleanupEnabled) return 0
        return try {
            audit.deleteBefore(clock.instant().minus(Duration.ofDays(properties.auditRetentionDays)), properties.auditCleanupBatchSize)
        } catch (_: Exception) {
            throw OperatorQueryException(OperatorQueryError.OPERATOR_UNAVAILABLE)
        }
    }
}
