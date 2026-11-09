package com.meetme.server.support

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflinePipeline
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.submission.domain.dsl.ApprovedTimeTermApplication
import com.meetme.server.submission.domain.dsl.ProposedApprovedTimeTermRef
import com.meetme.server.submission.domain.dsl.ProposedParticipantScheduleProgram
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsProposal
import com.meetme.server.submission.domain.dsl.ScheduleDslNode
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslProgram
import com.meetme.server.submission.domain.dsl.ScheduleDslProposal
import com.meetme.server.submission.domain.dsl.ScheduleDslValidator
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import com.meetme.server.submission.domain.ir.ProposedIrV2Condition
import com.meetme.server.submission.domain.ir.ProposedIrV2Result
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_V2_VERSION
import com.meetme.server.submission.domain.ir.ScheduleIrV2Proposal
import com.meetme.server.submission.domain.ir.ScheduleIrV2Validator
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTerm
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalDateTime

/** Manual synthetic semantics; not a natural-language/model-accuracy oracle. */
object ApprovedHalfDayTestFixture {
    val q = ScheduleDslQuestionContextFixture
    val ir = q.ir
    val policy = q.wire.f.policy
    val day: LocalDate = LocalDate.of(2027, 1, 8)
    const val FIRST = "🙂 2027-01-08 오후 가능."
    const val SECOND = "2027-01-08 14:00부터 18:00까지 가능."
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    fun input(text: String = FIRST): ScheduleDslOfflineInput =
        q.input1().let { old ->
            old.copy(
                inputs =
                    old.inputs.mapIndexed { index, item ->
                        item.copy(rawText = if (index == 0) text else SECOND, evidence = InputEvidence(3, ir.reference))
                    },
            )
        }

    fun snapshot(input: ScheduleDslOfflineInput = input()) = ScheduleDslOfflinePipeline.snapshot(input)

    fun capsule(input: ScheduleDslOfflineInput = input()) = ScheduleDslProviderInputCapsule.create(snapshot(input), q.native(input))

    fun evidence(
        input: ScheduleDslOfflineInput,
        quote: String,
        index: Int = 0,
    ): ProposedIrEvidence {
        val text = input.inputs[index].rawText
        val utf16 = text.indexOf(quote)
        require(utf16 >= 0)
        val start = text.codePointCount(0, utf16)
        return ProposedIrEvidence("original", 3, start, start + quote.codePointCount(0, quote.length), quote)
    }

    fun claim(
        input: ScheduleDslOfflineInput = input(),
        id: String = "half",
        effect: IrEffect = IrEffect.AVAILABLE,
        scope: IrScope? = null,
        index: Int = 0,
    ) = ProposedIrV2Condition(
        id,
        effect,
        IrInterpretation.EXPLICIT,
        if (scope?.start == null) IrTemporalResolution.NON_PRECISE else IrTemporalResolution.PRECISE_INTERVAL,
        scope,
        listOf(evidence(input, input.inputs[index].rawText, index)),
    )

    fun scope(
        start: String,
        end: String,
    ) = IrScope(IrDimension.TIME, LocalDateTime.parse(start), LocalDateTime.parse(end))

    fun node(
        input: ScheduleDslOfflineInput,
        id: String,
        op: ScheduleDslOperator,
        children: List<String> = emptyList(),
        claim: String? = null,
        index: Int = 0,
    ) = ScheduleDslNode(
        id,
        op,
        claim,
        children,
        null,
        emptyList(),
        IrInterpretation.EXPLICIT,
        IrAssertion.ASSERTED,
        listOf(evidence(input, input.inputs[index].rawText, index)),
    )

    fun ref(
        input: ScheduleDslOfflineInput,
        id: String,
        claim: String,
        index: Int = 0,
    ) = node(input, id, ScheduleDslOperator.CLAIM_REF, claim = claim, index = index)

    fun program(
        nodes: List<ScheduleDslNode<ProposedIrEvidence>>,
        allow: String?,
        block: String? = null,
        prefer: String? = null,
    ) = ScheduleDslProgram(nodes, allow, block, prefer, emptyList())

    fun term(
        input: ScheduleDslOfflineInput = input(),
        id: String = "half",
        term: ApprovedKoreanTimeTerm = ApprovedKoreanTimeTerm.PM,
        date: LocalDate = day,
    ) = ProposedApprovedTimeTermRef(
        ir.first,
        id,
        term,
        date,
        evidence(input, if (term == ApprovedKoreanTimeTerm.AM) "오전" else "오후"),
        listOf(evidence(input, date.toString())),
    )

    fun proposal(
        input: ScheduleDslOfflineInput = input(),
        claims: List<ProposedIrV2Condition> = listOf(claim(input)),
        program: ScheduleDslProgram<ProposedIrEvidence> = this.program(listOf(ref(input, "root", "half")), "root"),
        terms: List<ProposedApprovedTimeTermRef> = listOf(term(input)),
    ): ScheduleDslApprovedTermsProposal {
        val peer = claim(input, "peer", scope = scope("2027-01-08T14:00", "2027-01-08T18:00"), index = 1)
        val base =
            ScheduleDslProposal(
                SCHEDULE_DSL_SEMANTIC_VERSION,
                SCHEDULE_AST_VERSION,
                ScheduleIrV2Proposal(
                    SCHEDULE_IR_V2_VERSION,
                    listOf(
                        ProposedIrV2Result(ir.first, claims),
                        ProposedIrV2Result(ir.second, listOf(peer)),
                    ),
                ),
                listOf(
                    ProposedParticipantScheduleProgram(ir.first, program),
                    ProposedParticipantScheduleProgram(ir.second, this.program(listOf(ref(input, "peer-ref", "peer", 1)), "peer-ref")),
                ),
            )
        return ScheduleDslApprovedTermsProposal("schedule-ir-product-4", SCHEDULE_AST_VERSION, "ko-time-terms-1", base, terms)
    }

