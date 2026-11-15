package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_TEMPORAL_RANGE_REQUEST_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_TEMPORAL_RANGE_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_WEEKLY_REQUEST_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.TemporalRangeAdmission
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import org.slf4j.LoggerFactory
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper

/** New producer admission with unchanged native format12; historical generation five remains independent. */
class TemporalRangeEvidenceProviderContract(
    private val mapper: ObjectMapper,
    private val allowPlaceConstraints: Boolean = false,
) {
    private val previous = WeeklyEvidenceProviderContract(mapper, allowPlaceConstraints)
    private val logger = LoggerFactory.getLogger(TemporalRangeEvidenceProviderContract::class.java)
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
        root["request_contract_version"] = SERVER_EVIDENCE_TEMPORAL_RANGE_REQUEST_CONTRACT
        root["response_contract_version"] = SERVER_EVIDENCE_TEMPORAL_RANGE_RESPONSE_CONTRACT
        root["producer_schema_revision"] = TemporalRangeAdmission.VERSION
        root["temporal_range_admission_version"] = TemporalRangeAdmission.VERSION
        root["clock_evidence_policy"] =
            mapOf(
                "version" to "clock-evidence-policy-1",
                "atomic_when_available" to true,
                "context_fallback_without_atomic" to true,
                "genuine_unknown" to "PRESERVE_UNKNOWN",
            )
        root["schema"] = responseSchema(capsule.snapshot)
        root["prompt"] = (root.getValue("prompt") as String).replace(
            "$SERVER_EVIDENCE_WEEKLY_REQUEST_CONTRACT / $SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT",
            "$SERVER_EVIDENCE_TEMPORAL_RANGE_REQUEST_CONTRACT / $SERVER_EVIDENCE_TEMPORAL_RANGE_RESPONSE_CONTRACT",
        ) + "\n\n" + INSTRUCTIONS
        return mapper.writeValueAsBytes(root).also { require(it.size <= 1_048_576) { "TEMPORAL_RANGE_REQUEST_BYTE_LIMIT" } }
    }

    fun responseSchema(snapshot: ScheduleEvidenceSnapshot): Map<String, Any?> {
        val root = previous.responseSchema(snapshot)
        val properties = root.getValue("properties") as Map<*, *>
        return root +
            mapOf(
                "properties" to
                    (
                        properties +
                            (
                                "temporal_range_admission_version" to
                                    mapOf("type" to "string", "enum" to listOf(TemporalRangeAdmission.VERSION))
                            )
                    ),
                "required" to ((root.getValue("required") as List<*>) + "temporal_range_admission_version"),
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
            require(json.toByteArray(Charsets.UTF_8).size <= 262_144) { "TEMPORAL_RANGE_RAW_BYTE_LIMIT" }
            val root = reader.readValue<Map<String, Any?>>(json)
            require(root["temporal_range_admission_version"] == TemporalRangeAdmission.VERSION) { "TEMPORAL_RANGE_ADMISSION_MARKER" }
            TemporalRangeRawScopeSyntax.validate(root, snapshot.context.timeZone)
            previous
                .parseProviderResponse(
                    mapper.writeValueAsString(root - "temporal_range_admission_version"),
                    snapshot,
                    semanticVersion,
                    astVersion,
                    vocabularyVersion,
                ).also {
                    val coverage = TemporalRangeAdmission.assess(it, snapshot)
                    logger.info(
                        "event=temporal_range_admission checkedConditions={} unclassifiedConditions={} version={}",
                        coverage.checkedConditions,
                        coverage.unclassifiedConditions,
                        TemporalRangeAdmission.VERSION,
                    )
                }
        } catch (failure: NaturalLanguageParserException) {
            throw failure
        } catch (failure: RuntimeException) {
            val safeCode = failure.message?.takeIf { it.matches(Regex("TEMPORAL_RANGE_[A-Z_]+")) } ?: "TEMPORAL_RANGE_INVALID_RESPONSE"
            throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE, cause = IllegalArgumentException(safeCode))
        }

    companion object {
        private const val INSTRUCTIONS = """
            Temporal range admission (temporal-range-admission-1):
            Return temporal_range_admission_version exactly as supplied. Interpret each owning input separately.
            A clock-only availability, unavailability or preference range with no stated meeting date or weekday is a
            recurring rule for EVERY date in the CURRENT meeting search period. Represent its independent base with
            WEEKLY, all seven weekdays, and an empty anchor_filter. Its weekday_evidence may select the owning clock
            clause; it is context for implicit all-search scope, not an invented weekday quotation. Never choose the
            first search date as local_date merely because the source lacks a date. Search start is not a date claim.
            Keep each separately stated weekday/date exception in its own condition and BLOCK/EXCEPT graph role;
            do not narrow a date-less base to a peer's dates, exception weekdays, or recommendation intersections.
            Genuine specific dates, ordinal/last weekdays, relative dates and explicitly scoped recurring rules retain
            their stated date/weekday authority. Do not broaden them to the entire period.
            Read the COMPLETE active clock relation before choosing an atom. A stated from/to range has TWO effective
            boundaries: lower and upper must both be present with the native clock values, even if the lower also says
            after. Never discard its explicit end or replace it with civil midnight.
            Apply clock_evidence_policy (clock-evidence-policy-1) separately to EACH endpoint:
            atomic_when_available: select the exact issued owning CLOCK entry for a literal endpoint when available.
            A numeric standalone range with two issued endpoint CLOCK entries requires BOTH distinct selections in
            clock_evidence. Putting an issued endpoint only in condition meaning evidence or selecting one broad
            CONTEXT instead does not prove its bound role. Use exact owning clause context and endpoint selections.
            context_fallback_without_atomic: the catalog is not a complete clock or duration recognizer. When an
            unambiguous literal clock is written in words or an explicit duration determines an endpoint but no exact
            CLOCK entry exists for that endpoint, select its issued owning CONTEXT evidence. Preserve both known
            bounds from the complete source relation. An issued lower CLOCK can coexist with CONTEXT for a duration
            derived upper bound. Never invent a catalog entry, reference index, source quotation or omitted endpoint.
            Missing atomic CLOCK evidence alone does not make an otherwise stated time UNKNOWN. Keep the native
            interpretation/provenance, including INFERRED when applicable; do not relabel it to conceal uncertainty.
            genuine_unknown: PRESERVE_UNKNOWN for a genuinely missing personal boundary or ambiguous relation.
            Genuinely one-sided after/before conditions keep only their stated endpoint. A class, performance or other
            event range does not supply the endpoints of a different availability clause. Retractions, corrections,
            alternatives and actually linked supplements preserve their existing native graph/source roles; do not
            revive a superseded range or force two source clocks into an unrelated condition. If a personal time is
            unknown, preserve UNKNOWN and its uncertainty rather than inventing numeric availability.
            RAW_SCOPE TIME endpoints, when non-null, must be complete valid ISO timestamps with an actual date,
            uppercase T and a civil clock. A clock alone or a date prefix without a clock is not a timestamp.
            Use RAW_SCOPE for explicitly dated complete timestamp relations, including stated overnight endpoints;
            use WEEKLY for recurring civil clocks. Never encode a clock range into the year/month/day fields or invent
            a date to make a timestamp. Retain valid null/unknown and non-time declarations in their existing roles.
            Preserve original text, all independent conditions and each source/revision. Do not treat a passed source
            reference, search-date enum, or this bounded consistency check as proof of arbitrary semantic correctness.

            Explicit weekday-list completeness:
            A standalone numeric from/to clause shared by an explicit list of weekdays must retain EVERY listed
            weekday in ONE owning WEEKLY rule with their exact set, empty anchor_filter, both stated endpoints and
            both issued CLOCK selections. This applies to availability, unavailability and preference alike. List order,
            ordinary conjunctions, separators and full or colloquial weekday names do not remove any listed member.
            Do not select only the first weekday, replace the list with one dated interval, add unmentioned weekdays,
            or split a shared recurrence into per-day conditions. Evaluate each owner's entire stated scope before
            intersections; another owner's weekdays or an exception must not prune this list. Keep explicitly qualified
            dates, ordinal/relative dates, choices, event context, corrections, supplements and genuine UNKNOWN in
            their existing native roles rather than forcing them into a standalone weekday-list rule.
        """
    }
}
