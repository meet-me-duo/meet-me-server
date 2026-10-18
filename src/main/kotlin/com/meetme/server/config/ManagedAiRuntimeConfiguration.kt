package com.meetme.server.config

import com.meetme.server.precheck.adapter.output.classification.AmbiguityClassifierRuntimeProperties
import com.meetme.server.precheck.adapter.output.provider.PrecheckDecisionRoutingProperties
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderRuntimeProperties
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.application.port.output.AiBudgetUnit
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
import java.time.Clock
import java.time.Instant
import java.util.Collections

@ConfigurationProperties("meetme.ai.budget")
data class AiRuntimePolicyProperties(
    val policyPath: String = "",
    val policySha256: String = "",
)

data class ApprovedAiOperationPolicy(
    val profileFingerprint: String,
    val evidenceFingerprint: String,
    val maximumAmount: BigInteger,
    val validUntil: Instant,
)

data class ApprovedAiRuntimePolicy(
    val fingerprint: String,
    val unit: AiBudgetUnit,
    val roomCap: BigInteger,
    val minimumConfidence: Double,
    val operations: Map<AiPhysicalOperation, ApprovedAiOperationPolicy>,
)

data class ManagedAiRuntimeSelection(
    val enabled: Boolean,
    val policy: ApprovedAiRuntimePolicy?,
)

object ManagedAiRuntimePolicyLoader {
    private const val PROTOCOL = "meetme-managed-ai-policy-v1"
    private const val LIMIT = 65_536
    private val HASH = Regex("[0-9a-f]{64}")
    private val AMOUNT = Regex("0|[1-9][0-9]{0,37}")
    private val reader =
        JsonMapper.builder().build().readerFor(JsonNode::class.java).with(
            DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
        )

    // Frozen physical profiles include endpoint, method, model and capture limit; they are not a billing proof.
    private val profiles =
        mapOf(
            AiPhysicalOperation.PRECHECK_CLASSIFICATION to "308b2fd0b2d0892f59aa7f953247ea9c855597d0da5a30a6923070596149ec81",
            AiPhysicalOperation.PRECHECK_COUNT to "658a65563b34d43f854cc47dcd93d274d23789745e718b5f7ef49e1b59e8e307",
            AiPhysicalOperation.PRECHECK_GENERATION to "b9ce9802d9dc3f4396f49c796cfe173fda708c6a35ac40c79d5bdc5091419363",
            AiPhysicalOperation.FINAL_PRIMARY to "0a3fc94ba00d368b4e7c9a41d0b231faab5058aa893dc11082ef786fc0a08d04",
            AiPhysicalOperation.FINAL_FALLBACK to "9c5021916c80178f58d235e55bbbad4889875560b710a505fbde5dedd1a824ab",
        )

    fun load(
        properties: AiRuntimePolicyProperties,
        clock: Clock,
    ): ApprovedAiRuntimePolicy? {
        if (properties.policyPath.isEmpty() && properties.policySha256.isEmpty()) return null
        try {
            require(properties.policyPath.isNotBlank() && HASH.matches(properties.policySha256))
            val path = Path.of(properties.policyPath)
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            val bytes =
                Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    require(channel.size() <= LIMIT)
                    val buffer = ByteBuffer.allocate(LIMIT + 1)
                    while (buffer.hasRemaining() && channel.read(buffer) != -1) { /* bounded local artifact */ }
                    require(buffer.position() <= LIMIT)
                    buffer.array().copyOf(buffer.position())
                }
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(fingerprint == properties.policySha256)
            val text =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            val root = reader.readValue<JsonNode>(text)
            require(text(root, "protocol") == PROTOCOL)
            if (text(root, "state") == "UNCONFIGURED") {
                fields(root, "protocol", "state")
                return null
            }
            fields(root, "protocol", "state", "unit", "roomCap", "minimumConfidence", "operations")
            require(text(root, "state") == "CONFIGURED")
            val unitNode = root.path("unit")
            fields(unitNode, "code", "scale")
            val code = text(unitNode, "code")
            require(
                code.isNotBlank() && code.length <= 32 && code == code.trim() && code.none { it == '\u0000' || it == '\r' || it == '\n' },
            )
            val scale = unitNode.path("scale")
            require(scale.isIntegralNumber && scale.canConvertToInt() && scale.intValue() in 0..38)
            val cap = amount(root.path("roomCap"))
            require(cap.signum() > 0)
            val thresholdNode = root.path("minimumConfidence")
            require(thresholdNode.isNumber)
            val threshold = thresholdNode.doubleValue()
            require(threshold.isFinite() && threshold in 0.0..1.0)
            val operations = root.path("operations")
            require(operations.isArray && operations.size() == AiPhysicalOperation.entries.size)
            val policies = linkedMapOf<AiPhysicalOperation, ApprovedAiOperationPolicy>()
            val now = clock.instant()
            operations.forEach { row ->
                fields(row, "operation", "profileFingerprint", "evidenceFingerprint", "maximumAmount", "validUntil")
                val operation = AiPhysicalOperation.valueOf(text(row, "operation"))
                require(operation !in policies)
                val profile = text(row, "profileFingerprint")
                require(profile == profiles.getValue(operation))
                val evidence = text(row, "evidenceFingerprint")
                require(HASH.matches(evidence))
                val expiresText = text(row, "validUntil")
                require(expiresText.endsWith("Z"))
                val expires = Instant.parse(expiresText)
                require(expires > now)
                policies[operation] = ApprovedAiOperationPolicy(profile, evidence, amount(row.path("maximumAmount")), expires)
            }
            require(policies.keys == AiPhysicalOperation.entries.toSet())
            return ApprovedAiRuntimePolicy(
                fingerprint,
                AiBudgetUnit(code, scale.intValue()),
                cap,
                threshold,
                Collections.unmodifiableMap(policies),
            )
        } catch (_: Exception) {
            throw IllegalStateException("Managed AI policy configuration is invalid")
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
    ): String {
        val value = node.path(name)
        require(value.isString)
        return value.stringValue()
    }

    private fun amount(node: JsonNode): BigInteger {
        require(node.isString && AMOUNT.matches(node.stringValue()))
        return BigInteger(node.stringValue())
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
    AiRuntimePolicyProperties::class,
    PrecheckProviderRuntimeProperties::class,
    PrecheckDecisionRoutingProperties::class,
    AmbiguityClassifierRuntimeProperties::class,
    AiDispatchGateProperties::class,
)
class ManagedAiRuntimeConfiguration {
    @Bean
    @ConditionalOnMissingBean(ManagedAiRuntimeSelection::class)
    fun managedAiRuntimeSelection(
        policyProperties: AiRuntimePolicyProperties,
        provider: PrecheckProviderRuntimeProperties,
        routing: PrecheckDecisionRoutingProperties,
        classifier: AmbiguityClassifierRuntimeProperties,
        budget: AiDispatchGateProperties,
        clock: Clock,
    ): ManagedAiRuntimeSelection {
        val flags = listOf(provider.enabled, routing.enabled, classifier.enabled, budget.enabled)
        require(flags.all { it } || flags.none { it }) { "Managed AI runtime configuration is incomplete" }
        if (flags.none { it }) return ManagedAiRuntimeSelection(false, null)
        val policy = requireNotNull(ManagedAiRuntimePolicyLoader.load(policyProperties, clock)) { "Managed AI policy is required" }
        val threshold = classifier.minimumConfidence
        require(threshold != null && threshold.isFinite() && threshold == policy.minimumConfidence) {
            "Managed AI classifier threshold must match its policy"
        }
        return ManagedAiRuntimeSelection(true, policy)
    }
}
