package com.meetme.server.precheck.adapter.input.web

import io.swagger.v3.oas.models.SpecVersion
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PrecheckOpenApiConfiguration {
    @Bean
    fun nullablePrecheckSnapshots(): OpenApiCustomizer =
        OpenApiCustomizer { document ->
            val schemas = document.components?.schemas ?: return@OpenApiCustomizer
            mapOf(
                "PrecheckResponse" to
                    mapOf(
                        "candidate" to "PrecheckTextResponse",
                        "accepted" to "PrecheckAcceptedResponse",
                        "check" to "PrecheckStateResponse",
                        "operation_outcome" to "PrecheckOutcomeResponse",
                    ),
                "PrecheckStateResponse" to mapOf("question" to "PrecheckQuestionResponse"),
            ).forEach { (name, references) ->
                val properties = schemas[name]?.properties ?: return@forEach
                references.forEach { (field, target) ->
                    val original = properties[field] ?: return@forEach
                    // OpenAPI 3.1 null is a union member, never a sibling constraint on an object reference.
                    properties[field] =
                        Schema<Any>(SpecVersion.V31).apply {
                            description = original.description
                            oneOf =
                                listOf(
                                    Schema<Any>(SpecVersion.V31).apply { `$ref` = "#/components/schemas/$target" },
                                    Schema<Any>(SpecVersion.V31).apply { types = linkedSetOf("null") },
                                )
                        }
                }
            }
        }
}
