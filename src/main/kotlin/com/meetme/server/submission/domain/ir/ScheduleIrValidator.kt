package com.meetme.server.submission.domain.ir

import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

class ScheduleIrValidator {
    fun validate(
        proposal: ScheduleIrProposal,
        snapshot: ScheduleEvidenceSnapshot,
    ): ScheduleIrEnvelope {
        require(proposal.semanticVersion == SCHEDULE_IR_VERSION) { "Unsupported schedule IR" }
        validateSnapshot(snapshot)
        val expected = snapshot.context.inputs.map { it.submissionVersionId }
        require(proposal.results.size == expected.size && proposal.results.map { it.submissionVersionId }.toSet() == expected.toSet()) {
            "IR batch identity mismatch"
        }
        return ScheduleIrEnvelope(
            SCHEDULE_IR_VERSION,
            snapshot.context,
            proposal.results.map { validateResult(it, snapshot) },
        )
    }

    fun validateRestored(
        envelope: ScheduleIrEnvelope,
        snapshot: ScheduleEvidenceSnapshot,
    ): ScheduleIrEnvelope {
        require(envelope.semanticVersion == SCHEDULE_IR_VERSION && envelope.context == snapshot.context) { "IR replay context mismatch" }
        val proposed =
            envelope.results.map { result ->
                require(result.quarantined.all { it.scope == null }) { "Untrusted quarantine scope" }
                ProposedIrResult(
                    result.submissionVersionId,
                    result.availability,
                    result.conditions.map { condition ->
                        ProposedIrCondition(
                            condition.id,
                            condition.effect,
                            condition.interpretation,
                            condition.scope,
                            condition.evidence.map { span ->
                                val quote =
                                    extract(snapshot, result.submissionVersionId, span.sourceId, span.startCodePoint, span.endCodePoint)
                                require(IrEvidenceBindings.sha256(quote) == span.contentSha256) { "IR evidence digest mismatch" }
                                ProposedIrEvidence(span.sourceId, span.revision, span.startCodePoint, span.endCodePoint, quote)
                            },
                            condition.assertion,
                            condition.supersedes,
                            condition.alternativeGroup,
                            condition.alternative,
                        )
                    },
                    result.quarantined,
                )
            }
        require(validate(ScheduleIrProposal(envelope.semanticVersion, proposed), snapshot) == envelope) { "Invalid persisted IR" }
        return envelope
    }

    private fun validateSnapshot(snapshot: ScheduleEvidenceSnapshot) {
        val context = snapshot.context
        ZoneId.of(context.timeZone)
        require(ChronoUnit.DAYS.between(context.searchStart, context.searchEnd) in 1..31)
        require(context.policyVersion.matches(TOKEN))
        require(context.inputs.size in 2..50)
        require(
            context.inputs
                .map { it.submissionVersionId }
                .distinct()
                .size == context.inputs.size,
        )
        require(
            context.inputs
                .map { it.participantId }
                .distinct()
                .size == context.inputs.size,
        )
        context.inputs.forEach { input ->
            require(input.submissionRevision > 0 && (input.originalRevision == null || input.originalRevision > 0))
            require(input.contentSha256.matches(DIGEST))
            require(
                input.sources.size in 1..2 &&
                    input.sources
                        .map { it.sourceId }
                        .distinct()
                        .size == input.sources.size,
            )
            val original = input.sources.singleOrNull { it.kind == IrSourceKind.ORIGINAL }
            require(original != null && original.sourceId == "original")
            input.sources.forEach { source ->
                val text = requireNotNull(snapshot.sourceText(input.submissionVersionId, source.sourceId)) { "Missing source" }
                require(source.revision == (input.originalRevision ?: input.submissionRevision))
                require(source.codePointLength in 1..500 && text.codePointCount(0, text.length) == source.codePointLength)
                require(source.contentSha256 == IrEvidenceBindings.sha256(text)) { "Source digest mismatch" }
                when (source.kind) {
                    IrSourceKind.ORIGINAL ->
                        require(source.questionId == null && source.originalStartCodePoint == null && source.originalEndCodePoint == null)
                    IrSourceKind.SUPPLEMENT -> {
                        require(input.originalRevision != null && source.questionId != null)
                        require(source.sourceId == "supplement:${source.questionId}")
                        val start = requireNotNull(source.originalStartCodePoint)
                        val end = requireNotNull(source.originalEndCodePoint)
                        require(start >= 0 && end > start && end <= original.codePointLength)
                    }
                }
            }
        }
    }

