package com.meetme.server.shared.operations

import com.meetme.server.config.OperatorQueryProperties
import com.meetme.server.shared.application.port.input.operations.InvocationDetail
import com.meetme.server.shared.application.port.input.operations.OperationsCursor
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
import com.meetme.server.shared.application.service.operations.OperatorQueryService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Issue #110: authorization precedes parsing and lookup; every outcome requires durable audit. */
class OperatorQueryServiceTest {
    private val operator = UUID.fromString("00000000-0000-0000-0000-000000000110")
    private val target = UUID.fromString("00000000-0000-0000-0000-000000000210")
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `missing identity rejects every route before invalid query parsing or business lookup`() {
        routes().forEach { route ->
            val fixture = fixture(actor = null)
            failure(OperatorQueryError.OPERATOR_UNAUTHENTICATED) { route.second(fixture.service) }
            assertEquals(0, fixture.query.calls)
            assertAudit(fixture.audit, route.first, AuditOutcome.DENIED, null, null)
        }
    }

    @Test
    fun `unlisted authenticated account cannot gain operator permission from a valid target`() {
        val actor = UUID.fromString("00000000-0000-0000-0000-000000000999")
        val fixture = fixture(actor = actor)
        failure(OperatorQueryError.OPERATOR_FORBIDDEN) { fixture.service.roomDetail(target.toString()) }
        assertEquals(0, fixture.query.calls)
        assertAudit(fixture.audit, AuditRoute.ROOM_DETAIL, AuditOutcome.DENIED, actor, target)
    }

    @Test
    fun `disabled feature and empty allowlist deny even a verified operator`() {
        listOf(
            OperatorQueryProperties(enabled = false, operatorUserIds = setOf(operator)),
            OperatorQueryProperties(enabled = true),
            OperatorQueryProperties(),
        ).forEach { properties ->
            val fixture = fixture(properties = properties)
            failure(OperatorQueryError.OPERATOR_FORBIDDEN) { fixture.service.listRooms() }
            assertEquals(0, fixture.query.calls)
            assertAudit(fixture.audit, AuditRoute.ROOM_LIST, AuditOutcome.DENIED, operator, null)
        }
    }

    @Test
    fun `absent verified identity adapter fails closed`() {
        val query = QueryFake()
        val audit = AuditFake()
        val service = OperatorQueryService(query, audit, enabled(), clock)
        failure(OperatorQueryError.OPERATOR_UNAUTHENTICATED) { service.invocationDetail(target.toString()) }
        assertEquals(0, query.calls)
        assertAudit(audit, AuditRoute.INVOCATION_DETAIL, AuditOutcome.DENIED, null, target)
    }

    @Test
    fun `allowed list uses default bounded page and emits server generated audit`() {
        val fixture = fixture()
        assertEquals(OperationsPage<RoomSummary>(emptyList(), null), fixture.service.listRooms())
        assertEquals(listOf(20 to null), fixture.query.pages)
        assertAudit(fixture.audit, AuditRoute.ROOM_LIST, AuditOutcome.ALLOWED, operator, null)
    }

    @Test
    fun `page boundary limits one and one hundred are accepted`() {
        val fixture = fixture()
        fixture.service.listRooms("1")
        fixture.service.listRooms("100")
        assertEquals(listOf(1, 100), fixture.query.pages.map { it.first })
        assertEquals(listOf(AuditOutcome.ALLOWED, AuditOutcome.ALLOWED), fixture.audit.events.map { it.outcome })
        assertEquals(
            2,
            fixture.audit.events
                .map { it.id }
                .distinct()
                .size,
        )
    }

    @Test
    fun `invalid limits are rejected without business reads and without copying raw values into audit`() {
        listOf("0", "101", "-1", "1.5", "not-a-number", "999999999999999999999999").forEach { raw ->
            val fixture = fixture()
            failure(OperatorQueryError.OPERATOR_INVALID_QUERY) { fixture.service.listRooms(raw) }
            assertEquals(0, fixture.query.calls)
            assertAudit(fixture.audit, AuditRoute.ROOM_LIST, AuditOutcome.INVALID_QUERY, operator, null)
        }
    }

    @Test
    fun `invalid UUIDs and invalid or oversized cursors never reach repository`() {
        val invalidQueries: List<(OperatorQueryService) -> Any?> =
            listOf(
                { it.roomDetail("private-raw-room-input") },
                { it.invocationDetail("private-raw-invocation-input") },
                { it.listRooms(cursor = "!!not_base64!!") },
                { it.listRooms(cursor = "a".repeat(10000)) },
            )
        invalidQueries.forEach { action ->
            val fixture = fixture()
            failure(OperatorQueryError.OPERATOR_INVALID_QUERY) { action(fixture.service) }
            assertEquals(0, fixture.query.calls)
            assertEquals(
                AuditOutcome.INVALID_QUERY,
                fixture.audit.events
                    .single()
                    .outcome,
            )
            assertNull(
                fixture.audit.events
                    .single()
                    .targetId,
            )
        }
    }

