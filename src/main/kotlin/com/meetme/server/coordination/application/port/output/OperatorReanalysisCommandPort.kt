package com.meetme.server.coordination.application.port.output

import com.meetme.server.coordination.application.port.input.OperatorReanalysisReceipt
import com.meetme.server.coordination.application.port.input.OperatorReanalysisRequest
import java.util.UUID

fun interface OperatorReanalysisCommandPort {
    fun enqueue(
        actorId: UUID,
        roomId: UUID,
        request: OperatorReanalysisRequest,
    ): OperatorReanalysisReceipt
}

data class OperatorReanalysisCommandRecord(
    val roomId: UUID,
    val actorId: UUID,
    val request: OperatorReanalysisRequest,
    val receipt: OperatorReanalysisReceipt,
)

interface OperatorReanalysisCommandRepository {
    fun hasOpenRevisionRound(roomId: UUID): Boolean

    fun find(
        roomId: UUID,
        requestId: UUID,
    ): OperatorReanalysisCommandRecord?

    fun insert(record: OperatorReanalysisCommandRecord)
}
