package com.meetme.server.submission.adapter.input.web

import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SubmissionOpenApiConfiguration {
    @Bean
    fun emptyManualAvailabilitySchema(): OpenApiCustomizer =
        OpenApiCustomizer { document ->
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