    @Test
    fun `authorized missing room and invocation return not found with validated target audit`() {
        listOf(
            AuditRoute.ROOM_DETAIL to { service: OperatorQueryService -> service.roomDetail(target.toString()) },
            AuditRoute.INVOCATION_DETAIL to { service: OperatorQueryService -> service.invocationDetail(target.toString()) },
        ).forEach { (route, action) ->
            val fixture = fixture()
            failure(OperatorQueryError.OPERATOR_NOT_FOUND) { action(fixture.service) }
            assertEquals(1, fixture.query.calls)
            assertAudit(fixture.audit, route, AuditOutcome.NOT_FOUND, operator, target)
        }
    }

    @Test
    fun `repository exception becomes fixed unavailable error and bounded audit`() {
        val fixture = fixture()
        fixture.query.fail = true
        failure(OperatorQueryError.OPERATOR_UNAVAILABLE) { fixture.service.invocationDetail(target.toString()) }
        assertAudit(fixture.audit, AuditRoute.INVOCATION_DETAIL, AuditOutcome.UNAVAILABLE, operator, target)
    }

    @Test
    fun `audit failure prevents an allowed response and exposes no internal exception`() {
        val fixture = fixture()
        fixture.audit.fail = true
        failure(OperatorQueryError.OPERATOR_UNAVAILABLE) { fixture.service.listRooms() }
        assertEquals(1, fixture.audit.appendCalls)
    }

    @Test
    fun `denied request also fails closed if durable audit is unavailable`() {
        val fixture = fixture(actor = null)
        fixture.audit.fail = true
        failure(OperatorQueryError.OPERATOR_UNAVAILABLE) { fixture.service.roomDetail(target.toString()) }
        assertEquals(0, fixture.query.calls)
        assertEquals(1, fixture.audit.appendCalls)
    }

    private fun routes(): List<Pair<AuditRoute, (OperatorQueryService) -> Any?>> =
        listOf(
            AuditRoute.ROOM_LIST to { it.listRooms("secret-invalid-limit", "secret-invalid-cursor") },
            AuditRoute.ROOM_DETAIL to { it.roomDetail("secret-invalid-id", "secret-invalid-limit") },
            AuditRoute.INVOCATION_DETAIL to { it.invocationDetail("secret-invalid-id") },
        )

    private fun enabled() = OperatorQueryProperties(enabled = true, operatorUserIds = setOf(operator))

    private fun fixture(
        actor: UUID? = operator,
        properties: OperatorQueryProperties = enabled(),
    ): Fixture {
        val query = QueryFake()
        val audit = AuditFake()
        return Fixture(OperatorQueryService(query, audit, properties, clock, OperatorIdentityPort { actor }), query, audit)
    }

    private fun failure(
        code: OperatorQueryError,
        action: () -> Any?,
    ) {
        val exception = assertThrows(OperatorQueryException::class.java) { action() }
        assertEquals(code, exception.code)
        assertEquals(code.name, exception.message)
    }

    private fun assertAudit(
        audit: AuditFake,
        route: AuditRoute,
        outcome: AuditOutcome,
        actor: UUID?,
        target: UUID?,
    ) {
        val event = audit.events.single()
        assertEquals(route, event.route)
        assertEquals(outcome, event.outcome)
        assertEquals(actor, event.actorUserId)
        assertEquals(target, event.targetId)
        assertEquals(now, event.occurredAt)
        assertTrue(event.id != UUID(0, 0))
    }

    private data class Fixture(
        val service: OperatorQueryService,
        val query: QueryFake,
        val audit: AuditFake,
    )

    private class QueryFake : OperationsQueryRepository {
        var calls = 0
        var fail = false
        val pages = mutableListOf<Pair<Int, OperationsCursor?>>()

        override fun listRooms(
            limit: Int,
            cursor: OperationsCursor?,
        ): OperationsPage<RoomSummary> {
            record()
            pages += limit to cursor
            return OperationsPage(emptyList(), null)
        }

        override fun roomDetail(
            roomId: UUID,
            limit: Int,
            cursor: OperationsCursor?,
        ): RoomDetail? {
            record()
            return null
        }

        override fun invocationDetail(invocationId: UUID): InvocationDetail? {
            record()
            return null
        }

        private fun record() {
            calls++
            if (fail) error("private database exception with synthetic-secret")
        }
    }

    private class AuditFake : OperatorAccessAuditPort {
        var fail = false
        var appendCalls = 0
        val events = mutableListOf<OperatorAccessAudit>()

        override fun append(event: OperatorAccessAudit) {
            appendCalls++
            if (fail) error("private audit connection synthetic-secret")
            events += event
        }

        override fun deleteBefore(
            cutoff: Instant,
            limit: Int,
        ): Int = error("not used by query")
    }
}
