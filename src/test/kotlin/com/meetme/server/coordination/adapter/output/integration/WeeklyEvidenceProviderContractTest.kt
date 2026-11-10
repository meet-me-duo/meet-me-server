package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.submission.domain.dsl.ServerEvidenceKind
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.time.APPROVED_KOREAN_TIME_TERM_VERSION
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Frozen synthetic rules verify the provider boundary, not natural-language model accuracy. */
class WeeklyEvidenceProviderContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `WEP01 new request adds only weekly contract authority while retaining unchanged indexed native context`() {
        val f = Fixture()
        val old = IndexedEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER)
        val oldSchema = mapper.writeValueAsBytes(IndexedEvidenceProviderContract(mapper).responseSchema(f.snapshot))
        val request = mapper.readTree(WeeklyEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER))
        assertEquals("schedule-dsl-evidence-request-5", request.path("request_contract_version").asText())
        assertEquals("schedule-dsl-evidence-response-5", request.path("response_contract_version").asText())
        assertEquals("weekly-schedule-rule-1", request.path("weekly_rules_version").asText())
        val prior = mapper.readTree(old)
        for (field in listOf("inputs", "evidence_catalog", "relative_date_context", "meeting_date_scope", "calendar_context")) {
            assertEquals(prior.path(field), request.path(field), field)
        }
        for (field in listOf("date_scope_version", "calendar_facts_version", "evidence_catalog_version", "evidence_selection_version")) {
            assertEquals(prior.path(field), request.path(field), field)
        }
        assertTrue(f.catalog.entries.none { request.toString().contains(it.id) })
        assertContentEquals(old, IndexedEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER))
        assertContentEquals(oldSchema, mapper.writeValueAsBytes(IndexedEvidenceProviderContract(mapper).responseSchema(f.snapshot)))
    }

    @Test
    fun `WEP02 one owned weekly condition expands every Friday across the year without cloning the native condition or graph`() {
        val f = Fixture()
        val frozen = mapper.writeValueAsBytes(f.document())
        val actual = parse(f, f.document())
        val app = actual.weeklyApplications.single()
        assertEquals(H.ir.first, app.inputId)
        assertEquals("weekly", app.conditionId)
        assertEquals("weekly-schedule-rule-1", app.version)
        assertTrue(app.resolved)
        assertEquals(setOf(DayOfWeek.FRIDAY), app.rule.weekdays)
        assertEquals(
            listOf(LocalDate.parse("2027-12-31"), LocalDate.parse("2028-01-07")),
            app.occurrences.filter { it.searchSupport != null }.map { it.anchorDate },
        )
        assertEquals(
            listOf(H.scope("2027-12-31T18:00", "2027-12-31T20:00"), H.scope("2028-01-07T18:00", "2028-01-07T20:00")),
            app.occurrences.filter { it.searchSupport != null }.map { it.localScope },
        )
        val own =
            actual.base.base.declaration.claims.results
                .single { it.submissionVersionId == H.ir.first }
        val condition = own.conditions.single()
        assertEquals("weekly", condition.id)
        assertEquals(IrTemporalResolution.NON_PRECISE, condition.temporalResolution)
        assertNull(condition.scope)
        val program =
            assertNotNull(
                actual.base.base.declaration.programs
                    .single { it.submissionVersionId == H.ir.first }
                    .program,
            )
        assertEquals(listOf("weekly"), program.nodes.mapNotNull { it.claimId })
        assertEquals(
            f.catalog.entries[f.whole()].id,
            app.rule.weekdayEvidence
                .single()
                .catalogId,
        )
        assertEquals(
            setOf(f.catalog.entries[f.clock("오후 6시")].id, f.catalog.entries[f.clock("8시")].id),
            app.rule.time.clockEvidence
                .map { it.catalogId }
                .toSet(),
        )
        assertContentEquals(frozen, mapper.writeValueAsBytes(f.document()))
        assertEquals(f.raw, f.snapshot.sourceText(H.ir.first, "original"))
    }

    @Test
    fun `WEP03 an explicit owner month filter narrows anchors while a peer filter cannot grant authority`() {
        val f = Fixture("🙂 12월 금요일 오후 6시부터 8시까지 가능.")
        val atom = f.weekly()
        atom["anchor_filter"] = f.filter(months = listOf("DECEMBER"), evidence = listOf(f.whole()))
        val actual = parse(f, f.document(atom))
        val app = actual.weeklyApplications.single()
        assertEquals(setOf(Month.DECEMBER), app.rule.filter.months)
        assertEquals(listOf(LocalDate.parse("2027-12-31")), app.occurrences.map { it.anchorDate })
        assertEquals(
            f.catalog.entries[f.whole()].id,
            app.rule.filter.evidence
                .single()
                .catalogId,
        )
        val foreign = f.copy(f.document(atom))
        filter(foreign)["evidence"] = listOf(f.whole(H.ir.second))
        invalid { parse(f, foreign) }
    }

    @Test
    fun `WEP04 weekly whole day and approved afternoon retain distinct native time declarations for every anchor`() {
        val day = Fixture("🙂 금요일 하루 전체 가능.")
        val whole = parse(day, day.document(day.weekly(time = day.time("WHOLE_DAY"))))
        assertEquals(
            listOf(H.scope("2027-12-31T00:00", "2028-01-01T00:00"), H.scope("2028-01-07T00:00", "2028-01-08T00:00")),
            whole.weeklyApplications
                .single()
                .occurrences
                .filter { it.searchSupport != null }
                .map { it.localScope },
        )
        val pm = Fixture("🙂 금요일 오후 가능.")
        val term = pm.selection("오후", kind = ServerEvidenceKind.TERM)
        val atom = pm.weekly(time = pm.time("TERM", term = "PM", termEvidence = term))
        val actual = parse(pm, pm.document(atom))
        assertEquals(
            listOf(H.scope("2027-12-31T12:00", "2028-01-01T00:00"), H.scope("2028-01-07T12:00", "2028-01-08T00:00")),
            actual.weeklyApplications
                .single()
                .occurrences
                .filter { it.searchSupport != null }
                .map { it.localScope },
        )
        assertEquals(
            pm.catalog.entries[term].id,
            actual.weeklyApplications
                .single()
                .rule.time.termEvidence
                ?.catalogId,
        )
    }

    @Test
    fun `WEP05 an unknown personal time remains unresolved while a known empty filter stays resolved and empty`() {
        val unknown = Fixture("🙂 금요일 공연이 끝나면 가능.")
        val uncertain = parse(unknown, unknown.document(unknown.weekly(time = unknown.time("UNKNOWN"))))
        assertFalse(uncertain.weeklyApplications.single().resolved)
        assertTrue(
            uncertain.weeklyApplications
                .single()
                .occurrences
                .isEmpty(),
        )
        val empty = Fixture("🙂 11월 금요일 하루 전체 가능.")
        val atom = empty.weekly(time = empty.time("WHOLE_DAY"))
        atom["anchor_filter"] = empty.filter(months = listOf("NOVEMBER"), evidence = listOf(empty.whole()))
        val known = parse(empty, empty.document(atom))
        assertTrue(known.weeklyApplications.single().resolved)
        assertTrue(
            known.weeklyApplications
                .single()
                .occurrences
                .isEmpty(),
        )
        assertNull(
            known.base.base.declaration.claims.results
                .single { it.submissionVersionId == H.ir.first }
                .conditions
                .single()
                .scope,
        )
    }

    @Test
    fun `WEP06 markers invalid weekdays mixed time fields and falsely precise weekly declarations are rejected`() {
        val f = Fixture()
        for (value in listOf(null, "weekly-schedule-rule-0")) {
            val doc = f.document()
            if (value == null) doc.remove("weekly_rules_version") else doc["weekly_rules_version"] = value
            invalid { parse(f, doc) }
        }
        for (weekdays in listOf(emptyList<String>(), listOf("FUNDAY"), listOf("FRIDAY", "FRIDAY"))) {
            val doc = f.document()
            atom(doc)["weekdays"] = weekdays
            invalid { parse(f, doc) }
        }
        for (field in listOf("months", "excluded_dates")) {
            val doc = f.document()
            val repeated = if (field == "months") "DECEMBER" else "2027-12-31"
            filter(doc)[field] = listOf(repeated, repeated)
            filter(doc)["evidence"] = listOf(f.whole())
            invalid { parse(f, doc) }
        }
        val falsePrecision = f.document()
        condition(falsePrecision)["temporal_resolution"] = "PRECISE_INTERVAL"
        invalid { parse(f, falsePrecision) }
        val mixed = f.document(f.weekly(time = f.time("WHOLE_DAY", lower = "18:00")))
        invalid { parse(f, mixed) }
        val termWithoutProof = f.document(f.weekly(time = f.time("TERM", term = "PM")))
        invalid { parse(f, termWithoutProof) }
        val clockless = f.document(f.weekly(time = f.time("BOUNDS", lower = "18:00", upper = "20:00")))
        invalid { parse(f, clockless) }
        val missing = f.document()
        atom(missing).remove("anchor_filter")
        invalid { parse(f, missing) }
    }

    @Test
    fun `WEP07 all new evidence positions reject numeric coercion bounds and foreign participant selections`() {
        val f = Fixture("🙂 12월 금요일 오후 6시부터 8시까지 가능.")
        for (bad in listOf<Any>("0", 0.5, -1, f.catalog.entries.size)) {
            val doc = f.document()
            atom(doc)["weekday_evidence"] = listOf(bad)
            invalid { parse(f, doc) }
        }
        val weekday = f.document()
        atom(weekday)["weekday_evidence"] = listOf(f.whole(H.ir.second))
        invalid { parse(f, weekday) }
        val clock = f.document()
        time(clock)["clock_evidence"] = listOf(f.whole(H.ir.second))
        invalid { parse(f, clock) }
        val pm = Fixture("🙂 금요일 오후 가능.")
        val term = pm.document(pm.weekly(time = pm.time("TERM", term = "PM", termEvidence = pm.whole(H.ir.second))))
        invalid { parse(pm, term) }
    }

    @Test
    fun `WEP08 both provider wires preserve the finite old grammar and add a strict bounded weekly rule schema`() {
        val f = Fixture()
        val old = IndexedEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER)
        val oldBodies = AnalysisProvider.entries.associateWith { provider -> wire(old, provider).toString() }
        val neutral = WeeklyEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER)
        for (provider in listOf(AnalysisProvider.OPENAI, AnalysisProvider.GEMINI)) {
            val body = wire(neutral, provider)
            val schema =
                if (provider == AnalysisProvider.OPENAI) {
                    body.path("text").path("format").path("schema")
                } else {
                    body.path("generationConfig").path("responseJsonSchema")
                }
            val weekly =
                objects(schema).filter {
                    it
                        .path("properties")
                        .path("kind")
                        .path("enum")
                        .any { k -> k.asText() == "WEEKLY" }
                }
            assertTrue(weekly.isNotEmpty())
            weekly.forEach { node ->
                assertTrue(node.has("additionalProperties"))
                assertFalse(node.path("additionalProperties").asBoolean())
                assertEquals(
                    setOf("kind", "weekdays", "weekday_evidence", "anchor_filter", "time"),
                    node
                        .path("required")
                        .toList()
                        .map { it.asText() }
                        .toSet(),
                )
                val refs = node.path("properties").path("weekday_evidence").path("items")
                assertEquals("integer", refs.path("type").asText())
                assertEquals(0, refs.path("minimum").asInt())
                assertEquals(f.catalog.entries.size - 1, refs.path("maximum").asInt())
            }
            val filters = objects(schema).filter { it.path("properties").has("months") }
            assertTrue(filters.isNotEmpty())
            filters.forEach { node ->
                assertTrue(node.has("additionalProperties"))
                assertFalse(node.path("additionalProperties").asBoolean())
                assertEquals(
                    setOf("months", "from_inclusive", "until_exclusive", "excluded_dates", "evidence"),
                    node
                        .path("required")
                        .toList()
                        .map { it.asText() }
                        .toSet(),
                )
            }
            val times = objects(schema).filter { it.path("properties").has("end_day_offset") }
            assertTrue(times.isNotEmpty())
            times.forEach { node ->
                assertTrue(node.has("additionalProperties"))
                assertFalse(node.path("additionalProperties").asBoolean())
                assertEquals(
                    setOf("kind", "lower", "upper", "end_day_offset", "clock_evidence", "term", "term_evidence"),
                    node
                        .path("required")
                        .toList()
                        .map { it.asText() }
                        .toSet(),
                )
            }
            val kinds =
                objects(schema)
                    .flatMap {
                        it
                            .path("properties")
                            .path("kind")
                            .path("enum")
                            .toList()
                            .map { k -> k.asText() }
                    }.toSet()
            assertTrue(kinds.containsAll(setOf("WEEKLY", "APPROVED_TERM", "BOUNDS", "UNKNOWN", "RAW_SCOPE")))
            assertFiniteSchema(schema)
            assertTrue(schema.path("\$defs").has("meeting_date"))
            assertTrue(schema.path("required").any { it.asText() == "weekly_rules_version" })
            assertEquals(oldBodies.getValue(provider), wire(old, provider).toString())
        }
        val ai = wire(neutral, AnalysisProvider.OPENAI)
        val gemini = wire(neutral, AnalysisProvider.GEMINI)
        val geminiInput =
            gemini
                .path("contents")
                .path(0)
                .path("parts")
                .path(0)
                .path("text")
                .asText()
        assertEquals(mapper.readTree(ai.path("input").asText()), mapper.readTree(geminiInput))
        assertEquals(
            ai.path("instructions").asText(),
            gemini
                .path("systemInstruction")
                .path("parts")
                .path(0)
                .path("text")
                .asText(),
        )
    }

    @Test
    fun `WEP09 generation five also accepts existing date atoms without weekly applications and generation four stays strict`() {
        val f = Fixture()
        val dated = f.document(linkedMapOf("kind" to "WHOLE_DAY", "local_date" to "2027-12-31", "date_evidence" to listOf(f.whole())))
        val actual = parse(f, dated)
        assertTrue(actual.weeklyApplications.isEmpty())
        invalid { indexedParse(f, dated) }
        val old = f.copy(dated).also { it.remove("weekly_rules_version") }
        val before = indexedParse(f, old)
        assertEquals(before.base, actual.base)
        assertEquals(before.calculationProjection, actual.calculationProjection)
        val invalidOld = f.document().also { it.remove("weekly_rules_version") }
        invalid { indexedParse(f, invalidOld) }
        invalid { parse(f, old) }
    }

    @Test
    fun `WEP10 date filters anchor exclusions and overnight offsets survive typed provider resolution unchanged`() {
        val f = Fixture("🙂 2027-12-31부터 2028-01-08 전까지 금요일 23:00~01:00 가능. 2028-01-07 제외.")
        val atom = f.weekly(time = f.time("BOUNDS", "23:00", "01:00", listOf(f.clock("23:00"), f.clock("01:00"))))
        @Suppress("UNCHECKED_CAST")
        (atom["time"] as MutableMap<String, Any?>)["end_day_offset"] = 1
        val filter = f.filter(evidence = listOf(f.whole()))
        filter["from_inclusive"] = "2027-12-31"
        filter["until_exclusive"] = "2028-01-08"
        filter["excluded_dates"] = listOf("2028-01-07")
        atom["anchor_filter"] = filter
        val app = parse(f, f.document(atom)).weeklyApplications.single()
        assertTrue(app.resolved)
        assertEquals(LocalDate.parse("2027-12-31"), app.rule.filter.fromInclusive)
        assertEquals(LocalDate.parse("2028-01-08"), app.rule.filter.untilExclusive)
        assertEquals(setOf(LocalDate.parse("2028-01-07")), app.rule.filter.excludedDates)
        assertEquals(1, app.rule.time.endDayOffset)
        assertEquals(listOf(H.scope("2027-12-31T23:00", "2028-01-01T01:00")), app.occurrences.map { it.localScope })
    }

    private fun wire(
        neutral: ByteArray,
        provider: AnalysisProvider,
    ): JsonNode {
        val model = if (provider == AnalysisProvider.OPENAI) "gpt-6-luna" else "gemini-3.8-flash"
        return mapper.readTree(ServerEvidenceWire.bytes(mapper, neutral, provider, model))
    }

    private fun assertFiniteSchema(schema: JsonNode) {
        val definitions = schema.path("\$defs").properties().associate { it.key to it.value }
        val expressions = setOf("c5_inline_expression") + (2..8).map { "c5_inline_expression_depth_$it" }
        assertEquals(expressions + "meeting_date", definitions.keys)

        fun references(node: JsonNode): Set<String> =
            objects(node)
                .mapNotNull { objectNode ->
                    objectNode.path("\$ref").takeIf { it.isTextual }?.asText()?.let { reference ->
                        assertTrue(reference.startsWith("#/\$defs/"), "Unexpected nonlocal schema reference: $reference")
                        reference.removePrefix("#/\$defs/")
                    }
                }.toSet()
        assertTrue(references(schema).all { it in definitions }, "Every schema reference must resolve")

        fun depth(
            name: String,
            path: Set<String>,
        ): Int {
            assertFalse(name in path, "Recursive schema path: $path -> $name")
            assertTrue(name in definitions, "Undefined schema reference: $name")
            val children = references(definitions.getValue(name))
            return 1 + (children.maxOfOrNull { depth(it, path + name) } ?: 0)
        }
        definitions.keys.forEach { name ->
            assertTrue(depth(name, emptySet()) <= 8, "Logical schema exceeds the existing maximum expression depth")
        }
    }

    private fun objects(node: JsonNode): List<JsonNode> =
        when {
            node.isObject -> listOf(node) + node.properties().flatMap { (_, child) -> objects(child) }
            node.isArray -> node.flatMap(::objects)
            else -> emptyList()
        }

    private fun parse(
        f: Fixture,
        document: Map<String, Any?>,
    ) = WeeklyEvidenceProviderContract(mapper).parseProviderResponse(
        mapper.writeValueAsString(document),
        f.snapshot,
        SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
        SCHEDULE_AST_VERSION,
        APPROVED_KOREAN_TIME_TERM_VERSION,
    )

    private fun indexedParse(
        f: Fixture,
        document: Map<String, Any?>,
    ) = IndexedEvidenceProviderContract(mapper).parseProviderResponse(
        mapper.writeValueAsString(document),
        f.snapshot,
        SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
        SCHEDULE_AST_VERSION,
        APPROVED_KOREAN_TIME_TERM_VERSION,
    )

    private fun invalid(block: () -> Unit) {
        assertEquals(ParserFailureKind.INVALID_RESPONSE, assertFailsWith<NaturalLanguageParserException>(block = block).kind)
    }

    @Suppress("UNCHECKED_CAST")
    private fun condition(doc: MutableMap<String, Any?>) =
        ((doc["results"] as List<MutableMap<String, Any?>>).first()["conditions"] as List<MutableMap<String, Any?>>).first()

    @Suppress("UNCHECKED_CAST")
    private fun atom(doc: MutableMap<String, Any?>) = condition(doc)["temporal"] as MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun filter(doc: MutableMap<String, Any?>) = atom(doc)["anchor_filter"] as MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun time(doc: MutableMap<String, Any?>) = atom(doc)["time"] as MutableMap<String, Any?>

    private class Fixture(
        val raw: String = "🙂 금요일 오후 6시부터 8시까지 가능.",
    ) {
        private val mapper = JsonMapper.builder().findAndAddModules().build()
        private val input =
            H.input(raw).let { old ->
                old.copy(
                    policy = N.policy,
                    meetingMode = MeetingMode.EITHER,
                    searchStart = LocalDate.parse("2027-12-30"),
                    searchEnd = LocalDate.parse("2028-01-09"),
                    inputs =
                        old.inputs.mapIndexed { index, item ->
                            if (index == 1) item.copy(rawText = "2028-01-01 하루 전체 가능. 1월 조건은 내 조건.") else item
                        },
                )
            }
        val snapshot = H.snapshot(input)
        val capsule = H.capsule(input)
        val catalog = ServerEvidenceCatalog.create(snapshot)

        fun selection(
            text: String,
            owner: UUID = H.ir.first,
            kind: ServerEvidenceKind = ServerEvidenceKind.CONTEXT,
        ): Int = catalog.entries.indexOfFirst { it.inputId == owner && it.kind == kind && it.text == text }.also { require(it >= 0) }

        fun whole(owner: UUID = H.ir.first) = selection(assertNotNull(snapshot.sourceText(owner, "original")), owner)

        fun clock(text: String) = selection(text, kind = ServerEvidenceKind.CLOCK)

        fun filter(
            months: List<String> = emptyList(),
            evidence: List<Int> = emptyList(),
        ): MutableMap<String, Any?> =
            linkedMapOf(
                "months" to months,
                "from_inclusive" to null,
                "until_exclusive" to null,
                "excluded_dates" to emptyList<String>(),
                "evidence" to evidence,
            )

        fun time(
            kind: String,
            lower: String? = null,
            upper: String? = null,
            clocks: List<Int> = emptyList(),
            term: String? = null,
            termEvidence: Int? = null,
        ): MutableMap<String, Any?> =
            linkedMapOf(
                "kind" to kind,
                "lower" to lower,
                "upper" to upper,
                "end_day_offset" to 0,
                "clock_evidence" to clocks,
                "term" to term,
                "term_evidence" to termEvidence,
            )

        fun weekly(time: MutableMap<String, Any?>? = null): MutableMap<String, Any?> =
            linkedMapOf(
                "kind" to "WEEKLY",
                "weekdays" to listOf("FRIDAY"),
                "weekday_evidence" to listOf(whole()),
                "anchor_filter" to filter(),
                "time" to (time ?: this.time("BOUNDS", "18:00", "20:00", listOf(clock("오후 6시"), clock("8시")))),
            )

        fun document(atom: MutableMap<String, Any?> = weekly()): MutableMap<String, Any?> {
            val peer = whole(H.ir.second)
            return linkedMapOf(
                "semantic_version" to SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
                "ast_version" to SCHEDULE_AST_VERSION,
                "vocabulary_version" to APPROVED_KOREAN_TIME_TERM_VERSION,
                "evidence_catalog_version" to "server-evidence-catalog-1",
                "date_scope_version" to "meeting-date-scope-1",
                "calendar_facts_version" to "server-calendar-facts-2",
                "evidence_selection_version" to "server-evidence-selection-1",
                "weekly_rules_version" to "weekly-schedule-rule-1",
                "results" to
                    listOf(
                        result(H.ir.first, "weekly", atom, whole()),
                        result(
                            H.ir.second,
                            "peer",
                            linkedMapOf("kind" to "WHOLE_DAY", "local_date" to "2028-01-01", "date_evidence" to listOf(peer)),
                            peer,
                        ),
                    ),
            )
        }

        private fun result(
            owner: UUID,
            id: String,
            atom: MutableMap<String, Any?>,
            proof: Int,
        ): MutableMap<String, Any?> =
            linkedMapOf(
                "input_ref" to owner.toString(),
                "conditions" to
                    listOf(
                        linkedMapOf(
                            "id" to id,
                            "effect" to "AVAILABLE",
                            "interpretation" to "EXPLICIT",
                            "temporal_resolution" to "NON_PRECISE",
                            "evidence" to listOf(proof),
                            "assertion" to "ASSERTED",
                            "supersedes" to emptyList<String>(),
                            "temporal" to atom,
                        ),
                    ),
                "program" to
                    linkedMapOf(
                        "nodes" to emptyList<Any>(),
                        "availability_root" to
                            linkedMapOf(
                                "operator" to "CLAIM_REF",
                                "claim_id" to id,
                                "children" to emptyList<Any>(),
                                "interpretation" to "EXPLICIT",
                                "assertion" to "ASSERTED",
                                "evidence" to listOf(proof),
                            ),
                        "exclusion_root" to null,
                        "preference_root" to null,
                        "history_condition_ids" to emptyList<String>(),
                    ),
                "approved_terms" to emptyList<Any>(),
                "directional_times" to emptyList<Any>(),
                "mode_restrictions" to emptyList<Any>(),
            )

        @Suppress("UNCHECKED_CAST")
        fun copy(doc: Map<String, Any?>): MutableMap<String, Any?> =
            mapper.readValue(mapper.writeValueAsBytes(doc), MutableMap::class.java) as MutableMap<String, Any?>
    }
}
