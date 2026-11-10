package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.support.ScheduleDslQuestionContextFixture
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import kotlin.test.assertEquals

/** Literal old-API byte oracles captured before terms implementation; no expected value derives from new code. */
class ScheduleDslApprovedTermsLegacyByteTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val fixture = ScheduleDslQuestionContextFixture

    @Test
    fun `AHB01 response1 request bytes equal the independently captured old contract`() {
        val bytes =
            ScheduleDslProviderContract(mapper).requestBytes(
                fixture.snapshot(),
                SCHEDULE_DSL_RESPONSE_CONTRACT,
                SCHEDULE_DSL_SEMANTIC_VERSION,
                SCHEDULE_AST_VERSION,
            )
        assertEquals("ee533db7dce6a4328f35d76d1f1e4b76bd9eb08b931357bba9aef68f1796c003", sha256(bytes))
    }

    @Test
    fun `AHB02 context2 request bytes equal the independently captured old contract`() {
        val capsule = ScheduleDslProviderInputCapsule.create(fixture.snapshot(), fixture.native())
        val bytes = ScheduleDslContextProviderContract(mapper).requestBytes(capsule)
        assertEquals("cbcb9f6a1c9cc6d0a0b21ce96a8fcab28f189f8a3e0ed8047510cf4958b49609", sha256(bytes))
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
}
