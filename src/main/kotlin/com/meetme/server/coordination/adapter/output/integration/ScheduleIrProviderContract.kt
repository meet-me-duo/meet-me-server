package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrAvailabilityState
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrProblem
import com.meetme.server.submission.domain.ir.IrQuarantine
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.ProposedIrCondition
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import com.meetme.server.submission.domain.ir.ProposedIrResult
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_VERSION
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.ir.ScheduleIrEnvelope
import com.meetme.server.submission.domain.ir.ScheduleIrProposal
import com.meetme.server.submission.domain.ir.ScheduleIrValidator
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import java.util.UUID

/** Explicit new response contract; the legacy provider contract remains byte-for-byte unchanged. */
class ScheduleIrProviderContract(
    objectMapper: ObjectMapper,
) {
    private val reader =
        objectMapper
            .readerFor(Map::class.java)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun parseProviderResponse(
        json: String,
        snapshot: ScheduleEvidenceSnapshot,
        semanticVersion: String,
    ): ScheduleIrEnvelope =
        try {
            require(semanticVersion == SCHEDULE_IR_VERSION && json.toByteArray(Charsets.UTF_8).size <= 262_144)
            val root = fields(reader.readValue<Map<*, *>>(json), setOf("semantic_version", "results"))
            require(root["semantic_version"] == semanticVersion)
            val results = list(root["results"])
            require(results.size in 2..50)
            val proposal = ScheduleIrProposal(semanticVersion, results.map(::result))
            ScheduleIrValidator().validate(proposal, snapshot)
        } catch (_: RuntimeException) {
            // Do not retain parser exceptions containing raw JSON, quotes or answers.
            throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
        }

    private fun result(value: Any?): ProposedIrResult {
        val map = fields(value, setOf("input_ref", "availability", "conditions"))
        val inputRef = text(map["input_ref"])
        val inputId = UUID.fromString(inputRef)
        require(inputRef == inputId.toString())
        val conditions = list(map["conditions"])
        require(conditions.size <= 32)
        val suppliedIds = conditions.mapNotNull { (it as? Map<*, *>)?.get("id") as? String }
        require(suppliedIds.distinct().size == suppliedIds.size)
        val rejected = mutableListOf<IrQuarantine>()
        val parsed =
            conditions.mapNotNull { raw ->
                try {
                    condition(raw)
                } catch (_: RuntimeException) {
                    val id = ((raw as? Map<*, *>)?.get("id") as? String)?.takeIf { it.matches(TOKEN) }
                    rejected += IrQuarantine(id, IrProblem.SHAPE, null)
                    null
                }
            }
        return ProposedIrResult(inputId, IrAvailabilityState.valueOf(text(map["availability"])), parsed, rejected)
    }

    private fun condition(value: Any?): ProposedIrCondition {
        val map =
            fields(
                value,
                setOf("id", "effect", "interpretation", "scope", "evidence", "assertion", "supersedes", "alternative_group", "alternative"),
            )
        val id = text(map["id"])
        require(id.matches(TOKEN))
        val scope =
            map["scope"]?.let {
                val fields = fields(it, setOf("dimension", "start", "end"))
                IrScope(
                    IrDimension.valueOf(text(fields["dimension"])),
                    optionalText(fields["start"])?.let(LocalDateTime::parse),
                    optionalText(fields["end"])?.let(LocalDateTime::parse),
                )
            }
        val evidence =
            list(map["evidence"]).map {
                val fields = fields(it, setOf("source_id", "revision", "start_code_point", "end_code_point", "quote"))
                ProposedIrEvidence(
                    text(fields["source_id"]),
                    integer(fields["revision"]),
                    integer(fields["start_code_point"]),
                    integer(fields["end_code_point"]),
                    text(fields["quote"]),
                )
            }
        return ProposedIrCondition(
            id,
            IrEffect.valueOf(text(map["effect"])),
            IrInterpretation.valueOf(text(map["interpretation"])),
            scope,
            evidence,
            IrAssertion.valueOf(text(map["assertion"])),
            list(map["supersedes"]).map(::text),
            optionalText(map["alternative_group"]),
            optionalText(map["alternative"]),
        )
    }

    private fun fields(
        value: Any?,
        names: Set<String>,
    ): Map<*, *> {
        val fields = value as? Map<*, *> ?: invalid()
        require(fields.keys == names)
        return fields
    }

    private fun list(value: Any?): List<*> = value as? List<*> ?: invalid()

    private fun text(value: Any?): String = value as? String ?: invalid()

    private fun optionalText(value: Any?): String? = if (value == null) null else text(value)

    private fun integer(value: Any?): Int {
        require(value is Int || value is Long)
        val number = (value as Number).toLong()
        require(number in Int.MIN_VALUE..Int.MAX_VALUE)
        return number.toInt()
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid IR shape")

    companion object {
        private val TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
    }
}
