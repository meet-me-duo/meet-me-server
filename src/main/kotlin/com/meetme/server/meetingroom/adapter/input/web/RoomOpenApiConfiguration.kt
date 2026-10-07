package com.meetme.server.meetingroom.adapter.input.web

import io.swagger.v3.oas.models.SpecVersion
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RoomOpenApiConfiguration {
    @Bean
    fun nullableRevisionRoundSchema(): OpenApiCustomizer =
        OpenApiCustomizer { document ->
            val properties =
                document.components
                    ?.schemas
                    ?.get("RoomResponse")
                    ?.properties ?: return@OpenApiCustomizer
            val original = properties["revision_round"] ?: return@OpenApiCustomizer
            // A nullable object must use a union; a sibling null type intersects with the referenced object in OpenAPI 3.1.
            properties["revision_round"] =
                Schema<Any>(SpecVersion.V31).apply {
                    description = original.description
                    anyOf =
                        listOf(
                            Schema<Any>(SpecVersion.V31).apply { `$ref` = "#/components/schemas/RevisionRoundResponse" },
                            Schema<Any>(SpecVersion.V31).apply { types = linkedSetOf("null") },
                        )
                }
        }
}
