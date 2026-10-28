package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.AiRuntimePolicyProperties
import com.meetme.server.config.ManagedAiRuntimeConfiguration
import com.meetme.server.config.ManagedAiRuntimePolicyLoader
import com.meetme.server.config.ManagedAiRuntimeSelection
import com.meetme.server.shared.adapter.output.budget.AiBudgetAuthorityConfiguration
import com.meetme.server.shared.adapter.output.budget.ConfiguredAiBudgetAuthorityRegistry
import com.meetme.server.shared.application.port.output.AiBudgetAuthorityRegistryPort
import com.meetme.server.shared.application.port.output.AiBudgetBillRequest
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetQuoteRequest
import com.meetme.server.shared.application.port.output.AiBudgetUnit
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Configuration/quote protocol only: arbitrary TEST_SUBUNIT values are not an operating price or threshold. */
class Issue131ManagedRuntimeConfigurationTest {
    @TempDir lateinit var directory: Path

    private val clock = MutableClock(NOW)

    @Test
    fun `MB01 all flags default OFF select disabled and keep production authority denying`() {
        runner().run { context ->
            assertNull(context.startupFailure, "Coherent default OFF must still start")
            val selected = context.getBean(ManagedAiRuntimeSelection::class.java)
            assertFalse(selected.enabled)
            assertNull(selected.policy)
            val authority = context.getBean(AiBudgetAuthorityRegistryPort::class.java)
            assertNull(assertDoesNotThrow { authority.quote(request(AiPhysicalOperation.PRECHECK_CLASSIFICATION, hash("unused"))) })
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] mask={0}")
    @ValueSource(ints = [1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 14])
    fun `MB02 unsupported partial provider and budget flag combinations fail startup`(mask: Int) {
        val file = policyFile()
        runner(file, mask, "0.73").run { context ->
            assertNotNull(context.startupFailure, "Partial ON must not become an unmanaged single-generator runtime")
        }
    }

    @Test
    fun `OA04 immutable financial metadata remains valid without being an activation prerequisite`() {
        val file = policyFile()
        runner(file, 15, "0.73").run { context ->
            assertNull(context.startupFailure, "Accepted provider configuration must bind")
        }
        // Preserve financial artifact validation independently of operational admission.
        val policy = assertNotNull(ManagedAiRuntimePolicyLoader.load(properties(file), clock))
        assertEquals(hash(Files.readString(file)), policy.fingerprint)
        assertEquals(AiBudgetUnit("TEST_SUBUNIT", 0), policy.unit)
        assertEquals(BigInteger("1000"), policy.roomCap)
        assertEquals(0.73, policy.minimumConfidence)
        assertEquals(AiPhysicalOperation.entries.toSet(), policy.operations.keys)
    }

    @Test
    fun `OA04 absent classifier threshold is allowed while an explicitly conflicting threshold remains invalid`() {
        val file = policyFile()
        runner(file, 15).run { context -> assertNull(context.startupFailure) }
        runner(file, 15, "0.74").run { context -> assertNotNull(context.startupFailure) }
    }

    @Test
    fun `MB04 the default UNCONFIGURED artifact supplies no financial policy`() {
        val file = policyFile("""{"protocol":"meetme-managed-ai-policy-v1","state":"UNCONFIGURED"}""")
        assertNull(assertDoesNotThrow { ManagedAiRuntimePolicyLoader.load(properties(file), clock) })
        runner(file, 15, "0.73").run { context -> assertNull(context.startupFailure) }
    }

    enum class InvalidPolicy {
        PROTOCOL,
        UNIT_MISSING,
        THRESHOLD_MISSING,
        THRESHOLD_NULL,
        THRESHOLD_RANGE,
        OPERATION_MISSING,
        OPERATION_DUPLICATED,
        UNKNOWN_OPERATION,
        FOREIGN_PROFILE,
        NEGATIVE_AMOUNT,
        NONSTRING_AMOUNT,
        AMOUNT_OVERFLOW,
        EXPIRED,
        UNKNOWN_FIELD,
        DUPLICATE_THRESHOLD,
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(InvalidPolicy::class)
    fun `MB04 malformed or unvetted artifact cannot configure a managed runtime`(fault: InvalidPolicy) {
        val valid = policyJson()
        val changed =
            when (fault) {
                InvalidPolicy.PROTOCOL -> valid.replace("meetme-managed-ai-policy-v1", "unverified-policy")
                InvalidPolicy.UNIT_MISSING -> valid.replace("\"unit\":{\"code\":\"TEST_SUBUNIT\",\"scale\":0},", "")
                InvalidPolicy.THRESHOLD_MISSING -> valid.replace("\"minimumConfidence\":0.73,", "")
                InvalidPolicy.THRESHOLD_NULL -> valid.replace("\"minimumConfidence\":0.73", "\"minimumConfidence\":null")
                InvalidPolicy.THRESHOLD_RANGE -> valid.replace("\"minimumConfidence\":0.73", "\"minimumConfidence\":1.01")
                InvalidPolicy.OPERATION_MISSING -> policyJson(AiPhysicalOperation.entries.dropLast(1))
                InvalidPolicy.OPERATION_DUPLICATED -> policyJson(AiPhysicalOperation.entries + AiPhysicalOperation.PRECHECK_CLASSIFICATION)
                InvalidPolicy.UNKNOWN_OPERATION -> valid.replace("PRECHECK_COUNT", "UNVERIFIED_OPERATION")
                InvalidPolicy.FOREIGN_PROFILE ->
                    valid.replace(
                        PROFILES.getValue(AiPhysicalOperation.PRECHECK_COUNT),
                        hash("foreign-profile"),
                    )
                InvalidPolicy.NEGATIVE_AMOUNT -> valid.replace("\"maximumAmount\":\"3\"", "\"maximumAmount\":\"-1\"")
                InvalidPolicy.NONSTRING_AMOUNT -> valid.replace("\"maximumAmount\":\"3\"", "\"maximumAmount\":3")
                InvalidPolicy.AMOUNT_OVERFLOW -> valid.replace("\"roomCap\":\"1000\"", "\"roomCap\":\"1${"0".repeat(38)}\"")
                InvalidPolicy.EXPIRED -> valid.replace(EXPIRES.toString(), NOW.minusSeconds(1).toString())
                InvalidPolicy.UNKNOWN_FIELD -> valid.replace("\"state\":\"CONFIGURED\",", "\"state\":\"CONFIGURED\",\"verified\":true,")
                InvalidPolicy.DUPLICATE_THRESHOLD ->
                    valid.replace(
                        "\"minimumConfidence\":0.73",
                        "\"minimumConfidence\":0.73,\"minimumConfidence\":0.9",
                    )
            }
        val file = policyFile(changed)
        assertFails("Invalid artifact must not yield a trusted policy") { ManagedAiRuntimePolicyLoader.load(properties(file), clock) }
        runner(file, 15, "0.73").run { context -> assertNotNull(context.startupFailure) }
    }

    @Test
    fun `MB04 wrong byte hash or symlink cannot substitute the approved immutable artifact`() {
        val file = policyFile()
        assertFails { ManagedAiRuntimePolicyLoader.load(AiRuntimePolicyProperties(file.toString(), hash("other-bytes")), clock) }
        val link = directory.resolve("linked-policy.json")
        Files.createSymbolicLink(link, file)
        assertFails { ManagedAiRuntimePolicyLoader.load(AiRuntimePolicyProperties(link.toString(), hash(Files.readString(file))), clock) }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(AiPhysicalOperation::class)
    fun `MB05 quote binds each fixed profile policy evidence and actual payload hash`(operation: AiPhysicalOperation) {
        val file = policyFile()
        val policy = assertNotNull(assertDoesNotThrow { ManagedAiRuntimePolicyLoader.load(properties(file), clock) })
        val authority = ConfiguredAiBudgetAuthorityRegistry(ManagedAiRuntimeSelection(true, policy), clock)
        val request = request(operation, policy.fingerprint)
        val quote = assertNotNull(assertDoesNotThrow { authority.quote(request) })
        assertEquals(policy.fingerprint, quote.policyFingerprint)
        assertEquals(PROFILES.getValue(operation), quote.profileFingerprint)
        assertEquals(request.call.wholePayloadFingerprint, quote.wholePayloadFingerprint)
        assertEquals(hash("synthetic-evidence:${operation.name}"), quote.evidenceFingerprint)
        assertEquals(BigInteger.valueOf(operation.ordinal.toLong() + 3), quote.maximumAmount)
        assertEquals(AiBudgetUnit("TEST_SUBUNIT", 0), quote.unit)
        assertEquals(EXPIRES, quote.validUntil)
        assertEquals(quote, assertNotNull(assertDoesNotThrow { authority.quote(request) }), "Identical immutable quote lookup is stable")
        val next = request.copy(call = request.call.copy(wholePayloadFingerprint = hash("different-legal-payload")))
        val other = assertNotNull(assertDoesNotThrow { authority.quote(next) })
        assertEquals(next.call.wholePayloadFingerprint, other.wholePayloadFingerprint)
        assertTrue(
            other.quoteFingerprint != quote.quoteFingerprint,
            "Legal dynamic payloads are separately bound, not pre-enumerated or reused",
        )
    }

    @Test
    fun `MB05 foreign policy profile malformed hash and elapsed evidence remain denied`() {
        val file = policyFile()
        val policy = assertNotNull(assertDoesNotThrow { ManagedAiRuntimePolicyLoader.load(properties(file), clock) })
        val authority = ConfiguredAiBudgetAuthorityRegistry(ManagedAiRuntimeSelection(true, policy), clock)
        val original = request(AiPhysicalOperation.PRECHECK_CLASSIFICATION, policy.fingerprint)
        val invalid =
            listOf(
                original.copy(policyFingerprint = hash("foreign-policy")),
                original.copy(call = original.call.copy(profileFingerprint = hash("foreign-profile"))),
                original.copy(call = original.call.copy(wholePayloadFingerprint = "not-a-sha256")),
            )
        invalid.forEach { assertNull(assertDoesNotThrow { authority.quote(it) }) }
        clock.set(EXPIRES)
        assertNull(assertDoesNotThrow { authority.quote(original) }, "Expiry is checked again after startup")
    }

    @Test
    fun `MB05 provider success or caller evidence reference cannot fabricate a complete bill`() {
        val file = policyFile()
        val policy = assertNotNull(assertDoesNotThrow { ManagedAiRuntimePolicyLoader.load(properties(file), clock) })
        val authority = ConfiguredAiBudgetAuthorityRegistry(ManagedAiRuntimeSelection(true, policy), clock)
        assertNull(
            assertDoesNotThrow {
                authority.completeBill(AiBudgetBillRequest(UUID.randomUUID(), hash("quote"), "provider-usage-is-not-invoice"))
            },
        )
    }

    @Test
    fun `MB04 policy byte ceiling rejects even otherwise valid padded JSON`() {
        val file = policyFile(policyJson() + " ".repeat(65_536))
        assertFails { ManagedAiRuntimePolicyLoader.load(properties(file), clock) }
    }

    @Test
    fun `MB05 loaded policy is an immutable snapshot rather than a reread of changed file`() {
        val file = policyFile()
        val originalHash = hash(Files.readString(file))
        val policy = assertNotNull(assertDoesNotThrow { ManagedAiRuntimePolicyLoader.load(properties(file), clock) })
        val authority = ConfiguredAiBudgetAuthorityRegistry(ManagedAiRuntimeSelection(true, policy), clock)
        Files.writeString(file, policyJson().replace("\"maximumAmount\":\"3\"", "\"maximumAmount\":\"30\""))
        val quote =
            assertNotNull(assertDoesNotThrow { authority.quote(request(AiPhysicalOperation.PRECHECK_CLASSIFICATION, originalHash)) })
        assertEquals(BigInteger("3"), quote.maximumAmount)
        assertEquals(originalHash, quote.policyFingerprint)
        assertFails { ManagedAiRuntimePolicyLoader.load(AiRuntimePolicyProperties(file.toString(), originalHash), clock) }
    }

    private fun runner(
        file: Path? = null,
        mask: Int = 0,
        threshold: String? = null,
    ): ApplicationContextRunner {
        val values = FLAGS.mapIndexed { index, key -> "$key=${(mask and (1 shl index)) != 0}" }.toMutableList()
        if (file != null) {
            values += "meetme.ai.budget.policy-path=$file"
            values += "meetme.ai.budget.policy-sha256=${hash(Files.readString(file))}"
        }
        if (threshold != null) values += "meetme.precheck.classifier.minimum-confidence=$threshold"
        return ApplicationContextRunner()
            .withUserConfiguration(ManagedAiRuntimeConfiguration::class.java, AiBudgetAuthorityConfiguration::class.java)
            .withBean(Clock::class.java, Supplier { clock })
            .withPropertyValues(*values.toTypedArray())
    }

    private fun policyFile(json: String = policyJson()): Path =
        Files.writeString(directory.resolve("policy-${UUID.randomUUID()}.json"), json)

    private fun properties(file: Path): AiRuntimePolicyProperties = AiRuntimePolicyProperties(file.toString(), hash(Files.readString(file)))

    private fun policyJson(operations: List<AiPhysicalOperation> = AiPhysicalOperation.entries): String {
        val rows =
            operations.joinToString(",") { operation ->
                val profile = PROFILES.getValue(operation)
                val evidence = hash("synthetic-evidence:${operation.name}")
                """{"operation":"${operation.name}","profileFingerprint":"$profile","evidenceFingerprint":"$evidence",""" +
                    """"maximumAmount":"${operation.ordinal + 3}","validUntil":"$EXPIRES"}"""
            }
        return """{"protocol":"meetme-managed-ai-policy-v1","state":"CONFIGURED",""" +
            """"unit":{"code":"TEST_SUBUNIT","scale":0},"roomCap":"1000",""" +
            """"minimumConfidence":0.73,"operations":[$rows]}"""
    }

    private fun request(
        operation: AiPhysicalOperation,
        policy: String,
    ): AiBudgetQuoteRequest =
        AiBudgetQuoteRequest(
            AiBudgetExecution(
                UUID.randomUUID(),
                if (operation.name.startsWith("FINAL_")) AiBudgetExecutionKind.FINAL_ANALYSIS else AiBudgetExecutionKind.PRECHECK,
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                1,
                hash("synthetic-source"),
                hash("synthetic-context"),
            ),
            AiPhysicalCallSpec(
                operation,
                1,
                hash("synthetic-whole-payload"),
                PROFILES.getValue(operation),
                if (operation.name.startsWith("FINAL_")) UUID.randomUUID() else null,
            ),
            policy,
        )

    private class MutableClock(
        at: Instant,
    ) : Clock() {
        private val value = AtomicReference(at)

        fun set(at: Instant) {
            value.set(at)
        }

        override fun instant(): Instant = value.get()

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    companion object {
        private val NOW = Instant.parse("2026-10-18T00:00:00Z")
        private val EXPIRES = NOW.plusSeconds(60)
        private val FLAGS =
            listOf(
                "meetme.precheck.provider.enabled",
                "meetme.precheck.decision-routing.enabled",
                "meetme.precheck.classifier.enabled",
                "meetme.ai.budget.enabled",
            )

        // Parent-frozen public wire-profile list; not obtained from the loader/registry under test.
        private val PROFILES =
            mapOf(
                AiPhysicalOperation.PRECHECK_CLASSIFICATION to "c8d01d4a04aa077e0488400dc3b4791753b8428db48c6712aa78f903608a4dfc",
                AiPhysicalOperation.PRECHECK_COUNT to "658a65563b34d43f854cc47dcd93d274d23789745e718b5f7ef49e1b59e8e307",
                AiPhysicalOperation.PRECHECK_GENERATION to "86e4593b78ec903e1c9b4bbbffe204d2b8343e1752d2b6353703ce3fb9b65714",
                AiPhysicalOperation.FINAL_PRIMARY to "0a3fc94ba00d368b4e7c9a41d0b231faab5058aa893dc11082ef786fc0a08d04",
                AiPhysicalOperation.FINAL_FALLBACK to "9c5021916c80178f58d235e55bbbad4889875560b710a505fbde5dedd1a824ab",
            )

        private fun hash(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
    }
}
