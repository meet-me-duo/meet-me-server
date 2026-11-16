package com.meetme.server.shared.operations

import com.meetme.server.config.OperatorQueryProperties
import com.meetme.server.shared.application.port.input.operations.InvocationDetail
import com.meetme.server.shared.application.port.input.operations.OperationsCursor
import com.meetme.server.shared.application.port.input.operations.OperationsCursorCodec
import com.meetme.server.shared.application.port.input.operations.OperationsPage
import com.meetme.server.shared.application.port.input.operations.OperatorAnalysis
import com.meetme.server.shared.application.port.input.operations.OperatorQueryError
import com.meetme.server.shared.application.port.input.operations.OperatorQueryException
import com.meetme.server.shared.application.port.input.operations.OperatorRoomDiagnostics
import com.meetme.server.shared.application.port.input.operations.RoomDetail
import com.meetme.server.shared.application.port.input.operations.RoomSummary
import com.meetme.server.shared.application.port.output.operations.AuditOutcome
import com.meetme.server.shared.application.port.output.operations.AuditRoute
import com.meetme.server.shared.application.port.output.operations.OperationsQueryRepository
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAudit
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAuditPort
import com.meetme.server.shared.application.port.output.operations.OperatorDiagnosticsRepository
import com.meetme.server.shared.application.port.output.operations.OperatorIdentityPort
import com.meetme.server.shared.application.service.operations.OperatorQueryService
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OPS241: privileged reads retain the real operator, durable audit and exact selection boundaries. */
class OperatorDiagnosticsAuthorizationTest {
    enum class Read {
        HISTORY,
        DIAGNOSTICS,
    }

    @ParameterizedTest
    @EnumSource(Read::class)
    fun `anonymous caller is rejected before malformed targets or query values`(read: Read) {
        val fixture = fixture(actor = null)
        failure(OperatorQueryError.OPERATOR_UNAUTHENTICATED) { invalidRead(fixture.service, read) }
        fixture.assertNoLookup()
        fixture.assertAudit(AuditOutcome.DENIED, actor = null, target = null)
        assertEquals(1, fixture.identity.calls)
    }

    @ParameterizedTest
    @EnumSource(Read::class)
    fun `unlisted authenticated caller cannot use malformed query to bypass authorization`(read: Read) {
        val fixture = fixture(actor = OTHER)
        failure(OperatorQueryError.OPERATOR_FORBIDDEN) { invalidRead(fixture.service, read) }
        fixture.assertNoLookup()
        fixture.assertAudit(AuditOutcome.DENIED, actor = OTHER, target = null)
    }

    @ParameterizedTest
    @EnumSource(Read::class)
    fun `disabled capability and empty allowlist deny a verified operator`(read: Read) {
        listOf(
            OperatorQueryProperties(enabled = false, operatorUserIds = setOf(ACTOR)),
            OperatorQueryProperties(enabled = true),
            OperatorQueryProperties(),
        ).forEach { properties ->
            val fixture = fixture(properties = properties)
            failure(OperatorQueryError.OPERATOR_FORBIDDEN) { validRead(fixture.service, read) }
            fixture.assertNoLookup()
            fixture.assertAudit(AuditOutcome.DENIED)
        }
    }

    @ParameterizedTest
    @EnumSource(Read::class)
    fun `missing verified identity adapter is fail closed`(read: Read) {
        val fixture = fixture(identityPresent = false)
        failure(OperatorQueryError.OPERATOR_UNAUTHENTICATED) { validRead(fixture.service, read) }
        fixture.assertNoLookup()
        fixture.assertAudit(AuditOutcome.DENIED, actor = null)
        assertEquals(0, fixture.identity.calls)
    }

