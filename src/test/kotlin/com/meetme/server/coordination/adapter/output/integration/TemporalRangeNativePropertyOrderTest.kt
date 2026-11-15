package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.meetingroom.domain.MeetingMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.meetme.server.support.TemporalRangeAdmissionTestFixture as F

/** Presentation only: all goldens were measured from frozen9b8a before the candidate implementation. */
class TemporalRangeNativePropertyOrderTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val conditionOrder =
        listOf("id", "effect", "interpretation", "temporal", "temporal_resolution", "evidence", "assertion", "supersedes")

    @ParameterizedTest
    @EnumSource(AnalysisProvider::class)
    fun `NPO01 every native six condition branch chooses temporal immediately before precision with other fields stable`(
        provider: AnalysisProvider,
    ) {
        val schema = schema(sixWire(provider), provider)
        val branches =
            schema
                .path("properties")
                .path("results")
                .path("items")
                .path("properties")
                .path("conditions")
                .path("items")
                .path("anyOf")
        assertEquals(10, branches.size())
        for ((index, branch) in branches.withIndex()) {
            val order = branch.path("properties").properties().map { it.key }
            assertEquals(conditionOrder, order, "$provider branch $index")
            assertEquals(
                conditionOrder.toSet(),
                branch
                    .path("required")
                    .toList()
                    .map { it.asText() }
                    .toSet(),
            )
        }
    }

    @ParameterizedTest
    @EnumSource(AnalysisProvider::class)
    fun `NPO02 canonical native six schema preserves every frozen constraint while ignoring only property and required order`(
        provider: AnalysisProvider,
    ) {
        val actual = schema(sixWire(provider), provider)
        assertEquals(
            "ea84b1a3e0e2dea9f8d5e7cee32125c4364980cdaaa83f8404ddc8d82ee37dee",
            sha(mapper.writeValueAsBytes(canonical(actual))),
        )
        // Concrete precision checks make the canonical golden's meaning visible to reviewers.
        val branches =
            actual
                .path("properties")
                .path("results")
                .path("items")
                .path("properties")
                .path("conditions")
                .path("items")
                .path("anyOf")
        val weekly =
            branches.single { branch ->
                branch.path("properties").path("temporal").path("anyOf").any { atom ->
                    atom
                        .path("properties")
                        .path("kind")
                        .path("enum")
                        .any { it.asText() == "WEEKLY" }
                }
            }
        assertEquals(
            listOf("NON_PRECISE"),
            weekly
                .path("properties")
                .path("temporal_resolution")
                .path("enum")
                .toList()
                .map { it.asText() },
        )
        val unknown =
            branches.single { branch ->
                branch.path("properties").path("temporal").path("anyOf").any { atom ->
                    atom
                        .path("properties")
                        .path("kind")
                        .path("enum")
                        .any { it.asText() == "UNKNOWN" }
                }
            }
        assertEquals(
            listOf("NON_PRECISE"),
            unknown
                .path("properties")
                .path("temporal_resolution")
                .path("enum")
                .toList()
                .map { it.asText() },
        )
        assertTrue(
            unknown
                .path("properties")
                .path("interpretation")
                .path("enum")
                .any { it.asText() == "INFERRED" },
        )
    }

    @Test
    fun `NPO03 the neutral schema native input and instructions remain byte identical to the frozen candidate baseline`() {
        val f = F()
        val neutral = TemporalRangeEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER)
        assertEquals(
            "eb1e47d617d9165b28057d46bbd1b38f18ff3ab5dda9dce7503f640de4afe0ca",
            sha(mapper.writeValueAsBytes(mapper.readTree(neutral).path("schema"))),
        )
        for (provider in AnalysisProvider.entries) {
            val wire = wire(neutral, provider)
            val instructions =
                if (provider == AnalysisProvider.OPENAI) {
                    wire.path("instructions").asText()
                } else {
                    wire
                        .path("systemInstruction")
                        .path("parts")
                        .path(0)
                        .path("text")
                        .asText()
                }
            val data =
                if (provider == AnalysisProvider.OPENAI) {
                    wire.path("input").asText()
                } else {
                    wire
                        .path("contents")
                        .path(0)
                        .path("parts")
                        .path(0)
                        .path("text")
                        .asText()
                }
            assertEquals("2997979bec0e0ef4ebc24cc978670e4ce42227548fc62c7f88f0b8013b4c8092", sha(instructions.toByteArray(Charsets.UTF_8)))
            assertEquals("b318c8529dcf3153c44d31a437c6fd9fd057a264b362fbd9a74bf98abafa5d36", sha(data.toByteArray(Charsets.UTF_8)))
        }
    }

    @ParameterizedTest
    @EnumSource(AnalysisProvider::class)
    fun `NPO04 old five neutral and each provider native body retain their absolute preimplementation digests`(provider: AnalysisProvider) {
        val f = F()
        val neutral = WeeklyEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER)
        assertEquals("0bcdcb82841cf3da2c40a3f054790bf107879a1e4f345a573f53be9378a85d6f", sha(neutral))
        val expected =
            if (provider == AnalysisProvider.OPENAI) {
                "06bf7a477a94972416a952fe6a269ae1a9f3c0921c7dec48c90abc8e2395afdf"
            } else {
                "9c80b562ebcf08e78a25ffdb12e91879c04faa9ebb46e5faf8fde2092ca86423"
            }
        assertEquals(expected, sha(wireBytes(neutral, provider)))
    }

    /** Canonical JSON ignores object presentation and required ordering; every other array stays ordered. */
    private fun canonical(
        node: JsonNode,
        parentField: String? = null,
    ): JsonNode =
        when {
            node.isObject -> mapper.valueToTree(node.properties().sortedBy { it.key }.associate { it.key to canonical(it.value, it.key) })
            node.isArray -> {
                val entries = if (parentField == "required") node.toList().sortedBy { it.asText() } else node.toList()
                mapper.valueToTree(entries.map { canonical(it) })
            }
            else -> node
        }

    private fun sixWire(provider: AnalysisProvider): JsonNode {
        val f = F()
        return wire(TemporalRangeEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER), provider)
    }

    private fun schema(
        wire: JsonNode,
        provider: AnalysisProvider,
    ): JsonNode =
        if (provider == AnalysisProvider.OPENAI) {
            wire.path("text").path("format").path("schema")
        } else {
            wire.path("generationConfig").path("responseJsonSchema")
        }

    private fun wire(
        neutral: ByteArray,
        provider: AnalysisProvider,
    ): JsonNode = mapper.readTree(wireBytes(neutral, provider))

    private fun wireBytes(
        neutral: ByteArray,
        provider: AnalysisProvider,
    ): ByteArray =
        ServerEvidenceWire.bytes(mapper, neutral, provider, if (provider == AnalysisProvider.OPENAI) "gpt-6-luna" else "gemini-3.8-flash")

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
