package com.meetme.server.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

class LayerDependencyTest {
    companion object {
        private val productionClasses by lazy {
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.meetme.server")
        }
    }

    @Test
    fun `domain is independent of outer layers and infrastructure`() {
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "..application..",
                "..adapter..",
                "com.meetme.server.config..",
                "org.springframework..",
                "org.komapper..",
                "jakarta..",
                "com.google.genai..",
                "io.lettuce..",
                "java.net.http..",
                "java.sql..",
            ).check(productionClasses)
    }

    @Test
    fun `application uses ports instead of concrete adapters`() {
        noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.meetme.server..adapter..")
            .check(productionClasses)
    }
}
