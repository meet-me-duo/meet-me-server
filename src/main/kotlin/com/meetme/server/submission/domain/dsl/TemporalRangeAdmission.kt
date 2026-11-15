package com.meetme.server.submission.domain.dsl

import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrSourceKind
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import java.time.DayOfWeek
import java.time.LocalTime

/** Counts coverage of this bounded check, never general semantic correctness. */
data class TemporalRangeAdmissionReport(
    val checkedConditions: Int,
    val unclassifiedConditions: Int,
)

/** Rejects a supported native contradiction; never fills, broadens or repairs provider declarations. */
object TemporalRangeAdmission {
    const val VERSION = "temporal-range-admission-1"
    private const val HOUR = "(?:[01]?\\d|2[0-3])"
    private const val CLOCK =
        "(?:(?:오전|오후)\\s*)?(?:$HOUR:[0-5]\\d(?::[0-5]\\d)?|$HOUR\\s*시(?:\\s*(?:반|[0-5]?\\d\\s*분))?)"
    private const val WEEKDAY_WORD = "(?:월|화|수|목|금|토|일)(?:요일|욜)"
    private const val WEEKDAY_SEPARATOR = "(?:\\s*[,/]\\s*|\\s*(?:와|과|및|하고)\\s*|\\s+)"
    private const val WEEKDAY_PREFIX =
        "(?:매주\\s*)?(?<weekdays>$WEEKDAY_WORD(?:$WEEKDAY_SEPARATOR$WEEKDAY_WORD)*)(?:에는|에만|은|는|에|만)?\\s*"
    private val weekdayWord = Regex(WEEKDAY_WORD)
    private val weekdayNames = "월화수목금토일".mapIndexed { index, name -> name to DayOfWeek.of(index + 1) }.toMap()
    private val standalone =
        Regex(
            "\\s*(?:매일\\s*|$WEEKDAY_PREFIX)?(?<lower>$CLOCK)\\s*(?:이후\\s*)?(?:부터|[~～〜–-])\\s*" +
                "(?<upper>$CLOCK)\\s*(?:까지(?:는|가)?|(?=\\s|가능|불가능|좋|괜찮|선호))\\s*" +
                "(?:가능(?:합니다|해요|해|하다)?|불가능(?:합니다|해요|하다)?|좋아요|괜찮아요|선호해요|안\\s*됩니다)" +
                "\\s*[.!?。！？]?\\s*",
        )
    private val numericClock =
        Regex("(?:(오전|오후)\\s*)?(?:($HOUR):([0-5]\\d)(?::([0-5]\\d))?|($HOUR)\\s*시(?:\\s*(반|[0-5]?\\d\\s*분))?)")
    private val correctionContext = Regex("철회|정정|취소|아니라|대신")

    fun validate(
        envelope: ScheduleDslStructuralEnvelope,
        snapshot: ScheduleEvidenceSnapshot,
    ) {
        assess(envelope, snapshot)
    }

    fun assess(
        envelope: ScheduleDslStructuralEnvelope,
        snapshot: ScheduleEvidenceSnapshot,
    ): TemporalRangeAdmissionReport {
        require(envelope.context == snapshot.context) { "TEMPORAL_RANGE_CONTEXT" }
        require(
            envelope.evidenceCatalogVersion == ServerEvidenceCatalog.VERSION && envelope.weeklyRulesVersion == WeeklyScheduleRules.VERSION,
        ) {
            "TEMPORAL_RANGE_PREREQUISITES"
        }
        val catalog = ServerEvidenceCatalog.create(snapshot)
        var checked = 0
        var unclassified = 0
        val declaration = envelope.base.base.declaration
        declaration.claims.results.forEach { result ->
            val owner = result.submissionVersionId
            val binding = snapshot.context.inputs.single { it.submissionVersionId == owner }
            val program = declaration.programs.single { it.submissionVersionId == owner }.program
            val native = requireNotNull(snapshot.sourceText(owner, "original"))
            if (program == null ||
                binding.sources.any { it.kind == IrSourceKind.SUPPLEMENT } ||
                program.historyConditionIds.isNotEmpty() ||
                program.nodes.any { it.operator == ScheduleDslOperator.CHOICE } ||
                result.conditions.any { it.supersedes.isNotEmpty() || it.assertion != IrAssertion.ASSERTED } ||
                correctionContext.containsMatchIn(native)
            ) {
                unclassified += result.conditions.size
                return@forEach
            }
            val byNode = program.nodes.associateBy { it.id }
            val activeClaims = mutableSetOf<String>()
            val visited = mutableSetOf<String>()

            fun visit(id: String) {
                if (!visited.add(id)) return
                val node = byNode.getValue(id)
                if (node.assertion != IrAssertion.ASSERTED || node.interpretation == IrInterpretation.UNRESOLVED) return
                node.claimId?.let(activeClaims::add)
                node.children.forEach(::visit)
            }
            listOfNotNull(program.availabilityRoot, program.exclusionRoot, program.preferenceRoot).forEach(::visit)
            val ownEntries = catalog.entries.filter { it.inputId == owner && it.span.sourceId == "original" }
            val ranges = ownEntries.filter { it.kind == ServerEvidenceKind.CONTEXT }.mapNotNull { relation(it, ownEntries) }
            result.conditions.filter { it.id in activeClaims }.forEach { condition ->
                val app = envelope.weeklyApplications.singleOrNull { it.inputId == owner && it.conditionId == condition.id }
                val directional =
                    envelope.directionalApplications.singleOrNull {
                        it.submissionVersionId == owner && it.declaration.conditionId == condition.id
                    }
                val term =
                    envelope.base.base.termApplications.singleOrNull {
                        it.submissionVersionId == owner && it.conditionId == condition.id
                    }
                val known =
                    condition.scope != null ||
                        app?.resolved == true ||
                        directional != null ||
                        term != null ||
                        envelope.base.contextApplications.any { it.submissionVersionId == owner && it.conditionId == condition.id }
                val scopeEvidence =
                    when {
                        app != null -> (app.rule.weekdayEvidence + app.rule.filter.evidence).map { it.span }
                        directional != null -> directional.declaration.dateEvidence.map { it.span }
                        term != null -> term.dateEvidence
                        // Complete BOUNDS/RAW_SCOPE do not retain a separate date role in format12.
                        // Broad or separately selected owning context therefore remains unclassified.
                        else -> condition.evidence
                    }
                val related =
                    ranges.filter { r ->
                        condition.evidence.any { it == r.context || it == r.lower.span || it == r.upper.span } &&
                            scopeEvidence.isNotEmpty() &&
                            scopeEvidence.all { within(r.context, it) }
                    }
                if (!known || condition.interpretation == IrInterpretation.UNRESOLVED || related.isEmpty()) {
                    unclassified++
                    return@forEach
                }
                require(app != null && app.resolved) { "TEMPORAL_RANGE_SEARCH_SCOPE" }
                val filter = app.rule.filter
                require(
                    filter.months.isEmpty() &&
                        filter.fromInclusive == null &&
                        filter.untilExclusive == null &&
                        filter.excludedDates.isEmpty(),
                ) {
                    "TEMPORAL_RANGE_ANCHOR_FILTER"
                }
                val time = app.rule.time
                related.forEach { relation ->
                    require(app.rule.weekdays == relation.weekdays) { "TEMPORAL_RANGE_SEARCH_SCOPE" }
                    require(
                        time.kind == WeeklyTimeKind.BOUNDS &&
                            time.lower == relation.lowerClock &&
                            time.upper == relation.upperClock &&
                            time.endDayOffset == 0,
                    ) { "TEMPORAL_RANGE_BOUNDARIES" }
                    listOf(relation.lower, relation.upper).forEach { endpoint ->
                        require(
                            time.clockEvidence.any {
                                it.derivation == EvidenceLocationDerivation.SERVER_CATALOG_REFERENCE &&
                                    it.catalogId == endpoint.id &&
                                    it.span == endpoint.span
                            },
                        ) { "TEMPORAL_RANGE_ENDPOINT_PROOFS" }
                    }
                }
                checked++
            }
        }
        return TemporalRangeAdmissionReport(checked, unclassified)
    }

