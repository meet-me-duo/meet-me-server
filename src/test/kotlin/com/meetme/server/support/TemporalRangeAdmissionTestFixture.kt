package com.meetme.server.support

import com.meetme.server.coordination.adapter.output.integration.TemporalRangeEvidenceProviderContract
import com.meetme.server.coordination.adapter.output.integration.WeeklyEvidenceProviderContract
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.InputSupplement
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.submission.domain.dsl.ServerEvidenceKind
import com.meetme.server.submission.domain.dsl.TemporalRangeAdmission
import com.meetme.server.submission.domain.time.APPROVED_KOREAN_TIME_TERM_VERSION
import tools.jackson.databind.json.JsonMapper
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertNotNull
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Entirely invented inputs and manual declarations. No actual participant/model response is an oracle. */
class TemporalRangeAdmissionTestFixture(
    val raw: String = RAW,
    linkedAnswer: Boolean = false,
) {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    val input =
        H.input(raw).let { old ->
            old.copy(
                policy = N.policy,
                meetingMode = MeetingMode.EITHER,
                searchStart = LocalDate.parse("2036-04-14"),
                searchEnd = LocalDate.parse("2036-04-21"),
                inputs =
                    old.inputs.mapIndexed { index, item ->
                        when {
                            index == 1 -> item.copy(rawText = "2036-04-16 하루 전체 가능.")
                            linkedAnswer ->
                                item.copy(
                                    evidence =
                                        assertNotNull(item.evidence).copy(
                                            supplement =
                                                InputSupplement(
                                                    UUID(0, 7631),
                                                    3,
                                                    raw.take(10),
                                                    0,
                                                    "합성 종료 시각 확인",
                                                    ANSWER,
                                                    null,
                                                    H.ir.reference.plusSeconds(60),
                                                    H.ir.reference.plusSeconds(60),
                                                ),
                                        ),
                                )
                            else -> item
                        }
                    },
            )
        }
    val snapshot = H.snapshot(input)
    val capsule = H.capsule(input)
    val catalog = ServerEvidenceCatalog.create(snapshot)
    val first = H.ir.first
    val second = H.ir.second

    fun selection(
        text: String,
        kind: ServerEvidenceKind = ServerEvidenceKind.CONTEXT,
        owner: UUID = first,
    ): Int =
        catalog.entries
            .indexOfFirst {
                it.inputId == owner && it.kind == kind && it.text == text
            }.also { require(it >= 0) { "Missing synthetic native selection: $text" } }

    fun clock(text: String) = selection(text, ServerEvidenceKind.CLOCK)

    fun whole(owner: UUID = first) = selection(assertNotNull(snapshot.sourceText(owner, "original")), owner = owner)

    fun time(
        lower: String? = "16:20",
        upper: String? = "20:40",
        clocks: List<Int> = listOf(clock("오후 4시 20분"), clock("8시 40분")),
        kind: String = "BOUNDS",
    ): MutableMap<String, Any?> =
        linkedMapOf(
            "kind" to kind,
            "lower" to lower,
            "upper" to upper,
            "end_day_offset" to 0,
            "clock_evidence" to clocks,
            "term" to null,
            "term_evidence" to null,
        )

    fun filter(): MutableMap<String, Any?> =
        linkedMapOf(
            "months" to emptyList<String>(),
            "from_inclusive" to null,
            "until_exclusive" to null,
            "excluded_dates" to emptyList<String>(),
            "evidence" to emptyList<Int>(),
        )

    fun weekly(time: MutableMap<String, Any?> = time()): MutableMap<String, Any?> =
        linkedMapOf(
            "kind" to "WEEKLY",
            "weekdays" to DayOfWeek.entries.map { it.name },
            "weekday_evidence" to listOf(whole()),
            "anchor_filter" to filter(),
            "time" to time,
        )

    fun dated(
        lower: String? = "16:20",
        upper: String? = null,
        lowerProof: Int = clock("오후 4시 20분"),
        upperProof: Int = clock("8시 40분"),
    ): MutableMap<String, Any?> =
        linkedMapOf(
            "kind" to "BOUNDS",
            "local_date" to "2036-04-14",
            "date_evidence" to listOf(whole()),
            "lower" to lower?.let { mapOf("clock" to it, "clock_evidence" to listOf(lowerProof)) },
            "upper" to upper?.let { mapOf("clock" to it, "clock_evidence" to listOf(upperProof)) },
        )

    fun oldDocument(atom: MutableMap<String, Any?> = weekly()): MutableMap<String, Any?> {
        val peer = whole(second)
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
                    result(first, "range", atom, whole()),
                    result(
                        second,
                        "peer",
                        linkedMapOf("kind" to "WHOLE_DAY", "local_date" to "2036-04-16", "date_evidence" to listOf(peer)),
                        peer,
                    ),
                ),
        )
    }

    fun newDocument(atom: MutableMap<String, Any?> = weekly()) =
        oldDocument(atom).also {
            it["temporal_range_admission_version"] =
                TemporalRangeAdmission.VERSION
        }

    fun oldParse(document: Map<String, Any?> = oldDocument()) =
        WeeklyEvidenceProviderContract(mapper).parseProviderResponse(
            mapper.writeValueAsString(document),
            snapshot,
            SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
            SCHEDULE_AST_VERSION,
            APPROVED_KOREAN_TIME_TERM_VERSION,
        )

    fun newParse(document: Map<String, Any?> = newDocument()) =
        TemporalRangeEvidenceProviderContract(mapper).parseProviderResponse(
            mapper.writeValueAsString(document),
            snapshot,
            SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
            SCHEDULE_AST_VERSION,
            APPROVED_KOREAN_TIME_TERM_VERSION,
        )

    @Suppress("UNCHECKED_CAST")
    fun condition(document: MutableMap<String, Any?>): MutableMap<String, Any?> =
        ((document["results"] as List<MutableMap<String, Any?>>).first()["conditions"] as List<MutableMap<String, Any?>>).single()

    @Suppress("UNCHECKED_CAST")
    fun fields(value: Any?) = value as MutableMap<String, Any?>

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

    companion object {
        const val RAW = "오후 4시 20분부터 8시 40분까지 가능합니다."
        const val ANSWER = "끝나는 시각을 오후 7시 50분으로 정정합니다."
    }
}
