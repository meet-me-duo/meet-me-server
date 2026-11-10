package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_PLACE_CONSTRAINT_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslPlaceConstraintPolicy
import com.meetme.server.submission.domain.dsl.SchedulePlaceConstraintKind
import com.meetme.server.submission.domain.dsl.SchedulePlaceReferenceKind
import com.meetme.server.submission.domain.dsl.SchedulePlaceTimeScope
import com.meetme.server.submission.domain.dsl.ScheduleTravelMode
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.ScopedTermEvidenceTestFixture as S
import com.meetme.server.support.TravelPlaceTestFixture as F

/** Strict opt-in contract tested with synthetic response bytes, not substituted live provider responses. */
class TravelPlaceProviderContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    private fun invalid(block: () -> Unit) {
        val failure = assertFailsWith<NaturalLanguageParserException>(block = block)
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
    }

    @Test
    fun `TPP01 default reader rejects new wire while opted reader preserves exact typed evidence without changing source bytes`() {
        val document = F.document()
        val frozen = H.json(document)
        invalid { F.parse(document, enabled = false) }
        invalid {
            StrictScheduleDslStructuralResponseParser(mapper).parse(
                frozen,
                F.snapshot(),
                "schedule-dsl-response-5",
                "schedule-ir-product-6",
                SCHEDULE_AST_VERSION,
                "ko-time-terms-1",
            )
        }
        val envelope = F.parse(document)
        assertEquals(SCHEDULE_PLACE_CONSTRAINT_VERSION, envelope.placeConstraintsVersion)
        assertEquals(listOf(F.constraint()), envelope.placeConstraints)
        assertEquals(F.base().calculationProjection, envelope.calculationProjection)
        assertEquals(frozen, H.json(document))
        assertEquals(F.RAW, F.snapshot().sourceText(F.first, "original"))
        assertEquals(
            listOf(F.constraint()),
            ScheduleDslPlaceConstraintPolicy.validate(envelope.placeConstraints, envelope, F.snapshot()),
        )
        val old = F.parse(F.legacyDocument(), enabled = false)
        assertNull(old.placeConstraintsVersion)
        assertTrue(old.placeConstraints.isEmpty())
    }

    @Test
    fun `TPP02 wire version required lists exact fields integer minutes and exact literal locators fail closed`() {
        val mutations: List<(MutableMap<String, Any?>) -> Unit> =
            listOf(
                { it.remove("place_constraints_version") },
                { it["place_constraints_version"] = "schedule-place-constraints-999" },
                { S.results(it).first().remove("place_constraints") },
                { S.results(it).last().remove("place_constraints") },
                { S.results(it).first()["place_constraints"] = null },
                { S.results(it).first()["place_constraints"] = mapOf("id" to "not-a-list") },
                { declaration(it)["extra"] = true },
                { declaration(it).remove("travel_mode") },
                { declaration(it)["max_minutes"] = 45.5 },
                { declaration(it)["max_minutes"] = "45" },
                { declaration(it)["max_minutes"] = 2147483648L },
                { declaration(it)["max_minutes"] = 0 },
                { declaration(it)["max_minutes"] = 10081 },
                { declaration(it)["max_minutes"] = 46 },
                { declaration(it)["travel_mode"] = "TRANSIT" },
                { declaration(it)["kind"] = "OTHER" },
                { declaration(it)["travel_mode"] = "FLY" },
                { declaration(it)["reference_kind"] = 0 },
                { declaration(it)["time_scope"] = "GUESS" },
                { F.fields(declaration(it)["evidence"])["start_code_point"] = 0 },
                { F.fields(declaration(it)["place_evidence"])["end_code_point"] = 1 },
            )
        mutations.forEach { mutate ->
            val document = F.document()
            mutate(document)
            invalid { F.parse(document) }
        }
    }

    @Test
    fun `TPP03 native source revision name containment metadata namespace and condition budget cannot be forged`() {
        val mutations: List<(MutableMap<String, Any?>) -> Unit> =
            listOf(
                { F.fields(declaration(it)["evidence"])["source_id"] = "foreign-source" },
                { F.fields(declaration(it)["evidence"])["revision"] = 4 },
                { F.fields(declaration(it)["place_evidence"])["revision"] = 4 },
                { F.fields(declaration(it)["place_evidence"])["quote"] = "가상붉은역" },
                { declaration(it)["id"] = "half" },
                { declaration(it)["id"] = "root" },
                { S.results(it).last()["place_constraints"] = listOf(F.declaration()) },
                { S.results(it).first()["place_constraints"] = listOf(F.declaration(), F.declaration()) },
                {
                    S.results(it).first()["place_constraints"] =
                        (1..33).map { index -> F.declaration().also { fields -> fields["id"] = "travel-$index" } }
                },
            )
        mutations.forEach { mutate ->
            val document = F.document()
            mutate(document)
            invalid { F.parse(document) }
        }
        val raw = "${F.RAW} 참고 가상붉은역."
        val outside = F.document(raw).also { F.fields(declaration(it)["place_evidence"])["quote"] = "가상붉은역" }
        invalid { F.parse(outside, raw) }
    }

    @Test
    fun `TPP04 declared travel modes unresolved meaning and unresolved time link are preserved rather than guessed`() {
        val namedModes =
            mapOf(
                ScheduleTravelMode.WALK to "도보",
                ScheduleTravelMode.TRANSIT to "대중교통",
                ScheduleTravelMode.CAR to "자동차",
                ScheduleTravelMode.BICYCLE to "자전거",
            )
        for ((mode, nativeName) in namedModes) {
            val expression = "${F.NAME}에서 $nativeName 45분 이내인 곳."
            val raw = "${F.TIME} $expression"
            val document = F.document(raw, listOf(F.declaration(expression).also { it["travel_mode"] = mode.name }))
            assertEquals(
                mode,
                F
                    .parse(document, raw)
                    .placeConstraints
                    .single()
                    .travelMode,
            )
        }
        val unresolved =
            F.document().also {
                declaration(it)["kind"] = "UNRESOLVED"
                declaration(it)["reference_kind"] = "UNKNOWN"
                declaration(it)["place_evidence"] = null
                declaration(it)["max_minutes"] = null
                declaration(it)["time_scope"] = "UNRESOLVED_TIME_LINK"
            }
        val constraint = F.parse(unresolved).placeConstraints.single()
        assertEquals(SchedulePlaceConstraintKind.UNRESOLVED, constraint.kind)
        assertEquals(SchedulePlaceReferenceKind.UNKNOWN, constraint.referenceKind)
        assertEquals(SchedulePlaceTimeScope.UNRESOLVED_TIME_LINK, constraint.timeScope)
        assertNull(constraint.placeEvidence)
        assertNull(constraint.maxMinutes)
        assertNull(constraint.travelMode)
        val descriptions = ScheduleDslPlaceConstraintPolicy.descriptions(listOf(constraint), F.snapshot(), F.publicNames)
        assertNull(descriptions.single().publicPlaceName)
        assertNull(descriptions.single().maxMinutes)
        assertNull(descriptions.single().travelMode)
    }

    @Suppress("UNCHECKED_CAST")
    private fun declaration(document: MutableMap<String, Any?>) =
        (S.results(document).first()["place_constraints"] as List<MutableMap<String, Any?>>).single()
}
