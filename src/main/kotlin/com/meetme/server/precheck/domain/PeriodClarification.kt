package com.meetme.server.precheck.domain

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A clock-only result never authorizes submission, matching, or whole-input CLEAR. */
data class PeriodClarificationInput(
    val rawText: String,
    val referenceAt: Instant,
    val timeZone: ZoneId,
    val searchStartDate: LocalDate,
    val displayedEndInclusive: LocalDate,
    val searchEndExclusive: LocalDate,
    val meetingDuration: Duration,
) {
    init {
        require(rawText.codePoints().noneMatch { it in 0xD800..0xDFFF })
        require(rawText.isNotBlank() && rawText.codePointCount(0, rawText.length) <= 500 && '\u0000' !in rawText)
        require(searchStartDate <= displayedEndInclusive && searchEndExclusive == displayedEndInclusive.plusDays(1))
        require(!meetingDuration.isZero && !meetingDuration.isNegative)
    }
}

enum class ClockNotation { HOUR, ELIDED_RANGE, COLON }

data class ClockCandidate(
    val id: String,
    val start: Int,
    val end: Int,
    val span: String,
    val hour: Int,
    val minute: Int,
    val notation: ClockNotation,
) {
    val fixed24Hour: Boolean get() = hour == 0 || hour in 13..24

    fun validate(rawText: String) {
        require(id.matches(Regex("q[0-9]{2}")) && start >= 0 && end > start)
        require(end <= rawText.codePointCount(0, rawText.length) && hour in 0..24 && minute in 0..59)
        require(hour != 24 || minute == 0)
        require(rawText.substring(rawText.offsetByCodePoints(0, start), rawText.offsetByCodePoints(0, end)) == span)
    }
}

enum class ClockRelevance { RELEVANT, BACKGROUND, UNRESOLVED }

enum class ClockPeriod { AM, PM, NOT_STATED, UNRESOLVED }

/** Raw probabilities are retained; no renormalization, confidence threshold, or answer repair. */
data class ClockChoice<T : Enum<T>>(
    val choice: T,
    val probabilities: Map<T, BigDecimal>,
    val confidence: BigDecimal,
) {
    fun uniqueChoice(): T? {
        require(probabilities.keys == choice.declaringJavaClass.enumConstants.toSet())
        val values = probabilities.values
        require(values.all { it >= BigDecimal.ZERO && it <= BigDecimal.ONE && it.precision() <= 64 && it.scale() in -64..64 })
        require(confidence >= BigDecimal.ZERO && confidence <= BigDecimal.ONE)
        require(
            probabilities.values
                .fold(BigDecimal.ZERO, BigDecimal::add)
                .subtract(BigDecimal.ONE)
                .abs() <= BigDecimal("0.02"),
        )
        val maximum = probabilities.values.maxOrNull()!!
        require(probabilities.getValue(choice).compareTo(maximum) == 0) { "CHOICE_ARGMAX_MISMATCH" }
        return probabilities.filterValues { it.compareTo(maximum) == 0 }.keys.singleOrNull()
    }
}

enum class PeriodClarificationOutcome { NO_PERIOD_QUESTION, ASK_PERIOD, HOLD }

data class PeriodClarificationResult(
    val originalInput: PeriodClarificationInput,
    val candidates: List<ClockCandidate>,
    val relevance: Map<String, ClockChoice<ClockRelevance>>,
    val periods: Map<String, ClockChoice<ClockPeriod>>,
    val missingPeriods: List<ClockCandidate>,
    val heldTargets: Set<String>,
    val outcome: PeriodClarificationOutcome,
    val technicalFailure: String? = null,
) {
    val hasWholeClearAuthority: Boolean get() = false
    val downstreamInput: PeriodClarificationInput get() = originalInput
}

