package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.ScheduleDslProviderPort
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderRequest
import com.meetme.server.coordination.domain.dsl.ScheduleDslCalculationResult
import com.meetme.server.coordination.domain.ir.IrExecutionTicket
import java.time.Clock
import java.time.Duration

class ScheduleDslAnalysisWorker(
    private val service: ScheduleDslAnalysisService,
    private val provider: ScheduleDslProviderPort,
    private val clock: Clock,
) {
    fun process(
        ticket: IrExecutionTicket,
        timeout: Duration,
    ): ScheduleDslCalculationResult {
        require(!timeout.isNegative && !timeout.isZero)
        service.replayIfPresent(ticket)?.let { return it }
        val snapshot = service.input(ticket)
        val response = provider.generate(ScheduleDslProviderRequest(ticket, snapshot, timeout))
        return service.consume(ticket, response.responseJson, clock.instant())
    }
}
