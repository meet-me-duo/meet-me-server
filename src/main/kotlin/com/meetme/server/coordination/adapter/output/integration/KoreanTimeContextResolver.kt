package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.TimePolarity
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** Bounded recovery for complete, affirmative, pure-time clauses; unsupported text stays rejected. */
internal object KoreanTimeContextResolver {
    sealed interface Result {
        data object NotApplicable : Result

        data class Resolved(
            val conditions: List<StructuredCondition.TimeWindow>,
        ) : Result

        data class Rejected(
            val code: String = "AMBIGUOUS_TIME_CONSTRAINT",
        ) : Result
    }

    private const val ENDING = "(?:가능합니다|가능해요|가능하고|되어요|돼요|되요)"
    private val base =
        Regex("(평일|주말)(?:에는|은|에|는)?${time("start")}(?:부터|~)${time("end")}(?:까지)?$ENDING")
    private val exception =
        Regex("(이번주|다음주)(?:에는|는|에|은)?([월화수목금토일])요일(?:에만|만|에는|은|에)?${time("start")}부터$ENDING")
    private val separator = Regex("(?:[.,!?ㅜㅠ]|음~?|아)+")
    private val contextStart =
        Regex("(?:이번주|다음주)(?:에는|는|에|은)?[월화수목금토일]요일(?:에만|만|에는|은|에)?${time("start")}부터")
    private val explicitEnd = Regex("^${time("end")}까지")
    private val conditionalPlace = Regex("[월화수목금토일]요일(?:에는|은|에|만)[가-힣A-Za-z0-9]+(?:에서만|근처에서만)")

    fun resolve(
        input: NaturalLanguageInput,
        request: NaturalLanguageBatchRequest,
    ): Result {
        if (input.locale.language != "ko") return Result.NotApplicable
        val text = input.rawText.replace(Regex("\\s+"), "")
        val scopedStartOnly = contextStart.findAll(text).any { !explicitEnd.containsMatchIn(text.substring(it.range.last + 1)) }
        if (!scopedStartOnly) return Result.NotApplicable
        val rejection =
            Result.Rejected(
                if (conditionalPlace.containsMatchIn(text)) "UNSUPPORTED_CONDITIONAL_CONSTRAINT" else "AMBIGUOUS_TIME_CONSTRAINT",
            )
        val parents = mutableMapOf<String, Pair<LocalTime, LocalTime>>()
        val exceptions = mutableListOf<MatchResult>()
        var cursor = 0
        while (cursor < text.length) {
            val skip = separator.find(text, cursor)?.takeIf { it.range.first == cursor }
            if (skip != null) {
                cursor = skip.range.last + 1
                continue
            }
            val parent = base.find(text, cursor)?.takeIf { it.range.first == cursor }
            val child = exception.find(text, cursor)?.takeIf { it.range.first == cursor }
            when {
                parent != null -> {
                    val kind = parent.groupValues[1]
                    if (kind in parents) return rejection
                    val window = parentWindow(parent, kind) ?: return rejection
                    parents[kind] = window
                    cursor = parent.range.last + 1
                }
                child != null -> {
                    exceptions += child
                    cursor = child.range.last + 1
                }
                else -> return rejection
            }
        }
        if (parents.isEmpty() || exceptions.isEmpty()) return rejection
        val conditions = mutableListOf<StructuredCondition.TimeWindow>()
        parents.forEach { (kind, window) ->
            days(kind).forEach { day ->
                conditions += StructuredCondition.TimeWindow(TimePolarity.AVAILABLE, null, day, window.first, window.second)
            }
        }
        val seenDates = mutableSetOf<java.time.LocalDate>()
        for (child in exceptions) {
            val day = DayOfWeek.of("월화수목금토일".indexOf(child.groupValues[2]) + 1)
            val parent = parents[if (day.value <= 5) "평일" else "주말"] ?: return rejection
            val start = choices(child, "start").singleOrNull { it >= parent.first && it < parent.second } ?: return rejection
            val monday = input.referenceDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val date = monday.plusDays((day.value - 1).toLong() + if (child.groupValues[1] == "다음주") 7 else 0)
            if (!seenDates.add(date)) return rejection
            if (date >= request.searchStartDate && date < request.searchEndDate && start > parent.first) {
                conditions += StructuredCondition.TimeWindow(TimePolarity.UNAVAILABLE, date, null, parent.first, start)
            }
        }
        return if (conditions.size <= 32) Result.Resolved(conditions) else rejection
    }

    private fun parentWindow(
        match: MatchResult,
        kind: String,
    ): Pair<LocalTime, LocalTime>? {
        val startPeriod = match.groups["startPeriod"]?.value
        val endPeriod = match.groups["endPeriod"]?.value
        val starts = choices(match, "start", endPeriod)
        val ends = choices(match, "end", startPeriod)
        val conventional = if (kind == "평일") 19 to 21 else 14 to 19
        val unmarked =
            startPeriod == null && endPeriod == null && match.groups["startColon"] == null && match.groups["endColon"] == null
        val start =
            starts.singleOrNull() ?: starts.singleOrNull { unmarked && it == LocalTime.of(conventional.first, 0) } ?: return null
        val end = ends.singleOrNull() ?: ends.singleOrNull { unmarked && it == LocalTime.of(conventional.second, 0) } ?: return null
        return (start to end).takeIf { start < end }
    }

    private fun choices(
        match: MatchResult,
        name: String,
        inheritedPeriod: String? = null,
    ): List<LocalTime> {
        when (match.groups[name + "Special"]?.value) {
            "정오" -> return listOf(LocalTime.NOON)
            "자정" -> return listOf(LocalTime.MIDNIGHT)
        }
        val hour = match.groups[name + "Hour"]?.value?.toIntOrNull() ?: return emptyList()
        val minute = match.groups[name + "Minute"]?.value?.toIntOrNull() ?: 0
        val colon = match.groups[name + "Colon"]?.value != null
        val period = match.groups[name + "Period"]?.value ?: inheritedPeriod?.takeIf { !colon && hour in 1..12 }.orEmpty()
        if (hour !in 0..23) return emptyList()
        val hours =
            when {
                period.isNotEmpty() && hour !in 1..12 -> emptyList()
                period in setOf("오전", "아침") -> listOf(hour % 12)
                period == "밤" && hour == 12 -> listOf(0)
                period == "밤" && hour <= 6 -> emptyList()
                period in setOf("오후", "저녁", "밤", "낮") -> listOf(hour % 12 + 12)
                colon || hour == 0 || hour >= 13 -> listOf(hour)
                else -> listOf(hour % 12, hour % 12 + 12)
            }
        return hours.map { LocalTime.of(it, minute) }
    }

    private fun time(name: String): String =
        "(?:(?<${name}Special>정오|자정)|(?<${name}Period>오전|오후|저녁|밤|아침|낮)?(?<${name}Hour>\\d{1,2})" +
            "(?:(?<${name}Colon>:)(?<${name}Minute>[0-5]\\d)|시)?)"

    private fun days(kind: String): List<DayOfWeek> = DayOfWeek.entries.filter { if (kind == "평일") it.value <= 5 else it.value >= 6 }
}
