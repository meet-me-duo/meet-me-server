package com.meetme.server.shared.operations

import com.meetme.server.account.adapter.input.web.AuthController
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.account.application.port.output.RefreshSessionPort
import com.meetme.server.account.application.service.OAuthLoginService
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.config.AccountAuthProperties
import com.meetme.server.config.AnonymousAccessProperties
import com.meetme.server.config.OperatorQueryConfiguration
import com.meetme.server.shared.application.port.input.operations.InvocationDetail
import com.meetme.server.shared.application.port.input.operations.OperationsCursor
import com.meetme.server.shared.application.port.input.operations.OperationsPage
import com.meetme.server.shared.application.port.input.operations.OperatorQueryError
import com.meetme.server.shared.application.port.input.operations.OperatorQueryException
import com.meetme.server.shared.application.port.input.operations.RoomDetail
import com.meetme.server.shared.application.port.input.operations.RoomSummary
import com.meetme.server.shared.application.port.output.operations.AuditOutcome
import com.meetme.server.shared.application.port.output.operations.OperationsQueryRepository
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAudit
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAuditPort
import com.meetme.server.shared.application.port.output.operations.OperatorIdentityPort
import com.meetme.server.shared.application.service.operations.OperatorAccessPolicy
import com.meetme.server.shared.application.service.operations.OperatorQueryService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Actual Spring constructor injection, configuration binding and protected-query authorization share one contract. */
class OperatorCapabilitySpringWiringTest {
    @Test
    fun `OW01 real Spring graph grants both configured accounts and denies third with unchanged audit semantics`() {
        val fixture = Fixture()
        fixture.runner(true).run { context ->
            assertNull(context.startupFailure)
            val auth = context.getBean(AuthController::class.java)
            val query = context.getBean(OperatorQueryService::class.java)
            listOf(FIRST, SECOND).forEach { id ->
                fixture.actor = id
                assertCapability(auth, true)
                assertEquals(emptyList(), query.listRooms().items)
            }
            fixture.actor = OUTSIDER
            assertCapability(auth, false)
            val failure = assertFailsWith<OperatorQueryException> { query.listRooms() }
            assertEquals(OperatorQueryError.OPERATOR_FORBIDDEN, failure.code)
            assertEquals(listOf(AuditOutcome.ALLOWED, AuditOutcome.ALLOWED, AuditOutcome.DENIED), fixture.audit.events.map { it.outcome })
        }
    }

    @Test
    fun `OW02 disabled configured graph denies query and capability for listed accounts`() {
        val fixture = Fixture()
        fixture.runner(false).run { context ->
            assertNull(context.startupFailure)
            assertCapability(context.getBean(AuthController::class.java), false)
            val failure = assertFailsWith<OperatorQueryException> { context.getBean(OperatorQueryService::class.java).listRooms() }
            assertEquals(OperatorQueryError.OPERATOR_FORBIDDEN, failure.code)
            assertEquals(listOf(AuditOutcome.DENIED), fixture.audit.events.map { it.outcome })
        }
    }

    @Test
    fun `OW03 positive capability never bypasses fail closed protected query audit`() {
        val fixture = Fixture()
        fixture.audit.fail = true
        fixture.runner(true).run { context ->
            assertNull(context.startupFailure)
            assertCapability(context.getBean(AuthController::class.java), true)
            val failure = assertFailsWith<OperatorQueryException> { context.getBean(OperatorQueryService::class.java).listRooms() }
            assertEquals(OperatorQueryError.OPERATOR_UNAVAILABLE, failure.code)
        }
    }

    private fun assertCapability(
        controller: AuthController,
        expected: Boolean,
    ) {
        val value = ObjectMapper().convertValue(assertNotNull(controller.me().body), Map::class.java)
        assertEquals(mapOf("canViewOperations" to expected), value["capabilities"])
    }

    private class Fixture {
        var actor = FIRST
        val audit = Audit()

        fun runner(enabled: Boolean) =
            ApplicationContextRunner()
                .withUserConfiguration(
                    OperatorQueryConfiguration::class.java,
                    OperatorAccessPolicy::class.java,
                    AuthController::class.java,
                    OperatorQueryService::class.java,
                ).withPropertyValues("meetme.operations.enabled=$enabled", "meetme.operations.operator-user-ids=$FIRST,$SECOND")
                .withBean(OAuthLoginService::class.java, Supplier { mock(OAuthLoginService::class.java) })
                .withBean(RefreshSessionPort::class.java, Supplier { mock(RefreshSessionPort::class.java) })
                .withBean(AccessTokenPort::class.java, Supplier { mock(AccessTokenPort::class.java) })
                .withBean(CurrentAccountPort::class.java, Supplier { CurrentAccountPort { AccountPrincipal(actor, "Synthetic") } })
                .withBean(AccountAuthProperties::class.java, Supplier { AccountAuthProperties() })
                .withBean(AnonymousAccessProperties::class.java, Supplier { AnonymousAccessProperties() })
                .withBean(Clock::class.java, Supplier { Clock.systemUTC() })
                .withBean(OperatorIdentityPort::class.java, Supplier { OperatorIdentityPort { actor } })
                .withBean(OperatorAccessAuditPort::class.java, Supplier { audit })
                .withBean(OperationsQueryRepository::class.java, Supplier { Query() })
    }

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
        var fail = false

        override fun append(event: OperatorAccessAudit) {
            if (fail) error("synthetic audit unavailable")
            events += event
        }

        override fun deleteBefore(
            cutoff: Instant,
            limit: Int,
        ) = 0
    }

    companion object {
        private val FIRST = UUID.fromString("11111111-2222-3333-4444-555555555555")
        private val SECOND = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        private val OUTSIDER = UUID.fromString("99999999-8888-7777-6666-555555555555")
    }
}
