package com.meetme.server.submission.adapter.input.web

import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SubmissionOpenApiConfiguration {
    @Bean
    fun emptyManualAvailabilitySchema(): OpenApiCustomizer =
        OpenApiCustomizer { document ->
            // JsonNode is shared with string payload fields; publish this strict JSON integer independently.
            document.components?.schemas?.get("AnswerClarificationRequest")?.properties?.set(
                "expected_revision",
                IntegerSchema().apply {
                    format = "int32"
                    description = "질문의 source_revision 정수. 문자열·소수·null은 400, 현재 revision과 다르면 409"
                },
            )
            document.components
                ?.schemas
                ?.get("SaveSubmissionRequest")
                ?.properties
                ?.set(
                    "manual_available_times",
                    ArraySchema().apply {
                        types = linkedSetOf("array", "null")
                        items = Schema<Any>()
                        maxItems = 0
                        deprecated = true
                        description = "호환 필드: 생략·[]·null만 허용. Nonempty 배열은 SUBMISSION_MANUAL_AVAILABILITY_UNSUPPORTED"
                    },
                )
            // Swagger annotation resolution omits a zero array upper bound; the runtime accepts only an empty shim.
            listOf("SaveSubmissionRequest", "SubmissionResponse", "SavedSubmissionResponse").forEach { name ->
                document.components
                    ?.schemas
                    ?.get(name)
                    ?.properties
                    ?.get("manual_available_times")
                    ?.maxItems = 0
            }
        }
}
