package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.shared.application.port.output.AiBudgetUnit
import com.meetme.server.shared.application.port.output.AiCompleteBillEvidenceCatalogPort
import com.meetme.server.shared.application.port.output.AiReviewedCompleteBillEvidence
import org.springframework.boot.context.properties.ConfigurationProperties
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

@ConfigurationProperties("meetme.ai.budget.billing")
data class AiCompleteBillEvidenceProperties(
    val path: String = "",
    val sha256: String = "",
)

object ReviewedAiCompleteBillCatalogLoader {
    private const val LIMIT = 65_536
    private val hash = Regex("[0-9a-f]{64}")
    private val amount = Regex("0|[1-9][0-9]{0,37}")
    private val reference = Regex("[A-Za-z0-9._:-]{1,128}")
    private val reader =
        JsonMapper.builder().build().readerFor(JsonNode::class.java).with(
            DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
        )

    /** The separately reviewed expected SHA is authority; this parser does not approve an invoice or infer a bill from usage. */
    fun load(properties: AiCompleteBillEvidenceProperties): AiCompleteBillEvidenceCatalogPort {
        if (properties.path.isEmpty() && properties.sha256.isEmpty()) return AiCompleteBillEvidenceCatalogPort { emptyList() }
        try {
            require(properties.path.isNotBlank() && hash.matches(properties.sha256))
            val path = Path.of(properties.path)
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            val bytes =
                Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    require(channel.size() <= LIMIT)
                    val buffer = ByteBuffer.allocate(LIMIT + 1)
                    while (buffer.hasRemaining() && channel.read(buffer) != -1) { /* bounded immutable evidence snapshot */ }
                    require(buffer.position() <= LIMIT)
                    buffer.array().copyOf(buffer.position())
                }
            require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == properties.sha256)
            val text =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            val root = reader.readValue<JsonNode>(text)
            fields(root, "protocol", "bills")
            require(text(root, "protocol") == "meetme-complete-bills-v1")
            val bills = root.path("bills")
            require(bills.isArray && bills.size() <= 128)
            val seen = hashSetOf<UUID>()
            val entries =
                bills
                    .asSequence()
                    .map { row ->
                        fields(
                            row,
                            "reservationId",
                            "quoteFingerprint",
                            "evidenceReference",
                            "evidenceFingerprint",
                            "amount",
                            "unit",
                            "reviewReference",
                        )
                        val idText = text(row, "reservationId")
                        val id = UUID.fromString(idText)
                        require(id.toString() == idText && seen.add(id))
                        val quote = text(row, "quoteFingerprint").also { require(hash.matches(it)) }
                        val evidence = text(row, "evidenceFingerprint").also { require(hash.matches(it)) }
                        val evidenceReference = text(row, "evidenceReference").also { require(reference.matches(it)) }
                        val review = text(row, "reviewReference").also { require(reference.matches(it)) }
                        val charge = text(row, "amount").also { require(amount.matches(it)) }
                        val unit = row.path("unit")
                        fields(unit, "code", "scale")
                        val code = text(unit, "code")
                        require(
                            code.isNotBlank() &&
                                code.length <= 32 &&
                                code == code.trim() &&
                                code.none { it == '\u0000' || it == '\r' || it == '\n' },
                        )
                        val scale = unit.path("scale")
                        require(scale.isIntegralNumber && scale.canConvertToInt() && scale.intValue() in 0..38)
                        AiReviewedCompleteBillEvidence(
                            id,
                            quote,
                            evidenceReference,
                            evidence,
                            BigInteger(charge),
                            AiBudgetUnit(code, scale.intValue()),
                            review,
                        )
                    }.toList()
            val snapshot = Collections.unmodifiableList(entries)
            return AiCompleteBillEvidenceCatalogPort { snapshot }
        } catch (_: Exception) {
            throw IllegalStateException("Reviewed AI complete-bill evidence is invalid")
        }
    }

    private fun fields(
        node: JsonNode,
        vararg expected: String,
    ) {
        require(node.isObject && node.propertyNames().toSet() == expected.toSet())
    }

    private fun text(
        node: JsonNode,
        name: String,
    ): String = node.path(name).also { require(it.isString) }.stringValue()
}
