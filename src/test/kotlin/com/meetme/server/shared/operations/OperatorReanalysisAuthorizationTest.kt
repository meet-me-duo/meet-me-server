package com.meetme.server.shared.operations

import com.meetme.server.config.OperatorQueryProperties
import com.meetme.server.coordination.application.port.input.OperatorReanalysisReceipt
import com.meetme.server.coordination.application.port.input.OperatorReanalysisRequest
import com.meetme.server.coordination.application.port.output.OperatorReanalysisCommandPort
import com.meetme.server.coordination.application.service.OperatorReanalysisService
import com.meetme.server.shared.adapter.input.web.operations.OperatorNoStoreFilter
import com.meetme.server.shared.application.port.input.operations.OperatorQueryError
import com.meetme.server.shared.application.port.input.operations.OperatorQueryException
import com.meetme.server.shared.application.port.output.operations.AuditOutcome
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAudit
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAuditPort
import com.meetme.server.shared.application.port.output.operations.OperatorIdentityPort
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue236: verified operator authorization and durable admission precede every command-side effect. */
class OperatorReanalysisAuthorizationTest {
    @Test
    fun `ORA01 missing identity denies before malformed target and lazy request validation`() {
        val fixture = Fixture(actor = { null })
        fixture.failure(OperatorQueryError.OPERATOR_UNAUTHENTICATED, "synthetic-private-target")
        fixture.assertNoCommandOrDecode()
        fixture.assertAudit(AuditOutcome.DENIED, null, null)
    }

    @Test
    fun `ORA02 missing identity adapter cannot grant permission`() {
        val fixture = Fixture(omitIdentityAdapter = true)
        fixture.failure(OperatorQueryError.OPERATOR_UNAUTHENTICATED)
        fixture.assertNoCommandOrDecode()
        fixture.assertAudit(AuditOutcome.DENIED, null)
    }

    @Test
    fun `ORA03 unlisted member cannot validate malformed target or lazy body`() {
        val fixture = Fixture(actor = { MEMBER })
        fixture.failure(OperatorQueryError.OPERATOR_FORBIDDEN, "synthetic-private-target")
        fixture.assertNoCommandOrDecode()
        fixture.assertAudit(AuditOutcome.DENIED, MEMBER, null)
    }

    @Test
    fun `ORA04 disabled feature and empty allowlist deny a verified account`() {
        listOf(
            OperatorQueryProperties(),
            OperatorQueryProperties(enabled = false, operatorUserIds = setOf(OPERATOR)),
            OperatorQueryProperties(enabled = true),
        ).forEach { properties ->
            val fixture = Fixture(properties = properties)
            fixture.failure(OperatorQueryError.OPERATOR_FORBIDDEN)
            fixture.assertNoCommandOrDecode()
            fixture.assertAudit(AuditOutcome.DENIED, OPERATOR)
        }
    }

    @Test
    fun `ORA05 typed invalid authentication remains unauthenticated`() {
        val fixture = Fixture(actor = { throw OperatorQueryException(OperatorQueryError.OPERATOR_UNAUTHENTICATED) })
        fixture.failure(OperatorQueryError.OPERATOR_UNAUTHENTICATED)
        fixture.assertNoCommandOrDecode()
        fixture.assertAudit(AuditOutcome.DENIED, null)
    }

    @Test
    fun `ORA06 authentication infrastructure errors remain unavailable without command access`() {
        listOf<() -> UUID?>(
            { throw OperatorQueryException(OperatorQueryError.OPERATOR_UNAVAILABLE) },
            { error("synthetic-private-authentication-detail") },
        ).forEach { actor ->
            val fixture = Fixture(actor = actor)
            fixture.failure(OperatorQueryError.OPERATOR_UNAVAILABLE)
            fixture.assertNoCommandOrDecode()
            fixture.assertAudit(AuditOutcome.UNAVAILABLE, null)
        }
    }

    @Test
    fun `ORA07 authorized valid request writes its one durable admission audit before command lookup`() {
        val fixture = Fixture()
        val receipt = fixture.invoke()
        assertEquals(RECEIPT, receipt)
        assertEquals(listOf("identity", "decode", "audit", "command"), fixture.events)
        assertEquals(listOf(Triple(OPERATOR, ROOM, REQUEST)), fixture.command.calls)
        fixture.assertAudit(AuditOutcome.ALLOWED, OPERATOR)
    }

