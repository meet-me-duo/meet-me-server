package com.meetme.server.shared.operations

import com.meetme.server.config.AnonymousAccessProperties
import com.meetme.server.config.AnonymousWebSecurityConfiguration
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
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRD 4.3 and Architecture's exact-origin CORS and protected operations contracts:
 * transport preflight is handled by MVC CORS without reading or auditing business data.
 * OPTIONS without both preflight headers and actual GET requests remain protected.
 */
class OperatorCorsPreflightContractTest {
    @Test
    fun `OC01 allowed browser preflight succeeds without identity lookup query or business audit`() {
        val fixture = Fixture()
        fixture.withMvc { mvc ->
            val response = mvc.perform(preflight()).andReturn().response
            assertTrue(response.status in 200..299, response.contentAsString)
            assertEquals(ORIGIN, response.getHeader("Access-Control-Allow-Origin"))
            assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"))
            assertTrue("GET" in headerTokens(response, "Access-Control-Allow-Methods"))
            assertTrue("authorization" in headerTokens(response, "Access-Control-Allow-Headers"))
            assertEquals("", response.contentAsString)
            assertPrivacyHeaders(response)
            fixture.assertNoBusinessAccess()
        }
    }

    @Test
    fun `OC02 preflight is independent of unavailable identity and durable audit infrastructure`() {
        val fixture = Fixture(identityFails = true, auditFails = true)
        fixture.withMvc { mvc ->
            val response = mvc.perform(preflight()).andReturn().response
            assertTrue(response.status in 200..299, response.contentAsString)
            assertEquals(ORIGIN, response.getHeader("Access-Control-Allow-Origin"))
            assertEquals("", response.contentAsString)
            assertPrivacyHeaders(response)
            fixture.assertNoBusinessAccess()
        }
    }

    @Test
    fun `OC03 unapproved origin is rejected by real MVC CORS without business access`() {
        assertCorsRejected(preflight(origin = "https://unapproved.example"))
    }

    @Test
    fun `OC04 unapproved requested method is rejected by real MVC CORS without business access`() {
        assertCorsRejected(preflight(method = "DELETE"))
    }

    @Test
    fun `OC05 unapproved requested header is rejected by real MVC CORS without business access`() {
        assertCorsRejected(preflight(headers = "X-Operator-Override"))
    }

    @Test
    fun `OC06 bare and incomplete OPTIONS cannot bypass protected response audit`() {
        listOf(
            options(PATH),
            options(PATH).header("Origin", ORIGIN),
            options(PATH).header("Access-Control-Request-Method", "GET"),
        ).forEach { request ->
            val fixture = Fixture()
            fixture.withMvc { mvc ->
                val response = mvc.perform(request).andReturn().response
                assertTrue(response.status >= 400, response.contentAsString)
                assertPrivacyHeaders(response)
                assertFalse(response.contentAsString.contains(PROTECTED_CURSOR))
                assertEquals(1, fixture.identityCalls)
                assertEquals(0, fixture.query.calls)
                assertEquals(1, fixture.audit.appendCalls)
            }
        }
    }

    @Test
    fun `OC07 GET with preflight headers still requires authenticated authorized identity`() {
        listOf(
            Triple(null, 401, "OPERATOR_UNAUTHENTICATED"),
            Triple(OUTSIDER, 403, "OPERATOR_FORBIDDEN"),
        ).forEach { (actor, status, code) ->
            val fixture = Fixture(actor = actor)
            fixture.withMvc { mvc ->
                val response =
                    mvc
                        .perform(
                            get(PATH)
                                .header("Origin", ORIGIN)
                                .header("Access-Control-Request-Method", "GET")
                                .header("Access-Control-Request-Headers", "authorization"),
                        ).andReturn()
                        .response
                assertProblem(response, status, code)
                assertEquals(1, fixture.identityCalls)
                assertEquals(0, fixture.query.calls)
                fixture.assertAudit(AuditOutcome.DENIED, actor)
            }
        }
    }

