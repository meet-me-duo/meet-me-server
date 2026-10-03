package com.meetme.server.submission.adapter.input.web

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

// Opt in only after GREEN; default test runs do not create or overwrite the requested artifact.
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MEETME_ISSUE83_EXPORT_OPENAPI", matches = "true")
class Issue83OpenApiExportIntegrationTest {
    @Autowired
    private lateinit var mvc: MockMvc

    @Test
    fun `export actual generated OpenAPI JSON after Issue 83 GREEN`() {
        val response =
            mvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.components.schemas.SaveSubmissionRequest.required[?(@ == 'raw_text')]").isNotEmpty)
                .andExpect(jsonPath("$.components.schemas.SaveSubmissionRequest.properties.manual_available_times.deprecated").value(true))
                .andExpect(jsonPath("$.components.schemas.SaveSubmissionRequest.properties.manual_available_times.maxItems").value(0))
                .andReturn()
                .response
        Files.writeString(
            Path.of("C:/Users/jinhy/AppData/Local/Temp/meet-me-server-issue-83-openapi.json"),
            String(response.contentAsByteArray, StandardCharsets.UTF_8),
            StandardCharsets.UTF_8,
        )
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
