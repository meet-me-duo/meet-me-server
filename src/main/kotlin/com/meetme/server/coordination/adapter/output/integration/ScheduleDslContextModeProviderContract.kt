package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.ProposedScheduleModeRestriction
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_CONTEXT_MODE_POLICY_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_APPROVED_TERMS_REQUEST_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_CONTEXT_MODE_REQUEST_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_CONTEXT_MODE_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeValidator
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.time.APPROVED_KOREAN_TIME_TERM_VERSION
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Explicit C4 composition; older request bytes and decoders remain unchanged. */
class ScheduleDslContextModeProviderContract(
    private val mapper: ObjectMapper,
) {
    private val approved = ScheduleDslApprovedTermsProviderContract(mapper)
    private val reader =
        mapper
            .readerFor(Map::class.java)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun requestBytes(
        capsule: ScheduleDslProviderInputCapsule,
        meetingMode: MeetingMode,
    ): ByteArray {
        require(capsule.snapshot.context.policyVersion == SCHEDULE_CONTEXT_MODE_POLICY_VERSION)
        val root = map(mapper.readValue(approved.requestBytes(capsule, meetingMode), Map::class.java)).toMutableMap()
        root["request_contract_version"] = SCHEDULE_DSL_CONTEXT_MODE_REQUEST_CONTRACT
        root["response_contract_version"] = SCHEDULE_DSL_CONTEXT_MODE_RESPONSE_CONTRACT
        root["semantic_version"] = SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION
        root["schema"] = responseSchema()
        val prompt =
            text(root["prompt"])
                .replace(SCHEDULE_DSL_APPROVED_TERMS_REQUEST_CONTRACT, SCHEDULE_DSL_CONTEXT_MODE_REQUEST_CONTRACT)
                .replace(SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT, SCHEDULE_DSL_CONTEXT_MODE_RESPONSE_CONTRACT)
                .replace(SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION, SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION)
                .replace("response3 schema", "response4 schema")
        root["prompt"] = refinePrompt(prompt) + "\n\n" + INSTRUCTIONS
        return mapper.writeValueAsBytes(root)
    }

    private fun refinePrompt(prompt: String): String =
        PROMPT_REFINEMENTS.fold(prompt) { refined, (previous, replacement) ->
            check(previous in refined)
            refined.replace(previous, replacement)
        }

    fun responseSchema(): Map<String, Any?> {
        val old = approved.responseSchema()
        val properties = map(old["properties"])
        val results = map(properties["results"])
        val resultProperties = map(map(results["items"])["properties"])
        val condition = map(map(map(resultProperties["conditions"])["items"])["properties"])
        val localScope =
            objectSchema(
                linkedMapOf(
                    "dimension" to mapOf("type" to "string", "enum" to listOf("TIME")),
                    "start" to mapOf("anyOf" to listOf(mapOf("type" to "string"), mapOf("type" to "null"))),
                    "end" to mapOf("anyOf" to listOf(mapOf("type" to "string"), mapOf("type" to "null"))),
                ),
            )
        val restriction =
            objectSchema(
                linkedMapOf(
                    "id" to condition.getValue("id"),
                    "scope" to mapOf("anyOf" to listOf(localScope, mapOf("type" to "null"))),
                    "permitted_modes" to
                        mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "string", "enum" to listOf("REMOTE", "IN_PERSON")),
                            "minItems" to 1,
                            "maxItems" to 2,
                        ),
                    "mode_interpretation" to condition.getValue("interpretation"),
                    "time_interpretation" to condition.getValue("interpretation"),
                    "temporal_resolution" to condition.getValue("temporal_resolution"),
                    "evidence" to condition.getValue("evidence"),
                    "assertion" to condition.getValue("assertion"),
                    "supersedes" to condition.getValue("supersedes"),
                    "alternative_group" to condition.getValue("alternative_group"),
                    "alternative" to condition.getValue("alternative"),
                ),
            )
        return objectSchema(
            properties +
                mapOf(
                    "semantic_version" to mapOf("type" to "string", "enum" to listOf(SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION)),
                    "results" to
                        (
                            results + (
                                "items" to
                                    objectSchema(
                                        resultProperties + (
                                            "mode_restrictions" to
                                                mapOf("type" to "array", "items" to restriction, "minItems" to 0, "maxItems" to 32)
                                        ),
                                    )
                            )
                        ),
                ),
        )
    }

    fun parseProviderResponse(
        json: String,
        snapshot: ScheduleEvidenceSnapshot,
        semanticVersion: String,
        astVersion: String,
        vocabularyVersion: String,
    ): ScheduleDslContextModeEnvelope =
        try {
            require(
                semanticVersion == SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION &&
                    astVersion == SCHEDULE_AST_VERSION &&
                    vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION,
            )
            require(json.toByteArray(Charsets.UTF_8).size <= 262_144)
            val root = fields(reader.readValue<Map<*, *>>(json), setOf("semantic_version", "ast_version", "vocabulary_version", "results"))
            require(
                root["semantic_version"] == semanticVersion &&
                    root["ast_version"] == astVersion &&
                    root["vocabulary_version"] == vocabularyVersion,
            )
            val results = list(root["results"])
            require(results.size in 2..50)
            val restrictions = mutableListOf<ProposedScheduleModeRestriction>()
            val declarations =
                results.map { raw ->
                    val result = fields(raw, setOf("input_ref", "conditions", "program", "approved_terms", "mode_restrictions"))
                    val reference = text(result["input_ref"])
                    val input = UUID.fromString(reference)
                    require(input.toString() == reference)
                    val modes = list(result["mode_restrictions"])
                    require(modes.size <= 32)
                    restrictions += modes.map { mode(input, it, snapshot.context.timeZone) }
                    result - "mode_restrictions"
                }
            // Deliberate C3 declaration decoder inside C4, never response-version fallback.
            val base =
                approved.parseProviderResponse(
                    mapper.writeValueAsString(
                        root +
                            mapOf(
                                "semantic_version" to SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION,
                                "results" to declarations,
                            ),
                    ),
                    snapshot,
                    SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION,
                    astVersion,
                    vocabularyVersion,
                    acceptRoomZoneOffsets = true,
                )
            ScheduleDslContextModeValidator().validateDeclaration(base, restrictions, snapshot)
        } catch (_: RuntimeException) {
            throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
        }

    private fun mode(
        input: UUID,
        value: Any?,
        roomTimeZone: String,
    ): ProposedScheduleModeRestriction {
        val raw =
            fields(
                value,
                setOf(
                    "id",
                    "scope",
                    "permitted_modes",
                    "mode_interpretation",
                    "time_interpretation",
                    "temporal_resolution",
                    "evidence",
                    "assertion",
                    "supersedes",
                    "alternative_group",
                    "alternative",
                ),
            )
        val permitted = list(raw["permitted_modes"]).map { MeetingMode.valueOf(text(it)) }
        require(permitted.size in 1..2 && permitted.distinct() == permitted && MeetingMode.EITHER !in permitted)
        val scope =
            raw["scope"]?.let {
                val fields = fields(it, setOf("dimension", "start", "end"))
                IrScope(
                    IrDimension.valueOf(text(fields["dimension"])),
                    optional(fields["start"])?.let { ScheduleDslRoomLocalTime.parse(it, roomTimeZone) },
                    optional(fields["end"])?.let { ScheduleDslRoomLocalTime.parse(it, roomTimeZone) },
                )
            }
        return ProposedScheduleModeRestriction(
            input,
            text(raw["id"]),
            scope,
            permitted.toSet(),
            IrInterpretation.valueOf(text(raw["mode_interpretation"])),
            IrInterpretation.valueOf(text(raw["time_interpretation"])),
            IrTemporalResolution.valueOf(text(raw["temporal_resolution"])),
            list(raw["evidence"]).map(::evidence),
            IrAssertion.valueOf(text(raw["assertion"])),
            list(raw["supersedes"]).map(::text),
            optional(raw["alternative_group"]),
            optional(raw["alternative"]),
        )
    }

    private fun evidence(value: Any?): ProposedIrEvidence {
        val raw = fields(value, setOf("source_id", "revision", "start_code_point", "end_code_point", "quote"))
        return ProposedIrEvidence(
            text(raw["source_id"]),
            integer(raw["revision"]),
            integer(raw["start_code_point"]),
            integer(raw["end_code_point"]),
            text(raw["quote"]),
        )
    }

    private fun map(value: Any?): Map<String, Any?> =
        requireNotNull(value as? Map<*, *>).entries.associate { (key, item) -> text(key) to item }

    private fun fields(
        value: Any?,
        names: Set<String>,
    ): Map<String, Any?> = map(value).also { require(it.keys == names) }

    private fun list(value: Any?): List<*> = requireNotNull(value as? List<*>)

    private fun text(value: Any?): String = requireNotNull(value as? String)

    private fun optional(value: Any?): String? = if (value == null) null else text(value)

    private fun integer(value: Any?): Int {
        require(value is Int || value is Long)
        val number = (value as Number).toLong()
        require(number in Int.MIN_VALUE..Int.MAX_VALUE)
        return number.toInt()
    }

    private fun objectSchema(properties: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf("type" to "object", "properties" to properties, "required" to properties.keys.toList(), "additionalProperties" to false)

    companion object {
        private val PROMPT_REFINEMENTS =
            listOf(
                "Each claim and each graph node needs exact source_id, source revision, Unicode code-point span and " +
                    "matching quote." to
                    "Copy each quote exactly from that input_ref's named source and revision. Spans count decoded " +
                    "Unicode code points, start inclusive and end exclusive; a single-code-point emoji counts as one; " +
                    "composed emoji may contain multiple code points; a decoded newline counts as one. Do not count " +
                    "UTF-8 bytes, UTF-16 units or JSON escape characters.",
                "Matching a span is not proof that an interpretation is correct. Do not replace original text with " +
                    "an answer." to
                    "Check source[start:end] equals quote before returning it. Preserve negation, exception and " +
                    "correction context; matching a substring does not prove the condition's effect. Approved term " +
                    "evidence must be its exact supported lexeme, with separately supported date evidence inside the " +
                    "condition evidence. Do not replace original text with an answer.",
                "CLAIM_REF references one condition, with no children. ALLOW roles reference AVAILABLE/UNCONSTRAINED " +
                    "only;\nBLOCK references UNAVAILABLE/UNRESOLVED only; PREFER roles reference PREFERRED only." to
                    "CLAIM_REF references one condition, with no children. Roots start roles: availability_root starts " +
                    "ALLOW; exclusion_root starts BLOCK; preference_root starts PREFER. Each CLAIM_REF must match its " +
                    "current role: ALLOW accepts AVAILABLE/UNCONSTRAINED; BLOCK accepts UNAVAILABLE/UNRESOLVED; PREFER " +
                    "accepts PREFERRED. Preserve the selected contract's existing EXCEPT and PREFER role transitions; do " +
                    "not treat every descendant as a direct root effect. Never change an uncertain effect merely to " +
                    "satisfy a role.",
                "All nodes must be reachable from a root." to
                    "Every node must be reachable from an actual root. Do not emit a duplicate orphan reference or " +
                    "delete a meaningful unknown/negative condition to obtain reachability.",
                "Every condition must be referenced or retained in history_condition_ids.\nHistory can contain " +
                    "nonasserted or syntactically superseded claims;\nit cannot hide an uncorrected asserted claim." to
                    "Every condition must be referenced or genuinely historical. history_condition_ids cannot hide " +
                    "ASSERTED conditions unless syntactically superseded. Keep corrections and branch dependencies; do " +
                    "not guess a missing relationship.",
                "NON_PRECISE bounds describe possible influence, not a precise positive interval." to
                    "NON_PRECISE bounds describe possible influence, not a precise positive interval. Current scope " +
                    "representation supports a complete finite interval or genuinely unknown scope, not a one-ended hard " +
                    "availability interval. Never fabricate a missing endpoint or actual attendance start. Preserve the " +
                    "condition/evidence and uncertainty when precise bounds cannot be represented.",
                "Explicit exclusions take precedence in every case." to
                    "Date-only availability denotes its stated local day from 00:00 to next-day 00:00. Context-derived " +
                    "day-wide AVAILABLE bounds remain INFERRED and NON_PRECISE, not explicit user clock numbers. " +
                    "Preference-only bounds may serve as availability under the selected policy; explicit unavailability " +
                    "takes priority. An existing UNRESOLVED claim is not automatically confirmed by those rules.",
                "Do not cite a question, choice label or unavailable choice definition as term evidence. Native " +
                    "evidence must contain the exact lexeme." to
                    "Do not cite a question, choice label or unavailable choice definition as term evidence. Native " +
                    "evidence must contain the exact lexeme. Do not use a fabricated approved-term lexeme for a " +
                    "contextual day-wide interpretation. ALL_DAY term evidence must match its actual supported lexeme. " +
                    "Policy/context-derived full-day bounds need the selected contextual derivation; they are not new " +
                    "explicit user clock numbers.",
            )

        private val INSTRUCTIONS =
            """
            Use the selected response4 schema and return mode_restrictions for every input, including an empty array when none are stated.
            The native text, trusted clarification linkage and approved eleven-term table above belong to this single interpretation request.
            A complete context-supported numeric time proposal is allowed even without an approved vocabulary word.
            Preserve INFERRED and NON_PRECISE on such proposals; do not invent a word, evidence quote, approved_terms entry or explicit user confirmation.
            The server records MODEL_CONTEXT derivations separately and performs the interval calculations.
            Prepared time terms are baselines; interpret supported shrinking or expansion from the full input context, preserving the original evidence.
            Keep explicit unavailability authoritative. A model estimate never withdraws an explicit exclusion.
            Keep known availability independent from hard mode_restrictions. For example, a Thursday remote-only statement restricts Thursday mode
            even when an ANY_OF node includes broader Thursday and Friday availability.
            Each mode restriction belongs only to its result input_ref, uses native evidence and a local TIME scope, and permits only atomic REMOTE/IN_PERSON.
            Distinguish mode_interpretation from time_interpretation and temporal_resolution. Preserve unresolved mode or scope instead of authorizing it.
            Quoted, retracted, correction and mutually exclusive alternative metadata must remain explicit.
            Meeting category alone does not require any place. Preserve actual place/distance constraints when stated, but never invent a place.
            Preserve availability-first, preference-only availability, default only with neither root, and explicit-exclusion precedence.
            """.trimIndent()
    }
}