    @Test
    fun `OC08 actual allowed GET returns data with one allowed audit and no-store`() {
        val fixture = Fixture(actor = OPERATOR)
        fixture.withMvc { mvc ->
            val response = mvc.perform(get(PATH).header("Origin", ORIGIN)).andReturn().response
            assertEquals(200, response.status, response.contentAsString)
            assertEquals(ORIGIN, response.getHeader("Access-Control-Allow-Origin"))
            assertPrivacyHeaders(response)
            assertEquals(PROTECTED_CURSOR, document(response)["next_cursor"])
            assertEquals(1, fixture.identityCalls)
            assertEquals(1, fixture.query.calls)
            fixture.assertAudit(AuditOutcome.ALLOWED, OPERATOR)
        }
    }

    @Test
    fun `OC09 actual GET denies anonymous and nonoperator access with durable audit`() {
        listOf(
            Triple(null, 401, "OPERATOR_UNAUTHENTICATED"),
            Triple(OUTSIDER, 403, "OPERATOR_FORBIDDEN"),
        ).forEach { (actor, status, code) ->
            val fixture = Fixture(actor = actor)
            fixture.withMvc { mvc ->
                val response = mvc.perform(get(PATH).header("Origin", ORIGIN)).andReturn().response
                assertProblem(response, status, code)
                assertEquals(1, fixture.identityCalls)
                assertEquals(0, fixture.query.calls)
                fixture.assertAudit(AuditOutcome.DENIED, actor)
            }
        }
    }

    @Test
    fun `OC10 actual allowed GET fails closed without protected data when audit fails`() {
        val fixture = Fixture(actor = OPERATOR, auditFails = true)
        fixture.withMvc { mvc ->
            val response = mvc.perform(get(PATH).header("Origin", ORIGIN)).andReturn().response
            assertProblem(response, 503, "OPERATOR_UNAVAILABLE")
            assertEquals(1, fixture.identityCalls)
            assertEquals(1, fixture.query.calls)
            assertEquals(1, fixture.audit.appendCalls)
            assertTrue(fixture.audit.events.isEmpty())
        }
    }

    @Test
    fun `OC11 GET preflight header spoof cannot release unaudited downstream output`() {
        val audit = Audit(fail = false)
        var identityCalls = 0
        val identity =
            OperatorIdentityPort {
                identityCalls++
                OPERATOR
            }
        val request = MockHttpServletRequest("GET", "/api/operations/rooms")
        request.addHeader("Origin", ORIGIN)
        request.addHeader("Access-Control-Request-Method", "GET")
        request.addHeader("Access-Control-Request-Headers", "authorization")
        val response = MockHttpServletResponse()
        val chain =
            FilterChain { _, downstream ->
                (downstream as HttpServletResponse).writer.write(PROTECTED_CURSOR)
            }
        OperatorNoStoreFilter(audit, CLOCK, identity).doFilter(request, response, chain)
        assertProblem(response, 503, "OPERATOR_UNAVAILABLE")
        assertEquals(1, identityCalls)
        assertEquals(1, audit.appendCalls)
        assertEquals(listOf(AuditOutcome.UNAVAILABLE), audit.events.map { it.outcome })
    }

    private fun assertCorsRejected(request: MockHttpServletRequestBuilder) {
        val fixture = Fixture(identityFails = true, auditFails = true)
        fixture.withMvc { mvc ->
            val response = mvc.perform(request).andReturn().response
            assertEquals(403, response.status, response.contentAsString)
            assertNull(response.getHeader("Access-Control-Allow-Origin"))
            assertPrivacyHeaders(response)
            assertFalse(response.contentAsString.contains(PROTECTED_CURSOR))
            assertFalse(response.contentAsString.contains("synthetic-private"))
            fixture.assertNoBusinessAccess()
        }
    }

    private fun preflight(
        origin: String = ORIGIN,
        method: String = "GET",
        headers: String = "authorization",
    ): MockHttpServletRequestBuilder =
        options(PATH)
            .header("Origin", origin)
            .header("Access-Control-Request-Method", method)
            .header("Access-Control-Request-Headers", headers)

    private fun headerTokens(
        response: MockHttpServletResponse,
        name: String,
    ) = response.getHeaders(name).flatMap { it.split(',') }.map { it.trim() }

    private fun assertPrivacyHeaders(response: MockHttpServletResponse) {
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals("no-cache", response.getHeader("Pragma"))
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"))
    }

