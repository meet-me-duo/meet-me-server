package com.meetme.server.support

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslStructuralResponseParser
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.ProposedLiteralQuoteLocator
import com.meetme.server.submission.domain.dsl.PublicPlaceNamePolicy
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_PLACE_CONSTRAINT_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslPlaceConstraint
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralNormalizer
import com.meetme.server.submission.domain.dsl.SchedulePlaceConstraintKind
import com.meetme.server.submission.domain.dsl.SchedulePlaceReferenceKind
import com.meetme.server.submission.domain.dsl.SchedulePlaceTimeScope
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.ScopedTermEvidenceTestFixture as S

/** Invented station names and manually declared semantics; never a live-language accuracy oracle. */
object TravelPlaceTestFixture {
    const val TIME = "🙂 2027-01-08 오후 가능."
    const val NAME = "가상푸른역"
    const val PLACE = "가상푸른역에서 45분 이내인 곳."
    const val RAW = "$TIME $PLACE"
    const val PRIVATE_NAME = "내 비공개 숙소"
    const val PRIVATE_PLACE = "내 비공개 숙소에서 45분 이내인 곳."
    const val PRIVATE_RAW = "$TIME $PRIVATE_PLACE"
    const val UNREGISTERED_NAME = "가상숨은역"
    const val UNREGISTERED_PLACE = "가상숨은역에서 45분 이내인 곳."
    const val UNREGISTERED_RAW = "$TIME $UNREGISTERED_PLACE"
    val first = H.ir.first
    val second = H.ir.second
    val firstParticipant = H.ir.firstParticipant
    val secondParticipant = H.ir.secondParticipant
    val policy = S.policy
    val publicNames = PublicPlaceNamePolicy(mapOf(NAME to NAME, "가상푸른역 별칭" to NAME, "가상붉은역" to "가상붉은역"))
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    fun input(raw: String = RAW) = S.input(raw).copy(meetingMode = MeetingMode.EITHER)

    fun snapshot(raw: String = RAW) = H.snapshot(input(raw))

    fun locator(quote: String): MutableMap<String, Any?> = mutableMapOf("source_id" to "original", "revision" to 3, "quote" to quote)

    fun nativeEvidence(
        quote: String,
        raw: String = RAW,
    ) = ScheduleDslStructuralNormalizer.locate(first, ProposedLiteralQuoteLocator("original", 3, quote), snapshot(raw))

    fun constraint(
        raw: String = RAW,
        expression: String = PLACE,
        name: String? = NAME,
        referenceKind: SchedulePlaceReferenceKind = SchedulePlaceReferenceKind.PUBLIC_NAMED_PLACE,
        kind: SchedulePlaceConstraintKind = SchedulePlaceConstraintKind.TRAVEL_TIME_LIMIT,
    ) = ScheduleDslPlaceConstraint(
        submissionVersionId = first,
        id = "travel-origin",
        kind = kind,
        referenceKind = referenceKind,
        placeEvidence = name?.let { nativeEvidence(it, raw) },
        evidence = nativeEvidence(expression, raw),
        maxMinutes = if (kind == SchedulePlaceConstraintKind.TRAVEL_TIME_LIMIT) 45 else null,
        travelMode = null,
        timeScope = SchedulePlaceTimeScope.ALL_AVAILABLE_TIMES,
    )

    fun declaration(
        expression: String = PLACE,
        name: String? = NAME,
    ): MutableMap<String, Any?> =
        mutableMapOf(
            "id" to "travel-origin",
            "kind" to "TRAVEL_TIME_LIMIT",
            "reference_kind" to "PUBLIC_NAMED_PLACE",
            "place_evidence" to name?.let(::locator),
            "evidence" to locator(expression),
            "max_minutes" to 45,
            "travel_mode" to null,
            "time_scope" to "ALL_AVAILABLE_TIMES",
        )

    fun legacyDocument(raw: String = RAW): MutableMap<String, Any?> = S.document(input(raw), contexts = listOf(TIME))

    fun document(
        raw: String = RAW,
        declarations: List<MutableMap<String, Any?>> = listOf(declaration()),
    ): MutableMap<String, Any?> =
        legacyDocument(raw).also { document ->
            document["place_constraints_version"] = SCHEDULE_PLACE_CONSTRAINT_VERSION
            S.results(document).forEachIndexed { index, result ->
                result["place_constraints"] = if (index == 0) declarations else emptyList<Any>()
            }
        }

    fun parse(
        document: MutableMap<String, Any?> = document(),
        raw: String = RAW,
        enabled: Boolean = true,
        objectMapper: ObjectMapper = mapper,
        scopedTerms: Boolean = false,
    ) = StrictScheduleDslStructuralResponseParser(
        objectMapper,
        allowScopedTermEvidence = scopedTerms,
        allowPlaceConstraints = enabled,
    ).parse(
        H.json(document),
        snapshot(raw),
        "schedule-dsl-response-5",
        "schedule-ir-product-6",
        SCHEDULE_AST_VERSION,
        "ko-time-terms-1",
    )

    fun base(raw: String = RAW) = parse(legacyDocument(raw), raw, enabled = false)

    @Suppress("UNCHECKED_CAST")
    fun fields(value: Any?) = value as MutableMap<String, Any?>
}
