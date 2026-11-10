package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.MeetingMonthCalendarFacts
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_CALENDAR_REQUEST_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_SCOPED_REQUEST_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper

/** Objective calendar data only; interpretation remains with the model and existing source/date guards remain in force. */
class CalendarFactsEvidenceProviderContract(
    private val mapper: ObjectMapper,
    private val allowPlaceConstraints: Boolean = false,
) {
    private val previous = SearchScopedEvidenceProviderContract(mapper, allowPlaceConstraints)
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
        val context = capsule.snapshot.context
        val days = MeetingMonthCalendarFacts.forRange(context.searchStart, context.searchEnd)
        val calendar = map(root["calendar_context"])
        require(calendar["version"] == "server-calendar-facts-1") { "Unexpected prior calendar version" }
        root["request_contract_version"] = SERVER_EVIDENCE_CALENDAR_REQUEST_CONTRACT
        root["response_contract_version"] = SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT
        root["producer_schema_revision"] = "server-evidence-month-calendar-1"
        root["calendar_facts_version"] = MeetingMonthCalendarFacts.VERSION
        root["calendar_context"] = calendar +
            mapOf(
                "version" to MeetingMonthCalendarFacts.VERSION,
                "search_days" to
                    days.map { day ->
                        mapOf(
                            "local_date" to day.localDate.toString(),
                            "weekday" to day.weekday.name,
                            "weekday_occurrence_in_month" to day.weekdayOccurrenceInMonth,
                            "is_last_weekday_in_month" to day.isLastWeekdayInMonth,
                            "month_first_weekday" to day.monthFirstWeekday.name,
                            "days_in_month" to day.daysInMonth,
                        )
                    },
            )
        root["schema"] = responseSchema(capsule.snapshot)
        root["prompt"] = (root.getValue("prompt") as String).replace(
            "$SERVER_EVIDENCE_SCOPED_REQUEST_CONTRACT / $SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT",
            "$SERVER_EVIDENCE_CALENDAR_REQUEST_CONTRACT / $SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT",
        ) + "\n\n" + INSTRUCTIONS
        return mapper.writeValueAsBytes(root).also { require(it.size <= 1_048_576) { "Calendar facts request byte limit" } }
    }

    fun responseSchema(snapshot: ScheduleEvidenceSnapshot): Map<String, Any?> {
        val root = previous.responseSchema(snapshot)
        return root +
            mapOf(
                "properties" to
                    (
                        map(root["properties"]) +
                            ("calendar_facts_version" to mapOf("type" to "string", "enum" to listOf(MeetingMonthCalendarFacts.VERSION)))
                    ),
                "required" to ((root["required"] as List<*>) + "calendar_facts_version"),
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
            require(json.toByteArray(Charsets.UTF_8).size <= 262_144) { "CALENDAR_FACTS_RAW_BYTE_LIMIT" }
            val root = reader.readValue<Map<String, Any?>>(json)
            require(root["calendar_facts_version"] == MeetingMonthCalendarFacts.VERSION) { "CALENDAR_FACTS_MARKER" }
            previous.parseProviderResponse(
                mapper.writeValueAsString(root - "calendar_facts_version"),
                snapshot,
                semanticVersion,
                astVersion,
                vocabularyVersion,
            )
        } catch (failure: NaturalLanguageParserException) {
            throw failure
        } catch (failure: RuntimeException) {
            val code =
                failure.message?.takeIf { it in setOf("CALENDAR_FACTS_RAW_BYTE_LIMIT", "CALENDAR_FACTS_MARKER") }
                    ?: "CALENDAR_FACTS_INVALID_RESPONSE"
            throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE, cause = IllegalArgumentException(code))
        }

    private fun map(value: Any?): Map<String, Any?> =
        (value as? Map<*, *>)?.entries?.associate { (it.key as String) to it.value }
            ?: throw IllegalArgumentException("Expected calendar facts object")

    companion object {
        private const val INSTRUCTIONS = """
            Objective month calendar facts (server-calendar-facts-2):
            Return calendar_facts_version exactly as supplied. Each search_days row includes its weekday's occurrence
            number over the ENTIRE calendar month. is_last_weekday_in_month=true means no further occurrence of that
            weekday exists in that month. weekday means day-of-week including Saturday/Sunday, not business day.
            month_first_weekday is the day-of-week of that month's first calendar date; days_in_month is its full length. A clipped search does not restart counting or make its last
            search day the month's last occurrence. Use these facts with the unchanged original, actual supplements
            and whole-room meeting context when interpreting ordinal or month-end dates.
            weekday_occurrence_in_month is NOT a calendar grid row or a product definition of week-of-month. Do not
            silently equate them or invent a week-start/first-week convention. Resolve supported ordinary meaning from
            complete context; preserve genuine remaining ambiguity. Keep each relevant resolved date; a date-only
            availability answer without a narrower restriction covers that day under the existing availability policy.
            Calendar facts do not assert availability, supply native evidence, resolve private events, or confirm attendance.
            Preserve explicit months/years and exclusions. Do not use another participant's outside date authority,
            reinterpret an unresolved personal boundary as whole-day, or repair a model date after generation.
        """
    }
}
