package com.meetme.server.coordination.adapter.input.web

import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.annotation.ImportCandidates
import kotlin.test.assertTrue

/** Actual public Boot discovery contract; no application context, DB or provider startup. */
class SelectedOperatingGraphRegistrationTest {
    @Test
    fun `selected production graph is discoverable by Boot auto configuration imports`() {
        val candidates = ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).toList()
        assertTrue(
            "com.meetme.server.config.SelectedScheduleDslAutoConfiguration" in candidates,
            "Boot discovery must include the selected production graph",
        )
    }
}
