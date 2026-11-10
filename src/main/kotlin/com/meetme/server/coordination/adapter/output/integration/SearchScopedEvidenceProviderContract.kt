package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.MeetingDateScopePolicy
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_SCOPED_REQUEST_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper

/** New frozen generation contract; old evidence responses keep their historical date semantics. */
class SearchScopedEvidenceProviderContract(
    private val mapper: ObjectMapper,
    private val allowPlaceConstraints: Boolean = false,
) {
    private val previous = ServerEvidenceProviderContract(mapper, allowPlaceConstraints)
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
        val policy = MeetingDateScopePolicy.create(capsule.snapshot)
        val context = capsule.snapshot.context
        root["request_contract_version"] = SERVER_EVIDENCE_SCOPED_REQUEST_CONTRACT
        root["response_contract_version"] = SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT
        root["producer_schema_revision"] = "server-evidence-search-dates-1"
        root["date_scope_version"] = MeetingDateScopePolicy.VERSION
        root["meeting_date_scope"] =
            mapOf(
                "version" to MeetingDateScopePolicy.VERSION,
                "start_inclusive" to context.searchStart.toString(),
                "end_exclusive" to context.searchEnd.toString(),
                "inputs" to
                    context.inputs.sortedBy { it.submissionVersionId.toString() }.map {
                        mapOf(
                            "input_ref" to it.submissionVersionId.toString(),
                            "allowed_dates" to policy.allowedDates(it.submissionVersionId).map { d -> d.toString() },
                        )
                    },
            )
        root["schema"] = responseSchema(capsule.snapshot)
        root["prompt"] =
            (root.getValue("prompt") as String)
                .replace(ScheduleDslRelativeDateContext.INSTRUCTIONS, INSTRUCTIONS)
                .replace(
                    "schedule-dsl-request-5 / schedule-dsl-response-5",
                    "$SERVER_EVIDENCE_SCOPED_REQUEST_CONTRACT / $SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT",
                ).replace(
                    "Use source reference_at, calendar_context and search context to resolve relative dates.",
                    "Use meeting_date_scope and its meeting search calendar to resolve relative meeting dates.",
                ).replace(
                    "Calendar facts do not limit output dates or remove out-of-range conditions.",
                    "Select dates within the owning input's allowed_dates; preserve its explicit absolute outside dates.",
                )
        return mapper.writeValueAsBytes(root).also { require(it.size <= 1_048_576) { "Meeting date request byte limit" } }
    }

    fun responseSchema(snapshot: ScheduleEvidenceSnapshot): Map<String, Any?> {
        val policy = MeetingDateScopePolicy.create(snapshot)

        fun dates(value: Any?): Any? =
            when (value) {
                is List<*> -> value.map { dates(it) }
                is Map<*, *> ->
                    value.entries.associate { (key, child) ->
                        val name = key as String
                        name to if (name == "local_date") mapOf("\$ref" to "#/\$defs/meeting_date") else dates(child)
                    }
                else -> value
            }
        val root = map(dates(previous.responseSchema()))
        return root +
            mapOf(
                "\$defs" to
                    (map(root["\$defs"]) + ("meeting_date" to mapOf("type" to "string", "enum" to policy.allDates.map { it.toString() }))),
                "properties" to
                    (
                        map(root["properties"]) +
                            ("date_scope_version" to mapOf("type" to "string", "enum" to listOf(MeetingDateScopePolicy.VERSION)))
                    ),
                "required" to ((root["required"] as List<*>) + "date_scope_version"),
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
            require(json.toByteArray(Charsets.UTF_8).size <= 262_144) { "MEETING_DATE_RAW_BYTE_LIMIT" }
            val root = reader.readValue<Map<String, Any?>>(json)
            require(root["date_scope_version"] == MeetingDateScopePolicy.VERSION) { "MEETING_DATE_SCOPE_MARKER" }
            previous
                .parseProviderResponse(
                    mapper.writeValueAsString(root - "date_scope_version"),
                    snapshot,
                    semanticVersion,
                    astVersion,
                    vocabularyVersion,
                ).also { MeetingDateScopePolicy.create(snapshot).validate(it) }
        } catch (failure: NaturalLanguageParserException) {
            throw failure
        } catch (failure: RuntimeException) {
            val code =
                failure.message?.takeIf {
                    it in
                        setOf("MEETING_DATE_RAW_BYTE_LIMIT", "MEETING_DATE_SCOPE_MARKER", "MEETING_DATE_OUTSIDE_INPUT_SCOPE")
                }
                    ?: "MEETING_DATE_SCOPE_INVALID_RESPONSE"
            throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE, cause = IllegalArgumentException(code))
        }

    private fun map(value: Any?): Map<String, Any?> =
        (value as? Map<*, *>)?.entries?.associate { (it.key as String) to it.value }
            ?: throw IllegalArgumentException("Expected meeting date object")

    companion object {
        private const val INSTRUCTIONS = """
            Meeting search date scope (meeting-date-scope-1):
            Return date_scope_version exactly as supplied. Interpret relative meeting dates and bare weekdays in the
            CURRENT MEETING SEARCH PERIOD, not the month/week of processing or input submission. For relative ordinal
            weeks and month-end expressions, use the months and calendar days represented by start_inclusive through
            end_exclusive (exclusive). Read weekday and date facts from the supplied calendar. Select dates only from
            meeting_date_scope.inputs.allowed_dates for the SAME input_ref; another participant's explicit date grants
            no authority. The shared schema enum is a union, not participant-specific permission.
            Source reference_at/input_local_date remains provenance and supports genuinely explicit input-date anchors;
            it does not move a relative meeting response into an unrelated earlier week or month.
            Preserve explicit numeric absolute dates exactly even outside the search: they are separately authorized only
            for the input that actually states them. Do not force explicit outside dates onto nearby in-range days.
            Search dates are calendar context, not availability or participant confirmation. Preserve negation, limits,
            alternatives and corrections. If the intended relative date or personal time boundary remains ambiguous,
            return UNKNOWN; never invent a date, convert UNKNOWN to whole-day, or silently repair an out-of-scope date.
            Known TIME dates, including RAW_SCOPE, are checked by the server. Endpoints may extend into the following
            calendar day for overnight/exclusive ends; that exception does not authorize a new starting date.
        """
    }
}
