package com.meetme.server.experiment

import com.meetme.server.shared.domain.InputInterpretation
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SavedLunaReplayContractTest {
    @Test
    fun `only input refs change when replaying a fixture response`() {
        val synthetic = SavedLunaReplaySyntheticFixture.data(JsonMapper.builder().build())
        assertEquals("SYNTHETIC", synthetic.origin)
        assertEquals(3, synthetic.originalInputs.size)
        assertEquals(
            3,
            synthetic.originalInputs
                .map { it.rawText }
                .toSet()
                .size,
        )
        assertEquals(1, synthetic.originalInputs.count { it.evidence?.supplement != null })
        assertEquals(1, synthetic.originalInputs.count { it.evidence?.interpretation == InputInterpretation.UNRESOLVED })
        synthetic.originalInputs.forEach { checkNotNull(it.evidence).validateAgainst(it.rawText) }

        val state = SavedLunaReplayState
        val priorResponses = state.responseCount.get()
        val mapping = state.originalInputs.associate { it.inputRef to UUID.randomUUID().toString() }
        val original = state.json.readTree(state.responseSourceBytes)
        val replay = state.json.readTree(state.remap(mapping))
        val oldMessage = original.path("output").values().single { it.path("type").asText() == "message" }
        val newMessage = replay.path("output").values().single { it.path("type").asText() == "message" }
        val oldText =
            state.json.readTree(
                oldMessage
                    .path("content")
                    .values()
                    .single()
                    .path("text")
                    .asText(),
            )
        val newText =
            state.json.readTree(
                newMessage
                    .path("content")
                    .values()
                    .single()
                    .path("text")
                    .asText(),
            )
        assertEquals(3, oldText.path("results").size())
        if (state.origin == "SYNTHETIC") {
            val conditions =
                oldText
                    .path("results")
                    .values()
                    .flatMap { it.path("conditions").values().toList() }
                    .toList()
            assertEquals(6, conditions.count { it.path("type").asText() == "TIME_WINDOW" })
            assertEquals(2, conditions.count { it.path("type").asText() == "TRAVEL_CONSTRAINT" })
        }
        assertEquals(original.propertyNames().toSet(), replay.propertyNames().toSet())
        for (field in original.propertyNames().filter { it != "output" }) assertEquals(original.path(field), replay.path(field))
        for (oldRow in oldText.path("results").values()) {
            val id = oldRow.path("input_ref").asText()
            val newRow = newText.path("results").values().single { it.path("input_ref").asText() == mapping.getValue(id) }
            assertEquals(oldRow.propertyNames().toSet(), newRow.propertyNames().toSet())
            for (field in oldRow.propertyNames().filter { it != "input_ref" }) assertEquals(oldRow.path(field), newRow.path(field))
        }
        assertEquals(priorResponses, state.responseCount.get())
        assertEquals(0, state.headerAttempts.get())
    }

    @Test
    fun `missing source or duplicate new refs cannot replay a response`() {
        val state = SavedLunaReplayState
        val mapping = state.originalInputs.associate { it.inputRef to UUID.randomUUID().toString() }
        assertFailsWith<IllegalArgumentException> { state.remap(mapping - mapping.keys.first()) }
        assertFailsWith<IllegalArgumentException> { state.remap(mapping.mapValues { mapping.values.first() }) }
    }
}
