package com.meetme.server.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AggregatePackageArchitectureTest {
    private val sourceRoot: Path = Path.of("src/main/kotlin/com/meetme/server")
    private val aggregates = setOf("meetingroom", "participant", "submission", "coordination")

    @Test
    fun `각 Aggregate는 최상위 기능 패키지 안에 헥사고날 계층을 소유한다`() {
        aggregates.forEach { aggregate ->
            val root = sourceRoot.resolve(aggregate)
            assertTrue(Files.isDirectory(root.resolve("domain")), "$aggregate domain package is missing")
            assertTrue(Files.isDirectory(root.resolve("application")), "$aggregate application package is missing")
            assertTrue(Files.isDirectory(root.resolve("adapter")), "$aggregate adapter package is missing")
        }
    }

    @Test
    fun `서버 최상위에는 Aggregate와 공통 지원 패키지만 둔다`() {
        val actual =
            Files.list(sourceRoot).use { paths ->
                paths
                    .filter(Files::isDirectory)
                    .map { it.fileName.toString() }
                    .toList()
                    .toSet()
            }

        assertEquals(aggregates + setOf("shared", "config"), actual)
    }

    @Test
    fun `Aggregate domain은 외부 프레임워크에 의존하지 않는다`() {
        val forbidden = listOf("org.springframework", "org.komapper", "jakarta.", "com.google.genai")
        aggregates.forEach { aggregate ->
            val domain = sourceRoot.resolve(aggregate).resolve("domain")
            if (!Files.exists(domain)) return@forEach
            Files.walk(domain).use { paths ->
                paths
                    .filter { it.toString().endsWith(".kt") }
                    .forEach { source ->
                        val content = Files.readString(source)
                        forbidden.forEach { dependency ->
                            assertTrue(
                                dependency !in content,
                                "${source.invariantSeparatorsPathString} imports forbidden dependency $dependency",
                            )
                        }
                    }
            }
        }
    }

    @Test
    fun `Kotlin 소스 경로와 package 선언은 일치한다`() {
        Files.walk(sourceRoot).use { paths ->
            paths
                .filter { it.toString().endsWith(".kt") }
                .forEach { source ->
                    val packageName =
                        Files
                            .readAllLines(source)
                            .first { it.startsWith("package ") }
                            .removePrefix("package ")
                    val expectedDirectory =
                        sourceRoot.parent.parent.parent
                            .resolve(packageName.replace('.', '/'))
                    assertEquals(
                        expectedDirectory.normalize(),
                        source.parent.normalize(),
                        "${source.invariantSeparatorsPathString} package does not match its directory",
                    )
                }
        }
    }

    @Test
    fun `domain과 application은 adapter에 의존하지 않는다`() {
        aggregates.forEach { aggregate ->
            listOf("domain", "application").forEach { layer ->
                Files.walk(sourceRoot.resolve(aggregate).resolve(layer)).use { paths ->
                    paths
                        .filter { it.toString().endsWith(".kt") }
                        .forEach { source ->
                            assertTrue(
                                ".adapter." !in Files.readString(source),
                                "${source.invariantSeparatorsPathString} depends on an adapter",
                            )
                        }
                }
            }
        }
    }
}
