package com.meetme.server.shared.operations

import com.meetme.server.shared.adapter.input.web.operations.OperatorNoStoreFilter
import com.meetme.server.shared.application.port.output.operations.AuditOutcome
import com.meetme.server.shared.application.port.output.operations.AuditRoute
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAudit
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAuditPort
import com.meetme.server.shared.application.port.output.operations.OperatorIdentityPort
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Issue #110/#109: pre-controller authentication failures need the same durable audit and privacy boundary. */
class OperatorNoStoreFilterTest {
    private val at = Instant.parse("2026-10-17T12:00:00Z")
    private val actor = UUID.fromString("00000000-0000-0000-0000-000000000110")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `precontroller unauthorized response is sanitized and audited as denied`() {
        val audit = AuditFake()
        val response = execute(401, audit, verifiedActor = null)
        assertProblem(response, 401, "OPERATOR_UNAUTHENTICATED")
        assertAudit(audit, AuditOutcome.DENIED, null)
    }

    @Test
    fun `precontroller authentication infrastructure failure is sanitized and audited as unavailable`() {
        val audit = AuditFake()
        val response = execute(503, audit)
        assertProblem(response, 503, "OPERATOR_UNAVAILABLE")
        assertAudit(audit, AuditOutcome.UNAVAILABLE, actor)
    }

    @Test
    fun `precontroller responses fail closed with sanitized unavailable when durable audit append fails`() {
        listOf(401, 503).forEach { downstreamStatus ->
            val audit = AuditFake(fail = true)
            val response = execute(downstreamStatus, audit)
            assertProblem(response, 503, "OPERATOR_UNAVAILABLE")
            assertEquals(1, audit.appendCalls)
            assertTrue(audit.events.isEmpty())
        }
    }

    @Test
    fun `unmarked successful response cannot expose protected data or bypass audit through a caller header`() {
        val audit = AuditFake()
        val response = execute(200, audit)
        assertProblem(response, 503, "OPERATOR_UNAVAILABLE")
        assertAudit(audit, AuditOutcome.UNAVAILABLE, actor)
    }

    @Test
    fun `server handled marker preserves safe controller success without a duplicate audit`() {
        val audit = AuditFake()
        val response = execute(200, audit, handled = true)
        assertEquals(200, response.status)
        assertEquals(SAFE_BODY, response.contentAsString)
        assertHeaders(response)
        assertEquals(0, audit.appendCalls)
        assertTrue(audit.events.isEmpty())
    }

    private fun execute(
        downstreamStatus: Int,
        audit: AuditFake,
        verifiedActor: UUID? = actor,
        handled: Boolean = false,
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest("GET", "/api/operations/rooms")
        request.queryString = "code=synthetic-private-code&state=synthetic-private-state"
        request.addHeader("Authorization", "Bearer synthetic-private-token")
        request.addHeader(OperatorNoStoreFilter.AUDIT_HANDLED_ATTRIBUTE, "true")
        val response = MockHttpServletResponse()
        val chain =
            FilterChain { downstreamRequest, downstreamResponse ->
                if (handled) downstreamRequest.setAttribute(OperatorNoStoreFilter.AUDIT_HANDLED_ATTRIBUTE, true)
                val http = downstreamResponse as HttpServletResponse
                http.status = downstreamStatus
                http.contentType = "application/json"
                http.characterEncoding = "UTF-8"
                http.writer.write(if (handled) SAFE_BODY else "synthetic-private-code-state-body")
                http.writer.flush()
            }
        OperatorNoStoreFilter(audit, clock, OperatorIdentityPort { verifiedActor }).doFilter(request, response, chain)
        return response
    }

    private fun assertHeaders(response: MockHttpServletResponse) {
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"))
    }

    private fun assertProblem(
        response: MockHttpServletResponse,
        status: Int,
        code: String,
    ) {
        assertEquals(status, response.status)
        assertHeaders(response)
        assertTrue(response.contentType.orEmpty().startsWith("application/problem+json"))
        val document = mapper.readValue(response.contentAsString, Map::class.java)
        assertEquals(setOf("code", "title", "status", "detail"), document.keys)
        assertEquals(code, document["code"])
        assertEquals(status, (document["status"] as Number).toInt())
        assertTrue(document["title"].toString().isNotBlank())
        assertTrue(document["detail"].toString().isNotBlank())
        assertFalse(response.contentAsString.contains("synthetic-private"))
        assertFalse(response.contentAsString.contains("/api/operations/rooms"))
    }

    private fun assertAudit(
        audit: AuditFake,
        outcome: AuditOutcome,
        verifiedActor: UUID?,
    ) {
        assertEquals(1, audit.appendCalls)
        val event = audit.events.single()
        assertEquals(AuditRoute.ROOM_LIST, event.route)
        assertEquals(outcome, event.outcome)
        assertEquals(verifiedActor, event.actorUserId)
        assertEquals(at, event.occurredAt)
        assertNull(event.targetId)
        assertTrue(event.id != UUID(0, 0))
    }

    private class AuditFake(
        private val fail: Boolean = false,
    ) : OperatorAccessAuditPort {
        var appendCalls = 0
        val events = mutableListOf<OperatorAccessAudit>()

        override fun append(event: OperatorAccessAudit) {
            appendCalls++
            if (fail) error("synthetic-private-audit-connection")
            events += event
        }

        override fun deleteBefore(
            cutoff: Instant,
            limit: Int,
        ): Int = error("not used by filter")
    }

    companion object {
        private const val SAFE_BODY = "{\"items\":[],\"next_cursor\":null}"
    }
}
