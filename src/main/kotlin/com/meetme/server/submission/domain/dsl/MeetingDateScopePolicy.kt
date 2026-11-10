package com.meetme.server.submission.domain.dsl

import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.UUID

const val SERVER_EVIDENCE_SCOPED_REQUEST_CONTRACT = "schedule-dsl-evidence-request-2"
const val SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT = "schedule-dsl-evidence-response-2"

/** Calendar authority, not a natural-language parser or an automatic correction of model dates. */
class MeetingDateScopePolicy private constructor(
    private val context: ScheduleIrContext,
    private val datesByInput: Map<UUID, Set<LocalDate>>,
) {
    val allDates: Set<LocalDate> = Collections.unmodifiableSet(datesByInput.values.flatten().toSortedSet())

    fun allowedDates(inputId: UUID): Set<LocalDate> = requireNotNull(datesByInput[inputId]) { "Unknown meeting date owner" }

    fun validate(envelope: ScheduleDslStructuralEnvelope) {
        require(envelope.context == context) { "Meeting date context mismatch" }

        fun date(
            input: UUID,
            value: LocalDate,
        ) {
            require(value in allowedDates(input)) { "MEETING_DATE_OUTSIDE_INPUT_SCOPE" }
        }

        fun scope(
            input: UUID,
            value: IrScope?,
        ) {
            val allowed = allowedDates(input)
            if (value == null || value.dimension != IrDimension.TIME) return
            value.start?.let { date(input, it.toLocalDate()) }
            value.end?.let { end ->
                val endDate = end.toLocalDate()
                require(endDate in allowed || endDate.minusDays(1) in allowed) { "MEETING_DATE_OUTSIDE_INPUT_SCOPE" }
            }
        }

        listOf(
            envelope.base.base.declaration,
            envelope.base.base.calculationProjection,
            envelope.base.calculationProjection,
            envelope.calculationProjection,
        ).forEach { view ->
            require(view.context == context) { "Meeting date projection context mismatch" }
            view.claims.results.forEach { input ->
                allowedDates(input.submissionVersionId)
                input.conditions.forEach { scope(input.submissionVersionId, it.scope) }
            }
        }
        envelope.directionalApplications.forEach {
            date(it.submissionVersionId, it.declaration.localDate)
            scope(it.submissionVersionId, it.resolvedScope)
        }
        envelope.base.base.termApplications.forEach {
            date(it.submissionVersionId, it.localDate)
            scope(it.submissionVersionId, it.declaredScope)
            scope(it.submissionVersionId, it.resolvedScope)
        }
        envelope.base.contextApplications.forEach {
            scope(it.submissionVersionId, it.declaredScope)
            scope(it.submissionVersionId, it.resolvedScope)
        }
        envelope.base.modeRestrictions.forEach { scope(it.submissionVersionId, it.scope) }
    }

    companion object {
        const val VERSION = "meeting-date-scope-1"
        const val MAX_SEARCH_DAYS = 366
        const val MAX_DATE_CANDIDATES = 400
        private val iso = Regex("(?<![0-9-])(\\d{4})-(\\d{2})-(\\d{2})(?![0-9-])")
        private val koreanYear = Regex("(?<![0-9])(\\d{4})\\s*년\\s*(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일(?![0-9])")
        private val koreanMonth = Regex("(?<![0-9])(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일(?![0-9])")
        private val slash = Regex("(?<![0-9/])(\\d{1,2})/(\\d{1,2})(?![0-9/])")

        fun create(snapshot: ScheduleEvidenceSnapshot): MeetingDateScopePolicy {
            // Date authority may only come from the same validated immutable native source registry as evidence IDs.
            ServerEvidenceCatalog.create(snapshot)
            val context = snapshot.context
            val days = ChronoUnit.DAYS.between(context.searchStart, context.searchEnd)
            require(days in 1..MAX_SEARCH_DAYS.toLong()) { "Meeting search date limit" }
            val search = (0 until days).map { context.searchStart.plusDays(it) }.toSet()
            val searchYears = search.map { it.year }.toSet()
            val zone = ZoneId.of(context.timeZone)
            val byInput =
                context.inputs.associate { input ->
                    val dates = search.toMutableSet()
                    input.sources.forEach { source ->
                        val text = requireNotNull(snapshot.sourceText(input.submissionVersionId, source.sourceId))
                        val years = searchYears + source.referenceAt.atZone(zone).year
                        val fullDates = (iso.findAll(text) + koreanYear.findAll(text)).toList()
                        fullDates.forEach { match ->
                            addDate(dates, match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
                        }
                        (koreanMonth.findAll(text) + slash.findAll(text)).forEach { match ->
                            if (fullDates.none { match.range.first >= it.range.first && match.range.last <= it.range.last }) {
                                years.forEach { year -> addDate(dates, year, match.groupValues[1].toInt(), match.groupValues[2].toInt()) }
                            }
                        }
                    }
                    input.submissionVersionId to Collections.unmodifiableSet(dates.toSortedSet())
                }
            return MeetingDateScopePolicy(context, byInput).also {
                require(it.allDates.size <= MAX_DATE_CANDIDATES) { "Meeting date candidate limit" }
            }
        }

        private fun addDate(
            dates: MutableSet<LocalDate>,
            year: Int,
            month: Int,
            day: Int,
        ) {
            try {
                dates += LocalDate.of(year, month, day)
            } catch (_: DateTimeException) {
                // An invalid literal grants no calendar authority and is never repaired to a nearby date.
            }
        }
    }
}