    /** Independent expected wrapper for repository RED; calls no new term/parser behavior. */
    fun expectedEnvelope(): ScheduleDslApprovedTermsEnvelope {
        val input = input()
        val proposed = proposal(input)
        val snapshot = snapshot(input)
        val declaration = ScheduleDslValidator().validate(proposed.base, snapshot)
        val expectedScope = scope("2027-01-08T12:00", "2027-01-09T00:00")
        val normalized =
            proposed.base.copy(
                claims =
                    proposed.base.claims.copy(
                        results =
                            proposed.base.claims.results.map { result ->
                                if (result.submissionVersionId != ir.first) {
                                    result
                                } else {
                                    result.copy(
                                        conditions =
                                            result.conditions.map {
                                                it.copy(temporalResolution = IrTemporalResolution.PRECISE_INTERVAL, scope = expectedScope)
                                            },
                                    )
                                }
                            },
                    ),
            )
        val projection = ScheduleDslValidator().validate(normalized, snapshot)

        fun verified(span: ProposedIrEvidence): IrEvidenceSpan {
            val proof =
                normalized.claims.copy(
                    results =
                        normalized.claims.results.map { result ->
                            if (result.submissionVersionId != ir.first) {
                                result
                            } else {
                                result.copy(
                                    conditions =
                                        result.conditions.map {
                                            it.copy(evidence = listOf(span))
                                        },
                                )
                            }
                        },
                )
            return ScheduleIrV2Validator()
                .validate(proof, snapshot)
                .results
                .first()
                .conditions
                .single()
                .evidence
                .single()
        }
        val term = proposed.termRefs.single()
        val application =
            ApprovedTimeTermApplication(
                ir.first,
                "half",
                ApprovedKoreanTimeTerm.PM,
                day,
                verified(term.termEvidence),
                term.dateEvidence.map(::verified),
                null,
                expectedScope,
            )
        return ScheduleDslApprovedTermsEnvelope(
            "schedule-ir-product-4",
            SCHEDULE_AST_VERSION,
            "ko-time-terms-1",
            declaration,
            projection,
            listOf(application),
        )
    }

    fun document(value: ScheduleDslApprovedTermsProposal = proposal()): MutableMap<String, Any?> =
        linkedMapOf(
            "semantic_version" to value.semanticVersion,
            "ast_version" to value.astVersion,
            "vocabulary_version" to value.vocabularyVersion,
            "results" to
                value.base.claims.results
                    .map { result ->
                        linkedMapOf<String, Any?>(
                            "input_ref" to result.submissionVersionId.toString(),
                            "conditions" to result.conditions.map(ir::wire).toMutableList(),
                            "program" to
                                q.wire.program(
                                    requireNotNull(
                                        value.base.programs
                                            .single {
                                                it.submissionVersionId == result.submissionVersionId
                                            }.program,
                                    ),
                                ),
                            "approved_terms" to
                                value.termRefs
                                    .filter { it.submissionVersionId == result.submissionVersionId }
                                    .map { term ->
                                        linkedMapOf<String, Any?>(
                                            "condition_id" to term.conditionId,
                                            "term" to term.term.name,
                                            "local_date" to term.localDate.toString(),
                                            "term_evidence" to evidenceMap(term.termEvidence),
                                            "date_evidence" to term.dateEvidence.map(::evidenceMap),
                                        )
                                    }.toMutableList(),
                        )
                    }.toMutableList(),
        )

    fun evidenceMap(value: ProposedIrEvidence): MutableMap<String, Any?> =
        linkedMapOf(
            "source_id" to value.sourceId,
            "revision" to value.revision,
            "start_code_point" to value.startCodePoint,
            "end_code_point" to value.endCodePoint,
            "quote" to value.quote,
        )

    fun json(value: Any = document()): String = mapper.writeValueAsString(value)

    @Suppress("UNCHECKED_CAST")
    fun firstResult(value: MutableMap<String, Any?>) = (value["results"] as MutableList<MutableMap<String, Any?>>).first()

    @Suppress("UNCHECKED_CAST")
    fun firstTerm(value: MutableMap<String, Any?>) = (firstResult(value)["approved_terms"] as MutableList<MutableMap<String, Any?>>).first()

    fun seed(
        jdbc: JdbcTemplate,
        mapper: ObjectMapper,
        input: ScheduleDslOfflineInput = input(),
    ) {
        FinalAnalysisRouteTestFixture.seed(jdbc, mapper)
        input.inputs.forEach { item ->
            jdbc.update(
                "UPDATE submission_versions SET raw_text=?,input_evidence=CAST(? AS jsonb) WHERE id=?",
                item.rawText,
                mapper.writeValueAsString(item.evidence),
                item.submissionVersionId,
            )
        }
    }
}