    private fun validateResult(
        proposed: ProposedIrResult,
        snapshot: ScheduleEvidenceSnapshot,
    ): IrResult {
        val stateDiagnostics = proposed.rejectedConditions.count { it.problem == IrProblem.AVAILABILITY }
        require(stateDiagnostics <= 1)
        require(proposed.rejectedConditions.none { it.problem == IrProblem.AVAILABILITY && it.conditionId != null })
        require(proposed.conditions.size + proposed.rejectedConditions.size - stateDiagnostics <= 32)
        require(proposed.rejectedConditions.all { it.scope == null && (it.conditionId == null || it.conditionId.matches(TOKEN)) })
        val ids = proposed.conditions.map { it.id } + proposed.rejectedConditions.mapNotNull { it.conditionId }
        require(ids.all { it.matches(TOKEN) } && ids.distinct().size == ids.size) { "Invalid condition identity" }
        val byId = proposed.conditions.associateBy { it.id }
        val problems = linkedMapOf<String, IrProblem>()
        val spans = mutableMapOf<String, List<IrEvidenceSpan>>()
        proposed.conditions.forEach { condition ->
            if (!validScope(condition, snapshot.context)) {
                problems[condition.id] = IrProblem.SCOPE
            } else {
                try {
                    require(condition.evidence.isNotEmpty() && condition.evidence.size <= 32)
                    spans[condition.id] = condition.evidence.map { validateEvidence(it, proposed.submissionVersionId, snapshot) }
                } catch (_: IllegalArgumentException) {
                    problems[condition.id] = IrProblem.EVIDENCE
                }
            }
            if ((condition.alternativeGroup == null) != (condition.alternative == null) ||
                condition.alternativeGroup?.matches(TOKEN) == false ||
                condition.alternative?.matches(TOKEN) == false ||
                condition.supersedes.distinct().size != condition.supersedes.size ||
                condition.supersedes.any { it !in byId || it == condition.id }
            ) {
                problems.putIfAbsent(condition.id, IrProblem.RELATION)
            }
        }

        fun returnsTo(origin: String): Boolean {
            val visited = mutableSetOf<String>()
            val pending = java.util.ArrayDeque(byId[origin]?.supersedes.orEmpty())
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                if (next == origin) return true
                if (visited.add(next)) pending.addAll(byId[next]?.supersedes.orEmpty())
            }
            return false
        }
        proposed.conditions.filter { returnsTo(it.id) }.forEach {
            problems.putIfAbsent(it.id, IrProblem.RELATION)
        }
        val groups = proposed.conditions.filter { it.alternativeGroup != null }.groupBy { it.alternativeGroup }
        do {
            val before = problems.size
            proposed.conditions.filter { condition -> condition.supersedes.any { it in problems } }.forEach {
                problems.putIfAbsent(it.id, IrProblem.RELATION)
            }
            groups.values
                .filter { group -> group.map { it.alternative }.distinct().size < 2 || group.any { it.id in problems } }
                .flatten()
                .forEach { problems.putIfAbsent(it.id, IrProblem.RELATION) }
        } while (problems.size != before)
        val accepted =
            proposed.conditions.filter { it.id !in problems }.map {
                IrCondition(
                    it.id,
                    it.effect,
                    it.interpretation,
                    it.scope,
                    spans.getValue(it.id),
                    it.assertion,
                    it.supersedes,
                    it.alternativeGroup,
                    it.alternative,
                )
            }
        val quarantined =
            proposed.rejectedConditions +
                proposed.conditions.filter { it.id in problems }.map { IrQuarantine(it.id, problems.getValue(it.id), null) }
        val superseded = accepted.filter { it.assertion == IrAssertion.ASSERTED }.flatMap { it.supersedes }.toSet()
        val active = accepted.filter { it.assertion == IrAssertion.ASSERTED && it.id !in superseded }
        val validState =
            when (proposed.availability) {
                IrAvailabilityState.UNKNOWN -> true
                IrAvailabilityState.KNOWN_WINDOWS ->
                    active.any {
                        it.effect == IrEffect.AVAILABLE
                    }
                IrAvailabilityState.UNCONSTRAINED ->
                    active.any {
                        it.effect == IrEffect.UNCONSTRAINED &&
                            it.alternativeGroup == null
                    }
                IrAvailabilityState.EMPTY ->
                    quarantined.isEmpty() &&
                        active.none { it.effect in setOf(IrEffect.AVAILABLE, IrEffect.UNRESOLVED) } &&
                        coversSearch(
                            active.filter {
                                it.effect == IrEffect.UNAVAILABLE &&
                                    it.alternativeGroup == null
                            },
                            snapshot.context,
                        )
            }
        return IrResult(
            proposed.submissionVersionId,
            if (validState) proposed.availability else IrAvailabilityState.UNKNOWN,
            accepted,
            if (validState) quarantined else quarantined + IrQuarantine(null, IrProblem.AVAILABILITY, null),
        )
    }

    private fun validScope(
        condition: ProposedIrCondition,
        context: ScheduleIrContext,
    ): Boolean {
        val scope = condition.scope ?: return condition.effect == IrEffect.UNRESOLVED
        if ((scope.start == null) != (scope.end == null)) return false
        if (scope.start == null) return condition.effect == IrEffect.UNRESOLVED
        val end = requireNotNull(scope.end)
        if (scope.start >= end) return false
        val rules = ZoneId.of(context.timeZone).rules
        if (rules.getValidOffsets(scope.start).size != 1 || rules.getValidOffsets(end).size != 1) return false
        if (condition.effect != IrEffect.UNRESOLVED && scope.dimension != IrDimension.TIME) return false
        return condition.effect != IrEffect.UNCONSTRAINED ||
            (scope.start == context.searchStart.atStartOfDay() && end == context.searchEnd.atStartOfDay())
    }

    private fun validateEvidence(
        evidence: ProposedIrEvidence,
        inputId: UUID,
        snapshot: ScheduleEvidenceSnapshot,
    ): IrEvidenceSpan {
        val input = snapshot.context.inputs.single { it.submissionVersionId == inputId }
        val binding = requireNotNull(input.sources.singleOrNull { it.sourceId == evidence.sourceId }) { "Unknown source" }
        require(binding.revision == evidence.revision) { "Evidence revision mismatch" }
        val quote = extract(snapshot, inputId, evidence.sourceId, evidence.startCodePoint, evidence.endCodePoint)
        require(quote == evidence.quote) { "Evidence span mismatch" }
        return IrEvidenceSpan(
            evidence.sourceId,
            evidence.revision,
            evidence.startCodePoint,
            evidence.endCodePoint,
            IrEvidenceBindings.sha256(quote),
        )
    }

    private fun extract(
        snapshot: ScheduleEvidenceSnapshot,
        inputId: UUID,
        sourceId: String,
        start: Int,
        end: Int,
    ): String {
        val text = requireNotNull(snapshot.sourceText(inputId, sourceId)) { "Unknown source" }
        require(start >= 0 && end > start && end <= text.codePointCount(0, text.length)) { "Invalid evidence span" }
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
    }

    private fun coversSearch(
        conditions: List<IrCondition>,
        context: ScheduleIrContext,
    ): Boolean {
        var coveredUntil = context.searchStart.atStartOfDay()
        val requiredEnd = context.searchEnd.atStartOfDay()
        for (scope in conditions.mapNotNull { it.scope }.sortedBy { it.start }) {
            val start = requireNotNull(scope.start)
            val end = requireNotNull(scope.end)
            if (start > coveredUntil) return false
            if (end > coveredUntil) coveredUntil = end
            if (coveredUntil >= requiredEnd) return true
        }
        return false
    }

    companion object {
        private val TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
        private val DIGEST = Regex("[a-f0-9]{64}")
    }
}