    @Test
    fun `ORA08 authorized malformed target produces bounded invalid query without command access`() {
        val fixture = Fixture()
        fixture.failure(OperatorQueryError.OPERATOR_INVALID_QUERY, "synthetic-private-not-a-uuid")
        assertTrue(fixture.command.calls.isEmpty())
        fixture.assertAudit(AuditOutcome.INVALID_QUERY, OPERATOR, null)
    }

    @Test
    fun `ORA09 authorized lazy body validation failure is audited without command access`() {
        val fixture = Fixture()
        val failure =
            assertFailsWith<OperatorQueryException> {
                fixture.service.request(ROOM.toString()) {
                    fixture.events += "decode"
                    throw OperatorQueryException(OperatorQueryError.OPERATOR_INVALID_QUERY)
                }
            }
        assertEquals(OperatorQueryError.OPERATOR_INVALID_QUERY, failure.code)
        assertTrue(fixture.command.calls.isEmpty())
        fixture.assertAudit(AuditOutcome.INVALID_QUERY, OPERATOR)
    }

    @Test
    fun `ORA10 authorized origin denial is audited as denied without command access`() {
        val fixture = Fixture()
        val failure =
            assertFailsWith<OperatorQueryException> {
                fixture.service.request(ROOM.toString()) { throw OperatorQueryException(OperatorQueryError.OPERATOR_FORBIDDEN) }
            }
        assertEquals(OperatorQueryError.OPERATOR_FORBIDDEN, failure.code)
        assertTrue(fixture.command.calls.isEmpty())
        fixture.assertAudit(AuditOutcome.DENIED, OPERATOR)
    }

    @Test
    fun `ORA11 admission audit failure prevents every business lookup or write`() {
        val fixture = Fixture(auditFails = true)
        fixture.failure(OperatorQueryError.OPERATOR_UNAVAILABLE)
        assertEquals(1, fixture.audit.appendAttempts)
        assertTrue(fixture.command.calls.isEmpty())
        assertTrue(fixture.audit.rows.isEmpty())
    }

    @Test
    fun `ORA12 denied request audit failure fails closed as unavailable`() {
        val fixture = Fixture(actor = { MEMBER }, auditFails = true)
        fixture.failure(OperatorQueryError.OPERATOR_UNAVAILABLE)
        fixture.assertNoCommandOrDecode()
        assertEquals(1, fixture.audit.appendAttempts)
        assertTrue(fixture.audit.rows.isEmpty())
    }

    @Test
    fun `ORA13 command not found retains only the earlier admission audit`() {
        val fixture = Fixture()
        fixture.command.failure = OperatorQueryException(OperatorQueryError.OPERATOR_NOT_FOUND)
        fixture.failure(OperatorQueryError.OPERATOR_NOT_FOUND)
        assertEquals(listOf("identity", "decode", "audit", "command"), fixture.events)
        fixture.assertAudit(AuditOutcome.ALLOWED, OPERATOR)
    }

    @Test
    fun `ORA14 an unchanged request replay still rechecks current operator authorization`() {
        var currentActor: UUID? = OPERATOR
        val fixture = Fixture(actor = { currentActor })
        assertEquals(RECEIPT, fixture.invoke())
        currentActor = MEMBER
        fixture.failure(OperatorQueryError.OPERATOR_FORBIDDEN)
        assertEquals(1, fixture.command.calls.size)
        assertEquals(listOf(AuditOutcome.ALLOWED, AuditOutcome.DENIED), fixture.audit.rows.map { it.outcome })
        assertEquals(listOf(OPERATOR, MEMBER), fixture.audit.rows.map { it.actorUserId })
        assertNotEquals(fixture.audit.rows[0].id, fixture.audit.rows[1].id)
    }

    @Test
    fun `ORA15 access audit contains only server generated fixed metadata`() {
        val fixture = Fixture()
        fixture.invoke()
        val row = fixture.audit.rows.single()
        assertEquals("ROOM_REANALYSIS", row.route.name)
        assertEquals(NOW, row.occurredAt)
        assertNotEquals(UUID(0, 0), row.id)
        assertNotEquals(REQUEST.requestId, row.id)
        assertNotEquals(REQUEST.sourceAnalysisId, row.id)
        assertEquals(ROOM, row.targetId)
    }

