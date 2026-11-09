package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.ProposedApprovedTimeTermRef
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_APPROVED_TERMS_REQUEST_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsValidator
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.time.APPROVED_KOREAN_TIME_TERM_VERSION
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTerm
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.util.UUID

class ScheduleDslApprovedTermsProviderContract(
    private val mapper: ObjectMapper,
) {
    private val reader =
        mapper
            .readerFor(Map::class.java)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun requestBytes(
        capsule: ScheduleDslProviderInputCapsule,
        meetingMode: MeetingMode,
    ): ByteArray {
        val old = ScheduleDslContextProviderContract(mapper).requestBytes(capsule)
        val root = map(mapper.readValue(old, Map::class.java)).toMutableMap()
        root["request_contract_version"] = SCHEDULE_DSL_APPROVED_TERMS_REQUEST_CONTRACT
        root["response_contract_version"] = SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT
        root["semantic_version"] = SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION
        root["ast_version"] = SCHEDULE_AST_VERSION
        root["vocabulary_version"] = APPROVED_KOREAN_TIME_TERM_VERSION
        root["context"] = map(root["context"]) + ("meeting_mode" to meetingMode.name)
        root["schema"] = responseSchema()
        root["prompt"] = text(root["prompt"])
            .replace("schedule-dsl-request-2", SCHEDULE_DSL_APPROVED_TERMS_REQUEST_CONTRACT)
            .replace("schedule-dsl-response-2", SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT)
            .replace(SCHEDULE_DSL_SEMANTIC_VERSION, SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION)
            .replace(
                "Never assign numeric defaults to vague time words, dates alone, meals or other imprecise expressions.",
                "Use the approved server vocabulary as a baseline. Context-supported numeric estimates remain INFERRED and NON_PRECISE proposals; never label them as explicit user numbers or confirmation.",
            ).replace(
                "PREFERRED alone cannot create availability.",
                "If availability_root is absent and preference_root is present, use the preference bounds as availability instead of the search-period default.",
            ).replace(
                "Preferences never create hard availability.",
                "If availability_root is present, keep its availability bounds; preferences add credit only inside those bounds.",
            ).replace(
                "A null availability_root grants no availability.",
                "Use the search-period default only when both availability_root and preference_root are absent. Errors, quarantine, or a missing program never authorize this default.",
            ).replace(
                "The semantic3/AST1 response schema and independent meaning/precision rules remain unchanged.",
                "Use the selected response3 schema and preserve independent meaning/precision rules.",
            ) + "\n\n" + TERM_INSTRUCTIONS
        return mapper.writeValueAsBytes(root)
    }

    fun responseSchema(): Map<String, Any?> {
        val root = ScheduleDslProviderContract(mapper).responseSchema()
        val properties = map(root["properties"])
        val results = map(properties["results"])
        val result = map(results["items"])
        val resultProperties = map(result["properties"])
        val condition = map(map(resultProperties["conditions"])["items"])
        val conditionProperties = map(condition["properties"])
        val evidence = map(map(conditionProperties["evidence"])["items"])
        val term =
            objectSchema(
                linkedMapOf(
                    "condition_id" to conditionProperties.getValue("id"),
                    "term" to mapOf("type" to "string", "enum" to ApprovedKoreanTimeTerm.entries.map { it.name }),
                    "local_date" to mapOf("type" to "string", "format" to "date"),
                    "term_evidence" to evidence,
                    "date_evidence" to mapOf("type" to "array", "items" to evidence, "minItems" to 1, "maxItems" to 32),
                ),
            )
        val extendedResult =
            objectSchema(
                resultProperties + (
                    "approved_terms" to
                        mapOf("type" to "array", "items" to term, "minItems" to 0, "maxItems" to 32)
                ),
            )
        return objectSchema(
            properties +
                mapOf(
                    "semantic_version" to mapOf("type" to "string", "enum" to listOf(SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION)),
                    "vocabulary_version" to mapOf("type" to "string", "enum" to listOf(APPROVED_KOREAN_TIME_TERM_VERSION)),
                    "results" to (results + ("items" to extendedResult)),
                ),
        )
    }

    fun parseProviderResponse(
        json: String,
        snapshot: ScheduleEvidenceSnapshot,
        semanticVersion: String,
        astVersion: String,
        vocabularyVersion: String,
        acceptRoomZoneOffsets: Boolean = false,
    ): ScheduleDslApprovedTermsEnvelope =
        try {
            require(
                semanticVersion == SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION &&
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
            val refs = mutableListOf<ProposedApprovedTimeTermRef>()
            val rejectedInputs = mutableSetOf<UUID>()
            val declarations =
                results.map { raw ->
                    val result = fields(raw, setOf("input_ref", "conditions", "program", "approved_terms"))
                    val reference = text(result["input_ref"])
                    val input = UUID.fromString(reference)
                    require(input.toString() == reference)
                    val claims = list(result["conditions"])
                    require(claims.size <= 32)
                    val ids = claims.mapNotNull { (it as? Map<*, *>)?.get("id") as? String }
                    require(ids.distinct().size == ids.size)
                    val terms = list(result["approved_terms"])
                    require(terms.size <= 32)
                    val bound = mutableSetOf<String>()
                    terms.forEach { rawTerm ->
                        val rawFields = map(rawTerm)
                        val id = text(rawFields["condition_id"])
                        require(id in ids && bound.add(id)) { "Invalid approved term reference" }
                        try {
                            val term = fields(rawFields, setOf("condition_id", "term", "local_date", "term_evidence", "date_evidence"))
                            val date = text(term["local_date"])
                            val resolvedDate = LocalDate.parse(date)
                            require(resolvedDate.toString() == date)
                            refs +=
                                ProposedApprovedTimeTermRef(
                                    input,
                                    id,
                                    ApprovedKoreanTimeTerm.valueOf(text(term["term"])),
                                    resolvedDate,
                                    evidence(term["term_evidence"]),
                                    list(term["date_evidence"]).map(::evidence),
                                )
                        } catch (_: RuntimeException) {
                            rejectedInputs += input
                        }
                    }
                    result - "approved_terms"
                }
            // This is a deliberate declaration decoder inside response3, not response-version fallback.
            val declarationJson =
                mapper.writeValueAsString(
                    mapOf(
                        "semantic_version" to SCHEDULE_DSL_SEMANTIC_VERSION,
                        "ast_version" to SCHEDULE_AST_VERSION,
                        "results" to declarations,
                    ),
                )
            val declaration =
                ScheduleDslProviderContract(mapper).parseProviderResponse(
                    declarationJson,
                    snapshot,
                    SCHEDULE_DSL_SEMANTIC_VERSION,
                    SCHEDULE_AST_VERSION,
                    acceptRoomZoneOffsets,
                )
            ScheduleDslApprovedTermsValidator().validateDeclaration(declaration, refs, snapshot, rejectedInputs)
        } catch (_: RuntimeException) {
            throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
        }

    private fun evidence(value: Any?): ProposedIrEvidence {
        val fields = fields(value, setOf("source_id", "revision", "start_code_point", "end_code_point", "quote"))
        return ProposedIrEvidence(
            text(fields["source_id"]),
            integer(fields["revision"]),
            integer(fields["start_code_point"]),
            integer(fields["end_code_point"]),
            text(fields["quote"]),
        )
    }

    private fun map(value: Any?): Map<String, Any?> {
        val raw = requireNotNull(value as? Map<*, *>) { "Invalid approved term object" }
        return raw.entries.associate { (key, item) -> text(key) to item }
    }

    private fun fields(
        value: Any?,
        names: Set<String>,
    ): Map<String, Any?> = map(value).also { require(it.keys == names) }

    private fun list(value: Any?): List<*> = requireNotNull(value as? List<*>) { "Invalid approved term array" }

    private fun text(value: Any?): String = requireNotNull(value as? String) { "Invalid approved term string" }

    private fun integer(value: Any?): Int {
        require(value is Int || value is Long)
        val number = (value as Number).toLong()
        require(number in Int.MIN_VALUE..Int.MAX_VALUE)
        return number.toInt()
    }

    private fun objectSchema(properties: Map<String, Any?>): Map<String, Any?> =
        mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to properties.keys.toList(),
            "additionalProperties" to false,
        )

    companion object {
        private val TERM_INSTRUCTIONS =
            """
            The complete approved vocabulary is ko-time-terms-1. Each term refers to one resolved local date, with inclusive start and exclusive end:
            AM=오전 00:00–12:00; PM=오후 12:00–next-day 00:00; DAWN=새벽 00:00–06:00;
            MORNING=아침 06:00–11:00; LUNCH=점심 11:00–15:00; DAYTIME=낮 09:00–18:00;
            MIDDAY=한낮 11:00–15:00; EVENING=저녁 17:00–22:00; NIGHT=밤 20:00–next-day 06:00;
            LATE_NIGHT=심야 or 한밤중 22:00–next-day 04:00; ALL_DAY=하루종일 00:00–next-day 00:00.
            These are local calendar boundaries, not fixed elapsed UTC durations. Keep timezone and existing DST-boundary uncertainty.
            For an unadjusted approved term keep the original condition NON_PRECISE with null scope or TIME scope with null endpoints.
            A participant's surrounding context may reasonably shrink or expand the baseline, including relative phrases without numeric hours.
            In that case preserve INFERRED and NON_PRECISE with a complete TIME scope containing the proposed local numeric interval, plus its approved baseline term reference.
            Those numbers are a model-context proposal, not a literal user assertion or a fixed offset for that phrase. The server preserves their MODEL_CONTEXT origin.
            Never mark a context-adjusted proposal PRECISE_INTERVAL to bypass that origin. The server may use the proposal only under the explicitly selected contextual matching policy.
            Add approved_terms with condition_id, the exact listed term ID, local_date, exact native term_evidence and nonempty date_evidence.
            The server applies this table. Never emit numeric endpoints inside a term reference. Explicit numeric conditions take precedence over vocabulary intervals.
            When the input directly states the complete numeric interval, preserve EXPLICIT, PRECISE_INTERVAL and its actual endpoints; do not overwrite it with the table.
            Preserve the actual effect, interpretation, assertion, corrections and alternatives. Numeric normalization does not confirm a claim.
            Keep separately stated numeric intervals and constraints in their actual conditions and supported ALL_OF or EXCEPT graph. Never widen their actual scope.
            Return required approved_terms arrays, including empty arrays when no approved baseline reference applies.
            Use the whole input context rather than word replacement. Do not ask again solely because a phrase is relative when a reasonable ordinary interpretation is supported.
            Do not invent unavailable personal facts such as an unknown actual work finish. Preserve materially conflicting interpretations as CHOICE or UNRESOLVED instead of silently merging them.
            A model estimate alone never cancels an asserted unavailability or unresolved exclusion.
            Do not use an INFERRED proposal's supersedes relation to withdraw such a negative condition.
            If the native input explicitly withdraws the earlier negative, retain it as RETRACTED history with the actual withdrawal evidence and keep the new inferred interval independent.
            Genuine EXPLICIT corrections and inferred corrections of positive conditions retain their own supported relationships.
            Keep the actual semantic role and all remaining negative constraints in the AST.
            One reference means one resolved date. Preserve gaps with explicit per-date conditions and source-supported ANY_OF; never merge unmentioned time between dates.
            Do not cite a question, choice label or unavailable choice definition as term evidence. Native evidence must contain the exact lexeme.
            meeting_mode is frozen room context, not provider output and not a requirement to invent a place.
            If availability_root is present, keep its availability bounds; preferences add credit only inside those bounds.
            If availability_root is absent and preference_root is present, use the preference bounds as availability instead of the search-period default.
            Use the search-period default only when both availability_root and preference_root are absent.
            Explicit exclusions take precedence in every case.
            Successful absence of constraints is different from failed or uncertain interpretation. Never replace a failure, rejected condition, incomplete graph or uninterpretable input with an empty program.
            Preserve an uncertain negative as an evidence-supported UNRESOLVED claim in its actual exclusion scope. Unknown temporal scope remains unknown; never erase it to obtain a constraint-free result.
            Unknown negative bounds remain possible exclusions and can prevent precise candidates. Retain the actual ALL_OF, ANY_OF, EXCEPT and CHOICE meaning; independent negative intervals use ANY_OF.
            Preserve an explicit positive availability AST even when its bounds are NON_PRECISE or its choice is unresolved. Never delete that root to obtain the no-constraint default.
            The server, not the provider, applies the default only after structural, evidence, quarantine and program checks. Do not fabricate an AVAILABLE or UNCONSTRAINED claim to express this policy.
            Errors, quarantine, a null program and program diagnostics never authorize search-base fallback. Exclusions win and preference rank remains separate.
            A PREFER annotation remains clipped to its ALLOW base branch; never turn a branch-scoped preference into an unconditional root.
            Preserve existing condition, graph, evidence and response budgets. Never truncate dates or add an unsupported response field.
            """.trimIndent()
    }
}
