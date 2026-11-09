package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper

/** Identical provider wire functions for the real adapter and the DB-free pilot; no network here. */
object ScheduleDslGeminiWire {
    const val PINNED_MODEL = "gemini-3.8-flash"
    const val MAX_OUTPUT_TOKENS = 32768

    fun requestContextBytes(
        mapper: ObjectMapper,
        capsule: ScheduleDslProviderInputCapsule,
        model: String,
    ): ByteArray {
        require(model == PINNED_MODEL) { "Unsupported DSL model" }
        val prompt = ScheduleDslContextProviderContract(mapper).requestBytes(capsule)
        return mapper.writeValueAsBytes(
            linkedMapOf(
                "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt.toString(Charsets.UTF_8))))),
                "generationConfig" to
                    linkedMapOf(
                        "responseMimeType" to "application/json",
                        "responseJsonSchema" to ScheduleDslProviderContract(mapper).responseSchema(),
                        "candidateCount" to 1,
                        "maxOutputTokens" to MAX_OUTPUT_TOKENS,
                        "thinkingConfig" to mapOf("thinkingLevel" to "LOW"),
                    ),
            ),
        )
    }

    fun requestBytes(
        mapper: ObjectMapper,
        snapshot: ScheduleEvidenceSnapshot,
        model: String,
    ): ByteArray {
        require(model == PINNED_MODEL) { "Unsupported DSL model" }
        val contract = ScheduleDslProviderContract(mapper)
        val prompt = contract.requestBytes(snapshot, SCHEDULE_DSL_RESPONSE_CONTRACT, SCHEDULE_DSL_SEMANTIC_VERSION, SCHEDULE_AST_VERSION)
        return mapper.writeValueAsBytes(
            linkedMapOf(
                "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt.toString(Charsets.UTF_8))))),
                "generationConfig" to
                    linkedMapOf(
                        "responseMimeType" to "application/json",
                        "responseJsonSchema" to contract.responseSchema(),
                        "candidateCount" to 1,
                        "maxOutputTokens" to MAX_OUTPUT_TOKENS,
                        "thinkingConfig" to mapOf("thinkingLevel" to "LOW"),
                    ),
            ),
        )
    }

    fun response(
        mapper: ObjectMapper,
        responseBytes: ByteArray,
    ): ScheduleDslProviderResponse =
        try {
            require(responseBytes.size <= 1_048_576)
            val root =
                mapper
                    .readerFor(Map::class.java)
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue<Map<*, *>>(responseBytes)
            val candidates = root["candidates"] as? List<*> ?: invalid()
            require(candidates.size == 1)
            val candidate = candidates.single() as? Map<*, *> ?: invalid()
            require(candidate["finishReason"] == "STOP")
            val content = candidate["content"] as? Map<*, *> ?: invalid()
            val parts = content["parts"] as? List<*> ?: invalid()
            require(parts.isNotEmpty())
            val text =
                parts.joinToString("") { raw ->
                    val part = raw as? Map<*, *> ?: invalid()
                    require(part.keys == setOf("text") || part.keys == setOf("text", "thoughtSignature"))
                    part["text"] as? String ?: invalid()
                }
            require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= 262_144)
            val usage = root["usageMetadata"] as? Map<*, *>

            fun count(name: String): Long? =
                usage?.get(name)?.let {
                    require(it is Int || it is Long)
                    (it as Number).toLong().also { value -> require(value >= 0) }
                }
            ScheduleDslProviderResponse(
                text,
                ParserUsage(count("promptTokenCount"), count("candidatesTokenCount"), responseBytes.size),
                AnalysisProvider.GEMINI,
                PINNED_MODEL,
            )
        } catch (_: RuntimeException) {
            invalid()
        }

    private fun invalid(): Nothing = throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
}
