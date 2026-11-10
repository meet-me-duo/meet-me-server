package com.meetme.server.submission.domain.dsl

import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import com.meetme.server.submission.domain.ir.IrSourceKind
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTerm
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTermPolicy
import java.util.Collections
import java.util.UUID

const val SERVER_EVIDENCE_REQUEST_CONTRACT = "schedule-dsl-evidence-request-1"
const val SERVER_EVIDENCE_RESPONSE_CONTRACT = "schedule-dsl-evidence-response-1"

enum class ServerEvidenceKind { CONTEXT, TERM, CLOCK, PLACE }

data class ServerEvidenceEntry(
    val id: String,
    val inputId: UUID,
    val text: String,
    val kind: ServerEvidenceKind,
    val span: IrEvidenceSpan,
) {
    override fun toString(): String = "ServerEvidenceEntry(id=$id, kind=$kind)"
}

/** Literal segmentation and identity only. No availability, polarity, or temporal interpretation. */
class ServerEvidenceCatalog private constructor(
    entries: List<ServerEvidenceEntry>,
    private val boundSnapshot: ScheduleEvidenceSnapshot,
) {
    val entries: List<ServerEvidenceEntry> = Collections.unmodifiableList(entries.toList())
    private val byId = entries.associateBy { it.id }

    fun resolve(
        inputId: UUID,
        evidenceId: String,
    ): LocatedLiteralEvidence {
        val entry = requireNotNull(byId[evidenceId]) { "Unknown server evidence reference" }
        require(entry.inputId == inputId) { "Foreign server evidence owner" }
        return LocatedLiteralEvidence(entry.span, EvidenceLocationDerivation.SERVER_CATALOG_REFERENCE, catalogId = entry.id)
    }

    internal fun requireContext(snapshot: ScheduleEvidenceSnapshot) {
        require(boundSnapshot === snapshot) { "Catalog validation context mismatch" }
    }

    companion object {
        const val VERSION = "server-evidence-catalog-1"
        const val MAX_SOURCE_CODE_POINTS = 8000
        const val MAX_ENTRIES = 4096
        private val lines = Regex("[^\\r\\n]+")
        private val sentences = Regex("[^.!?。！？\\r\\n]+[.!?。！？]?")
        private val clocks =
            Regex(
                "(?<![0-9:])(?:(?:오전|오후|새벽|아침|저녁|밤|낮)\\s*)?(?:(?:[01]?\\d|2[0-3]):[0-5]\\d(?::[0-5]\\d)?(?![0-9:])|(?:[01]?\\d|2[0-3])\\s*시(?:\\s*(?:반|[0-5]?\\d\\s*분))?(?!(?:[0-9시간분초반]|\\s*\\d+\\s*분|\\s*반)))",
            )

        fun create(snapshot: ScheduleEvidenceSnapshot): ServerEvidenceCatalog {
            val context = snapshot.context
            require(
                context.inputs.size in 2..50 &&
                    context.inputs
                        .map { it.submissionVersionId }
                        .distinct()
                        .size == context.inputs.size,
            )
            val ordered =
                context.inputs.sortedBy { it.submissionVersionId }.map {
                    it.copy(
                        sources =
                            it.sources.sortedBy { source ->
                                source.sourceId
                            },
                    )
                }
            val binding = IrEvidenceBindings.sha256(context.copy(inputs = ordered).toString())
            val entries = linkedMapOf<String, ServerEvidenceEntry>()
            ordered.forEach { input ->
                require(
                    input.sources.isNotEmpty() &&
                        input.sources
                            .map { it.sourceId }
                            .distinct()
                            .size == input.sources.size,
                )
                require(input.sources.count { it.kind == IrSourceKind.ORIGINAL && it.sourceId == "original" } == 1)
                input.sources.forEach { source ->
                    require(source.revision > 0)
                    require(
                        if (source.kind == IrSourceKind.ORIGINAL) {
                            source.sourceId == "original" && source.questionId == null
                        } else {
                            source.questionId != null && source.sourceId == "supplement:${source.questionId}"
                        },
                    )
                    val native = requireNotNull(snapshot.sourceText(input.submissionVersionId, source.sourceId)) { "Missing frozen source" }
                    require(
                        '\u0000' !in native && native.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == native,
                    ) { "Invalid source Unicode" }
                    require(
                        native.codePointCount(0, native.length) == source.codePointLength &&
                            source.codePointLength <= MAX_SOURCE_CODE_POINTS,
                    )
                    require(IrEvidenceBindings.sha256(native) == source.contentSha256) { "Frozen source digest mismatch" }

                    fun add(
                        kind: ServerEvidenceKind,
                        start: Int,
                        end: Int,
                    ) {
                        if (start == end || native.substring(start, end).isBlank()) return
                        val text = native.substring(start, end)
                        val span =
                            IrEvidenceSpan(
                                source.sourceId,
                                source.revision,
                                native.codePointCount(0, start),
                                native.codePointCount(0, end),
                                IrEvidenceBindings.sha256(text),
                            )
                        val identity = listOf(VERSION, binding, input.submissionVersionId, kind, span).joinToString("|")
                        val id = "ev:" + IrEvidenceBindings.sha256(identity).take(32)
                        val entry = ServerEvidenceEntry(id, input.submissionVersionId, text, kind, span)
                        require(entries[id] == null || entries[id] == entry) { "Evidence ID collision" }
                        entries[id] = entry
                        require(entries.size <= MAX_ENTRIES) { "Server evidence catalog entry limit" }
                    }
                    add(ServerEvidenceKind.CONTEXT, 0, native.length)
                    lines.findAll(native).forEach { add(ServerEvidenceKind.CONTEXT, it.range.first, it.range.last + 1) }
                    sentences.findAll(native).forEach { match ->
                        val start = match.range.first + match.value.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                        add(ServerEvidenceKind.CONTEXT, start, match.range.last + 1)
                    }

                    fun occurrences(
                        kind: ServerEvidenceKind,
                        literal: String,
                    ) {
                        var at = native.indexOf(literal)
                        while (at >= 0) {
                            add(kind, at, at + literal.length)
                            at = native.indexOf(literal, at + 1)
                        }
                    }
                    ApprovedKoreanTimeTerm.entries.flatMap(ApprovedKoreanTimeTermPolicy::lexemes).distinct().forEach {
                        occurrences(ServerEvidenceKind.TERM, it)
                    }
                    clocks.findAll(native).forEach { add(ServerEvidenceKind.CLOCK, it.range.first, it.range.last + 1) }
                    ApprovedPublicPlaceNames.policy
                        .exactNativeNames()
                        .sorted()
                        .forEach { occurrences(ServerEvidenceKind.PLACE, it) }
                }
            }
            return ServerEvidenceCatalog(entries.values.toList(), snapshot)
        }
    }
}
