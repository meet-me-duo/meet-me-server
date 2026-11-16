package com.meetme.server.shared.application.port.output.operations

import com.meetme.server.shared.application.port.input.operations.OperationsCursor
import com.meetme.server.shared.application.port.input.operations.OperationsPage
import com.meetme.server.shared.application.port.input.operations.OperatorAnalysis
import com.meetme.server.shared.application.port.input.operations.OperatorRoomDiagnostics
import java.util.UUID

interface OperatorDiagnosticsRepository {
    fun analyses(
        roomId: UUID,
        limit: Int,
        cursor: OperationsCursor?,
    ): OperationsPage<OperatorAnalysis>?

    /** Null analysisId explicitly selects current collection; historical reads use exact frozen batch. */
    fun diagnostics(
        roomId: UUID,
        analysisId: UUID?,
    ): OperatorRoomDiagnostics?
}
