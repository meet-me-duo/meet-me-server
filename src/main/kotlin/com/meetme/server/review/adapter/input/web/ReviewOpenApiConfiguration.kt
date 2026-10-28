package com.meetme.server.review.adapter.input.web

import io.swagger.v3.oas.models.media.IntegerSchema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ReviewOpenApiConfiguration {
    @Bean
    fun integerReviewChoices(): OpenApiCustomizer =
        OpenApiCustomizer { document ->
            val submission = document.components?.schemas?.get("SubmitReviewRequest") ?: return@OpenApiCustomizer
            submission.required = listOf("choices")
            // Swagger allowableValues annotations otherwise serialize integer choices as JSON strings.
            submission.properties?.get("choices")?.items =
                IntegerSchema().apply {
                    description = "1 만족은 배타. 2~5 복수 선택"
                    format = "int32"
                    setEnum(listOf(1, 2, 3, 4, 5))
                }
        }
}
