package com.meetme.server.shared.operations

import com.meetme.server.config.OperatorQueryProperties
import com.meetme.server.shared.adapter.input.web.operations.OperatorNoStoreFilter
import com.meetme.server.shared.adapter.input.web.operations.OperatorQueryController
import com.meetme.server.shared.adapter.input.web.operations.OperatorQueryExceptionHandler
import com.meetme.server.shared.application.port.input.operations.InvocationDetail
import com.meetme.server.shared.application.port.input.operations.OperationsCursor
import com.meetme.server.shared.application.port.input.operations.OperationsPage
import com.meetme.server.shared.application.port.input.operations.OperatorAnalysis
import com.meetme.server.shared.application.port.input.operations.OperatorInput
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
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** OPS241: real HTTP adapters retain fail-closed audit and no-store for newly privileged reads. */
class OperatorDiagnosticsHttpSecurityTest {
    @ParameterizedTest
    @ValueSource(strings = ["analyses", "diagnostics"])
    fun `new GET routes return structured projection with exactly one durable room audit`(route: String) {
        val fixture = fixture()
        val response =
            fixture.mvc
                .perform(get(path(route)))
                .andReturn()
                .response
        assertEquals(200, response.status, response.contentAsString)
        noStore(response)
        fixture.assertAudit(AuditOutcome.ALLOWED)
        assertEquals(1, fixture.repository.calls)
        assertEquals(0, fixture.legacy.calls)
        val json = JSON.readTree(response.contentAsString)
        if (route == "analyses") {
            assertEquals(setOf("items", "next_cursor"), json.propertyNames().toSet())
            assertEquals(0, json["items"].size())
            assertTrue(json["next_cursor"].isNull)
            assertFalse(response.contentAsString.contains("raw_text"))
        } else {
            assertEquals(ROOM.toString(), json["room_id"].asText())
            assertEquals("CURRENT_COLLECTION", json["scope"].asText())
            assertTrue(json["analysis"].isNull)
            assertEquals("가*", json["inputs"][0]["display_name"].asText())
            assertEquals(PROTECTED_TEXT, json["inputs"][0]["raw_text"].asText())
            assertEquals(
                setOf(
                    "room_id",
                    "scope",
                    "analysis",
                    "inputs",
                    "inputs_truncated",
                    "invocations",
                    "invocations_truncated",
                    "timeline",
                    "timeline_truncated",
                    "results",
                    "results_truncated",
                ),
                json.propertyNames().toSet(),
            )
        }
        listOf("owner_binding", "access_token", "refresh_token", "raw_response", "full_prompt", "cookie", "invite_code")
            .forEach { assertFalse(response.contentAsString.contains(it), it) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["analyses", "diagnostics"])
    fun `anonymous HTTP request stays unauthorized before malformed target and query`(route: String) {
        val fixture = fixture(actor = null)
        val response =
            fixture.mvc
                .perform(
                    get("/api/operations/rooms/not-a-uuid/$route?analysis_id=invalid&limit=invalid&cursor=invalid"),
                ).andReturn()
                .response
        assertError(response, 401, "OPERATOR_UNAUTHENTICATED")
        fixture.assertAudit(AuditOutcome.DENIED, actor = null, target = null)
        assertEquals(0, fixture.repository.calls)
        assertEquals(0, fixture.legacy.calls)
    }

    @ParameterizedTest
    @ValueSource(strings = ["analyses", "diagnostics"])
    fun `authenticated nonoperator HTTP request is forbidden without exposing target existence`(route: String) {
        val fixture = fixture(actor = OTHER)
        val response =
            fixture.mvc
                .perform(get(path(route)))
                .andReturn()
                .response
        assertError(response, 403, "OPERATOR_FORBIDDEN")
        fixture.assertAudit(AuditOutcome.DENIED, actor = OTHER)
        assertEquals(0, fixture.repository.calls)
        assertEquals(0, fixture.legacy.calls)
    }

    @ParameterizedTest
    @ValueSource(strings = ["analyses", "diagnostics"])
    fun `authorized malformed request is bad query and never releases input data`(route: String) {
        val fixture = fixture()
        val suffix = if (route == "analyses") "?limit=101" else "?analysis_id=1-1-1-1-1"
        val response =
            fixture.mvc
                .perform(get(path(route) + suffix))
                .andReturn()
                .response
        assertError(response, 400, "OPERATOR_INVALID_QUERY")
        fixture.assertAudit(AuditOutcome.INVALID_QUERY)
        assertEquals(0, fixture.repository.calls)
        assertEquals(0, fixture.legacy.calls)
    }

    @ParameterizedTest
    @ValueSource(strings = ["analyses", "diagnostics"])
    fun `missing selected data returns safe not found instead of a current or legacy fallback`(route: String) {
        val fixture = fixture()
        fixture.repository.missing = true
        val suffix = if (route == "diagnostics") "?analysis_id=$ANALYSIS" else ""
        val response =
            fixture.mvc
                .perform(get(path(route) + suffix))
                .andReturn()
                .response
        assertError(response, 404, "OPERATOR_NOT_FOUND")
        fixture.assertAudit(AuditOutcome.NOT_FOUND)
        assertEquals(1, fixture.repository.calls)
        assertEquals(0, fixture.legacy.calls)
        if (route == "diagnostics") assertEquals(listOf<UUID?>(ANALYSIS), fixture.repository.selectedAnalyses)
    }

    @ParameterizedTest
    @ValueSource(strings = ["analyses", "diagnostics"])
    fun `repository infrastructure errors are sanitized and audited once`(route: String) {
        val fixture = fixture()
        fixture.repository.fail = true
        val response =
            fixture.mvc
                .perform(get(path(route)))
                .andReturn()
                .response
        assertError(response, 503, "OPERATOR_UNAVAILABLE")
        fixture.assertAudit(AuditOutcome.UNAVAILABLE)
        assertEquals(0, fixture.legacy.calls)
    }

    @ParameterizedTest
    @ValueSource(strings = ["analyses", "diagnostics"])
    fun `durable audit failure blocks otherwise available projection and is not retried by filter`(route: String) {
        val fixture = fixture()
        fixture.audit.fail = true
        val response =
            fixture.mvc
                .perform(get(path(route)))
                .andReturn()
                .response
        assertError(response, 503, "OPERATOR_UNAVAILABLE")
        assertEquals(1, fixture.audit.appendCalls)
        assertTrue(fixture.audit.events.isEmpty())
        assertEquals(0, fixture.legacy.calls)
    }

    @ParameterizedTest
    @ValueSource(strings = ["analyses", "diagnostics"])
    fun `identity verification outage exposes neither raw error nor diagnostic projection`(route: String) {
        val fixture = fixture(identityFailure = true)
        val response =
            fixture.mvc
                .perform(get(path(route)))
                .andReturn()
                .response
        assertError(response, 503, "OPERATOR_UNAVAILABLE")
        fixture.assertAudit(AuditOutcome.UNAVAILABLE, actor = null)
        assertEquals(0, fixture.repository.calls)
        assertEquals(0, fixture.legacy.calls)
    }

    @Test
    fun `analysis id query is passed explicitly while omission selects current collection`() {
        val fixture = fixture()
        assertEquals(
            200,
            fixture.mvc
                .perform(get(path("diagnostics") + "?analysis_id=$ANALYSIS"))
                .andReturn()
                .response.status,
        )
        assertEquals(
            200,
            fixture.mvc
                .perform(get(path("diagnostics")))
                .andReturn()
                .response.status,
        )
        assertEquals(listOf(ANALYSIS, null), fixture.repository.selectedAnalyses)
        assertEquals(listOf(AuditOutcome.ALLOWED, AuditOutcome.ALLOWED), fixture.audit.events.map { it.outcome })
    }

    private fun assertError(
        response: MockHttpServletResponse,
        status: Int,
        code: String,
    ) {
        assertEquals(status, response.status, response.contentAsString)
        noStore(response)
        assertTrue(response.contentType.orEmpty().startsWith("application/problem+json"))
        assertEquals(code, JSON.readTree(response.contentAsString)["code"].asText())
        listOf(PROTECTED_TEXT, "synthetic-private", "raw_text", "display_name", "analysis_id", "database", "token", "cookie")
            .forEach { assertFalse(response.contentAsString.contains(it), response.contentAsString) }
    }

    private fun noStore(response: MockHttpServletResponse) {
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals("no-cache", response.getHeader("Pragma"))
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"))
    }

    private fun fixture(
        actor: UUID? = ACTOR,
        identityFailure: Boolean = false,
    ): Fixture {
        val audit = Audit()
        val repository = Diagnostics()
        val legacy = Legacy()
        val identity =
            OperatorIdentityPort {
                if (identityFailure) throw OperatorQueryException(OperatorQueryError.OPERATOR_UNAVAILABLE)
                actor
            }
        val service =
            OperatorQueryService(
                legacy,
                audit,
                OperatorQueryProperties(enabled = true, operatorUserIds = setOf(ACTOR)),
                CLOCK,
                identity = identity,
                diagnostics = repository,
            )
        val mvc =
            MockMvcBuilders
                .standaloneSetup(OperatorQueryController(service))
                .setControllerAdvice(OperatorQueryExceptionHandler())
                .addFilters<StandaloneMockMvcBuilder>(OperatorNoStoreFilter(audit, CLOCK, identity))
                .build()
        return Fixture(mvc, audit, repository, legacy)
    }

    private data class Fixture(
        val mvc: MockMvc,
        val audit: Audit,
        val repository: Diagnostics,
        val legacy: Legacy,
    ) {
        fun assertAudit(
            outcome: AuditOutcome,
            actor: UUID? = ACTOR,
            target: UUID? = ROOM,
        ) {
            assertEquals(1, audit.appendCalls)
            val event = audit.events.single()
            assertEquals(AuditRoute.ROOM_DETAIL, event.route)
            assertEquals(actor, event.actorUserId)
            assertEquals(target, event.targetId)
            assertEquals(outcome, event.outcome)
            assertEquals(NOW, event.occurredAt)
        }
    }

    private class Audit : OperatorAccessAuditPort {
        val events = mutableListOf<OperatorAccessAudit>()
        var appendCalls = 0
        var fail = false

        override fun append(event: OperatorAccessAudit) {
            appendCalls++
            if (fail) error("synthetic-private durable audit failure")
            events += event
        }

        override fun deleteBefore(
            cutoff: Instant,
            limit: Int,
        ): Int = error("read only")
    }

    private class Diagnostics : OperatorDiagnosticsRepository {
        var calls = 0
        var missing = false
        var fail = false
        val selectedAnalyses = mutableListOf<UUID?>()

        override fun analyses(
            roomId: UUID,
            limit: Int,
            cursor: OperationsCursor?,
        ): OperationsPage<OperatorAnalysis>? {
            calls++
            if (fail) error("synthetic-private database exception")
            return OperationsPage<OperatorAnalysis>(emptyList(), null).takeUnless { missing }
        }

        override fun diagnostics(
            roomId: UUID,
            analysisId: UUID?,
        ): OperatorRoomDiagnostics? {
            calls++
            selectedAnalyses += analysisId
            if (fail) error("synthetic-private database exception")
            return OperatorRoomDiagnostics(
                roomId,
                "CURRENT_COLLECTION",
                null,
                listOf(OperatorInput("참가자 A1B2C3D4", "가*", 3, PROTECTED_TEXT, null, null, null, null, null, emptyList(), false)),
                false,
                emptyList(),
                false,
                emptyList(),
                false,
                emptyList(),
                false,
            ).takeUnless { missing }
        }
    }

    private class Legacy : OperationsQueryRepository {
        var calls = 0

        override fun listRooms(
            limit: Int,
            cursor: OperationsCursor?,
        ): OperationsPage<RoomSummary> {
            calls++
            error("synthetic-private forbidden legacy fallback")
        }

        override fun roomDetail(
            roomId: UUID,
            limit: Int,
            cursor: OperationsCursor?,
        ): RoomDetail? {
            calls++
            error("synthetic-private forbidden legacy fallback")
        }

        override fun invocationDetail(invocationId: UUID): InvocationDetail? {
            calls++
            error("synthetic-private forbidden legacy fallback")
        }
    }

    companion object {
        private val ACTOR = UUID(0, 110)
        private val OTHER = UUID(0, 999)
        private val ROOM = UUID(0, 210)
        private val ANALYSIS = UUID(0, 310)
        private val NOW = Instant.parse("2044-02-29T12:00:00Z")
        private val CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)
        private val JSON = JsonMapper.builder().findAndAddModules().build()
        private const val PROTECTED_TEXT = "가나다는 2044-02-29 19:00부터 가능 [전화번호 숨김] [이메일 숨김]"

        private fun path(route: String) = "/api/operations/rooms/$ROOM/$route"
    }
}