/** Lexical coverage only. Semantic relevance must be supplied by the relevance port. */
class ClockCandidateDetector {
    fun detect(rawText: String): List<ClockCandidate> {
        require(rawText.codePoints().noneMatch { it in 0xD800..0xDFFF })

        data class Token(
            val start: Int,
            val end: Int,
            val hour: Int,
            val minute: Int,
            val notation: ClockNotation,
        )
        val tokens = mutableListOf<Token>()

        fun add(
            start: Int,
            end: Int,
            hour: Int,
            minute: Int,
            notation: ClockNotation,
        ) {
            if (tokens.any { start < it.end && it.start < end }) return
            require(hour in 0..24 && minute in 0..59 && (hour != 24 || minute == 0))
            tokens += Token(start, end, hour, minute, notation)
        }
        Regex("(?<![0-9:])([0-9]{1,2}):([0-9]{2})(?![0-9])").findAll(rawText).forEach {
            add(it.range.first, it.range.last + 1, it.groupValues[1].toInt(), it.groupValues[2].toInt(), ClockNotation.COLON)
        }
        Regex("(?<![0-9:])([0-9]{1,2})\\s*시(?!간)(?:\\s*(반|[0-9]{1,2}\\s*분))?").findAll(rawText).forEach {
            val suffix = it.groupValues[2]
            val minute = if (suffix == "반") 30 else suffix.filter(Char::isDigit).takeIf(String::isNotEmpty)?.toInt() ?: 0
            add(it.range.first, it.range.last + 1, it.groupValues[1].toInt(), minute, ClockNotation.HOUR)
        }
        Regex("(?<![0-9:/])([0-9]{1,2})(?:\\s*시)?\\s*(?:~|∼|〜|–|-)\\s*([0-9]{1,2})(?![0-9:])").findAll(rawText).forEach {
            for (index in listOf(1, 2)) {
                val group = it.groups[index]!!
                add(group.range.first, group.range.last + 1, group.value.toInt(), 0, ClockNotation.ELIDED_RANGE)
            }
        }
        require(tokens.size <= 8) { "CLOCK_COVERAGE_LIMIT" }
        return tokens.sortedBy { it.start }.mapIndexed { index, token ->
            ClockCandidate(
                "q%02d".format(java.util.Locale.ROOT, index + 1),
                rawText.codePointCount(0, token.start),
                rawText.codePointCount(0, token.end),
                rawText.substring(token.start, token.end),
                token.hour,
                token.minute,
                token.notation,
            ).also { it.validate(rawText) }
        }
    }
}

class PeriodClarificationPolicy {
    fun aggregate(
        input: PeriodClarificationInput,
        candidates: List<ClockCandidate>,
        relevance: Map<String, ClockChoice<ClockRelevance>>,
        periods: Map<String, ClockChoice<ClockPeriod>>,
    ): PeriodClarificationResult {
        require(candidates.size <= 8 && candidates.map { it.id }.distinct().size == candidates.size)
        candidates.forEach { it.validate(input.rawText) }
        require(candidates.zipWithNext().all { (a, b) -> a.end <= b.start })
        require(relevance.keys == candidates.map { it.id }.toSet())
        val selected = relevance.mapValues { it.value.uniqueChoice() }
        val requiredPeriods = candidates.filter { selected[it.id] == ClockRelevance.RELEVANT && !it.fixed24Hour }
        require(periods.keys == requiredPeriods.map { it.id }.toSet())
        val resolvedPeriods = periods.mapValues { it.value.uniqueChoice() }
        val held =
            candidates
                .filter { selected[it.id] == null || selected[it.id] == ClockRelevance.UNRESOLVED }
                .map { it.id }
                .toMutableSet()
        requiredPeriods
            .filter { resolvedPeriods[it.id] == null || resolvedPeriods[it.id] == ClockPeriod.UNRESOLVED }
            .forEach { held += it.id }
        val missing = requiredPeriods.filter { resolvedPeriods[it.id] == ClockPeriod.NOT_STATED }
        val outcome =
            when {
                held.isNotEmpty() -> PeriodClarificationOutcome.HOLD
                missing.isNotEmpty() -> PeriodClarificationOutcome.ASK_PERIOD
                else -> PeriodClarificationOutcome.NO_PERIOD_QUESTION
            }
        return PeriodClarificationResult(input, candidates.toList(), relevance.toMap(), periods.toMap(), missing, held, outcome)
    }
}