    @ParameterizedTest
    @EnumSource(Read::class)
    fun `failed authentication stays unauthorized while identity infrastructure failure stays unavailable`(read: Read) {
        listOf(
            OperatorQueryException(OperatorQueryError.OPERATOR_UNAUTHENTICATED) to OperatorQueryError.OPERATOR_UNAUTHENTICATED,
            OperatorQueryException(OperatorQueryError.OPERATOR_FORBIDDEN) to OperatorQueryError.OPERATOR_UNAVAILABLE,
            IllegalStateException("synthetic-private identity detail") to OperatorQueryError.OPERATOR_UNAVAILABLE,
        ).forEach { (exception, expected) ->
            val fixture = fixture()
            fixture.identity.failure = exception
            failure(expected) { invalidRead(fixture.service, read) }
            fixture.assertNoLookup()
            fixture.assertAudit(
                if (expected == OperatorQueryError.OPERATOR_UNAUTHENTICATED) AuditOutcome.DENIED else AuditOutcome.UNAVAILABLE,
                actor = null,
                target = null,
            )
        }
    }

    @ParameterizedTest
    @EnumSource(Read::class)
    fun `valid authorized read records exactly one durable room detail audit before returning`(read: Read) {
        val fixture = fixture()
        val result = validRead(fixture.service, read)
        assertEquals(if (read == Read.HISTORY) fixture.repository.historyResult else fixture.repository.diagnosticsResult, result)
        fixture.assertAudit(AuditOutcome.ALLOWED)
        assertEquals(1, fixture.repository.calls)
        assertEquals(0, fixture.legacy.calls)
        assertEquals(1, fixture.identity.calls)
    }

    @ParameterizedTest
    @EnumSource(Read::class)
    fun `malformed canonical room identifiers fail only after authorization without business reads`(read: Read) {
        listOf("", "not-a-room", "1-1-1-1-1", " $ROOM", "$ROOM ", "x".repeat(1000)).forEach { raw ->
            val fixture = fixture()
            failure(OperatorQueryError.OPERATOR_INVALID_QUERY) { validRead(fixture.service, read, raw) }
            fixture.assertNoLookup()
            fixture.assertAudit(AuditOutcome.INVALID_QUERY, target = null)
            assertEquals(1, fixture.identity.calls)
        }
    }

    @ParameterizedTest
    @EnumSource(Read::class)
    fun `canonical uppercase room UUID is accepted without changing its identity`(read: Read) {
        val fixture = fixture()
        validRead(fixture.service, read, ROOM.toString().uppercase())
        assertEquals(listOf(ROOM), fixture.repository.rooms)
        fixture.assertAudit(AuditOutcome.ALLOWED)
    }

    @ParameterizedTest
    @EnumSource(Read::class)
    fun `missing room or selected record is not found without consulting other routes`(read: Read) {
        val fixture = fixture()
        fixture.repository.missing = true
        failure(OperatorQueryError.OPERATOR_NOT_FOUND) { validRead(fixture.service, read) }
        fixture.assertAudit(AuditOutcome.NOT_FOUND)
        assertEquals(1, fixture.repository.calls)
        assertEquals(if (read == Read.HISTORY) 1 else 0, fixture.repository.historyCalls.size)
        assertEquals(if (read == Read.DIAGNOSTICS) 1 else 0, fixture.repository.diagnosticCalls.size)
        assertEquals(0, fixture.legacy.calls)
    }

    @ParameterizedTest
    @EnumSource(Read::class)
    fun `query infrastructure failure has a fixed public error and no alternate data source`(read: Read) {
        val fixture = fixture()
        fixture.repository.failure = IllegalStateException("synthetic-private db source and model payload")
        failure(OperatorQueryError.OPERATOR_UNAVAILABLE) { validRead(fixture.service, read) }
        fixture.assertAudit(AuditOutcome.UNAVAILABLE)
        assertEquals(1, fixture.repository.calls)
        assertEquals(0, fixture.legacy.calls)
    }

    @ParameterizedTest
    @EnumSource(Read::class)
    fun `absent diagnostics adapter is unavailable rather than a legacy or empty-success fallback`(read: Read) {
        val fixture = fixture(repositoryPresent = false)
        failure(OperatorQueryError.OPERATOR_UNAVAILABLE) { validRead(fixture.service, read) }
        fixture.assertNoLookup()
        fixture.assertAudit(AuditOutcome.UNAVAILABLE)
    }

