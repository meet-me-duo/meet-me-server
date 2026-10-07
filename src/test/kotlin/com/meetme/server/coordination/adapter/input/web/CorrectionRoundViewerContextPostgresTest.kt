package com.meetme.server.coordination.adapter.input.web

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Issue #92 self-scoped viewer context is a cache hint; credentials remain only in cookies.
class CorrectionRoundViewerContextPostgresTest : CorrectionRoundPostgresFixture() {
    @Test
    fun `two MEMBER sessions with same display name have distinct context and access only own raw`() {
        val (first, second) = sameNamedMembers()
        val firstViewer = child(room(first), "viewer")
        val secondViewer = child(room(second), "viewer")
        assertEquals(firstViewer["role"], secondViewer["role"])
        assertEquals(firstViewer["display_name"], secondViewer["display_name"])
        val firstContext = assertNotNull(firstViewer["context_id"])
        val secondContext = assertNotNull(secondViewer["context_id"])
        assertNotEquals(firstContext, secondContext)
        UUID.fromString(firstContext.toString())
        UUID.fromString(secondContext.toString())
        assertEquals("first own text", owner(first)["raw_text"])
        assertEquals("second own text", owner(second)["raw_text"])
        assertEquals(firstContext, child(room(first), "viewer")["context_id"])
        assertEquals(secondContext, child(room(second), "viewer")["context_id"])
        for (session in listOf(first, second)) {
            val publicDocument = room(session).toString()
            assertTrue(!publicDocument.contains(session.credential), "Cookie credential must not be exposed in the room JSON")
        }
    }

    @Test
    fun `anonymous room view explicitly has null context and cannot inherit authenticated member hint`() {
        val fixture = completedRoom()
        assertNotNull(child(room(fixture.member), "viewer")["context_id"])
        val response =
            mvc
                .perform(get("/api/rooms/{code}", fixture.host.code))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val anonymous = child(document(response), "viewer")
        assertEquals(false, anonymous["joined"])
        assertTrue(anonymous.containsKey("context_id"))
        assertNull(anonymous["context_id"])
    }

    @Test
    fun `expired guest session room view has no self context`() {
        val fixture = completedRoom()
        val before = child(room(fixture.member), "viewer")
        assertNotNull(before["context_id"])
        jdbc.update(
            "UPDATE guest_browser_sessions SET created_at = now() - interval '31 days', expires_at = now() - interval '1 second' " +
                "WHERE id IN (SELECT guest_session_id FROM participants WHERE room_id = ? AND role = 'MEMBER')",
            fixture.roomId,
        )
        val expired = child(room(fixture.member), "viewer")
        assertEquals(false, expired["joined"])
        assertTrue(expired.containsKey("context_id"))
        assertNull(expired["context_id"])
    }

    private fun sameNamedMembers(): Pair<CorrectionSession, CorrectionSession> {
        val created =
            mvc
                .perform(
                    post("/api/rooms")
                        .header("Origin", CorrectionRoundPostgresFixture.ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"purpose":"synthetic identity","meeting_mode":"REMOTE","host_display_name":"host","manual_only":true}""",
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        val code = document(created).getValue("invite_code").toString()

        fun join(): CorrectionSession {
            val response =
                mvc
                    .perform(
                        post("/api/rooms/{code}/participants", code)
                            .header("Origin", CorrectionRoundPostgresFixture.ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"display_name":"same member"}"""),
                    ).andExpect(status().isCreated)
                    .andReturn()
                    .response
            val cookie =
                requireNotNull(response.getHeader(HttpHeaders.SET_COOKIE))
                    .substringAfter("meet_me_guest=")
                    .substringBefore(';')
            return CorrectionSession(code, cookie)
        }
        val first = join()
        val second = join()
        mvc.perform(save(first, "first own text")).andExpect(status().isOk)
        mvc.perform(save(second, "second own text")).andExpect(status().isOk)
        return first to second
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = CorrectionRoundPostgresFixture.registerCorrectionDatabase(registry, postgres)

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
