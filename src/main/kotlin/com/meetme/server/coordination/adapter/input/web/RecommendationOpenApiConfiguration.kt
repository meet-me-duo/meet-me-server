package com.meetme.server.coordination.adapter.input.web

import io.swagger.v3.oas.models.SpecVersion
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

@Configuration
class RecommendationOpenApiConfiguration {
    @Bean
    fun recommendationPageSizeSchema(): OpenApiCustomizer =
        OpenApiCustomizer { document ->
            for ((owner, property, reference) in listOf(
                Triple("RecommendationVariantResponse", "place", "CandidatePlaceResponse"),
                Triple("ConfirmedResultResponse", "selection", "RecommendationSelectionResponse"),
            )) {
                val properties =
                    document.components
                        ?.schemas
                        ?.get(owner)
                        ?.properties ?: continue
                val original = properties[property] ?: continue
                properties[property] =
                    Schema<Any>(SpecVersion.V31).apply {
                        description = original.description
                        anyOf =
                            listOf(
                                Schema<Any>(SpecVersion.V31).apply { `$ref` = "#/components/schemas/$reference" },
                                Schema<Any>(SpecVersion.V31).apply { types = linkedSetOf("null") },
                            )
                    }
            }
            document.paths["/api/rooms/{inviteCode}/recommendations/alternatives"]
                ?.get
                ?.parameters
                ?.firstOrNull { it.name == "limit" }
                ?.schema =
                IntegerSchema().apply {
                    specVersion = SpecVersion.V31
                    types = linkedSetOf("integer")
                    minimum = BigDecimal.ONE
                    maximum = BigDecimal.valueOf(100)
                    setDefault(20)
                }
        }
}