    @ParameterizedTest
    @EnumSource(Read::class)
    fun `audit outage prevents release of an otherwise successful protected view`(read: Read) {
        val fixture = fixture()
        fixture.audit.fail = true
        failure(OperatorQueryError.OPERATOR_UNAVAILABLE) { validRead(fixture.service, read) }
        assertEquals(1, fixture.audit.appendCalls)
        assertTrue(fixture.audit.events.isEmpty())
        assertEquals(0, fixture.legacy.calls)
    }

    @ParameterizedTest
    @EnumSource(Read::class)
    fun `audit outage on a denied request also fails closed`(read: Read) {
        val fixture = fixture(actor = null)
        fixture.audit.fail = true
        failure(OperatorQueryError.OPERATOR_UNAVAILABLE) { validRead(fixture.service, read) }
        fixture.assertNoLookup()
        assertEquals(1, fixture.audit.appendCalls)
    }

    @ParameterizedTest
    @EnumSource(Read::class)
    fun `every read verifies identity again and cannot reuse previous operator authorization`(read: Read) {
        val fixture = fixture()
        validRead(fixture.service, read)
        fixture.identity.actor = OTHER
        failure(OperatorQueryError.OPERATOR_FORBIDDEN) { validRead(fixture.service, read) }
        assertEquals(2, fixture.identity.calls)
        assertEquals(1, fixture.repository.calls)
        assertEquals(listOf(AuditOutcome.ALLOWED, AuditOutcome.DENIED), fixture.audit.events.map { it.outcome })
        assertEquals(listOf(ACTOR, OTHER), fixture.audit.events.map { it.actorUserId })
        assertNotEquals(fixture.audit.events[0].id, fixture.audit.events[1].id)
    }

    @Test
    fun `history defaults to twenty and forwards bounded page boundaries once`() {
        val fixture = fixture()
        fixture.service.analyses(ROOM.toString())
        fixture.service.analyses(ROOM.toString(), "1")
        fixture.service.analyses(ROOM.toString(), "100")
        assertEquals(listOf(20, 1, 100), fixture.repository.historyCalls.map { it.first })
        assertTrue(fixture.repository.historyCalls.all { it.second == null })
        assertTrue(fixture.repository.diagnosticCalls.isEmpty())
        assertEquals(0, fixture.legacy.calls)
        assertEquals(3, fixture.audit.events.size)
    }

    @Test
    fun `history rejects invalid page sizes without querying diagnostic data`() {
        listOf("", "0", "101", "-1", "1.5", "raw-input", "999999999999999999").forEach { limit ->
            val fixture = fixture()
            failure(OperatorQueryError.OPERATOR_INVALID_QUERY) { fixture.service.analyses(ROOM.toString(), limit) }
            fixture.assertNoLookup()
            fixture.assertAudit(AuditOutcome.INVALID_QUERY)
        }
    }

    @Test
    fun `history forwards the exact decoded cursor rather than choosing latest analysis`() {
        val fixture = fixture()
        val cursor = OperationsCursor(NOW.minusSeconds(73), ANALYSIS)
        fixture.service.analyses(ROOM.toString(), "1", OperationsCursorCodec.encode(cursor))
        assertEquals(listOf<Pair<Int, OperationsCursor?>>(1 to cursor), fixture.repository.historyCalls)
        assertTrue(fixture.repository.diagnosticCalls.isEmpty())
        fixture.assertAudit(AuditOutcome.ALLOWED)
    }

    @Test
    fun `malformed or oversized history cursor cannot trigger a repository lookup`() {
        listOf("", "!!", "a".repeat(257), cursor("not-an-instant|$ANALYSIS"), cursor("$NOW|1-1-1-1-1"), cursor("$NOW|$ANALYSIS|extra"))
            .forEach { raw ->
                val fixture = fixture()
                failure(OperatorQueryError.OPERATOR_INVALID_QUERY) { fixture.service.analyses(ROOM.toString(), cursor = raw) }
                fixture.assertNoLookup()
                fixture.assertAudit(AuditOutcome.INVALID_QUERY)
            }
    }

