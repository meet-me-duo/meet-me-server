package com.meetme.server.submission.domain.dsl

import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTerm
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.util.UUID

enum class WeeklyTimeKind { WHOLE_DAY, BOUNDS, TERM, UNKNOWN }

/** Closed union, validated without normalizing an invalid combination into another kind. */
data class WeeklyTimePattern(
    val kind: WeeklyTimeKind,
    val lower: LocalTime? = null,
    val upper: LocalTime? = null,
    val endDayOffset: Int = 0,
    val clockEvidence: List<LocatedLiteralEvidence> = emptyList(),
    val term: ApprovedKoreanTimeTerm? = null,
    val termEvidence: LocatedLiteralEvidence? = null,
)

/** These selectors filter an occurrence's anchor date; hard civil-date exclusions remain AST BLOCK/EXCEPT claims. */
data class WeeklyAnchorFilter(
    val months: Set<Month> = emptySet(),
    val fromInclusive: LocalDate? = null,
    val untilExclusive: LocalDate? = null,
    val excludedDates: Set<LocalDate> = emptySet(),
    val evidence: List<LocatedLiteralEvidence> = emptyList(),
)

/** Effect, assertion, corrections and alternatives remain on the original native condition. */
data class WeeklyScheduleRule(
    val weekdays: Set<DayOfWeek>,
    val time: WeeklyTimePattern,
    val weekdayEvidence: List<LocatedLiteralEvidence>,
    val filter: WeeklyAnchorFilter = WeeklyAnchorFilter(),
)

data class WeeklyScheduleOccurrence(
    val anchorDate: LocalDate,
    val localScope: IrScope,
    val searchSupport: InstantTimeRange?,
)

/** A resolved empty expansion is distinct from an unknown personal boundary. */
data class WeeklyScheduleApplication(
    val inputId: UUID,
    val conditionId: String,
    val rule: WeeklyScheduleRule,
    val occurrences: List<WeeklyScheduleOccurrence>,
    val resolved: Boolean,
    val version: String = WeeklyScheduleRules.VERSION,
)

object WeeklyScheduleRules {
    const val VERSION = "weekly-schedule-rule-1"
    const val MAX_SEARCH_DAYS = 31
    const val MAX_WINDOWS = 1024

