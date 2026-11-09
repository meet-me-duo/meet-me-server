package com.meetme.server.experiment

import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** HR01-HR05: synthetic helper contracts only; no HTTP, native admission or provider authority is exercised. */
class LiveEvaluationCasePolicyTest {
    private val mapper = JsonMapper.builder().build()
    private val recipients = setOf("TypeSafeAI", "OpenAI", "GoogleGemini")
    private val payloadSha256 = "a".repeat(64)
    private val sourceInput = "Synthetic weekday availability after the stated event."
    private val questionId = "00000000-0000-0000-0000-000000000123"
    private val question = node(mapOf("id" to questionId, "text" to "When does the stated availability begin?"))
    private val snapshot =
        node(
            mapOf(
                "candidate" to mapOf("revision" to 2),
                "check" to mapOf("version" to 3, "question" to question),
            ),
        )

    @Test
    fun `HR01 accepts exactly two three or four responding actors without adding the host`() {
        for (count in 2..4) {
            assertEquals(LiveEvaluationRoomSettings("EITHER", "LEGACY"), LiveEvaluationCasePolicy.roomSettings(room(count)))
        }
    }

    @Test
    fun `HR01 rejects one or five actors before an HTTP caller can proceed`() {
        for (count in listOf(1, 5)) {
            assertFailsWith<IllegalArgumentException> { LiveEvaluationCasePolicy.roomSettings(room(count)) }
        }
    }

    @Test
    fun `HR02 omitted old case options retain EITHER and LEGACY`() {
        assertEquals(LiveEvaluationRoomSettings("EITHER", "LEGACY"), LiveEvaluationCasePolicy.roomSettings(room(3)))
    }

    @Test
    fun `HR02 explicit case modes and selected V5 are retained without changing the default`() {
        for (mode in listOf("REMOTE", "IN_PERSON", "EITHER")) {
            assertEquals(
                LiveEvaluationRoomSettings(mode, "DSL_STRUCTURAL_V5"),
                LiveEvaluationCasePolicy.roomSettings(room(2, mode, "DSL_STRUCTURAL_V5")),
            )
        }
    }

    @Test
    fun `HR02 outgoing close body preserves omitted legacy and honors explicit route fields`() {
        for (count in 2..4) {
            assertEquals(mapOf("confirm_early" to true), LiveEvaluationCasePolicy.closeCommand(room(count)))
        }
        assertEquals(
            mapOf("confirm_early" to true, "analysis_contract" to "DSL_STRUCTURAL_V5"),
            LiveEvaluationCasePolicy.closeCommand(room(2, contract = "DSL_STRUCTURAL_V5")),
        )
        assertEquals(
            mapOf("confirm_early" to true, "analysis_contract" to "LEGACY"),
            LiveEvaluationCasePolicy.closeCommand(room(3, contract = "LEGACY")),
        )
    }

    @Test
    fun `HR03 one reference anchor continues advancing across later stages without sleeps or resets`() {
        val referenceAt = Instant.parse("2027-02-01T00:00:00Z")
        val system = ControlledClock(Instant.parse("2037-04-03T00:00:00Z"))
        val clock = LiveEvaluationCasePolicy.progressingClock(referenceAt, system)
        assertEquals(referenceAt, clock.instant())
        assertEquals(ZoneOffset.UTC, clock.zone)
        system.advance(Duration.ofSeconds(7))
        assertEquals(referenceAt.plusSeconds(7), clock.instant())
        system.advance(Duration.ofMillis(5))
        assertEquals(referenceAt.plusSeconds(7).plusMillis(5), clock.instant())
        assertEquals(referenceAt.plusSeconds(7).plusMillis(5), clock.instant())
    }

    @Test
    fun `HR05 matching synthetic receipt fixtures cover every explicitly requested provider`() {
        for (classification in listOf("REAL_FAMILY", "APPROVED_PERSONAL", "NON_PERSONAL_SYNTHETIC")) {
            LiveEvaluationCasePolicy.requireRecipientApproval(manifest(classification = classification), payloadSha256, recipients)
        }
    }

    @Test
    fun `HR05 false approval or changed payload fails before a live POST`() {
        assertFailsWith<IllegalArgumentException> {
            LiveEvaluationCasePolicy.requireRecipientApproval(manifest(approved = false), payloadSha256, recipients)
        }
        assertFailsWith<IllegalArgumentException> {
            LiveEvaluationCasePolicy.requireRecipientApproval(manifest(payload = "b".repeat(64)), payloadSha256, recipients)
        }
    }

