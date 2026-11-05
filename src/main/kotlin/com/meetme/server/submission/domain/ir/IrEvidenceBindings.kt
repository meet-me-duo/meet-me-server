package com.meetme.server.submission.domain.ir

import com.meetme.server.submission.domain.Submission
import java.security.MessageDigest
import java.util.HexFormat

/** Shared by the frozen-input application boundary and the database reconciliation boundary. */
object IrEvidenceBindings {
    fun create(submission: Submission): Pair<IrInputBinding, Map<String, String>> {
        val version = submission.latest
        val raw = requireNotNull(version.rawText) { "IR requires a natural-language source" }
        require(version.revision > 0 && raw.isNotBlank() && raw.codePointCount(0, raw.length) <= 500)
        require(!raw.contains('\u0000') && raw.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == raw)
        val evidence = version.evidence
        evidence?.validateAgainst(raw)
        require(evidence == null || evidence.referenceAt <= version.createdAt) { "Original reference follows acceptance" }
        require(evidence?.supplement == null || evidence.supplement.answeredAt <= version.createdAt) { "Answer follows acceptance" }
        val sources = mutableListOf<IrSourceBinding>()
        val texts = linkedMapOf("original" to raw)
        sources +=
            IrSourceBinding(
                "original",
                IrSourceKind.ORIGINAL,
                evidence?.originalRevision ?: version.revision,
                raw.codePointCount(0, raw.length),
                sha256(raw),
                evidence?.referenceAt ?: version.createdAt,
            )
        evidence?.supplement?.let { answer ->
            val id = "supplement:${answer.questionId}"
            texts[id] = answer.answerText
            sources +=
                IrSourceBinding(
                    id,
                    IrSourceKind.SUPPLEMENT,
                    answer.sourceRevision,
                    answer.answerText.codePointCount(0, answer.answerText.length),
                    sha256(answer.answerText),
                    answer.referenceAt,
                    answer.questionId,
                    answer.sourceStartCodePoint,
                    answer.sourceStartCodePoint + answer.sourceExcerpt.codePointCount(0, answer.sourceExcerpt.length),
                )
        }
        val content =
            listOf(raw, version.locale.toLanguageTag(), evidence?.canonical(), version.revision.toString())
                .joinToString("") { if (it == null) "-1:" else "${it.toByteArray(Charsets.UTF_8).size}:$it" }
        return IrInputBinding(
            version.id.value,
            submission.participantId.value,
            version.revision,
            evidence?.originalRevision,
            sha256(content),
            sources.toList(),
        ) to texts.toMap()
    }

    fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
}
