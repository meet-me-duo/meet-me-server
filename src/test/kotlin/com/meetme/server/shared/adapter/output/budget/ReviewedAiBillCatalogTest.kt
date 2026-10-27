package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.config.ApprovedAiOperationPolicy
import com.meetme.server.config.ApprovedAiRuntimePolicy
import com.meetme.server.config.ManagedAiRuntimeSelection
import com.meetme.server.shared.application.port.output.AiBudgetBillRequest
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetQuoteRequest
import com.meetme.server.shared.application.port.output.AiBudgetUnit
import com.meetme.server.shared.application.port.output.AiCompleteBillEvidenceCatalogPort
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiReviewedCompleteBillEvidence
import com.meetme.server.shared.application.port.output.AiVerifiedCompleteBill
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR055: all amounts and receipts below are synthetic TEST_SUBUNIT evidence, never actual billing approval. */
class ReviewedAiBillCatalogTest {
    @TempDir
    lateinit var directory: Path

    private val mapper = JsonMapper.builder().build()
    private val now = Instant.parse("2026-10-21T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val unit = AiBudgetUnit("TEST_SUBUNIT", 0)
    private val id = UUID(0, 101)

    @Test
    fun `BC01 absent billing configuration and explicitly reviewed empty catalog supply no receipts`() {
        assertEquals(emptyList(), ReviewedAiCompleteBillCatalogLoader.load(AiCompleteBillEvidenceProperties()).entries())
        assertEquals(emptyList(), load(bytes(emptyList())).entries())
    }

    @Test
    fun `BC02 valid SHA pinned receipt is snapshotted and cannot change after file or exposed list mutation`() {
        val expected = evidence()
        val file = write(bytes(listOf(row(expected))))
        val catalog = ReviewedAiCompleteBillCatalogLoader.load(properties(file))
        assertEquals(listOf(expected), catalog.entries())
        Files.writeString(file, "synthetic-unreviewed-replacement")
        runCatching { (catalog.entries() as MutableList<AiReviewedCompleteBillEvidence>).clear() }
        assertEquals(listOf(expected), catalog.entries(), "The loaded approval must be immutable rather than a live unverified file")
    }

    @Test
    fun `BC03 partial configuration bad hash missing path and mismatched file fail without leaking values`() {
        val file = write(bytes(listOf(row())))
        val configured = properties(file)
        for (invalid in listOf(
            AiCompleteBillEvidenceProperties(path = file.toString()),
            AiCompleteBillEvidenceProperties(sha256 = configured.sha256),
            configured.copy(sha256 = "not-a-fingerprint"),
            configured.copy(sha256 = hash("different-approved-bytes")),
            configured.copy(path = directory.resolve("synthetic-sensitive-missing-file").toString()),
        )) {
            assertSanitized(invalid)
        }
    }

    @Test
    fun `BC04 configured production authority resolves only the exact reviewed complete bill`() {
        val receipt = evidence()
        val authority = registry(listOf(receipt))
        assertEquals(
            AiVerifiedCompleteBill(receipt.quoteFingerprint, receipt.evidenceFingerprint, receipt.amount, receipt.unit),
            authority.completeBill(AiBudgetBillRequest(id, receipt.quoteFingerprint, receipt.evidenceReference)),
            "A real configured registry must resolve the reviewed receipt rather than always returning null",
        )
    }

    @Test
    fun `BC05 disabled missing policy and empty catalogs never infer billing authority`() {
        val receipt = evidence()
        val request = AiBudgetBillRequest(id, receipt.quoteFingerprint, receipt.evidenceReference)
        val catalog = AiCompleteBillEvidenceCatalogPort { listOf(receipt) }
        assertNull(ConfiguredAiBudgetAuthorityRegistry(ManagedAiRuntimeSelection(false, policy()), clock, catalog).completeBill(request))
        assertNull(ConfiguredAiBudgetAuthorityRegistry(ManagedAiRuntimeSelection(true, null), clock, catalog).completeBill(request))
        assertNull(registry(emptyList()).completeBill(request))
    }

    @Test
    fun `BC06 reservation quote and evidence reference must all match independently`() {
        val receipt = evidence()
        val authority = registry(listOf(receipt))
        val exact = AiBudgetBillRequest(id, receipt.quoteFingerprint, receipt.evidenceReference)
        for (invalid in listOf(
            exact.copy(reservationId = UUID(0, 102)),
            exact.copy(quoteFingerprint = hash("other-quote")),
            exact.copy(evidenceReference = "different-review-reference"),
        )) {
            assertNull(authority.completeBill(invalid))
        }
    }

    @Test
    fun `BC07 replacing bill evidence never changes the admission policy or quote fingerprint`() {
        val execution =
            AiBudgetExecution(UUID(0, 1), AiBudgetExecutionKind.PRECHECK, UUID(0, 2), 1, UUID(0, 3), 1, hash("source"), hash("context"))
        val call = AiPhysicalCallSpec(AiPhysicalOperation.PRECHECK_CLASSIFICATION, 1, hash("whole-wire"), hash("profile"))
        val request = AiBudgetQuoteRequest(execution, call, policy().fingerprint)
        val first = assertNotNull(registry(listOf(evidence())).quote(request))
        val second =
            assertNotNull(registry(listOf(evidence().copy(amount = BigInteger.valueOf(9), reviewReference = "review-2"))).quote(request))
        assertEquals(first, second, "Billing artifact updates must not invalidate already configured room policy hashes")
    }

    @Test
    fun `BC08 old quotes may receive complete charge evidence after their dispatch deadline expired`() {
        val receipt = evidence()
        val lateClock = Clock.fixed(now.plusSeconds(86_400), ZoneOffset.UTC)
        val authority =
            ConfiguredAiBudgetAuthorityRegistry(
                ManagedAiRuntimeSelection(true, policy()),
                lateClock,
                AiCompleteBillEvidenceCatalogPort {
                    listOf(receipt)
                },
            )
        assertEquals(
            receipt.amount,
            assertNotNull(authority.completeBill(AiBudgetBillRequest(id, receipt.quoteFingerprint, receipt.evidenceReference))).amount,
        )
    }

    @Test
    fun `BC09 strict JSON rejects duplicate keys trailing data unknown fields and malformed UTF8`() {
        val valid = bytes(listOf(row())).toString(Charsets.UTF_8)
        val invalid =
            listOf(
                valid.replace("\"protocol\":", "\"protocol\":\"meetme-complete-bills-v1\",\"protocol\":"),
                valid.replace("\"amount\":\"7\"", "\"amount\":\"7\",\"amount\":\"8\""),
                "$valid {}",
                valid.dropLast(1) + ",\"unknownRoot\":true}",
                valid.replace("\"amount\":\"7\"", "\"amount\":\"7\",\"unknownRow\":true"),
                valid.replace("\"scale\":0", "\"scale\":0,\"unknownUnit\":true"),
                valid.replace("meetme-complete-bills-v1", "unreviewed-protocol"),
                "{\"bills\":[]}",
                "[]",
            ).map { it.toByteArray() } + listOf(byteArrayOf(0xC3.toByte(), 0x28))
        invalid.forEach { assertSanitized(properties(write(it))) }
    }

    @Test
    fun `BC10 amount unit identity fingerprints and opaque references reject malformed values`() {
        val badRows = mutableListOf<Map<String, Any>>()
        for (value in listOf<Any>(-1, 7, "-1", "+7", "01", "7.0", "1e2", " ", BigInteger.TEN.pow(38).toString())) {
            badRows += row() + ("amount" to value)
        }
        for (field in listOf("quoteFingerprint", "evidenceFingerprint")) {
            for (value in listOf("bad", "A".repeat(64), "0".repeat(63))) badRows += row() + (field to value)
        }
        for (field in listOf("evidenceReference", "reviewReference")) {
            for (value in listOf("", " ", "x".repeat(129), "synthetic-sensitive\nvalue", "a/b", "a\u0000b")) {
                badRows +=
                    row() + (field to value)
            }
        }
        badRows += row() + ("reservationId" to "not-a-reservation")
        for (badUnit in listOf(
            mapOf("code" to "", "scale" to 0),
            mapOf("code" to " TEST_SUBUNIT", "scale" to 0),
            mapOf("code" to "TEST\nSUBUNIT", "scale" to 0),
            mapOf("code" to "T".repeat(33), "scale" to 0),
            mapOf("code" to "TEST_SUBUNIT", "scale" to -1),
            mapOf("code" to "TEST_SUBUNIT", "scale" to 39),
            mapOf("code" to "TEST_SUBUNIT", "scale" to 0.5),
        )) {
            badRows += row() + ("unit" to badUnit)
        }
        row().keys.forEach { missing -> badRows += row().filterKeys { it != missing } }
        badRows.forEach { assertSanitized(properties(write(bytes(listOf(it))))) }
    }

    @Test
    fun `BC11 zero and NUMERIC38 maximum are exact integer evidence rather than floating point estimates`() {
        val maximum = BigInteger.TEN.pow(38) - BigInteger.ONE
        for (amount in listOf(BigInteger.ZERO, maximum)) {
            assertEquals(amount, load(bytes(listOf(row(evidence().copy(amount = amount))))).entries().single().amount)
        }
    }

    @Test
    fun `BC12 duplicate reservation rows and excess rows reject the entire catalog`() {
        assertSanitized(properties(write(bytes(listOf(row(), row())))))
        assertSanitized(properties(write(bytes(listOf(row(), row(evidence().copy(amount = BigInteger.valueOf(8))))))))
        val rows = (1..129).map { row(evidence().copy(reservationId = UUID(0, it.toLong()))) }
        assertSanitized(properties(write(bytes(rows))))
        assertEquals(128, load(bytes(rows.take(128))).entries().size)
    }

    @Test
    fun `BC13 bounded whole file and no symlink rules cannot be bypassed by a matching digest`() {
        assertSanitized(properties(write(ByteArray(65_537) { ' '.code.toByte() })))
        val file = write(bytes(listOf(row())))
        val link = directory.resolve("linked-reviewed-bills.json")
        Files.createSymbolicLink(link, file)
        assertSanitized(properties(file).copy(path = link.toString()))
        assertSanitized(properties(file).copy(path = directory.toString()))
    }

    private fun evidence() =
        AiReviewedCompleteBillEvidence(
            id,
            hash("old-quote"),
            "bill-ref-1",
            hash("reviewed-charge"),
            BigInteger.valueOf(7),
            unit,
            "review-1",
        )

    private fun row(value: AiReviewedCompleteBillEvidence = evidence()): Map<String, Any> =
        linkedMapOf(
            "reservationId" to value.reservationId.toString(),
            "quoteFingerprint" to value.quoteFingerprint,
            "evidenceReference" to value.evidenceReference,
            "evidenceFingerprint" to value.evidenceFingerprint,
            "amount" to value.amount.toString(),
            "unit" to linkedMapOf("code" to value.unit.code, "scale" to value.unit.scale),
            "reviewReference" to value.reviewReference,
        )

    private fun bytes(rows: List<Map<String, Any>>) =
        mapper.writeValueAsBytes(
            linkedMapOf(
                "protocol" to "meetme-complete-bills-v1",
                "bills" to rows,
            ),
        )

    private fun write(bytes: ByteArray): Path = Files.createTempFile(directory, "reviewed-bills-", ".json").also { Files.write(it, bytes) }

    private fun properties(path: Path) = AiCompleteBillEvidenceProperties(path.toString(), hash(Files.readAllBytes(path)))

    private fun load(bytes: ByteArray) = ReviewedAiCompleteBillCatalogLoader.load(properties(write(bytes)))

    private fun assertSanitized(properties: AiCompleteBillEvidenceProperties) {
        val failure = assertFailsWith<IllegalStateException> { ReviewedAiCompleteBillCatalogLoader.load(properties) }
        val exposed = generateSequence<Throwable>(failure) { it.cause }.take(8).joinToString(" ") { it.message.orEmpty() }
        assertTrue(exposed.isNotBlank(), "Configuration errors should have a bounded categorical message")
        assertTrue(
            !exposed.contains(directory.toString()) && !exposed.contains("synthetic-sensitive"),
            "No file path or raw evidence may leak",
        )
    }

    private fun registry(entries: List<AiReviewedCompleteBillEvidence>) =
        ConfiguredAiBudgetAuthorityRegistry(ManagedAiRuntimeSelection(true, policy()), clock, AiCompleteBillEvidenceCatalogPort { entries })

    private fun policy() =
        ApprovedAiRuntimePolicy(
            hash("policy"),
            unit,
            BigInteger.valueOf(100),
            0.5,
            AiPhysicalOperation.entries.associateWith {
                ApprovedAiOperationPolicy(hash("profile"), hash("price-evidence"), BigInteger.valueOf(30), now.plusSeconds(60))
            },
        )

    private fun hash(value: String): String = hash(value.toByteArray())

    private fun hash(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
}