    fun expand(
        inputId: UUID,
        conditionId: String,
        rule: WeeklyScheduleRule,
        snapshot: ScheduleEvidenceSnapshot,
    ): WeeklyScheduleApplication {
        val context = snapshot.context
        val days =
            java.time.temporal.ChronoUnit.DAYS
                .between(context.searchStart, context.searchEnd)
        require(days in 1..MAX_SEARCH_DAYS.toLong()) { "Weekly search range limit" }
        require(context.inputs.any { it.submissionVersionId == inputId }) { "Unknown weekly owner" }
        require(conditionId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_:-]{0,63}"))) { "Invalid weekly condition" }
        require(rule.weekdays.isNotEmpty() && rule.weekdays.size <= 7)
        val catalog = ServerEvidenceCatalog.create(snapshot)

        fun proofs(
            values: List<LocatedLiteralEvidence>,
            required: Boolean,
        ) {
            require(values.size <= 32 && (!required || values.isNotEmpty())) { "Missing weekly evidence" }
            values.forEach { ScheduleDslStructuralNormalizer.restoreEvidence(inputId, it, snapshot, catalog) }
        }
        proofs(rule.weekdayEvidence, true)
        val filter = rule.filter
        val filtered =
            filter.months.isNotEmpty() || filter.fromInclusive != null || filter.untilExclusive != null || filter.excludedDates.isNotEmpty()
        proofs(filter.evidence, filtered)
        require(filter.excludedDates.size <= MAX_SEARCH_DAYS + 1)
        if (filter.fromInclusive != null && filter.untilExclusive != null) require(filter.fromInclusive < filter.untilExclusive)
        val time = rule.time
        require(time.endDayOffset in 0..1)
        when (time.kind) {
            WeeklyTimeKind.WHOLE_DAY, WeeklyTimeKind.UNKNOWN -> {
                require(time.lower == null && time.upper == null && time.endDayOffset == 0)
                require(time.clockEvidence.isEmpty() && time.term == null && time.termEvidence == null)
            }
            WeeklyTimeKind.BOUNDS -> {
                require(time.lower != null || time.upper != null)
                require(time.term == null && time.termEvidence == null)
                require(time.endDayOffset == 0 || time.lower != null && time.upper != null)
                if (time.lower != null && time.upper != null && time.endDayOffset == 0) require(time.lower < time.upper)
                proofs(time.clockEvidence, true)
            }
            WeeklyTimeKind.TERM -> {
                require(time.lower == null && time.upper == null && time.endDayOffset == 0 && time.clockEvidence.isEmpty())
                val term = requireNotNull(time.term)
                val proof = requireNotNull(time.termEvidence)
                proofs(listOf(proof), true)
                val span = proof.span
                val text = requireNotNull(snapshot.sourceText(inputId, span.sourceId))
                val literal = text.substring(text.offsetByCodePoints(0, span.startCodePoint), text.offsetByCodePoints(0, span.endCodePoint))
                require(
                    literal in
                        com.meetme.server.submission.domain.time.ApprovedKoreanTimeTermPolicy
                            .lexemes(term),
                ) { "Wrong weekly term evidence" }
            }
        }
        if (time.kind == WeeklyTimeKind.UNKNOWN) return WeeklyScheduleApplication(inputId, conditionId, rule, emptyList(), false)
        val zone = java.time.ZoneId.of(context.timeZone)
        val searchStart = context.searchStart.atStartOfDay(zone).toInstant()
        val searchEnd = context.searchEnd.atStartOfDay(zone).toInstant()

        fun instant(value: java.time.LocalDateTime): java.time.Instant {
            val offsets = zone.rules.getValidOffsets(value)
            require(offsets.size == 1) { "Ambiguous weekly local endpoint" }
            return value.toInstant(offsets.single())
        }
        val occurrences =
            (-1L until days).mapNotNull { offset ->
                val date = context.searchStart.plusDays(offset)
                if (date.dayOfWeek !in rule.weekdays ||
                    filter.months.isNotEmpty() &&
                    date.month !in filter.months ||
                    filter.fromInclusive != null &&
                    date < filter.fromInclusive ||
                    filter.untilExclusive != null &&
                    date >= filter.untilExclusive ||
                    date in filter.excludedDates
                ) {
                    return@mapNotNull null
                }
                val scope =
                    when (time.kind) {
                        WeeklyTimeKind.WHOLE_DAY ->
                            IrScope(
                                com.meetme.server.submission.domain.ir.IrDimension.TIME,
                                date.atStartOfDay(zone).toLocalDateTime(),
                                date.plusDays(1).atStartOfDay(zone).toLocalDateTime(),
                            )
                        WeeklyTimeKind.TERM ->
                            com.meetme.server.submission.domain.time.ApprovedKoreanTimeTermPolicy.resolve(
                                requireNotNull(time.term),
                                date,
                            )
                        WeeklyTimeKind.BOUNDS ->
                            IrScope(
                                com.meetme.server.submission.domain.ir.IrDimension.TIME,
                                time.lower?.let(date::atTime) ?: date.atStartOfDay(zone).toLocalDateTime(),
                                time.upper?.let { date.plusDays(time.endDayOffset.toLong()).atTime(it) }
                                    ?: date.plusDays(1).atStartOfDay(zone).toLocalDateTime(),
                            )
                        WeeklyTimeKind.UNKNOWN -> error("Unknown weekly time cannot expand")
                    }
                val start = instant(requireNotNull(scope.start))
                val end = instant(requireNotNull(scope.end))
                require(start < end) { "Empty weekly interval" }
                val clippedStart = maxOf(start, searchStart)
                val clippedEnd = minOf(end, searchEnd)
                if (clippedStart >= clippedEnd) null else WeeklyScheduleOccurrence(date, scope, InstantTimeRange(clippedStart, clippedEnd))
            }
        require(occurrences.size <= MAX_WINDOWS)
        return WeeklyScheduleApplication(inputId, conditionId, rule, occurrences, true)
    }

    fun restore(
        application: WeeklyScheduleApplication,
        snapshot: ScheduleEvidenceSnapshot,
    ): WeeklyScheduleApplication {
        require(application.version == VERSION) { "Unknown weekly rules version" }
        val restored = expand(application.inputId, application.conditionId, application.rule, snapshot)
        require(restored == application) { "Weekly declaration or expansion mismatch" }
        return restored
    }
}