    @Test
    fun `omitted analysis explicitly requests current collection without discovering latest run`() {
        val fixture = fixture()
        val result = fixture.service.diagnostics(ROOM.toString())
        assertEquals("CURRENT_COLLECTION", result.scope)
        assertNull(result.analysis)
        assertEquals(listOf<UUID?>(null), fixture.repository.diagnosticCalls)
        assertTrue(fixture.repository.historyCalls.isEmpty())
        assertEquals(0, fixture.legacy.calls)
        fixture.assertAudit(AuditOutcome.ALLOWED)
    }

    @Test
    fun `explicit analysis is forwarded unchanged and does not select current collection`() {
        val fixture = fixture()
        fixture.repository.diagnosticsResult = diagnostic(OperatorAnalysis(ANALYSIS, "DONE", NOW, NOW, false, "V5"))
        assertEquals(fixture.repository.diagnosticsResult, fixture.service.diagnostics(ROOM.toString(), ANALYSIS.toString().uppercase()))
        assertEquals(listOf<UUID?>(ANALYSIS), fixture.repository.diagnosticCalls)
        assertTrue(fixture.repository.historyCalls.isEmpty())
        fixture.assertAudit(AuditOutcome.ALLOWED)
    }

    @Test
    fun `malformed analysis selection is invalid rather than silently selecting current collection`() {
        listOf("", "null", "1-1-1-1-1", " $ANALYSIS", "$ANALYSIS ", "x".repeat(1000)).forEach { raw ->
            val fixture = fixture()
            failure(OperatorQueryError.OPERATOR_INVALID_QUERY) { fixture.service.diagnostics(ROOM.toString(), raw) }
            fixture.assertNoLookup()
            fixture.assertAudit(AuditOutcome.INVALID_QUERY)
        }
    }

    @Test
    fun `unknown explicit analysis returns not found rather than retrying a different selection`() {
        val fixture = fixture()
        fixture.repository.missing = true
        failure(OperatorQueryError.OPERATOR_NOT_FOUND) { fixture.service.diagnostics(ROOM.toString(), ANALYSIS.toString()) }
        assertEquals(listOf<UUID?>(ANALYSIS), fixture.repository.diagnosticCalls)
        assertEquals(listOf(ROOM), fixture.repository.rooms)
        assertTrue(fixture.repository.historyCalls.isEmpty())
        assertEquals(0, fixture.legacy.calls)
        fixture.assertAudit(AuditOutcome.NOT_FOUND)
    }

    private fun invalidRead(
        service: OperatorQueryService,
        read: Read,
    ): Any =
        when (read) {
            Read.HISTORY -> service.analyses("private-invalid-room", "private-invalid-limit", "private-invalid-cursor")
            Read.DIAGNOSTICS -> service.diagnostics("private-invalid-room", "private-invalid-analysis")
        }

    private fun validRead(
        service: OperatorQueryService,
        read: Read,
        room: String = ROOM.toString(),
    ): Any =
        when (read) {
            Read.HISTORY -> service.analyses(room)
            Read.DIAGNOSTICS -> service.diagnostics(room)
        }

    private fun failure(
        code: OperatorQueryError,
        action: () -> Any?,
    ) {
        val exception = assertFailsWith<OperatorQueryException> { action() }
        assertEquals(code, exception.code)
        assertEquals(code.name, exception.message)
        assertNull(exception.cause)
    }

    private fun fixture(
        actor: UUID? = ACTOR,
        properties: OperatorQueryProperties = OperatorQueryProperties(enabled = true, operatorUserIds = setOf(ACTOR)),
        identityPresent: Boolean = true,
        repositoryPresent: Boolean = true,
    ): Fixture {
        val legacy = LegacyQuery()
        val repository = DiagnosticsQuery()
        val audit = Audit()
        val identity = Identity(actor)
        val service =
            OperatorQueryService(
                legacy,
                audit,
                properties,
                CLOCK,
                identity = identity.takeIf { identityPresent },
                diagnostics = repository.takeIf { repositoryPresent },
            )
        return Fixture(service, legacy, repository, audit, identity)
    }

