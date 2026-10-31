package com.meetme.server.experiment

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PilotTenPreflightContractTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `fresh synthetic pilot manifest validates without consuming approval opening sealed data or creating providers`() {
        val fixture = SyntheticPreflightFixture(temporary, pilotOnly = true)
        val before = fixture.treeSnapshot()
        val result = validate(fixture)
        assertEquals(3, result.path("pilotFiles").size())
        assertEquals(10, result.path("config").path("maximumPhysicalRequests").asInt())
        assertEquals("IN_PERSON", result.path("config").path("meetingMode").asText())
        assertFalse(Files.exists(fixture.output))
        assertFalse(Files.exists(fixture.scenarios.resolve("sealed")))
        assertEquals(before, fixture.treeSnapshot())
    }

    @Test
    fun `old twenty request configuration is rejected even with a recomputed config and manifest hash`() {
        val fixture = SyntheticPreflightFixture(temporary, pilotOnly = true)
        fixture.config["maximumPhysicalRequests"] = 20
        fixture.save()
        assertRejected(fixture, "CONFIG")
    }

    @Test
    fun `source and pilot byte tampering cannot bypass frozen hashes`() {
        val fixture = SyntheticPreflightFixture(temporary, pilotOnly = true)
        val source = Path.of(fixture.sourceFiles.first().getValue("path"))
        val original = Files.readAllBytes(source)
        Files.writeString(source, "changed independent source")
        assertRejected(fixture, "SOURCE_FILE_HASHES")
        Files.write(source, original)
        Files.writeString(fixture.scenarios.resolve("pilot/input.json"), "changed independent pilot bytes")
        assertRejected(fixture, "PILOT_FILE_HASHES")
    }

    @Test
    fun `existing output and absent required source remain rejected with valid recomputed manifest hashes`() {
        val fixture = SyntheticPreflightFixture(temporary, pilotOnly = true)
        Files.createDirectories(fixture.output)
        assertRejected(fixture, "APPROVAL")
        Files.delete(fixture.output)
        fixture.sourceFiles.removeAt(0)
        fixture.save()
        assertRejected(fixture, "SOURCE_PATHS")
    }

    private fun validate(fixture: SyntheticPreflightFixture) =
        PilotTenSettings.validateSnapshot(fixture.manifestPath, fixture.manifestHash(), fixture.workspace)

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
