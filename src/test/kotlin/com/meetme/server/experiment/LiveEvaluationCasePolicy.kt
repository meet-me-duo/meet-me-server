package com.meetme.server.experiment

import com.meetme.server.submission.domain.SubmissionRules
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Local test-harness decisions only; never structures provider output or changes service policy. */
internal object LiveEvaluationCasePolicy {
    private val mapper = JsonMapper.builder().build()
    private val recipients = setOf("TypeSafeAI", "OpenAI", "GoogleGemini")

    fun closeCommand(room: JsonNode): Map<String, Any> {
        val settings = roomSettings(room)
        return buildMap {
            put("confirm_early", true)
            if (room.has("analysisContract")) put("analysis_contract", settings.analysisContract)
        }
    }

    fun roomSettings(room: JsonNode): LiveEvaluationRoomSettings {
        require(room.path("participants").isArray && room.path("participants").size() in 2..4)
        val mode = if (room.has("meetingMode")) room.path("meetingMode").asText() else "EITHER"
        val contract = if (room.has("analysisContract")) room.path("analysisContract").asText() else "LEGACY"
        require(mode in setOf("REMOTE", "IN_PERSON", "EITHER"))
        require(contract in setOf("LEGACY", "DSL_STRUCTURAL_V5"))
        return LiveEvaluationRoomSettings(mode, contract)
    }

    fun progressingClock(
        referenceAt: Instant,
        system: Clock,
    ): Clock = Clock.offset(system.withZone(ZoneOffset.UTC), Duration.between(system.instant(), referenceAt))

    fun requireRecipientApproval(
        manifest: JsonNode,
        payloadSha256: String,
        recipients: Set<String>,
    ) {
        require(payloadSha256.matches(Regex("[0-9a-f]{64}")))
        require(manifest.path("approved").asBoolean(false))
        require(manifest.path("payloadSha256").asText() == payloadSha256)
        require(manifest.path("dataClassification").asText() in setOf("REAL_FAMILY", "APPROVED_PERSONAL", "NON_PERSONAL_SYNTHETIC"))
        require(recipients.isNotEmpty() && this.recipients.containsAll(recipients))
        val receipts = manifest.path("recipientApprovals")
        require(receipts.isArray)
        recipients.forEach { recipient ->
            val receipt = receipts.values().singleOrNull { it.path("recipient").asText() == recipient }
            require(receipt != null)
            require(receipt.path("messageId").isString && receipt.path("messageId").asText().isNotBlank())
            require(receipt.path("payloadSha256").asText() == payloadSha256)
            require(receipt.path("purpose").asText() == "MEETME_EVALUATION")
        }
    }

    fun syntheticAnswer(
        decision: JsonNode,
        snapshot: JsonNode,
        sourceInput: String,
        allowChoice: Boolean = false,
    ): LiveEvaluationSyntheticAnswer {
        val question = snapshot.path("check").path("question")
        val choicePath = decision.has("submissionPath") || decision.has("choiceId")
        require(!choicePath || (allowChoice && decision.path("submissionPath").asText() == "choice_id"))
        require(
            decision.isObject &&
                decision.propertyNames().toSet() ==
                (
                    setOf(
                        "mode",
                        "questionId",
                        "questionSha256",
                        "sourceSha256",
                        "revision",
                        "checkVersion",
                        "questionConsistent",
                        "answer",
                        "expectedOutcome",
                    ) + if (choicePath) setOf("submissionPath", "choiceId") else emptySet()
                ),
        )
        require(decision.path("mode").asText() == "TEST_SYNTHETIC")
        require(question.path("id").asText().isNotBlank())
        require(decision.path("questionId").asText() == question.path("id").asText())
        require(decision.path("questionSha256").asText() == sha(mapper.writeValueAsBytes(question)))
        require(decision.path("sourceSha256").asText() == sha(sourceInput.toByteArray(Charsets.UTF_8)))
        require(decision.path("revision").isIntegralNumber && decision.path("revision").asLong() > 0)
        require(decision.path("revision").asLong() == snapshot.path("candidate").path("revision").asLong())
        require(decision.path("checkVersion").isIntegralNumber && decision.path("checkVersion").asLong() > 0)
        require(decision.path("checkVersion").asLong() == snapshot.path("check").path("version").asLong())
        require(decision.path("questionConsistent").asBoolean(false))
        val answer = decision.path("answer")
        val expected = decision.path("expectedOutcome")
        require(answer.isString && answer.asText().isNotBlank() && answer.asText().length <= 2000)
        require(expected.isString && expected.asText().isNotBlank() && expected.asText().length <= 4000)
        val choiceId =
            if (choicePath) {
                val id = decision.path("choiceId")
                require(id.isString && id.asText().isNotBlank())
                val options = question.path("options")
                require(options.isArray && options.size() in 1..2)
                val all = options.values().toList()
                require(all.all { it.path("id").isString && it.path("id").asText().isNotBlank() })
                require(all.map { it.path("id").asText() }.distinct().size == all.size)
                val selected = requireNotNull(all.singleOrNull { it.path("id").asText() == id.asText() })
                require(selected.path("text").isString && selected.path("text").asText().isNotBlank())
                val proposed = selected.path("proposed_text")
                require(proposed.isString && proposed.asText().isNotBlank())
                require(SubmissionRules.trimRawText(proposed.asText()) == answer.asText())
                id.asText()
            } else {
                null
            }
        return LiveEvaluationSyntheticAnswer(answer.asText(), expected.asText(), choiceId)
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

internal data class LiveEvaluationRoomSettings(
    val meetingMode: String,
    val analysisContract: String,
)

internal data class LiveEvaluationSyntheticAnswer(
    val answer: String,
    val expectedOutcome: String,
    val choiceId: String? = null,
) {
    fun bodyFields(): Map<String, String> = choiceId?.let { mapOf("choice_id" to it) } ?: mapOf("direct_text" to answer)
}
