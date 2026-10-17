package com.meetme.server.shared.operations

import com.meetme.server.config.OperatorQueryProperties
import com.meetme.server.shared.adapter.input.web.operations.OperatorNoStoreFilter
import com.meetme.server.shared.adapter.input.web.operations.OperatorQueryController
import com.meetme.server.shared.adapter.input.web.operations.OperatorQueryExceptionHandler
import com.meetme.server.shared.application.port.input.operations.InvocationDetail
import com.meetme.server.shared.application.port.input.operations.OperationsCursor
import com.meetme.server.shared.application.port.input.operations.OperationsPage
import com.meetme.server.shared.application.port.input.operations.RoomDetail
import com.meetme.server.shared.application.port.input.operations.RoomSummary
import com.meetme.server.shared.application.port.output.operations.AuditOutcome
import com.meetme.server.shared.application.port.output.operations.OperationsQueryRepository
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAudit
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAuditPort
import com.meetme.server.shared.application.port.output.operations.OperatorIdentityPort
import com.meetme.server.shared.application.service.operations.OperatorQueryService
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Real controller/service/filter composition must audit once before releasing protected output. */
class OperatorControllerAuditBoundaryTest {
    @Test
    fun `allowed actual controller response stays successful and creates exactly one allowed audit`() {
        val audit = Audit()
        val response = mvc(audit).perform(get("/api/operations/rooms")).andReturn().response
        assertEquals(200, response.status, response.contentAsString)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals(listOf(AuditOutcome.ALLOWED), audit.events.map { it.outcome })
        assertEquals("{\"items\":[],\"next_cursor\":null}", response.contentAsString)
    }

    @Test
    fun `actual controller failures are sanitized with exactly one durable audit for each request`() {
        listOf(
            Triple("/api/operations/rooms?limit=101", 400, AuditOutcome.INVALID_QUERY),
            Triple("/api/operations/rooms/${UUID(0, 999)}", 404, AuditOutcome.NOT_FOUND),
        ).forEach { (path, status, outcome) ->
            val audit = Audit()
            val response = mvc(audit).perform(get(path)).andReturn().response
            assertEquals(status, response.status)
            assertEquals("no-store", response.getHeader("Cache-Control"))
            assertEquals(listOf(outcome), audit.events.map { it.outcome })
            assertFalse(response.contentAsString.contains("synthetic-private"))
        }
    }

    private fun mvc(audit: Audit) =
        MockMvcBuilders
            .standaloneSetup(
                OperatorQueryController(
                    OperatorQueryService(
                        Query(),
                        audit,
                        OperatorQueryProperties(
                            enabled = true,
                            operatorUserIds = setOf(ACTOR),
                        ),
                        CLOCK,
                        OperatorIdentityPort {
                            ACTOR
                        },
                    ),
                ),
            ).setControllerAdvice(
                OperatorQueryExceptionHandler(),
            ).addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(
                OperatorNoStoreFilter(
                    audit,
                    CLOCK,
                    OperatorIdentityPort {
                        ACTOR
                    },
                ),
            ).build()

    private class Query : OperationsQueryRepository {
        override fun listRooms(
            limit: Int,
            cursor: OperationsCursor?,
        ) = OperationsPage<RoomSummary>(emptyList(), null)

        override fun roomDetail(
            roomId: UUID,
            limit: Int,
            cursor: OperationsCursor?,
        ): RoomDetail? = null

        override fun invocationDetail(invocationId: UUID): InvocationDetail? = null
    }

    private class Audit : OperatorAccessAuditPort {
        val events = mutableListOf<OperatorAccessAudit>()

        override fun append(event: OperatorAccessAudit) {
            events += event
        }

        override fun deleteBefore(
            cutoff: Instant,
            limit: Int,
        ): Int = error("not used")
    }

    companion object {
        private val ACTOR = UUID(0, 110)
        private val CLOCK = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC)
    }
}
