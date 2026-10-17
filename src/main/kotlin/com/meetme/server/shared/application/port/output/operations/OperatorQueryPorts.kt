package com.meetme.server.shared.application.port.output.operations

import com.meetme.server.shared.application.port.input.operations.InvocationDetail
import com.meetme.server.shared.application.port.input.operations.OperationsCursor
import com.meetme.server.shared.application.port.input.operations.OperationsPage
import com.meetme.server.shared.application.port.input.operations.RoomDetail
import com.meetme.server.shared.application.port.input.operations.RoomSummary
import java.time.Instant
import java.util.UUID

/** Supplied by verified account authentication, never by a caller-controlled identity header. */
fun interface OperatorIdentityPort {
    fun currentUserId(): UUID?
}

interface OperationsQueryRepository {
    fun listRooms(
        limit: Int,
        cursor: OperationsCursor?,
    ): OperationsPage<RoomSummary>

    fun roomDetail(
        roomId: UUID,
        limit: Int,
        cursor: OperationsCursor?,
    ): RoomDetail?

    fun invocationDetail(invocationId: UUID): InvocationDetail?
}

enum class AuditRoute { ROOM_LIST, ROOM_DETAIL, INVOCATION_DETAIL }

enum class AuditOutcome { ALLOWED, DENIED, NOT_FOUND, INVALID_QUERY, UNAVAILABLE }

data class OperatorAccessAudit(
    val id: UUID,
    val occurredAt: Instant,
    val actorUserId: UUID?,
    val route: AuditRoute,
    val targetId: UUID?,
    val outcome: AuditOutcome,
)

interface OperatorAccessAuditPort {
    fun append(event: OperatorAccessAudit)

    fun deleteBefore(
        cutoff: Instant,
        limit: Int,
    ): Int
}
