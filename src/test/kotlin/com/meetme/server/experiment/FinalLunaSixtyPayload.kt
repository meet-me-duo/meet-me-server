package com.meetme.server.experiment

import com.meetme.server.coordination.adapter.output.integration.NaturalLanguageProviderContract
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.shared.domain.InputSupplement
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** Reconstructs the same logical frozen pilot, not an unrecorded historical HTTP packet. */
internal object FinalLunaSixtyPayload {
    val DATABASE: Path = Path.of("/workspace/scratch/live-eval/pilot10-d2119585-667e-47c4-8877-5d4152fd47e2/database.json")
    private val json = FinalLunaSixtyProbe.JSON

    fun request(): NaturalLanguageBatchRequest {
        val db = json.readTree(Files.readAllBytes(DATABASE))
        val rows = db.path("versions").values().associateBy { it.path("id").asText() }
        val inputs =
            db.path("frozen").values().map { frozen ->
                val id = frozen.path("submission_version_id").asText()
                val row = checkNotNull(rows[id])
                val raw = row.path("raw_text").asText()
                val evidence = evidence(json.readTree(row.path("input_evidence").asText()))
                evidence.validateAgainst(raw)
                NaturalLanguageInput(
                    id,
                    raw,
                    Locale.forLanguageTag("ko-KR"),
                    evidence.referenceAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),
                    evidence,
                )
            }
        check(inputs.size == 3 && inputs.count { it.evidence?.supplement != null } == 1)
        return NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            LocalDate.of(2026, 11, 12),
            LocalDate.of(2026, 11, 16),
            inputs,
            Duration.ofSeconds(60),
        )
    }

    fun bytes(request: NaturalLanguageBatchRequest = request()): ByteArray =
        json.writeValueAsBytes(
            mapOf(
                "model" to "gpt-6-luna",
                "store" to false,
                "input" to NaturalLanguageProviderContract(json).prompt(request),
                "max_output_tokens" to NaturalLanguageProviderContract.MAX_OUTPUT_TOKENS,
                "text" to
                    mapOf(
                        "format" to
                            mapOf(
                                "type" to "json_schema",
                                "name" to "meet_me_conditions_v3",
                                "strict" to true,
                                "schema" to NaturalLanguageProviderContract.RESPONSE_SCHEMA,
                            ),
                    ),
            ),
        )

    private fun evidence(node: JsonNode): InputEvidence {
        val supplement =
            node.path("supplement").takeIf { it.isObject }?.let {
                InputSupplement(
                    UUID.fromString(it.path("questionId").asText()),
                    it.path("sourceRevision").asInt(),
                    it.path("sourceExcerpt").asText(),
                    it.path("sourceStartCodePoint").asInt(),
                    it.path("prompt").asText(),
                    it.path("answerText").asText(),
                    it.path("choiceId").takeIf { value -> value.isTextual }?.asText(),
                    Instant.parse(it.path("answeredAt").asText()),
                    Instant.parse(it.path("referenceAt").asText()),
                )
            }
        return InputEvidence(
            node.path("originalRevision").asInt(),
            Instant.parse(node.path("referenceAt").asText()),
            supplement,
            InputInterpretation.valueOf(node.path("interpretation").asText()),
            node.path("unresolvedReason").takeIf { it.isTextual }?.asText(),
            node.path("protocol").asText(),
        )
    }
}