    private fun assertProblem(
        response: MockHttpServletResponse,
        status: Int,
        code: String,
    ) {
        assertEquals(status, response.status, response.contentAsString)
        assertPrivacyHeaders(response)
        assertTrue(response.contentType.orEmpty().startsWith("application/problem+json"))
        assertEquals(setOf("code", "title", "status", "detail"), document(response).keys)
        assertEquals(code, document(response)["code"])
        assertEquals(status, document(response)["status"])
        assertFalse(response.contentAsString.contains(PROTECTED_CURSOR))
        assertFalse(response.contentAsString.contains("synthetic-private"))
        assertFalse(response.contentAsString.contains("\"items\""))
    }

    private fun document(response: MockHttpServletResponse) =
        JsonMapper
            .builder()
            .build()
            .readValue(response.contentAsString, Map::class.java)

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(AnonymousWebSecurityConfiguration::class)
    class CorsWebContextConfiguration

    private class Fixture(
        private val actor: UUID? = null,
        private val identityFails: Boolean = false,
        auditFails: Boolean = false,
    ) {
        var identityCalls = 0
        val audit = Audit(auditFails)
        val query = Query()
        private val identity =
            OperatorIdentityPort {
                identityCalls++
                if (identityFails) error("synthetic-private-identity-failure")
                actor
            }

        fun withMvc(assertions: (MockMvc) -> Unit) {
            WebApplicationContextRunner()
                .withUserConfiguration(CorsWebContextConfiguration::class.java)
                .withBean(AnonymousAccessProperties::class.java, Supplier { AnonymousAccessProperties(allowedOrigins = listOf(ORIGIN)) })
                .withBean(
                    OperatorQueryController::class.java,
                    Supplier {
                        OperatorQueryController(
                            OperatorQueryService(
                                query,
                                audit,
                                OperatorQueryProperties(enabled = true, operatorUserIds = setOf(OPERATOR)),
                                CLOCK,
                                identity,
                            ),
                        )
                    },
                ).withBean(OperatorQueryExceptionHandler::class.java, Supplier { OperatorQueryExceptionHandler() })
                .run { context ->
                    assertNull(context.startupFailure)
                    val mvc =
                        MockMvcBuilders
                            .webAppContextSetup(context)
                            .addFilters<DefaultMockMvcBuilder>(OperatorNoStoreFilter(audit, CLOCK, identity))
                            .build()
                    assertions(mvc)
                }
        }

        fun assertNoBusinessAccess() {
            assertEquals(0, identityCalls)
            assertEquals(0, query.calls)
            assertEquals(0, audit.appendCalls)
            assertTrue(audit.events.isEmpty())
        }

        fun assertAudit(
            outcome: AuditOutcome,
            expectedActor: UUID?,
        ) {
            assertEquals(1, audit.appendCalls)
            assertEquals(listOf(outcome), audit.events.map { it.outcome })
            assertEquals(expectedActor, audit.events.single().actorUserId)
        }
    }

    private class Query : OperationsQueryRepository {
        var calls = 0

        override fun listRooms(
            limit: Int,
            cursor: OperationsCursor?,
        ): OperationsPage<RoomSummary> {
            calls++
            assertEquals(20, limit)
            return OperationsPage(emptyList(), PROTECTED_CURSOR)
        }

        override fun roomDetail(
            roomId: UUID,
            limit: Int,
            cursor: OperationsCursor?,
        ): RoomDetail? {
            calls++
            error("unexpected room detail access")
        }

        override fun invocationDetail(invocationId: UUID): InvocationDetail? {
            calls++
            error("unexpected invocation detail access")
        }
    }

    private class Audit(
        private val fail: Boolean,
    ) : OperatorAccessAuditPort {
        var appendCalls = 0
        val events = mutableListOf<OperatorAccessAudit>()

        override fun append(event: OperatorAccessAudit) {
            appendCalls++
            if (fail) error("synthetic-private-audit-failure")
            events += event
        }

        override fun deleteBefore(
            cutoff: Instant,
            limit: Int,
        ): Int = error("not used")
    }

    companion object {
        private const val ORIGIN = "https://app.meet-me.co.kr"
        private const val PATH = "/api/operations/rooms?limit=20"
        private const val PROTECTED_CURSOR = "synthetic-protected-cursor"
        private val OPERATOR = UUID(0, 110)
        private val OUTSIDER = UUID(0, 111)
        private val CLOCK = Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC)
    }
}