    @ParameterizedTest
    @ValueSource(ints = [409, 429])
    fun `ORA16 new command conflict and rate statuses do not loosen old GET sanitizing`(untrustedStatus: Int) {
        val audit = RecordingAudit(mutableListOf(), false)
        val response = MockHttpServletResponse()
        OperatorNoStoreFilter(audit, Clock.fixed(NOW, ZoneOffset.UTC), OperatorIdentityPort { OPERATOR }).doFilter(
            MockHttpServletRequest("GET", "/api/operations/rooms/$ROOM"),
            response,
            FilterChain { _, downstream ->
                (downstream as HttpServletResponse).status = untrustedStatus
                downstream.writer.write("{\"code\":\"synthetic-private-error\",\"detail\":\"synthetic-private-body\"}")
            },
        )
        assertEquals(503, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertTrue(response.contentAsString.contains("OPERATOR_UNAVAILABLE"))
        assertTrue(!response.contentAsString.contains("synthetic-private"))
        assertEquals(1, audit.appendAttempts)
        assertEquals(AuditOutcome.UNAVAILABLE, audit.rows.single().outcome)
        assertEquals(
            "ROOM_DETAIL",
            audit.rows
                .single()
                .route.name,
        )
    }

    private class Fixture(
        actor: () -> UUID? = { OPERATOR },
        properties: OperatorQueryProperties = OperatorQueryProperties(enabled = true, operatorUserIds = setOf(OPERATOR)),
        auditFails: Boolean = false,
        omitIdentityAdapter: Boolean = false,
    ) {
        val events = mutableListOf<String>()
        val command = RecordingCommand(events)
        val audit = RecordingAudit(events, auditFails)
        val service =
            OperatorReanalysisService(
                command,
                audit,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                if (omitIdentityAdapter) {
                    null
                } else {
                    OperatorIdentityPort {
                        events += "identity"
                        actor()
                    }
                },
            )

        fun invoke(rawRoomId: String = ROOM.toString()): OperatorReanalysisReceipt =
            service.request(rawRoomId) {
                events += "decode"
                REQUEST
            }

        fun failure(
            expected: OperatorQueryError,
            rawRoomId: String = ROOM.toString(),
        ) {
            val failure = assertFailsWith<OperatorQueryException> { invoke(rawRoomId) }
            assertEquals(expected, failure.code)
            assertEquals(expected.name, failure.message)
        }

        fun assertNoCommandOrDecode() {
            assertTrue(command.calls.isEmpty())
            assertTrue("decode" !in events)
        }

        fun assertAudit(
            outcome: AuditOutcome,
            actor: UUID?,
            target: UUID? = ROOM,
        ) {
            assertEquals(1, audit.appendAttempts)
            val row = audit.rows.single()
            assertEquals("ROOM_REANALYSIS", row.route.name)
            assertEquals(outcome, row.outcome)
            assertEquals(actor, row.actorUserId)
            if (target == null) assertNull(row.targetId) else assertEquals(target, row.targetId)
            assertEquals(NOW, row.occurredAt)
        }
    }

    private class RecordingCommand(
        private val events: MutableList<String>,
    ) : OperatorReanalysisCommandPort {
        val calls = mutableListOf<Triple<UUID, UUID, OperatorReanalysisRequest>>()
        var failure: OperatorQueryException? = null

        override fun enqueue(
            actorId: UUID,
            roomId: UUID,
            request: OperatorReanalysisRequest,
        ): OperatorReanalysisReceipt {
            events += "command"
            calls += Triple(actorId, roomId, request)
            failure?.let { throw it }
            return RECEIPT
        }
    }

    private class RecordingAudit(
        private val events: MutableList<String>,
        private val fails: Boolean,
    ) : OperatorAccessAuditPort {
        val rows = mutableListOf<OperatorAccessAudit>()
        var appendAttempts = 0

        override fun append(event: OperatorAccessAudit) {
            events += "audit"
            appendAttempts++
            if (fails) error("synthetic-private-audit-error")
            rows += event
        }

        override fun deleteBefore(
            cutoff: Instant,
            limit: Int,
        ): Int = error("No audit retention mutation in this contract")
    }

    private companion object {
        val OPERATOR = UUID(0, 0x2361)
        val MEMBER = UUID(0, 0x2362)
        val ROOM = UUID(0, 0x2363)
        val NOW: Instant = Instant.parse("2039-02-03T04:05:06Z")
        val REQUEST = OperatorReanalysisRequest(UUID(0, 0x2364), UUID(0, 0x2365), 7)
        val RECEIPT =
            OperatorReanalysisReceipt(
                REQUEST.requestId,
                REQUEST.sourceAnalysisId,
                UUID(0, 0x2366),
                8,
                NOW,
                "schedule-dsl-evidence-response-6",
            )
    }
}