    private data class Fixture(
        val service: OperatorQueryService,
        val legacy: LegacyQuery,
        val repository: DiagnosticsQuery,
        val audit: Audit,
        val identity: Identity,
    ) {
        fun assertNoLookup() {
            assertEquals(0, legacy.calls)
            assertEquals(0, repository.calls)
        }

        fun assertAudit(
            outcome: AuditOutcome,
            actor: UUID? = ACTOR,
            target: UUID? = ROOM,
        ) {
            assertEquals(1, audit.appendCalls)
            val event = audit.events.single()
            assertEquals(AuditRoute.ROOM_DETAIL, event.route)
            assertEquals(outcome, event.outcome)
            assertEquals(actor, event.actorUserId)
            assertEquals(target, event.targetId)
            assertEquals(NOW, event.occurredAt)
            assertNotEquals(UUID(0, 0), event.id)
        }
    }

    private class Identity(
        var actor: UUID?,
    ) : OperatorIdentityPort {
        var calls = 0
        var failure: Exception? = null

        override fun currentUserId(): UUID? {
            calls++
            failure?.let { throw it }
            return actor
        }
    }

    private class Audit : OperatorAccessAuditPort {
        val events = mutableListOf<OperatorAccessAudit>()
        var appendCalls = 0
        var fail = false

        override fun append(event: OperatorAccessAudit) {
            appendCalls++
            if (fail) error("synthetic-private audit infrastructure")
            events += event
        }

        override fun deleteBefore(
            cutoff: Instant,
            limit: Int,
        ): Int = error("not a read use case")
    }

    private class DiagnosticsQuery : OperatorDiagnosticsRepository {
        val rooms = mutableListOf<UUID>()
        val historyCalls = mutableListOf<Pair<Int, OperationsCursor?>>()
        val diagnosticCalls = mutableListOf<UUID?>()
        val calls: Int get() = rooms.size
        var missing = false
        var failure: Exception? = null
        val historyResult = OperationsPage<OperatorAnalysis>(emptyList(), null)
        var diagnosticsResult = diagnostic()

        override fun analyses(
            roomId: UUID,
            limit: Int,
            cursor: OperationsCursor?,
        ): OperationsPage<OperatorAnalysis>? {
            rooms += roomId
            historyCalls += limit to cursor
            failure?.let { throw it }
            return historyResult.takeUnless { missing }
        }

        override fun diagnostics(
            roomId: UUID,
            analysisId: UUID?,
        ): OperatorRoomDiagnostics? {
            rooms += roomId
            diagnosticCalls += analysisId
            failure?.let { throw it }
            return diagnosticsResult.takeUnless { missing }
        }
    }

    private class LegacyQuery : OperationsQueryRepository {
        var calls = 0

        override fun listRooms(
            limit: Int,
            cursor: OperationsCursor?,
        ): OperationsPage<RoomSummary> {
            calls++
            error("diagnostics cannot fall back to the room list")
        }

        override fun roomDetail(
            roomId: UUID,
            limit: Int,
            cursor: OperationsCursor?,
        ): RoomDetail? {
            calls++
            error("diagnostics cannot fall back to lightweight room detail")
        }

        override fun invocationDetail(invocationId: UUID): InvocationDetail? {
            calls++
            error("diagnostics cannot fall back to invocation detail")
        }
    }

    companion object {
        private val ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000110")
        private val OTHER = UUID.fromString("00000000-0000-0000-0000-000000000999")
        private val ROOM = UUID.fromString("af000000-0000-0000-0000-000000000210")
        private val ANALYSIS = UUID.fromString("af000000-0000-0000-0000-000000000310")
        private val NOW = Instant.parse("2044-02-29T12:00:00Z")
        private val CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)

        private fun cursor(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

        private fun diagnostic(analysis: OperatorAnalysis? = null) =
            OperatorRoomDiagnostics(
                ROOM,
                if (analysis == null) "CURRENT_COLLECTION" else "FROZEN_ANALYSIS",
                analysis,
                emptyList(),
                false,
                emptyList(),
                false,
                emptyList(),
                false,
                emptyList(),
                false,
            )
    }
}
