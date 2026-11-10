package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.LocatedLiteralEvidence
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_INDEXED_REQUEST_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_WEEKLY_REQUEST_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.submission.domain.dsl.WeeklyAnchorFilter
import com.meetme.server.submission.domain.dsl.WeeklyScheduleApplication
import com.meetme.server.submission.domain.dsl.WeeklyScheduleRule
import com.meetme.server.submission.domain.dsl.WeeklyScheduleRules
import com.meetme.server.submission.domain.dsl.WeeklyTimeKind
import com.meetme.server.submission.domain.dsl.WeeklyTimePattern
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTerm
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.util.UUID

/** Original weekly rules stay native; only server calendar expansion supplies their computed windows. */
class WeeklyEvidenceProviderContract(
    private val mapper: ObjectMapper,
    private val allowPlaceConstraints: Boolean = false,
) {
    private val previous = IndexedEvidenceProviderContract(mapper, allowPlaceConstraints)
    private val reader =
        mapper
            .readerFor(Map::class.java)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun requestBytes(
        capsule: ScheduleDslProviderInputCapsule,
        meetingMode: MeetingMode,
    ): ByteArray {
        val root = reader.readValue<Map<String, Any?>>(previous.requestBytes(capsule, meetingMode)).toMutableMap()
        root["request_contract_version"] = SERVER_EVIDENCE_WEEKLY_REQUEST_CONTRACT
        root["response_contract_version"] = SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT
        root["producer_schema_revision"] = WeeklyScheduleRules.VERSION
        root["weekly_rules_version"] = WeeklyScheduleRules.VERSION
        root["schema"] = responseSchema(capsule.snapshot)
        root["prompt"] = (root.getValue("prompt") as String).replace(
            "$SERVER_EVIDENCE_INDEXED_REQUEST_CONTRACT / $SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT",
            "$SERVER_EVIDENCE_WEEKLY_REQUEST_CONTRACT / $SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT",
        ) + "\n\n" + INSTRUCTIONS
        return mapper.writeValueAsBytes(root).also { require(it.size <= 1_048_576) { "Weekly request byte limit" } }
    }

    fun responseSchema(snapshot: ScheduleEvidenceSnapshot): Map<String, Any?> {
        val root = previous.responseSchema(snapshot)
        val props = map(root["properties"])
        val results = map(props["results"])
        val result = map(results["items"])
        val fields = map(result["properties"])
        val conditions = map(fields["conditions"])
        val items = map(conditions["items"])
        val variants = list(items["anyOf"])
        val original = map(variants[1])
        val index = mapOf("type" to "integer", "minimum" to 0, "maximum" to ServerEvidenceCatalog.create(snapshot).entries.size - 1)

        fun refs(min: Int) = mapOf("type" to "array", "items" to index, "minItems" to min, "maxItems" to 32)

        fun strings(
            values: List<String>,
            min: Int,
            max: Int,
        ) = mapOf(
            "type" to "array",
            "items" to mapOf("type" to "string", "enum" to values),
            "minItems" to min,
            "maxItems" to max,
        )
        val date = mapOf("type" to "string", "format" to "date", "pattern" to "^\\d{4}-\\d{2}-\\d{2}$")
        val clock = mapOf("type" to "string", "pattern" to "^(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9])?$")
        val filter =
            obj(
                mapOf(
                    "months" to strings(Month.entries.map { it.name }, 0, 12),
                    "from_inclusive" to nullable(date),
                    "until_exclusive" to nullable(date),
                    "excluded_dates" to mapOf("type" to "array", "items" to date, "minItems" to 0, "maxItems" to 32),
                    "evidence" to refs(0),
                ),
            )
        val time =
            obj(
                mapOf(
                    "kind" to mapOf("type" to "string", "enum" to WeeklyTimeKind.entries.map { it.name }),
                    "lower" to nullable(clock),
                    "upper" to nullable(clock),
                    "end_day_offset" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 1),
                    "clock_evidence" to refs(0),
                    "term" to nullable(mapOf("type" to "string", "enum" to ApprovedKoreanTimeTerm.entries.map { it.name })),
                    "term_evidence" to nullable(index),
                ),
            )
        val weekly =
            obj(
                mapOf(
                    "kind" to mapOf("type" to "string", "enum" to listOf("WEEKLY")),
                    "weekdays" to strings(DayOfWeek.entries.map { it.name }, 1, 7),
                    "weekday_evidence" to refs(1),
                    "anchor_filter" to filter,
                    "time" to time,
                ),
            )
        val branch =
            original + (
                "properties" to (
                    map(original["properties"]) +
                        mapOf(
                            "temporal" to mapOf("anyOf" to listOf(weekly)),
                            "temporal_resolution" to mapOf("type" to "string", "enum" to listOf("NON_PRECISE")),
                        )
                )
            )
        val next =
            result + ("properties" to (fields + ("conditions" to (conditions + ("items" to (items + ("anyOf" to variants + branch)))))))
        return root +
            mapOf(
                "properties" to (
                    props + ("results" to (results + ("items" to next))) +
                        ("weekly_rules_version" to mapOf("type" to "string", "enum" to listOf(WeeklyScheduleRules.VERSION)))
                ),
                "required" to ((root["required"] as List<*>) + "weekly_rules_version"),
            )
    }

    fun parseProviderResponse(
        json: String,
        snapshot: ScheduleEvidenceSnapshot,
        semanticVersion: String,
        astVersion: String,
        vocabularyVersion: String,
    ): ScheduleDslStructuralEnvelope =
        try {
            require(json.toByteArray(Charsets.UTF_8).size <= 262_144) { "WEEKLY_RAW_BYTE_LIMIT" }
            val root = reader.readValue<Map<String, Any?>>(json)
            require(root["weekly_rules_version"] == WeeklyScheduleRules.VERSION) { "WEEKLY_RULES_MARKER" }
            val catalog = ServerEvidenceCatalog.create(snapshot)
            val applications = mutableListOf<WeeklyScheduleApplication>()

            fun selection(
                value: Any?,
                input: UUID,
            ): LocatedLiteralEvidence {
                require(value is Int || value is Long)
                val n = (value as Number).toLong()
                require(n >= 0 && n < catalog.entries.size)
                return catalog.resolve(input, catalog.entries[n.toInt()].id)
            }

            fun proofs(
                value: Any?,
                input: UUID,
            ) = list(value).map { selection(it, input) }.also { require(it.size <= 32) }

            fun date(value: Any?): LocalDate? = value?.let { LocalDate.parse(it as String).also { d -> require(d.toString() == it) } }

            fun clock(value: Any?): LocalTime? =
                value?.let {
                    require(
                        it is String && it.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9])?")),
                    )
                    ; LocalTime.parse(it)
                }
            val results =
                list(root["results"]).map { raw ->
                    val result = map(raw)
                    val input = UUID.fromString(result["input_ref"] as String)
                    val conditions =
                        list(result["conditions"]).map { rawClaim ->
                            val claim = map(rawClaim)
                            val atom = map(claim["temporal"])
                            if (atom["kind"] != "WEEKLY") return@map claim
                            require(claim["temporal_resolution"] == "NON_PRECISE") { "Weekly original precision" }
                            fields(atom, setOf("kind", "weekdays", "weekday_evidence", "anchor_filter", "time"))
                            val days = list(atom["weekdays"]).map { DayOfWeek.valueOf(it as String) }
                            require(days.distinct().size == days.size)
                            val filter = map(atom["anchor_filter"])
                            fields(filter, setOf("months", "from_inclusive", "until_exclusive", "excluded_dates", "evidence"))
                            val months = list(filter["months"]).map { Month.valueOf(it as String) }
                            require(months.distinct().size == months.size)
                            val excluded = list(filter["excluded_dates"]).map { requireNotNull(date(it)) }
                            require(
                                excluded.distinct().size == excluded.size,
                            )
                            val time = map(atom["time"])
                            fields(time, setOf("kind", "lower", "upper", "end_day_offset", "clock_evidence", "term", "term_evidence"))
                            val end = time["end_day_offset"]
                            require(end is Int || end is Long)
                            require((end as Number).toLong() in 0..1)
                            val rule =
                                WeeklyScheduleRule(
                                    days.toSet(),
                                    WeeklyTimePattern(
                                        WeeklyTimeKind.valueOf(time["kind"] as String),
                                        clock(time["lower"]),
                                        clock(time["upper"]),
                                        end.toInt(),
                                        proofs(time["clock_evidence"], input),
                                        time["term"]?.let { ApprovedKoreanTimeTerm.valueOf(it as String) },
                                        time["term_evidence"]?.let { selection(it, input) },
                                    ),
                                    proofs(atom["weekday_evidence"], input),
                                    WeeklyAnchorFilter(
                                        months.toSet(),
                                        date(filter["from_inclusive"]),
                                        date(filter["until_exclusive"]),
                                        excluded.toSet(),
                                        proofs(filter["evidence"], input),
                                    ),
                                )
                            val app = WeeklyScheduleRules.expand(input, claim["id"] as String, rule, snapshot)
                            applications += app
                            val ruleProofs =
                                rule.weekdayEvidence + rule.filter.evidence + rule.time.clockEvidence +
                                    listOfNotNull(rule.time.termEvidence)
                            val indices =
                                ruleProofs.map { p ->
                                    catalog.entries.indexOfFirst { it.inputId == input && it.id == p.catalogId }.also {
                                        require(
                                            it >= 0,
                                        )
                                    }
                                }
                            val merged = (list(claim["evidence"]) + indices).distinct()
                            require(merged.size in 1..32)
                            claim + mapOf("evidence" to merged, "temporal" to mapOf("kind" to "UNKNOWN"))
                        }
                    result + ("conditions" to conditions)
                }
            val base =
                previous.parseProviderResponse(
                    mapper.writeValueAsString((root - "weekly_rules_version") + ("results" to results)),
                    snapshot,
                    semanticVersion,
                    astVersion,
                    vocabularyVersion,
                )
            ScheduleDslStructuralValidator().validateDeclaration(
                base.base,
                base.locatedEvidence,
                base.directionalApplications,
                snapshot,
                base.placeConstraints,
                base.placeConstraintsVersion,
                base.evidenceCatalogVersion,
                applications,
                WeeklyScheduleRules.VERSION,
            )
        } catch (failure: NaturalLanguageParserException) {
            throw failure
        } catch (failure: RuntimeException) {
            val code = failure.message?.takeIf { it in setOf("WEEKLY_RAW_BYTE_LIMIT", "WEEKLY_RULES_MARKER") } ?: "WEEKLY_INVALID_RESPONSE"
            throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE, cause = IllegalArgumentException(code))
        }

    private fun map(value: Any?): Map<String, Any?> =
        (value as? Map<*, *>)?.entries?.associate { (it.key as String) to it.value }
            ?: throw IllegalArgumentException("Expected weekly object")

    private fun list(value: Any?) = value as? List<*> ?: throw IllegalArgumentException("Expected weekly array")

    private fun fields(
        value: Map<String, Any?>,
        expected: Set<String>,
    ) {
        require(value.keys == expected) { "Unexpected weekly fields" }
    }

    private fun obj(fields: Map<String, Any?>) =
        mapOf(
            "type" to "object",
            "properties" to fields,
            "required" to fields.keys.toList(),
            "additionalProperties" to false,
        )

    private fun nullable(value: Map<String, Any?>) = mapOf("anyOf" to listOf(value, mapOf("type" to "null")))

    companion object {
        private const val INSTRUCTIONS = """
            Weekly rules (weekly-schedule-rule-1):
            Return weekly_rules_version exactly as supplied. Represent recurring weekdays (including bare weekdays,
            business weekdays and weekends) with ONE WEEKLY temporal atom per independently stated time rule, rather
            than enumerating selected calendar dates. The server expands every matching anchor across the ENTIRE meeting
            search period, even across months or years. Preserve the original condition ID and all graph relationships.
            Keep that original condition NON_PRECISE; numeric resolved windows are server derivations. WHOLE_DAY covers
            the whole selected civil day, BOUNDS keeps supplied lower/upper or null one-sided bounds, TERM selects the
            approved baseline without clocks, and UNKNOWN retains genuinely missing personal boundaries.
            Each WEEKLY selects weekdays and own-source weekday_evidence. anchor_filter contains only restrictions
            supported by THIS participant's original or actual linked supplement: months, from_inclusive,
            until_exclusive, excluded_dates and native evidence. Empty filters mean the whole search period. Never prune
            an individual's recurring dates to another person's month restriction or to the final attendance intersection.
            Whole-room context still helps interpretation; do not invent cross-person dependencies unsupported by the
            existing contract. A one-off date, ordinal weekday or last weekday of a month keeps the existing date atom;
            never silently turn it into every weekday. Preserve independent exclusions, corrections, branch and role
            relationships. A civil-date prohibition stays a BLOCK/EXCEPT condition when it also restricts overnight tails.
            Context-adjusted recurring time terms may use INFERRED BOUNDS with the selected native term/context
            evidence and the approved table as a baseline. Preserve explicit numeric bounds; never assign guessed
            clocks to genuinely unknown personal events.
            time.kind WHOLE_DAY/UNKNOWN has null lower,upper,term,term_evidence, end_day_offset:0 and clock_evidence:[].
            BOUNDS requires lower or upper and native clock_evidence, with null term/term_evidence. Do not infer rollover:
            end_day_offset:1 requires explicit overnight meaning and both clocks. Missing bounds end at the civil day
            boundary. TERM requires term/term_evidence, null clocks, end_day_offset:0 and clock_evidence:[].
            All *_evidence and filter.evidence select the same issued integer catalog entries, owned by input_ref.
            Filters apply to anchor dates; an explicitly overnight anchor keeps its next-day tail, clipped to the search.
            Do not merge a supplementary answer into the original or infer an answer that was never submitted.
        """
    }
}