    @Test
    fun `HR05 one missing provider or absent approval source cannot authorize the chain`() {
        assertFailsWith<IllegalArgumentException> {
            LiveEvaluationCasePolicy.requireRecipientApproval(
                manifest(receipts = listOf(receipt("OpenAI"), receipt("GoogleGemini"))),
                payloadSha256,
                recipients,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LiveEvaluationCasePolicy.requireRecipientApproval(
                manifest(receipts = recipients.map { receipt(it, messageId = "") }),
                payloadSha256,
                recipients,
            )
        }
    }

    @Test
    fun `HR05 receipt payload and purpose must match rather than borrow another approval`() {
        for (bad in listOf(receipt("OpenAI", payload = "b".repeat(64)), receipt("OpenAI", purpose = "OTHER_PURPOSE"))) {
            assertFailsWith<IllegalArgumentException> {
                LiveEvaluationCasePolicy.requireRecipientApproval(
                    manifest(receipts = listOf(receipt("TypeSafeAI"), bad, receipt("GoogleGemini"))),
                    payloadSha256,
                    recipients,
                )
            }
        }
    }

    @Test
    fun `HR05 missing or unknown data classification does not authorize new personal data`() {
        for (classification in listOf(null, "UNAPPROVED_PERSONAL")) {
            assertFailsWith<IllegalArgumentException> {
                LiveEvaluationCasePolicy.requireRecipientApproval(manifest(classification = classification), payloadSha256, recipients)
            }
        }
    }

    @Test
    fun `HR04 synthetic answer retains the independently frozen branch expectation and text`() {
        val actual = LiveEvaluationCasePolicy.syntheticAnswer(decision(), snapshot, sourceInput)
        assertEquals("Available after the stated event; the exact boundary remains unknown.", actual.answer)
        assertEquals("Preserve unknown boundary; do not invent a whole-day interval.", actual.expectedOutcome)
        val bounded = LiveEvaluationCasePolicy.syntheticAnswer(decision(mapOf("answer" to "a".repeat(2000))), snapshot, sourceInput)
        assertEquals(2000, bounded.answer.length)
    }

    @Test
    fun `HR04 stale question identity or altered question bytes cannot reuse an answer`() {
        for (change in listOf(mapOf("questionId" to "other-question"), mapOf("questionSha256" to "b".repeat(64)))) {
            assertFailsWith<IllegalArgumentException> {
                LiveEvaluationCasePolicy.syntheticAnswer(decision(change), snapshot, sourceInput)
            }
        }
    }

    @Test
    fun `HR04 an answer cannot be transferred from another source text`() {
        assertFailsWith<IllegalArgumentException> {
            LiveEvaluationCasePolicy.syntheticAnswer(decision(mapOf("sourceSha256" to "b".repeat(64))), snapshot, sourceInput)
        }
    }

    @Test
    fun `HR04 source revision and check version remain bound to the current question`() {
        for (change in listOf(mapOf("revision" to 1), mapOf("checkVersion" to 2))) {
            assertFailsWith<IllegalArgumentException> {
                LiveEvaluationCasePolicy.syntheticAnswer(decision(change), snapshot, sourceInput)
            }
        }
    }

    @Test
    fun `HR04 a contradictory question stops instead of being repaired by the answer`() {
        assertFailsWith<IllegalArgumentException> {
            LiveEvaluationCasePolicy.syntheticAnswer(decision(mapOf("questionConsistent" to false)), snapshot, sourceInput)
        }
    }

    @Test
    fun `HR04 absent expectation or blank or oversized answer cannot be posted`() {
        for (change in listOf(mapOf("expectedOutcome" to ""), mapOf("answer" to " "), mapOf("answer" to "a".repeat(2001)))) {
            assertFailsWith<IllegalArgumentException> {
                LiveEvaluationCasePolicy.syntheticAnswer(decision(change), snapshot, sourceInput)
            }
        }
    }

    @Test
    fun `HR16 explicit opted in native choice posts only the reviewed choice id`() {
        val (choiceDecision, choiceSnapshot) = choiceCase()
        val actual = LiveEvaluationCasePolicy.syntheticAnswer(choiceDecision, choiceSnapshot, sourceInput, allowChoice = true)
        assertEquals("Available after the event.", actual.answer)
        assertEquals("Preserve unknown boundary; do not invent a whole-day interval.", actual.expectedOutcome)
        assertEquals("A", actual.choiceId)
        assertEquals(mapOf("choice_id" to "A"), actual.bodyFields())
    }

    @Test
    fun `HR17 choice requires opt in and one matching native option with the exact reviewed proposed literal`() {
        val (choiceDecision, choiceSnapshot) = choiceCase()
        assertFailsWith<IllegalArgumentException> {
            LiveEvaluationCasePolicy.syntheticAnswer(choiceDecision, choiceSnapshot, sourceInput)
        }
        val validOption = mapOf("id" to "A", "text" to "After the event", "proposed_text" to "  Available after the event.\t")
        val cases =
            listOf(
                choiceCase(change = mapOf("choiceId" to "missing")),
                choiceCase(change = mapOf("choiceId" to null)),
                choiceCase(change = mapOf("revision" to 1)),
                choiceCase(change = mapOf("answer" to "Available before the event.")),
                choiceCase(change = mapOf("submissionPath" to "direct_text")),
                choiceCase(options = emptyList()),
                choiceCase(options = listOf(validOption, validOption)),
                choiceCase(options = listOf(validOption - "proposed_text")),
            )
        for ((invalidDecision, invalidSnapshot) in cases) {
            assertFailsWith<IllegalArgumentException> {
                LiveEvaluationCasePolicy.syntheticAnswer(invalidDecision, invalidSnapshot, sourceInput, allowChoice = true)
            }
        }
    }

    @Test
    fun `HR18 direct matching option text remains direct without inventing a choice even when opt in is enabled`() {
        val (_, choiceSnapshot) = choiceCase()
        val directDecision =
            decision(
                mapOf(
                    "questionSha256" to sha(mapper.writeValueAsBytes(choiceSnapshot.path("check").path("question"))),
                    "answer" to "Available after the event.",
                ),
            )
        for (allowChoice in listOf(false, true)) {
            val actual = LiveEvaluationCasePolicy.syntheticAnswer(directDecision, choiceSnapshot, sourceInput, allowChoice)
            assertEquals("Available after the event.", actual.answer)
            assertEquals(null, actual.choiceId)
            assertEquals(mapOf("direct_text" to "Available after the event."), actual.bodyFields())
        }
    }

    private fun choiceCase(
        options: List<Map<String, String>> =
            listOf(
                mapOf("id" to "A", "text" to "After the event", "proposed_text" to "  Available after the event.\t"),
                mapOf("id" to "B", "text" to "Before the event", "proposed_text" to "Available before the event."),
            ),
        change: Map<String, Any?> = emptyMap(),
    ): Pair<JsonNode, JsonNode> {
        val nativeQuestion =
            node(
                mapOf(
                    "id" to questionId,
                    "text" to "When does availability begin?",
                    "source_excerpt" to "stated event",
                    "options" to options,
                ),
            )
        val nativeSnapshot =
            node(mapOf("candidate" to mapOf("revision" to 2), "check" to mapOf("version" to 3, "question" to nativeQuestion)))
        val choiceDecision =
            decision(
                mapOf(
                    "questionSha256" to sha(mapper.writeValueAsBytes(nativeQuestion)),
                    "answer" to "Available after the event.",
                    "submissionPath" to "choice_id",
                    "choiceId" to "A",
                ) + change,
            )
        return choiceDecision to nativeSnapshot
    }

    private fun room(
        count: Int,
        mode: String? = null,
        contract: String? = null,
    ): JsonNode {
        val body = linkedMapOf<String, Any?>("participants" to List(count) { mapOf("participantId" to "actor-${it + 1}") })
        if (mode != null) body["meetingMode"] = mode
        if (contract != null) body["analysisContract"] = contract
        return node(body)
    }

    private fun receipt(
        recipient: String,
        messageId: String = "SYNTHETIC_RECEIPT_ONLY_NOT_LIVE_AUTHORITY",
        payload: String = payloadSha256,
        purpose: String = "MEETME_EVALUATION",
    ): Map<String, Any?> = mapOf("recipient" to recipient, "messageId" to messageId, "payloadSha256" to payload, "purpose" to purpose)

    private fun manifest(
        approved: Boolean = true,
        payload: String = payloadSha256,
        classification: String? = "REAL_FAMILY",
        receipts: List<Map<String, Any?>> = recipients.map { receipt(it) },
    ): JsonNode {
        val body =
            linkedMapOf<String, Any?>(
                "approved" to approved,
                "payloadSha256" to payload,
                "recipientApprovals" to receipts,
            )
        if (classification != null) body["dataClassification"] = classification
        return node(body)
    }

    private fun decision(change: Map<String, Any?> = emptyMap()): JsonNode =
        node(
            mapOf(
                "mode" to "TEST_SYNTHETIC",
                "questionId" to questionId,
                "questionSha256" to sha(mapper.writeValueAsBytes(question)),
                "sourceSha256" to sha(sourceInput.toByteArray(Charsets.UTF_8)),
                "revision" to 2,
                "checkVersion" to 3,
                "questionConsistent" to true,
                "answer" to "Available after the stated event; the exact boundary remains unknown.",
                "expectedOutcome" to "Preserve unknown boundary; do not invent a whole-day interval.",
            ) + change,
        )

    private fun node(value: Any): JsonNode = mapper.readTree(mapper.writeValueAsBytes(value))

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class ControlledClock(
        private val current: AtomicReference<Instant>,
        private val timeZone: ZoneId,
    ) : Clock() {
        constructor(at: Instant) : this(AtomicReference(at), ZoneOffset.UTC)

        fun advance(by: Duration) {
            current.updateAndGet { it.plus(by) }
        }

        override fun instant(): Instant = current.get()

        override fun getZone(): ZoneId = timeZone

        override fun withZone(zone: ZoneId): Clock = ControlledClock(current, zone)
    }
}
