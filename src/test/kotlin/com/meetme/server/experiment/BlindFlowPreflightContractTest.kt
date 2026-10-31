package com.meetme.server.experiment

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class BlindFlowPreflightContractTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `Path iterable overload reproduces original seven file set rejection`() {
        val root = temporary.resolve("scenarios")
        val six =
            listOf("pilot", "sealed")
                .flatMap { room ->
                    listOf("input.json", "answers.json", "gold.json").map { root.resolve(room).resolve(it) }
                }.toSet()
        val legacy = six + root.resolve("manifest.json")
        val corrected = six + setOf(root.resolve("manifest.json"))
        assertEquals(7, corrected.size)
        assertNotEquals(corrected, legacy)
    }

    @Test
    fun `full synthetic frozen manifest validates without claim output provider or scenario parsing`() {
        val fixture = SyntheticPreflightFixture(temporary, pilotOnly = false)
        val before = fixture.treeSnapshot()
        val manifest = validate(fixture)
        assertEquals(7, manifest.path("scenarioFiles").size())
        assertEquals(20, manifest.path("config").path("maximumPhysicalRequests").asInt())
        assertFalse(Files.exists(fixture.output))
        assertEquals(before, fixture.treeSnapshot(), "Read-only validation cannot consume or alter any claim, output or source")
    }

    @Test
    fun `source coverage cannot be bypassed by recomputing source and manifest hashes`() {
        val fixture = SyntheticPreflightFixture(temporary, pilotOnly = false)
        fixture.sourceFiles.removeAt(0)
        fixture.save()
        assertRejected(fixture, "SOURCE_PATHS")
    }

    @Test
    fun `opaque synthetic scenario tampering remains rejected without parsing its content`() {
        val fixture = SyntheticPreflightFixture(temporary, pilotOnly = false)
        Files.writeString(fixture.scenarios.resolve("sealed/gold.json"), "different independent opaque bytes")
        assertRejected(fixture, "SCENARIO_FILE_HASHES")
    }

    @Test
    fun `git revision approval and duplicate scenario paths remain mandatory`() {
        val fixture = SyntheticPreflightFixture(temporary, pilotOnly = false)
        val originalRevision = fixture.manifest.getValue("baseRevision")
        fixture.manifest["baseRevision"] = "0".repeat(40)
        fixture.save()
        assertRejected(fixture, "BASE_REVISION")
        fixture.manifest["baseRevision"] = originalRevision
        fixture.manifest["approved"] = false
        fixture.save()
        assertRejected(fixture, "APPROVAL")
        fixture.manifest["approved"] = true
        fixture.scenarioFiles.add(fixture.scenarioFiles.first())
        fixture.save()
        assertRejected(fixture, "SCENARIO_PATHS")
    }

    private fun validate(fixture: SyntheticPreflightFixture) =
        BlindFlowSettings.validateSnapshot(fixture.manifestPath, fixture.manifestHash(), fixture.workspace)

    private fun assertRejected(
        fixture: SyntheticPreflightFixture,
        stage: String,
    ) {
        val before = fixture.treeSnapshot()
        val failure = assertFailsWith<IllegalStateException> { validate(fixture) }
        assertEquals("Blind flow snapshot rejected at $stage", failure.message)
        assertEquals(before, fixture.treeSnapshot())
    }
}
