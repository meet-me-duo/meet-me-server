package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** A provider-delivery contract, not proof that a model follows the instructions or interprets meaning correctly. */
class ScheduleDslContextModePromptChecklistTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `CMP01 approved evidence and AST safeguards reach only C4 without changing native payload or schema selection`() {
        val capsule = N.capsule()
        val old = ScheduleDslApprovedTermsProviderContract(mapper)
        val oldBytes = old.requestBytes(capsule, MeetingMode.EITHER)
        val oldRequest = mapper.readTree(oldBytes)
        val contract = ScheduleDslContextModeProviderContract(mapper)
        val bytes = contract.requestBytes(capsule, MeetingMode.EITHER)
        val request = mapper.readTree(bytes)
        val prompt = requireNotNull(request.path("prompt").asString())
        for (clause in requiredClauses) {
            assertTrue(prompt.contains(clause), "Approved C4 safety clause is absent")
        }
        assertEquals("schedule-dsl-request-4", request.path("request_contract_version").asString())
        assertEquals("schedule-dsl-response-4", request.path("response_contract_version").asString())
        assertEquals("schedule-ir-product-5", request.path("semantic_version").asString())
        assertEquals("schedule-ast-1", request.path("ast_version").asString())
        assertEquals("ko-time-terms-1", request.path("vocabulary_version").asString())
        assertEquals(oldRequest.path("inputs"), request.path("inputs"))
        assertEquals(oldRequest.path("context"), request.path("context"))
        assertEquals(
            mapper.readTree(mapper.writeValueAsBytes(contract.responseSchema())),
            request.path("schema"),
        )
        assertEquals(
            setOf(
                "request_contract_version",
                "response_contract_version",
                "semantic_version",
                "ast_version",
                "vocabulary_version",
                "prompt",
                "schema",
                "context",
                "inputs",
            ),
            mapper.readValue(bytes, Map::class.java).keys,
        )
        val legacy =
            ScheduleDslProviderContract(mapper).requestBytes(
                capsule.snapshot,
                SCHEDULE_DSL_RESPONSE_CONTRACT,
                SCHEDULE_DSL_SEMANTIC_VERSION,
                SCHEDULE_AST_VERSION,
            )
        val context = ScheduleDslContextProviderContract(mapper).requestBytes(capsule)
        for (previousBytes in listOf(legacy, context, oldBytes)) {
            val previousPrompt = requireNotNull(mapper.readTree(previousBytes).path("prompt").asString())
            requiredClauses.forEach { clause ->
                assertFalse(previousPrompt.contains(clause), "C4 checklist changed an older prompt contract")
            }
        }
        assertContentEquals(oldBytes, old.requestBytes(capsule, MeetingMode.EITHER))
    }

    private val requiredClauses =
        listOf(
            "Copy each quote exactly from that input_ref's named source and revision. Spans count decoded " +
                "Unicode code points, start inclusive and end exclusive; a single-code-point emoji counts as one; " +
                "composed emoji may contain multiple code points; a decoded newline counts as one. Do not count " +
                "UTF-8 bytes, UTF-16 units or JSON escape characters.",
            "Check source[start:end] equals quote before returning it. Preserve negation, exception and " +
                "correction context; matching a substring does not prove the condition's effect. Approved term " +
                "evidence must be its exact supported lexeme, with separately supported date evidence inside the " +
                "condition evidence.",
            "Roots start roles: availability_root starts ALLOW; exclusion_root starts BLOCK; preference_root " +
                "starts PREFER. Each CLAIM_REF must match its current role: ALLOW accepts AVAILABLE/UNCONSTRAINED; " +
                "BLOCK accepts UNAVAILABLE/UNRESOLVED; PREFER accepts PREFERRED. Preserve the selected contract's " +
                "existing EXCEPT and PREFER role transitions; do not treat every descendant as a direct root effect. " +
                "Never change an uncertain effect merely to satisfy a role.",
            "Every node must be reachable from an actual root. Do not emit a duplicate orphan reference or " +
                "delete a meaningful unknown/negative condition to obtain reachability.",
            "Every condition must be referenced or genuinely historical. history_condition_ids cannot hide " +
                "ASSERTED conditions unless syntactically superseded. Keep corrections and branch dependencies; do " +
                "not guess a missing relationship.",
            "Current scope representation supports a complete finite interval or genuinely unknown scope, not a " +
                "one-ended hard availability interval. Never fabricate a missing endpoint or actual attendance " +
                "start. Preserve the condition/evidence and uncertainty when precise bounds cannot be represented.",
            "Date-only availability denotes its stated local day from 00:00 to next-day 00:00. Context-derived " +
                "day-wide AVAILABLE bounds remain INFERRED and NON_PRECISE, not explicit user clock numbers. " +
                "Preference-only bounds may serve as availability under the selected policy; explicit unavailability " +
                "takes priority. An existing UNRESOLVED claim is not automatically confirmed by those rules.",
            "Do not use a fabricated approved-term lexeme for a contextual day-wide interpretation. ALL_DAY term " +
                "evidence must match its actual supported lexeme. Policy/context-derived full-day bounds need the " +
                "selected contextual derivation; they are not new explicit user clock numbers.",
        )
}
