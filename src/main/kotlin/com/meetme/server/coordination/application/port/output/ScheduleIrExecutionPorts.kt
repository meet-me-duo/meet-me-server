package com.meetme.server.coordination.application.port.output

import com.meetme.server.coordination.domain.ir.IrExecutionTicket
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.ir.ScheduleIrEnvelope
import java.util.UUID

interface ScheduleIrExecutionRegistry {
    fun bind(ticket: IrExecutionTicket)

    fun find(runId: UUID): IrExecutionTicket?

    fun requireCurrent(ticket: IrExecutionTicket)
}

interface ScheduleIrResponseParser {
    val supportedResponseContractVersion: String

    fun parse(
        responseJson: String,
        snapshot: ScheduleEvidenceSnapshot,
        responseContractVersion: String,
        semanticVersion: String,
    ): ScheduleIrEnvelope
}
