package com.meetme.server.submission.domain.dsl

import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import java.util.UUID

const val SCHEDULE_PLACE_CONSTRAINT_VERSION = "schedule-place-constraints-1"

enum class SchedulePlaceConstraintKind { TRAVEL_TIME_LIMIT, MEETING_PLACE, UNRESOLVED }

enum class SchedulePlaceReferenceKind { PUBLIC_NAMED_PLACE, PRIVATE_REFERENCE, UNKNOWN }

enum class ScheduleTravelMode { WALK, TRANSIT, CAR, BICYCLE }

enum class SchedulePlaceTimeScope { ALL_AVAILABLE_TIMES, UNRESOLVED_TIME_LINK }

enum class SchedulePlaceVerification { UNVERIFIED }

/** A source-bound place declaration has no authority over time AST nodes or actual route feasibility. */
data class ScheduleDslPlaceConstraint(
    val submissionVersionId: UUID,
    val id: String,
    val kind: SchedulePlaceConstraintKind,
    val referenceKind: SchedulePlaceReferenceKind,
    val placeEvidence: LocatedLiteralEvidence?,
    val evidence: LocatedLiteralEvidence,
    val maxMinutes: Int?,
    val travelMode: ScheduleTravelMode?,
    val timeScope: SchedulePlaceTimeScope,
    val verification: SchedulePlaceVerification = SchedulePlaceVerification.UNVERIFIED,
)

/** Only canonical public names can leave the source-reading boundary. */
data class PublicPlaceConstraintDescription(
    val participantId: UUID,
    val kind: SchedulePlaceConstraintKind,
    val publicPlaceName: String?,
    val maxMinutes: Int?,
    val travelMode: ScheduleTravelMode?,
)

class PublicPlaceNamePolicy(
    private val approvedAliases: Map<String, String>,
) {
    fun resolve(exactNativeName: String): String? = approvedAliases[exactNativeName]

    fun exactNativeNames(): Set<String> = approvedAliases.keys.toSet()
}

object ScheduleDslPlaceConstraintPolicy {
    fun validate(
        constraints: List<ScheduleDslPlaceConstraint>,
        envelope: ScheduleDslStructuralEnvelope,
        snapshot: ScheduleEvidenceSnapshot,
        serverCatalog: ServerEvidenceCatalog? = null,
    ): List<ScheduleDslPlaceConstraint> {
        require(envelope.context == snapshot.context) { "Place source context mismatch" }
        require(constraints.map { it.submissionVersionId to it.id }.distinct().size == constraints.size) {
            "Duplicate place declaration"
        }
        constraints.forEach { validateNative(it, snapshot, serverCatalog) }
        val declaration = envelope.base.base.declaration
        constraints.groupBy { it.submissionVersionId }.forEach { (input, values) ->
            val result = declaration.claims.results.single { it.submissionVersionId == input }
            val modes = envelope.base.modeRestrictions.filter { it.submissionVersionId == input }
            require(result.conditions.size + result.quarantined.size + modes.size + values.size <= 32) { "Place condition budget exceeded" }
            val occupied =
                result.conditions.map { it.id } + result.quarantined.mapNotNull { it.conditionId } +
                    modes.map { it.id } +
                    declaration.programs
                        .single { it.submissionVersionId == input }
                        .program
                        ?.nodes
                        .orEmpty()
                        .map { it.id }
            require(values.none { it.id in occupied }) { "Place declaration reuses another proof role" }
        }
        return constraints.toList()
    }

    fun descriptions(
        constraints: List<ScheduleDslPlaceConstraint>,
        snapshot: ScheduleEvidenceSnapshot,
        publicNames: PublicPlaceNamePolicy,
    ): List<PublicPlaceConstraintDescription> {
        val serverCatalog =
            if (constraints.any { it.evidence.derivation == EvidenceLocationDerivation.SERVER_CATALOG_REFERENCE }) {
                ServerEvidenceCatalog.create(snapshot)
            } else {
                null
            }
        return constraints.map { constraint ->
            validateNative(constraint, snapshot, serverCatalog)
            val input = snapshot.context.inputs.single { it.submissionVersionId == constraint.submissionVersionId }
            val publicName =
                if (constraint.referenceKind == SchedulePlaceReferenceKind.PUBLIC_NAMED_PLACE &&
                    constraint.kind != SchedulePlaceConstraintKind.UNRESOLVED &&
                    constraint.timeScope == SchedulePlaceTimeScope.ALL_AVAILABLE_TIMES
                ) {
                    constraint.placeEvidence?.let {
                        publicNames.resolve(
                            native(constraint.submissionVersionId, it, snapshot, serverCatalog),
                        )
                    }
                } else {
                    null
                }
            PublicPlaceConstraintDescription(
                input.participantId,
                constraint.kind,
                publicName,
                constraint.maxMinutes.takeIf { publicName != null },
                constraint.travelMode.takeIf { publicName != null },
            )
        }
    }