    private fun within(
        parent: IrEvidenceSpan,
        proof: IrEvidenceSpan,
    ): Boolean =
        parent.sourceId == proof.sourceId &&
            parent.revision == proof.revision &&
            parent.startCodePoint <= proof.startCodePoint &&
            parent.endCodePoint >= proof.endCodePoint

    private data class NativeRange(
        val context: IrEvidenceSpan,
        val lower: ServerEvidenceEntry,
        val upper: ServerEvidenceEntry,
        val lowerClock: LocalTime,
        val upperClock: LocalTime,
        val weekdays: Set<DayOfWeek>,
    )

    private data class ClockFact(
        val value: LocalTime,
        val period: String?,
    )

    private fun relation(
        context: ServerEvidenceEntry,
        entries: List<ServerEvidenceEntry>,
    ): NativeRange? {
        val match = standalone.matchEntire(context.text) ?: return null
        val lower = requireNotNull(match.groups["lower"])
        val upper = requireNotNull(match.groups["upper"])
        val lo = clock(lower.value, null) ?: return null
        val hi = clock(upper.value, lo.period) ?: return null
        if (lo.value >= hi.value) return null // No implicit overnight interpretation.

        fun atomic(group: MatchGroup): ServerEvidenceEntry? {
            val start = context.span.startCodePoint + context.text.codePointCount(0, group.range.first)
            val end = context.span.startCodePoint + context.text.codePointCount(0, group.range.last + 1)
            return entries.singleOrNull {
                it.kind == ServerEvidenceKind.CLOCK &&
                    it.span.sourceId == context.span.sourceId &&
                    it.span.revision == context.span.revision &&
                    it.span.startCodePoint == start &&
                    it.span.endCodePoint == end
            }
        }
        val weekdays =
            match.groups["weekdays"]?.value?.let { names ->
                weekdayWord.findAll(names).map { weekdayNames.getValue(it.value.first()) }.toSet()
            } ?: DayOfWeek.entries.toSet()
        return NativeRange(context.span, atomic(lower) ?: return null, atomic(upper) ?: return null, lo.value, hi.value, weekdays)
    }

    private fun clock(
        text: String,
        inheritedPeriod: String?,
    ): ClockFact? {
        val match = numericClock.matchEntire(text) ?: return null
        val explicitPeriod = match.groupValues[1].takeIf(String::isNotEmpty)
        val colon = match.groupValues[2].isNotEmpty()
        var hour = match.groupValues[if (colon) 2 else 5].toInt()
        val minute =
            if (colon) {
                match.groupValues[3].toInt()
            } else {
                match.groupValues[6].let {
                    when {
                        it.isEmpty() -> 0
                        it == "반" -> 30
                        else -> it.filter(Char::isDigit).toInt()
                    }
                }
            }
        val second = if (colon) match.groupValues[4].ifEmpty { "0" }.toInt() else 0
        val period = explicitPeriod ?: inheritedPeriod.takeIf { !colon && hour in 1..12 }
        if (period != null) {
            if (hour !in 1..12) return null
            hour = hour % 12 + if (period == "오후") 12 else 0
        } else if (!colon && hour in 1..12) {
            return null // Unqualified twelve-hour notation stays outside verified coverage.
        }
        return ClockFact(LocalTime.of(hour, minute, second), period)
    }
}
