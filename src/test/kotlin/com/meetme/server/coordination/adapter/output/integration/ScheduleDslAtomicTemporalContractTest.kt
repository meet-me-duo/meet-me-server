package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.ApprovedTimeTermDerivation
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslProgram
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
import com.meetme.server.submission.domain.dsl.TemporalDirection
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Synthetic public producer contracts; atom syntax does not prove the model's language interpretation. */
class ScheduleDslAtomicTemporalContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val contract = ScheduleDslStructuralProviderContract(mapper)

    @Test
    fun `TA01 supplied meaning date and term proofs form one bundle for four preference-only windows`() {
        val fixture = preferences()
        val before = F.json(fixture.document)
        val envelope = fixture.parse()
        val claims = fixture.firstClaims(envelope)
        assertEquals(setOf("fri-lunch", "fri-evening", "sun-lunch", "sun-evening"), claims.map { it.id }.toSet())
        assertTrue(claims.all { it.effect == IrEffect.PREFERRED && it.scope == null })
        assertTrue(claims.all { it.temporalResolution == IrTemporalResolution.NON_PRECISE })
        val applications = envelope.base.base.termApplications
        assertEquals(4, applications.size)
        applications.forEach { application ->
            val claim = claims.single { it.id == application.conditionId }
            assertEquals(3, claim.evidence.size)
            assertTrue(claim.evidence.contains(application.termEvidence))
            assertTrue(claim.evidence.containsAll(application.dateEvidence))
            assertEquals(IrInterpretation.EXPLICIT, claim.interpretation)
            assertEquals(ApprovedTimeTermDerivation.SERVER_VOCABULARY, application.derivation)
            assertEquals(setOf("original"), claim.evidence.map { it.sourceId }.toSet())
            assertEquals(setOf(3), claim.evidence.map { it.revision }.toSet())
        }
        assertEquals(mealWindows(), fixture.available(envelope))
        assertNull(fixture.firstProgram(envelope).program!!.availabilityRoot)
        assertEquals(envelope, ScheduleDslStructuralValidator().validateRestored(envelope, F.snapshot(fixture.input)))
        assertEquals(before, F.json(fixture.document), "The original wire remains unchanged")
    }

    @Test
    fun `TA02 owned date bounds refine each window while independently available dates remain alternatives`() {
        val fixture =
            fixture(
                "2027-01-08 16:00 이후와 2027-01-10 19:00 이전이 가능해요.",
                listOf(
                    Spec("later", bounds("2027-01-08", lower = "16:00"), meaning = "가능해요."),
                    Spec("earlier", bounds("2027-01-10", upper = "19:00"), meaning = "가능해요."),
                ),
            )
        val envelope = fixture.parse()
        assertTrue(fixture.firstClaims(envelope).all { it.scope == null && it.temporalResolution == IrTemporalResolution.NON_PRECISE })
        assertEquals(
            setOf(TemporalDirection.AFTER, TemporalDirection.BEFORE),
            envelope.directionalApplications.map { it.declaration.direction }.toSet(),
        )
        assertEquals(
            listOf(window("2027-01-08T16:00", "2027-01-09T00:00"), window("2027-01-10T00:00", "2027-01-10T19:00")),
            fixture.available(envelope),
        )
        val independent =
            fixture(
                "2027-01-08은 10:00 이전 또는 18:00 이후 아무때나 가능해요.",
                listOf(
                    Spec("morning", bounds("2027-01-08", upper = "10:00")),
                    Spec("evening", bounds("2027-01-08", lower = "18:00")),
                ),
            )
        assertEquals(
            listOf(window("2027-01-08T00:00", "2027-01-08T10:00"), window("2027-01-08T18:00", "2027-01-09T00:00")),
            independent.available(independent.parse()),
            "Independent windows on one date remain ANY_OF, not an unconditional intersection",
        )
        val literal = twoBounds()
        val precise = literal.parse()
        val original = literal.firstClaims(precise).single()
        assertEquals(IrInterpretation.EXPLICIT, original.interpretation)
        assertEquals(IrTemporalResolution.PRECISE_INTERVAL, original.temporalResolution)
        assertEquals(F.scope("2027-01-08T16:00", "2027-01-08T19:00"), original.scope)
        assertTrue(precise.directionalApplications.isEmpty(), "Two literal endpoints do not fabricate two single-bound witnesses")
        assertEquals(listOf(window("2027-01-08T16:00", "2027-01-08T19:00")), literal.available(precise))
        val seconds =
            fixture(
                "2027-01-08 16:00:30부터 19:00:45까지 가능해요.",
                listOf(
                    Spec(
                        "seconds",
                        bounds("2027-01-08", "16:00:30", "19:00:45"),
                        resolution = IrTemporalResolution.PRECISE_INTERVAL,
                    ),
                ),
            )
        val exactSeconds = seconds.parse()
        assertEquals(F.scope("2027-01-08T16:00:30", "2027-01-08T19:00:45"), seconds.firstClaims(exactSeconds).single().scope)
        assertEquals(listOf(window("2027-01-08T16:00:30", "2027-01-08T19:00:45")), seconds.available(exactSeconds))
    }

    @Test
    fun `TA03 genuine mutually exclusive dates keep CHOICE rather than an automatic union or intersection`() {
        val fixture =
            fixture(
                "점심은 2027-01-08 또는 2027-01-10 중 하루만 가능해요.",
                listOf(
                    Spec("fri", term("LUNCH", "2027-01-08"), alternative = "fri"),
                    Spec("sun", term("LUNCH", "2027-01-10"), alternative = "sun"),
                ),
            ) { input ->
                F.program(
                    listOf(
                        F.ref(input, "fri-ref", "fri"),
                        F.ref(input, "sun-ref", "sun"),
                        F
                            .node(input, "choice", ScheduleDslOperator.CHOICE, listOf("fri-ref", "sun-ref"))
                            .copy(choiceGroup = "date-choice", branchLabels = listOf("fri", "sun")),
                    ),
                    "choice",
                )
            }
        val envelope = fixture.parse()
        assertNotNull(fixture.firstProgram(envelope).program)
        assertTrue(
            fixture
                .firstProgram(envelope)
                .program!!
                .nodes
                .any { it.operator == ScheduleDslOperator.CHOICE },
        )
        assertEquals(2, envelope.base.base.termApplications.size)
        assertTrue(fixture.available(envelope).isEmpty())
        assertEquals(setOf("fri", "sun"), fixture.firstClaims(envelope).mapNotNull { it.alternative }.toSet())
    }

    @Test
    fun `TA04 atomic compilation cannot turn an inferred correction into authority to erase a negative`() {
        val text =
            "2027-01-08 14:00부터 20:00까지 가능. 16:00부터 18:00까지 불가. " +
                "나중에는 16:00부터 19:00쯤 될 듯해요."
        val negativeQuote = "16:00부터 18:00까지 불가."
        val estimateQuote = "16:00부터 19:00쯤 될 듯해요."
        val fixture =
            fixture(
                text,
                listOf(
                    Spec(
                        "available",
                        bounds("2027-01-08", "14:00", "20:00"),
                        resolution = IrTemporalResolution.PRECISE_INTERVAL,
                    ),
                    Spec(
                        "blocked",
                        bounds("2027-01-08", "16:00", "18:00", negativeQuote, negativeQuote),
                        effect = IrEffect.UNAVAILABLE,
                        resolution = IrTemporalResolution.PRECISE_INTERVAL,
                        meaning = negativeQuote,
                    ),
                    Spec(
                        "estimated",
                        bounds("2027-01-08", "16:00", "19:00", estimateQuote, estimateQuote),
                        interpretation = IrInterpretation.INFERRED,
                        supersedes = listOf("blocked"),
                        meaning = estimateQuote,
                    ),
                ),
            )
        val envelope = fixture.parse()
        assertEquals(listOf("blocked"), fixture.firstClaims(envelope).single { it.id == "estimated" }.supersedes)
        assertEquals(IrEffect.UNAVAILABLE, fixture.firstClaims(envelope).single { it.id == "blocked" }.effect)
        assertEquals(
            2,
            fixture
                .firstClaims(envelope)
                .single { it.id == "blocked" }
                .evidence.size,
        )
        assertEquals(
            listOf(window("2027-01-08T14:00", "2027-01-08T16:00"), window("2027-01-08T18:00", "2027-01-08T20:00")),
            fixture.available(envelope),
        )
    }

    @Test
    fun `TA05 unknown differs from a whole day while inferred clocks and raw scopes keep their provenance`() {
        val day = fixture("2027-01-08 가능해요.", listOf(Spec("day", whole("2027-01-08"), meaning = "가능해요.")))
        val full = day.parse()
        assertNull(day.firstClaims(full).single().scope)
        assertEquals(
            TemporalDirection.WHOLE_DAY,
            full.directionalApplications
                .single()
                .declaration.direction,
        )
        assertNull(
            full.directionalApplications
                .single()
                .declaration.clock,
        )
        assertEquals(listOf(window("2027-01-08T00:00", "2027-01-09T00:00")), day.available(full))
        val unknown = fixture("행사가 끝나면 가능해요.", listOf(Spec("unknown", linkedMapOf("kind" to "UNKNOWN"))))
        val missing = unknown.parse()
        assertNull(unknown.firstClaims(missing).single().scope)
        assertTrue(missing.directionalApplications.isEmpty())
        assertTrue(unknown.available(missing).isEmpty())
        val inferred =
            fixture(
                "2027-01-10 늦은 오후부터 가능할 듯해요.",
                listOf(
                    Spec(
                        "context-clock",
                        bounds("2027-01-10", lower = "16:00", lowerQuote = "늦은 오후"),
                        interpretation = IrInterpretation.INFERRED,
                    ),
                ),
            )
        val estimated = inferred.parse()
        assertEquals(
            IrInterpretation.INFERRED,
            estimated.directionalApplications
                .single()
                .declaration.interpretation,
        )
        assertEquals(IrTemporalResolution.NON_PRECISE, inferred.firstClaims(estimated).single().temporalResolution)
        assertFalse(
            inferred.input.inputs
                .first()
                .rawText
                .contains("16"),
        )
        assertEquals(listOf(window("2027-01-10T16:00", "2027-01-11T00:00")), inferred.available(estimated))
        val contextual =
            fixture(
                "2027-01-08 18시 반쯤부터 21시쯤 가능해요.",
                listOf(
                    Spec(
                        "raw",
                        rawScope("2027-01-08T18:30", "2027-01-08T21:00"),
                        interpretation = IrInterpretation.INFERRED,
                    ),
                ),
            )
        val raw = contextual.parse()
        assertEquals(IrInterpretation.INFERRED, contextual.firstClaims(raw).single().interpretation)
        assertEquals(IrTemporalResolution.NON_PRECISE, contextual.firstClaims(raw).single().temporalResolution)
        assertEquals(
            ApprovedTimeTermDerivation.MODEL_CONTEXT,
            raw.base.contextApplications
                .single()
                .derivation,
        )
        assertEquals(listOf(window("2027-01-08T18:30", "2027-01-08T21:00")), contextual.available(raw))
    }

    @Test
    fun `TA06 missing meaning evidence invalid components hybrids and unsupported bounds never authorize availability`() {
        val faults =
            listOf(
                "empty-meaning",
                "bundle-overflow",
                "revision",
                "source",
                "scope-hybrid",
                "term-hybrid",
                "cohort-hybrid",
                "external-term",
                "external-clock",
                "date-proof",
                "extra",
                "two-nonprecise",
                "reversed",
                "no-bounds",
            )
        for (fault in faults) {
            val fixture = if (fault in setOf("two-nonprecise", "reversed", "no-bounds")) twoBounds() else preferences()
            val condition = fixture.conditions().first()
            val atom = fields(condition["temporal"])
            when (fault) {
                "empty-meaning" -> condition["evidence"] = emptyList<Any>()
                "bundle-overflow" -> {
                    val source =
                        fixture.input.inputs
                            .first()
                            .rawText
                    condition["evidence"] = (1..32).map { locator(source.takeLast(it)) }
                }
                "revision" -> fields(atom["term_evidence"])["revision"] = 99
                "source" -> fields(atom["term_evidence"])["source_id"] = "question:unavailable"
                "scope-hybrid" -> condition["scope"] = null
                "term-hybrid" -> condition["approved_term"] = null
                "cohort-hybrid" -> fixture.conditions().last().remove("temporal")
                "external-term" -> fixture.first()["approved_terms"] = listOf(mapOf("condition_id" to condition["id"]))
                "external-clock" -> fixture.first()["directional_times"] = listOf(DateScopeInlineFixture.side("AFTER"))
                "date-proof" -> atom["date_evidence"] = emptyList<Any>()
                "extra" -> atom["input_ref"] = F.ir.second.toString()
                "two-nonprecise" -> condition["temporal_resolution"] = "NON_PRECISE"
                "reversed" -> {
                    val lower = atom["lower"]
                    atom["lower"] = atom["upper"]
                    atom["upper"] = lower
                }
                else -> {
                    atom["lower"] = null
                    atom["upper"] = null
                }
            }
            val wire = F.json(fixture.document)
            val result = runCatching { fixture.parse() }
            if (result.isSuccess) {
                assertTrue(
                    fixture.available(result.getOrThrow()).isEmpty(),
                    "Invalid atom must not authorize a default or partial repair: $fault",
                )
                assertNull(fixture.firstProgram(result.getOrThrow()).program)
                assertNotNull(
                    result
                        .getOrThrow()
                        .base.base.declaration.programs
                        .single { it.submissionVersionId == F.ir.second }
                        .program,
                )
            }
            assertEquals(wire, F.json(fixture.document))
        }
    }

    @Test
    fun `TA07 new atoms do not assemble old evidence or replace an old explicit ANY_OF with refinement`() {
        val old =
            fixture(
                "2027-01-08은 가능하고 16:00 이후여야 해요.",
                listOf(Spec("day", whole("2027-01-08")), Spec("bound", bounds("2027-01-08", lower = "16:00"))),
            )
        @Suppress("UNCHECKED_CAST")
        (old.document["results"] as List<MutableMap<String, Any?>>).forEach { result ->
            @Suppress("UNCHECKED_CAST")
            (result["conditions"] as List<MutableMap<String, Any?>>).forEach { condition ->
                val atom = fields(condition.remove("temporal"))
                condition["scope"] =
                    when (atom["kind"]) {
                        "WHOLE_DAY" -> linkedMapOf("dimension" to "TIME", "start" to atom["local_date"], "end" to null)
                        "RAW_SCOPE" -> atom["scope"]
                        else -> null
                    }
            }
        }
        old.first()["directional_times"] = listOf(DateScopeInlineFixture.side("AFTER"))
        val raw = F.json(old.document)
        val envelope = old.parse()
        assertTrue(
            old
                .firstProgram(envelope)
                .program!!
                .nodes
                .any { it.operator == ScheduleDslOperator.ANY_OF },
        )
        assertEquals(listOf(window("2027-01-08T00:00", "2027-01-09T00:00")), old.available(envelope))
        assertEquals(raw, F.json(old.document))
        val legacy = preferences()
        @Suppress("UNCHECKED_CAST")
        (legacy.document["results"] as List<MutableMap<String, Any?>>).forEach { result ->
            @Suppress("UNCHECKED_CAST")
            val claims = result["conditions"] as List<MutableMap<String, Any?>>
            val refs = mutableListOf<Map<String, Any?>>()
            claims.forEach { claim ->
                val atom = fields(claim.remove("temporal"))
                claim["scope"] = if (atom["kind"] == "RAW_SCOPE") atom["scope"] else null
                if (atom["kind"] == "APPROVED_TERM") {
                    refs += atom.filterKeys { it !in setOf("kind", "model_scope") } + ("condition_id" to claim["id"])
                }
            }
            result["approved_terms"] = refs
        }
        val rejected = legacy.parse()
        assertNull(legacy.firstProgram(rejected).program, "Legacy disjoint evidence is not widened into a new declared bundle")
        assertTrue(legacy.available(rejected).isEmpty())
        assertNotNull(
            rejected.base.base.declaration.programs
                .single { it.submissionVersionId == F.ir.second }
                .program,
        )
    }

    @Test
    fun `TA08 closed temporal union preserves complete vocabulary native context modes and legacy C4 bytes`() {
        val capsule = N.capsule()
        val c4 = ScheduleDslContextModeProviderContract(mapper)
        val before = c4.requestBytes(capsule, MeetingMode.EITHER)
        val beforeSchema = mapper.writeValueAsBytes(c4.responseSchema())
        val previous = mapper.readTree(before)
        val request = mapper.readTree(contract.requestBytes(capsule, MeetingMode.EITHER))
        assertEquals("c5-owned-proof-choice-2", request.path("producer_schema_revision").asString())
        assertEquals(previous.path("inputs"), request.path("inputs"))
        assertEquals(previous.path("context"), request.path("context"))
        assertEquals("schedule-dsl-response-5", request.path("response_contract_version").asString())
        assertEquals("schedule-ir-product-6", request.path("semantic_version").asString())
        val schema = contract.responseSchema()
        val result = properties(item(properties(schema).getValue("results")))
        val conditions = variants(item(result.getValue("conditions")), schema)
        conditions.forEach { condition ->
            assertTrue("temporal" in strings(condition["required"]))
            assertFalse("scope" in properties(condition))
            assertFalse("approved_term" in properties(condition))
        }
        val arms = conditions.flatMap { variants(properties(it).getValue("temporal"), schema) }
        val expected =
            mapOf(
                "WHOLE_DAY" to setOf("kind", "local_date", "date_evidence"),
                "APPROVED_TERM" to setOf("kind", "term", "local_date", "term_evidence", "date_evidence", "model_scope"),
                "BOUNDS" to setOf("kind", "local_date", "date_evidence", "lower", "upper"),
                "UNKNOWN" to setOf("kind"),
                "RAW_SCOPE" to setOf("kind", "scope"),
            )
        assertEquals(5, arms.size)
        expected.forEach { (kind, keys) ->
            val arm = arms.single { kind in strings(map(properties(it)["kind"])["enum"]) || map(properties(it)["kind"])["const"] == kind }
            assertEquals(keys, properties(arm).keys)
        }
        val term = arms.single { "term_evidence" in properties(it) }
        val modelVariants = variants(properties(term).getValue("model_scope"), schema)
        assertTrue(modelVariants.any { it["type"] == "null" })
        val modelScope = modelVariants.single { "dimension" in properties(it) }
        assertEquals(setOf("dimension", "start", "end"), properties(modelScope).keys)
        assertEquals(setOf("TIME"), strings(map(properties(modelScope)["dimension"])["enum"]))
        assertEquals(false, modelScope["additionalProperties"])
        assertEquals(properties(modelScope).keys, strings(modelScope["required"]))
        for (key in listOf("start", "end")) {
            val endpoint = map(properties(modelScope)[key])
            assertEquals("string", endpoint["type"], "A model scope endpoint is a nonnull timestamp")
            val pattern = endpoint["pattern"] as? String
            assertTrue(
                endpoint["format"] == "date-time" ||
                    (
                        pattern != null &&
                            Regex(pattern).containsMatchIn("2027-01-08T18:30") &&
                            !Regex(pattern).containsMatchIn("2027-01-08")
                    ),
            )
        }
        val evidence = properties(term).getValue("term_evidence")
        assertEquals("string", map(evidence)["type"])
        conditions.forEach { condition ->
            val bank = variants(item(properties(condition).getValue("proofs")), schema)
            val termProof = bank.single { "enum" in map(properties(it)["quote"]) }
            assertEquals(setOf("id", "source_id", "revision", "quote"), properties(termProof).keys)
            assertEquals(
                setOf("오전", "오후", "새벽", "아침", "점심", "낮", "한낮", "저녁", "밤", "심야", "한밤중", "하루종일"),
                strings(map(properties(termProof)["quote"])["enum"]),
            )
        }
        assertEquals(0, map(result["approved_terms"])["maxItems"])
        assertEquals(0, map(result["directional_times"])["maxItems"])
        assertTrue("mode_restrictions" in result)
        objects(schema).filter { "properties" in it }.forEach {
            assertEquals(false, it["additionalProperties"])
            assertEquals(properties(it).keys, strings(it["required"]))
        }
        assertContentEquals(before, c4.requestBytes(capsule, MeetingMode.EITHER))
        assertContentEquals(beforeSchema, mapper.writeValueAsBytes(c4.responseSchema()))
    }

    @Test
    fun `TA10 meal literals keep precise bounds while contextual term atoms retain inferred provenance`() {
        val literal =
            fixture(
                "2027-01-08 점심은 12:00부터 13:00까지 가능해요.",
                listOf(
                    Spec(
                        "literal-meal",
                        bounds("2027-01-08", "12:00", "13:00"),
                        interpretation = IrInterpretation.EXPLICIT,
                        resolution = IrTemporalResolution.PRECISE_INTERVAL,
                    ),
                ),
            )
        val literalWire = F.json(literal.document)
        val exact = literal.parse()
        val literalClaim = literal.firstClaims(exact).single()
        assertEquals(IrEffect.AVAILABLE, literalClaim.effect)
        assertEquals(IrInterpretation.EXPLICIT, literalClaim.interpretation)
        assertEquals(IrTemporalResolution.PRECISE_INTERVAL, literalClaim.temporalResolution)
        assertEquals(F.scope("2027-01-08T12:00", "2027-01-08T13:00"), literalClaim.scope)
        assertEquals(listOf(window("2027-01-08T12:00", "2027-01-08T13:00")), literal.available(exact))
        assertTrue(
            exact.base.base.termApplications
                .isEmpty(),
            "A meal word does not widen explicit numeric endpoints",
        )
        assertTrue(exact.directionalApplications.isEmpty())
        assertEquals(exact, ScheduleDslStructuralValidator().validateRestored(exact, F.snapshot(literal.input)))
        assertEquals(literalWire, F.json(literal.document))

        val contextualAtom =
            term("EVENING", "2027-01-08").apply {
                this["model_scope"] = rawScope("2027-01-08T18:30", "2027-01-08T21:00").getValue("scope")
            }
        val contextual =
            fixture(
                "2027-01-08 저녁은 18:30쯤부터 21:00쯤까지 가능할 듯해요.",
                listOf(
                    Spec(
                        "contextual-meal",
                        contextualAtom,
                        interpretation = IrInterpretation.INFERRED,
                        resolution = IrTemporalResolution.NON_PRECISE,
                    ),
                ),
            )
        val contextualWire = F.json(contextual.document)
        val estimated = contextual.parse()
        val contextualClaim = contextual.firstClaims(estimated).single()
        assertEquals(IrEffect.AVAILABLE, contextualClaim.effect)
        assertEquals(IrInterpretation.INFERRED, contextualClaim.interpretation)
        assertEquals(IrTemporalResolution.NON_PRECISE, contextualClaim.temporalResolution)
        assertEquals(F.scope("2027-01-08T18:30", "2027-01-08T21:00"), contextualClaim.scope)
        val application =
            estimated.base.base.termApplications
                .single()
        assertEquals(ApprovedTimeTermDerivation.MODEL_CONTEXT, application.derivation)
        assertEquals(contextualClaim.scope, application.declaredScope)
        assertEquals(contextualClaim.scope, application.resolvedScope)
        assertEquals(listOf(window("2027-01-08T18:30", "2027-01-08T21:00")), contextual.available(estimated))
        assertEquals(estimated, ScheduleDslStructuralValidator().validateRestored(estimated, F.snapshot(contextual.input)))
        assertEquals(contextualWire, F.json(contextual.document))
    }

    @Test
    fun `TA11 grounded uncertainty extents clip only their possible restriction while UNKNOWN retains global influence`() {
        // These sources explicitly confine uncertainty. A date alone or unknown travel/spillover does not justify a finite extent.
        val base = "2027-01-08 14:00부터 18:00까지 가능하다."
        val monday = "2027-01-11 하루에만 추가 제약이 있을 수 있고 그 불확실성은 해당 날짜 밖에 영향을 주지 않는다."

        data class Case(
            val id: String,
            val source: String?,
            val start: String?,
            val end: String?,
            val precise: List<Pair<String, String>>,
        )

        val basePair = listOf("2027-01-08T14:00" to "2027-01-08T18:00")
        val cases =
            listOf(
                Case("BASE", null, null, null, basePair),
                Case("UNKNOWN_OUT", monday, null, null, emptyList()),
                Case("SCOPED_OUT", monday, "2027-01-11T00:00", "2027-01-12T00:00", basePair),
                Case(
                    "SCOPED_IN_DISJOINT",
                    "2027-01-09 하루에만 추가 제약이 있을 수 있고 그 불확실성은 해당 날짜 밖에 영향을 주지 않는다.",
                    "2027-01-09T00:00",
                    "2027-01-10T00:00",
                    basePair,
                ),
                Case(
                    "SCOPED_IN_OVERLAP",
                    "2027-01-08 15:00부터 16:00 사이에만 추가 제약이 있을 수 있고 그 밖에는 영향을 주지 않는다.",
                    "2027-01-08T15:00",
                    "2027-01-08T16:00",
                    listOf("2027-01-08T14:00" to "2027-01-08T15:00", "2027-01-08T16:00" to "2027-01-08T18:00"),
                ),
            )
        val observations = mutableListOf<Map<String, Any?>>()
        for (case in cases) {
            val text = listOfNotNull(base, case.source).joinToString(" ")
            val specs =
                listOf(
                    Spec(
                        "base",
                        rawScope("2027-01-08T14:00", "2027-01-08T18:00"),
                        resolution = IrTemporalResolution.PRECISE_INTERVAL,
                        meaning = base,
                    ),
                ) +
                    case.source
                        ?.let { source ->
                            listOf(
                                Spec(
                                    "uncertain",
                                    if (case.start == null) mapOf("kind" to "UNKNOWN") else rawScope(case.start, requireNotNull(case.end)),
                                    effect = IrEffect.UNRESOLVED,
                                    interpretation = IrInterpretation.UNRESOLVED,
                                    resolution = IrTemporalResolution.NON_PRECISE,
                                    meaning = source,
                                ),
                            )
                        }.orEmpty()
            val sample =
                fixture(text, specs) { input ->
                    F.program(
                        listOf(F.ref(input, "base-ref", "base")) +
                            if (case.source == null) emptyList() else listOf(F.ref(input, "uncertain-ref", "uncertain")),
                        "base-ref",
                        block = if (case.source == null) null else "uncertain-ref",
                    )
                }
            val raw = F.json(sample.document)
            val snapshot = F.snapshot(sample.input)
            assertEquals("2027-01-04", snapshot.context.searchStart.toString())
            assertEquals("2027-01-11", snapshot.context.searchEnd.toString())
            assertEquals(text, snapshot.sourceText(F.ir.first, "original"))
            val sourceBinding =
                snapshot.context.inputs
                    .single { it.submissionVersionId == F.ir.first }
                    .sources
                    .single()
            assertEquals(IrEvidenceBindings.sha256(text), sourceBinding.contentSha256)
            val parsed = sample.parse()
            val claims = sample.firstClaims(parsed)
            assertEquals(specs.map { it.id }.toSet(), claims.map { it.id }.toSet())
            assertTrue(sample.firstProgram(parsed).problems.isEmpty())
            assertNotNull(sample.firstProgram(parsed).program)
            assertEquals("base-ref", sample.firstProgram(parsed).program!!.availabilityRoot)
            assertEquals(if (case.source == null) null else "uncertain-ref", sample.firstProgram(parsed).program!!.exclusionRoot)
            val originalBase = claims.single { it.id == "base" }
            assertEquals(IrEffect.AVAILABLE, originalBase.effect)
            assertEquals(IrInterpretation.EXPLICIT, originalBase.interpretation)
            assertEquals(IrTemporalResolution.PRECISE_INTERVAL, originalBase.temporalResolution)
            assertEquals(F.scope("2027-01-08T14:00", "2027-01-08T18:00"), originalBase.scope)
            case.source?.let { source ->
                val unknown = claims.single { it.id == "uncertain" }
                assertEquals(IrEffect.UNRESOLVED, unknown.effect)
                assertEquals(IrInterpretation.UNRESOLVED, unknown.interpretation)
                assertEquals(IrTemporalResolution.NON_PRECISE, unknown.temporalResolution)
                assertEquals(case.start?.let { F.scope(it, requireNotNull(case.end)) }, unknown.scope)
                assertEquals("ASSERTED", unknown.assertion.name)
                val proof = unknown.evidence.single()
                assertEquals("original", proof.sourceId)
                assertEquals(3, proof.revision)
                assertEquals(IrEvidenceBindings.sha256(source), proof.contentSha256)
                assertEquals(source.codePointCount(0, source.length), proof.endCodePoint - proof.startCodePoint)
            }
            val calculated = ScheduleDslStructuralCalculator.calculate(parsed, snapshot, sample.input.policy, MeetingMode.REMOTE)
            val prepared = calculated.preparedByMode.getValue(MeetingMode.REMOTE)
            val first = prepared.participants.single { it.participantId.value == F.ir.firstParticipant }
            val peer = prepared.participants.single { it.participantId.value == F.ir.secondParticipant }
            val evidence = prepared.evidence.getValue(first.participantId)
            val baseWindow = listOf(window("2027-01-08T14:00", "2027-01-08T18:00"))
            assertEquals(case.precise.map { window(it.first, it.second) }, first.availableTimes, case.id)
            assertEquals(baseWindow, evidence.possibleWindows, case.id)
            assertEquals(baseWindow, peer.availableTimes, "The independent peer is unchanged")
            assertEquals(parsed, ScheduleDslStructuralValidator().validateRestored(parsed, snapshot))
            assertEquals(raw, F.json(sample.document))
            observations +=
                mapOf(
                    "case" to case.id,
                    "raw_sha256" to IrEvidenceBindings.sha256(text),
                    "source_sha256" to sourceBinding.contentSha256,
                    "declarations" to claims,
                    "precise_windows" to first.availableTimes,
                    "possible_windows" to evidence.possibleWindows,
                    "global_unresolved_time_observed_not_ideal_policy" to evidence.unresolvedTime,
                    "condition_dispositions" to evidence.conditions,
                    "node_dispositions" to evidence.nodes,
                    "status_observed_not_ideal_policy" to calculated.status,
                    "precision_outcome" to calculated.precisionOutcome,
                    "candidates" to calculated.candidates,
                    "recommendations" to calculated.recommendations,
                )
        }
        System.getProperty("meetme.free.unknown-extent-output")?.let { output ->
            val bytes =
                mapper.writeValueAsBytes(
                    mapOf(
                        "scope" to "SYNTHETIC_EXTENT_MATH_NOT_MODEL_SELECTION_OR_GLOBAL_STATUS_POLICY",
                        "semantic_guard" to "Date or event alone and unknown travel or spillover do not justify finite confinement.",
                        "cases" to observations,
                    ),
                )
            Files
                .newByteChannel(
                    Path.of(output),
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                ).use { channel ->
                    val buffer = java.nio.ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                }
        }
    }

    private data class Spec(
        val id: String,
        val temporal: Map<String, Any?>,
        val effect: IrEffect = IrEffect.AVAILABLE,
        val interpretation: IrInterpretation = IrInterpretation.EXPLICIT,
        val resolution: IrTemporalResolution = IrTemporalResolution.NON_PRECISE,
        val meaning: String? = null,
        val supersedes: List<String> = emptyList(),
        val alternative: String? = null,
    )

    private fun preferences() =
        fixture(
            "2027-01-08과 2027-01-10의 점심과 저녁만 좋아요.",
            listOf(
                Spec("fri-lunch", term("LUNCH", "2027-01-08"), IrEffect.PREFERRED, meaning = "좋아요."),
                Spec("fri-evening", term("EVENING", "2027-01-08"), IrEffect.PREFERRED, meaning = "좋아요."),
                Spec("sun-lunch", term("LUNCH", "2027-01-10"), IrEffect.PREFERRED, meaning = "좋아요."),
                Spec("sun-evening", term("EVENING", "2027-01-10"), IrEffect.PREFERRED, meaning = "좋아요."),
            ),
        )

    private fun twoBounds() =
        fixture(
            "2027-01-08 16:00부터 19:00까지 가능해요.",
            listOf(Spec("finite", bounds("2027-01-08", "16:00", "19:00"), resolution = IrTemporalResolution.PRECISE_INTERVAL)),
        )

    private fun fixture(
        text: String,
        specs: List<Spec>,
        graph: ((ScheduleDslOfflineInput) -> ScheduleDslProgram<ProposedIrEvidence>)? = null,
    ): DateScopeInlineFixture.Fixture {
        val input = F.input(text).copy(policy = N.policy, meetingMode = MeetingMode.REMOTE)
        val claims =
            specs.map {
                F.claim(input, it.id, it.effect).copy(
                    interpretation = it.interpretation,
                    temporalResolution = it.resolution,
                    supersedes = it.supersedes,
                    alternativeGroup = it.alternative?.let { "date-choice" },
                    alternative = it.alternative,
                )
            }
        val proposed = F.proposal(input, claims, graph?.invoke(input) ?: independent(input, specs), emptyList())
        val document = fields(locators(F.document(proposed)))
        val fixture = DateScopeInlineFixture.Fixture(input, document)
        document["semantic_version"] = "schedule-ir-product-6"
        @Suppress("UNCHECKED_CAST")
        (document["results"] as List<MutableMap<String, Any?>>).forEach { result ->
            result["mode_restrictions"] = emptyList<Any>()
            result["directional_times"] = emptyList<Any>()
            @Suppress("UNCHECKED_CAST")
            (result["conditions"] as List<MutableMap<String, Any?>>).forEach { condition ->
                condition["temporal"] = linkedMapOf("kind" to "RAW_SCOPE", "scope" to condition.remove("scope"))
            }
        }
        fixture.conditions().zip(specs).forEach { (condition, spec) ->
            condition["temporal"] = spec.temporal
            condition["evidence"] = listOf(locator(spec.meaning ?: text))
        }
        return fixture
    }

    private fun independent(
        input: ScheduleDslOfflineInput,
        specs: List<Spec>,
    ): ScheduleDslProgram<ProposedIrEvidence> {
        val nodes = specs.map { F.ref(input, "${it.id}-ref", it.id) }.toMutableList()

        fun root(effect: IrEffect): String? {
            val ids = specs.filter { it.effect == effect }.map { "${it.id}-ref" }
            if (ids.isEmpty()) return null
            if (ids.size == 1) return ids.single()
            val id = effect.name.lowercase() + "-root"
            nodes += F.node(input, id, ScheduleDslOperator.ANY_OF, ids)
            return id
        }
        val allow = root(IrEffect.AVAILABLE)
        val block = root(IrEffect.UNAVAILABLE)
        val prefer = root(IrEffect.PREFERRED)
        return F.program(nodes, allow, block, prefer)
    }

    private fun whole(date: String) =
        linkedMapOf<String, Any?>(
            "kind" to "WHOLE_DAY",
            "local_date" to date,
            "date_evidence" to listOf(locator(date)),
        )

    private fun term(
        term: String,
        date: String,
    ) = linkedMapOf<String, Any?>(
        "kind" to "APPROVED_TERM",
        "term" to term,
        "local_date" to date,
        "term_evidence" to locator(if (term == "LUNCH") "점심" else "저녁"),
        "date_evidence" to listOf(locator(date)),
        "model_scope" to null,
    )

    private fun bounds(
        date: String,
        lower: String? = null,
        upper: String? = null,
        lowerQuote: String? = lower,
        upperQuote: String? = upper,
    ) = linkedMapOf<String, Any?>(
        "kind" to "BOUNDS",
        "local_date" to date,
        "date_evidence" to listOf(locator(date)),
        "lower" to lower?.let { linkedMapOf("clock" to it, "clock_evidence" to listOf(locator(requireNotNull(lowerQuote)))) },
        "upper" to upper?.let { linkedMapOf("clock" to it, "clock_evidence" to listOf(locator(requireNotNull(upperQuote)))) },
    )

    private fun rawScope(
        start: String,
        end: String,
    ) = linkedMapOf<String, Any?>(
        "kind" to "RAW_SCOPE",
        "scope" to linkedMapOf("dimension" to "TIME", "start" to start, "end" to end),
    )

    private fun locator(quote: String) = linkedMapOf<String, Any?>("source_id" to "original", "revision" to 3, "quote" to quote)

    @Suppress("UNCHECKED_CAST")
    private fun fields(value: Any?) = value as MutableMap<String, Any?>

    private fun map(value: Any?) = (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

    private fun properties(value: Any?) = map(map(value)["properties"])

    private fun item(value: Any?) = map(value)["items"]

    private fun strings(value: Any?) = (value as? List<*>)?.map { it.toString() }?.toSet().orEmpty()

    private fun variants(
        value: Any?,
        root: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val schema = map(value)
        val reference = schema["\$ref"] as? String
        if (reference != null) return variants(map(root["\$defs"])[reference.substringAfterLast('/')], root)
        val alternatives = schema["anyOf"] ?: schema["oneOf"]
        return if (alternatives is List<*>) alternatives.flatMap { variants(it, root) } else listOf(schema)
    }

    private fun objects(value: Any?): List<Map<String, Any?>> =
        when (value) {
            is Map<*, *> -> listOf(map(value)) + value.values.flatMap(::objects)
            is List<*> -> value.flatMap(::objects)
            else -> emptyList()
        }

    private fun locators(value: Any?): Any? =
        when (value) {
            is Map<*, *> ->
                if ("start_code_point" in value) {
                    value.entries
                        .filter { it.key in setOf("source_id", "revision", "quote") }
                        .associate { it.key.toString() to it.value }
                        .toMutableMap()
                } else {
                    value.entries.associate { it.key.toString() to locators(it.value) }.toMutableMap()
                }
            is List<*> -> value.map(::locators).toMutableList()
            else -> value
        }

    private fun mealWindows() =
        listOf(
            window("2027-01-08T11:00", "2027-01-08T15:00"),
            window("2027-01-08T17:00", "2027-01-08T22:00"),
            window("2027-01-10T11:00", "2027-01-10T15:00"),
            window("2027-01-10T17:00", "2027-01-10T22:00"),
        )

    private fun window(
        start: String,
        end: String,
    ) = DateScopeInlineFixture.window(start, end)
}