    private fun validateNative(
        value: ScheduleDslPlaceConstraint,
        snapshot: ScheduleEvidenceSnapshot,
        serverCatalog: ServerEvidenceCatalog? = null,
    ) {
        require(value.id.matches(Regex("[A-Za-z0-9._:-]{1,64}"))) { "Invalid place declaration ID" }
        require(snapshot.context.inputs.any { it.submissionVersionId == value.submissionVersionId }) { "Foreign place input" }
        require(value.verification == SchedulePlaceVerification.UNVERIFIED)
        val expression = native(value.submissionVersionId, value.evidence, snapshot, serverCatalog)
        val parent = value.evidence.span
        value.placeEvidence?.let { place ->
            native(value.submissionVersionId, place, snapshot, serverCatalog)
            require(
                place.span.sourceId == parent.sourceId &&
                    place.span.revision == parent.revision &&
                    place.span.startCodePoint >= parent.startCodePoint &&
                    place.span.endCodePoint <= parent.endCodePoint,
            ) { "Place name is not contained in its own declaration" }
        }
        when (value.kind) {
            SchedulePlaceConstraintKind.TRAVEL_TIME_LIMIT -> {
                require(value.placeEvidence != null && value.maxMinutes != null && value.maxMinutes in 1..10080) { "Invalid travel bound" }
                require(minutes(expression) == value.maxMinutes) { "Travel bound disagrees with its native duration" }
                value.travelMode?.let { mode ->
                    require(
                        modeLexemes.getValue(mode).any { expression.contains(it, ignoreCase = true) },
                    ) { "Travel mode lacks native evidence" }
                }
            }
            SchedulePlaceConstraintKind.MEETING_PLACE -> {
                require(
                    value.placeEvidence != null && value.maxMinutes == null && value.travelMode == null,
                ) { "Invalid meeting place declaration" }
            }
            SchedulePlaceConstraintKind.UNRESOLVED -> {
                require(value.maxMinutes == null && value.travelMode == null) { "Unresolved place cannot invent a travel bound" }
                require(value.placeEvidence != null || value.referenceKind == SchedulePlaceReferenceKind.UNKNOWN)
            }
        }
    }

    private fun native(
        input: UUID,
        evidence: LocatedLiteralEvidence,
        snapshot: ScheduleEvidenceSnapshot,
        serverCatalog: ServerEvidenceCatalog? = null,
    ): String {
        require(
            evidence.derivation in
                setOf(EvidenceLocationDerivation.SERVER_LITERAL_QUOTE, EvidenceLocationDerivation.SERVER_CATALOG_REFERENCE) &&
                evidence.contextSpan == null,
        ) {
            "Place declarations require their own literal proof"
        }
        ScheduleDslStructuralNormalizer.restoreEvidence(input, evidence, snapshot, serverCatalog)
        val text = requireNotNull(snapshot.sourceText(input, evidence.span.sourceId))
        return text.substring(
            text.offsetByCodePoints(0, evidence.span.startCodePoint),
            text.offsetByCodePoints(0, evidence.span.endCodePoint),
        )
    }

    /** Unit conversion checks an explicitly declared bound; it does not classify or repair natural-language meaning. */
    private fun minutes(expression: String): Int {
        require(!Regex("시간\\s*반").containsMatchIn(expression)) { "Unsupported fractional duration" }
        val parts = duration.findAll(expression).toList()
        require(parts.isNotEmpty() && parts.size <= 2) { "Missing or unsupported native duration" }
        require(parts.all { it.groupValues[2] in setOf("시간", "분") }) { "Unsupported native duration unit" }
        require(parts.map { it.groupValues[2] }.distinct().size == parts.size) { "Independent durations need separate declarations" }
        if (parts.size == 2) {
            require(
                parts.first().groupValues[2] == "시간" &&
                    parts.last().groupValues[2] == "분" &&
                    expression.substring(parts.first().range.last + 1, parts.last().range.first).isBlank(),
            ) { "Independent durations need separate declarations" }
        }
        val total =
            parts.sumOf { part ->
                val token = part.groupValues[1]
                val number = if (token.all { it in '0'..'9' }) token.toLongOrNull() else nativeNumbers[token]?.toLong()
                require(number != null && number in 1..10080L) { "Unsupported native duration number" }
                number * if (part.groupValues[2] == "시간") 60L else 1L
            }
        require(total in 1..10080L) { "Unsupported native duration bound" }
        return total.toInt()
    }

    private val nativeNumbers =
        listOf("한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열", "열한", "열두")
            .mapIndexed { index, name -> name to index + 1 }
            .toMap()
    private const val NUMERAL_CHARACTERS = "한두세네다섯여섯일곱여덟아홉열스물서른마흔쉰예순일흔여든백천만일이삼사오육칠팔구십영공억"
    private val duration =
        Regex(
            "(?<![0-9.])([+-]?[0-9]+(?:(?:[.,][ \\t]*|[ \\t]+)[0-9]+)*" +
                "|(?<![가-힣])[$NUMERAL_CHARACTERS]+(?:[ \\t]+[$NUMERAL_CHARACTERS]+)*)" +
                "\\s*(밀리초|개월|시간|분|초|일|주|년|달)",
        )
    private val modeLexemes =
        mapOf(
            ScheduleTravelMode.WALK to listOf("도보", "걸어서", "걸어", "walking"),
            ScheduleTravelMode.TRANSIT to listOf("대중교통", "지하철", "전철", "버스", "transit"),
            ScheduleTravelMode.CAR to listOf("자동차", "자차", "운전", "driving"),
            ScheduleTravelMode.BICYCLE to listOf("자전거", "따릉이", "bicycle"),
        )
}
