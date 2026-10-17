package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.domain.ClarificationProposal
import com.meetme.server.submission.domain.StructuredSubmissionResult
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// Issue #107 independent public-contract tests. Synthetic structured output only; no paid provider calls.
class ClarificationHttpPostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var structured: StructuredSubmissionRepository

    @Test
    fun `owner reload returns same question identity with source revision and round editability`() {
        val fixture = ambiguousRoom()
        val before = questions(fixture.member)
        val question = question(before)
        assertEquals(1, (question.getValue("source_revision") as Number).toInt())
        assertEquals(EXCERPT, question["excerpt"])
        assertEquals(QUESTION, question["question"])
        assertEquals(OPTION_A, question["option_a"])
        assertEquals(OPTION_B, question["option_b"])
        assertEquals(false, question["editable"])
        assertEquals(emptyList<Any>(), questions(fixture.host)["questions"])
        assertEquals(before, questions(fixture.member))

        openRound(fixture)
        val after = questions(fixture.member)
        assertEquals(question["question_id"], question(after)["question_id"])
        assertEquals(true, question(after)["editable"])
        assertTrue((after.getValue("state_version") as Number).toLong() > (before.getValue("state_version") as Number).toLong())
        assertEquals(after, questions(fixture.member))
    }

    @Test
    fun `persisting same source output cannot regenerate question id or ask another participant`() {
        val fixture = ambiguousRoom()
        val original = question(questions(fixture.member))["question_id"]
        seedQuestion(fixture)
        assertEquals(original, question(questions(fixture.member))["question_id"])
        assertEquals(emptyList<Any>(), questions(fixture.host)["questions"])
    }

    @Test
    fun `answer creates immutable revision while retaining source and requires explicit host analysis`() {
        val fixture = ambiguousRoom()
        val sourceVersion = memberVersion(fixture)
        val id = questionId(fixture.member)
        val roundId = openRound(fixture)
        val versions = versionIds()
        val counts = workCounts()
        val hostBefore = owner(fixture.host)
        val stateBefore = (room(fixture.member).getValue("state_version") as Number).toLong()

        val response =
            mvc
                .perform(answer(fixture.member, id, roundId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.submission.revision").value(2))
                .andReturn()
                .response
        assertEquals(child(document(response), "submission"), owner(fixture.member))
        assertEquals(OPTION_A, owner(fixture.member)["raw_text"])
        assertEquals(
            sourceVersion,
            jdbc.queryForObject("SELECT source_version_id FROM submission_clarifications WHERE question_id = ?", UUID::class.java, id),
        )
        assertEquals(
            memberVersion(fixture),
            jdbc.queryForObject("SELECT saved_version_id FROM submission_clarifications WHERE question_id = ?", UUID::class.java, id),
        )
        assertEquals(
            "A",
            jdbc.queryForObject("SELECT answer_choice FROM submission_clarifications WHERE question_id = ?", String::class.java, id),
        )
        assertEquals(versions.size + 1, versionIds().size)
        assertNotEquals(sourceVersion, memberVersion(fixture))
        assertEquals(
            "M0 original",
            jdbc.queryForObject("SELECT raw_text FROM submission_versions WHERE id = ?", String::class.java, sourceVersion),
        )
        assertEquals(hostBefore - "state_version", owner(fixture.host) - "state_version")
        assertEquals(counts, workCounts())
        assertEquals(fixture.sourceRun, activeRun(fixture.host))
        assertEquals(emptyList<Any>(), questions(fixture.member)["questions"])
        assertTrue((room(fixture.member).getValue("state_version") as Number).toLong() > stateBefore)
    }

    @Test
    fun `B and trimmed direct answers save new versions without another parse job`() {
        for (choice in listOf("B", "DIRECT")) {
            val fixture = ambiguousRoom()
            val id = questionId(fixture.member)
            val roundId = openRound(fixture)
            val before = workCounts()
            mvc
                .perform(
                    answer(
                        fixture.member,
                        id,
                        roundId,
                        choice = choice,
                        directText =
                            if (choice ==
                                "DIRECT"
                            ) {
                                "\uFEFF  금요일 오후 3시부터 5시 가능  \u3000"
                            } else {
                                null
                            },
                    ),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.submission.revision").value(2))
            assertEquals(before, workCounts())
            assertEquals(emptyList<Any>(), questions(fixture.member)["questions"])
            assertEquals(2, (owner(fixture.member).getValue("revision") as Number).toInt())
            assertEquals(if (choice == "B") OPTION_B else "금요일 오후 3시부터 5시 가능", owner(fixture.member)["raw_text"])
        }
    }

    @Test
    fun `direct boundary accepts one and five hundred Unicode codepoints without truncation`() {
        for (text in listOf("가", "😀".repeat(500))) {
            val fixture = ambiguousRoom()
            val id = questionId(fixture.member)
            val roundId = openRound(fixture)
            mvc
                .perform(answer(fixture.member, id, roundId, choice = "DIRECT", directText = text))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.submission.revision").value(2))
        }
    }

    @Test
    fun `invalid direct or mixed choice payload never changes revision question or work`() {
        val fixture = ambiguousRoom()
        val id = questionId(fixture.member)
        val roundId = openRound(fixture)
        val versions = versionIds()
        val original = owner(fixture.member)
        val before = workCounts()
        val invalid =
            listOf(
                "DIRECT" to null,
                "DIRECT" to "",
                "DIRECT" to "\uFEFF\t \u3000",
                "DIRECT" to "😀".repeat(501),
                "A" to "additional text",
                "B" to "additional text",
                "CANCEL" to "additional text",
                "UNKNOWN" to null,
            )
        for ((choice, text) in invalid) {
            mvc.perform(answer(fixture.member, id, roundId, choice = choice, directText = text)).andExpect(status().isBadRequest)
            assertEquals(original, owner(fixture.member))
            assertEquals(versions, versionIds())
            assertEquals(id, questionId(fixture.member))
            assertEquals(before, workCounts())
        }
    }

    @Test
    fun `cancel is durable across reload and does not change submission revision or enqueue work`() {
        val fixture = ambiguousRoom()
        val id = questionId(fixture.member)
        val roundId = openRound(fixture)
        val versions = versionIds()
        val original = owner(fixture.member)
        val before = workCounts()
        val requestId = UUID.randomUUID()
        repeat(2) {
            mvc
                .perform(answer(fixture.member, id, roundId, requestId, choice = "CANCEL"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.submission").value(nullValue()))
        }
        assertEquals(original - "state_version", owner(fixture.member) - "state_version")
        assertEquals(versions, versionIds())
        assertEquals(before, workCounts())
        assertEquals(emptyList<Any>(), questions(fixture.member)["questions"])
    }

    @Test
    fun `same request retries replay response even after host consumes round but changed body conflicts`() {
        val fixture = ambiguousRoom()
        val id = questionId(fixture.member)
        val roundId = openRound(fixture)
        val requestId = UUID.randomUUID()
        val first =
            mvc
                .perform(
                    answer(fixture.member, id, roundId, requestId),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        mvc.perform(analyze(fixture, roundId)).andExpect(status().isAccepted)
        val counts = workCounts()
        val versions = versionIds()
        val replay =
            mvc
                .perform(
                    answer(fixture.member, id, roundId, requestId),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertEquals(first, replay)
        mvc
            .perform(answer(fixture.member, id, roundId, requestId, choice = "B"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
        assertEquals(versions, versionIds())
        assertEquals(counts, workCounts())
    }

    @Test
    fun `missing or consumed round and stale revision reject save and cancel without changing data`() {
        val fixture = ambiguousRoom()
        val id = questionId(fixture.member)
        val versions = versionIds()
        for (choice in listOf("A", "CANCEL")) {
            mvc.perform(answer(fixture.member, id, UUID.randomUUID(), choice = choice)).andExpect(status().isConflict)
        }
        val roundId = openRound(fixture)
        for (choice in listOf("A", "CANCEL")) {
            mvc
                .perform(answer(fixture.member, id, roundId, revision = 0, choice = choice))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
        }
        mvc.perform(analyze(fixture, roundId)).andExpect(status().isOk)
        for (choice in listOf("A", "CANCEL")) {
            mvc.perform(answer(fixture.member, id, roundId, choice = choice)).andExpect(status().isConflict)
        }
        assertEquals(versions, versionIds())
        assertEquals("M0 original", owner(fixture.member)["raw_text"])
    }

    @Test
    fun `unknown and another owners valid question return same conflict and never expose source`() {
        val fixture = ambiguousRoom()
        val id = questionId(fixture.member)
        val roundId = openRound(fixture)
        for (questionId in listOf(id, UUID.randomUUID())) {
            val response =
                mvc
                    .perform(answer(fixture.host, questionId, roundId))
                    .andExpect(status().isConflict)
                    .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
                    .andReturn()
                    .response.contentAsString
            assertTrue(!response.contains(EXCERPT))
            assertTrue(!response.contains("M0 original"))
        }
        assertEquals(id, questionId(fixture.member))
    }

    @Test
    fun `credential and exact Origin protect question reads and answer writes`() {
        val fixture = ambiguousRoom()
        val id = questionId(fixture.member)
        val roundId = openRound(fixture)
        mvc.perform(get("/api/rooms/{code}/submission/clarifications", fixture.member.code)).andExpect(status().isUnauthorized)
        mvc.perform(answer(fixture.member, id, roundId, authenticated = false)).andExpect(status().isUnauthorized)
        val foreign = completedRoom()
        mvc
            .perform(get("/api/rooms/{code}/submission/clarifications", fixture.member.code).cookie(foreign.member.cookie()))
            .andExpect(status().isForbidden)
        mvc.perform(answer(foreign.member.copy(code = fixture.member.code), id, roundId)).andExpect(status().isForbidden)
        for (origin in listOf<String?>(null, "https://attacker.invalid", "$ORIGIN.attacker.invalid")) {
            val request = answer(fixture.member, id, roundId)
            request.headers { it.remove("Origin") }
            origin?.let { request.header("Origin", it) }
            mvc.perform(request).andExpect(status().isForbidden)
        }
        assertEquals(1, (owner(fixture.member).getValue("revision") as Number).toInt())
        assertEquals(id, questionId(fixture.member))
    }

    @Test
    fun `original edit races question answer with source revision fencing`() {
        val fixture = ambiguousRoom()
        val id = questionId(fixture.member)
        val roundId = openRound(fixture)
        val before = workCounts()
        val codes =
            orderedRequests(fixture, save(fixture.member, "M1 explicit correction", roundId, 1), answer(fixture.member, id, roundId))
        assertEquals(200 to 409, codes)
        assertEquals("M1 explicit correction", owner(fixture.member)["raw_text"])
        assertEquals(2, (owner(fixture.member).getValue("revision") as Number).toInt())
        assertEquals(before, workCounts())
    }

    @Test
    fun `same request racing itself creates only one answer revision`() {
        val fixture = ambiguousRoom()
        val id = questionId(fixture.member)
        val roundId = openRound(fixture)
        val requestId = UUID.randomUUID()
        val versions = versionIds()
        val before = workCounts()
        val codes = orderedRequests(fixture, answer(fixture.member, id, roundId, requestId), answer(fixture.member, id, roundId, requestId))
        assertEquals(200 to 200, codes)
        assertEquals(versions.size + 1, versionIds().size)
        assertEquals(2, (owner(fixture.member).getValue("revision") as Number).toInt())
        assertEquals(before, workCounts())
    }

    @Test
    fun `host analysis winning race fences late answer out of frozen batch`() {
        val fixture = ambiguousRoom()
        val id = questionId(fixture.member)
        val roundId = openRound(fixture)
        val versions = versionIds()
        val codes = orderedRequests(fixture, analyze(fixture, roundId, force = true), answer(fixture.member, id, roundId))
        assertEquals(202 to 409, codes)
        assertEquals(versions, versionIds())
        assertEquals("M0 original", owner(fixture.member)["raw_text"])
    }

    @Test
    fun `unanswered question remains pending without inventing revision and host cannot read ambiguity source`() {
        val fixture = ambiguousRoom()
        val hostResponse =
            mvc
                .perform(get("/api/rooms/{code}/candidates/unapplied-inputs", fixture.host.code).cookie(fixture.host.cookie()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        for (secret in listOf("M0 original", EXCERPT, QUESTION, "AMBIGUOUS_TIME_CONSTRAINT")) {
            assertTrue(!hostResponse.contains(secret), "Another participant's ambiguity source and reason must remain private")
        }
        mvc
            .perform(get("/api/rooms/{code}/candidates/unapplied-inputs", fixture.member.code).cookie(fixture.member.cookie()))
            .andExpect(status().isForbidden)
        openRound(fixture)
        val original = owner(fixture.member)
        val versions = versionIds()
        val before = workCounts()
        val question = questions(fixture.member)
        repeat(3) { assertEquals(question, questions(fixture.member)) }
        mvc
            .perform(get("/api/rooms/{code}/candidates/unapplied-inputs", fixture.host.code).cookie(fixture.host.cookie()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CANDIDATES_NOT_READY"))
        assertEquals(original, owner(fixture.member))
        assertEquals(versions, versionIds())
        assertEquals(before, workCounts())
    }

    @Test
    fun `answer persistence failure rolls back new version head and provenance as one transaction`() {
        val fixture = ambiguousRoom()
        val id = questionId(fixture.member)
        val roundId = openRound(fixture)
        val original = owner(fixture.member)
        val versions = versionIds()
        val before = workCounts()
        jdbc.execute(
            "CREATE FUNCTION reject_synthetic_clarification_answer() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN IF NEW.answer_choice IS NOT NULL THEN RAISE EXCEPTION 'synthetic answer failure'; END IF; RETURN NEW; END \$\$",
        )
        jdbc.execute(
            "CREATE TRIGGER synthetic_answer_failure BEFORE UPDATE ON submission_clarifications FOR EACH ROW EXECUTE FUNCTION reject_synthetic_clarification_answer()",
        )
        try {
            assertFails { mvc.perform(answer(fixture.member, id, roundId)).andReturn() }
            assertEquals(original, owner(fixture.member))
            assertEquals(versions, versionIds())
            assertEquals(id, questionId(fixture.member))
            assertEquals(before, workCounts())
            assertEquals(
                null,
                jdbc.queryForObject("SELECT saved_version_id FROM submission_clarifications WHERE question_id = ?", UUID::class.java, id),
            )
        } finally {
            jdbc.execute("DROP TRIGGER synthetic_answer_failure ON submission_clarifications")
            jdbc.execute("DROP FUNCTION reject_synthetic_clarification_answer()")
        }
    }

    private fun ambiguousRoom(): CompletedCorrectionFixture = completedRoom().also { seedQuestion(it) }

    private fun seedQuestion(fixture: CompletedCorrectionFixture) {
        val batchId =
            requireNotNull(jdbc.queryForObject("SELECT batch_id FROM coordination_runs WHERE id = ?", UUID::class.java, fixture.sourceRun))
        structured.replaceForBatch(
            SubmissionBatchId(batchId),
            listOf(
                StructuredSubmissionResult(
                    SubmissionVersionId(memberVersion(fixture)),
                    emptyList(),
                    "AMBIGUOUS_TIME_CONSTRAINT",
                    clarification = ClarificationProposal(EXCERPT, QUESTION, OPTION_A, OPTION_B),
                ),
            ),
            Instant.parse("2026-10-07T05:00:00Z"),
        )
    }

    private fun memberVersion(fixture: CompletedCorrectionFixture): UUID =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT v.id FROM submission_versions v JOIN submission_heads h ON h.latest_version_id = v.id " +
                    "JOIN participants p ON p.id = h.participant_id WHERE p.room_id = ? AND p.display_name = 'member'",
                UUID::class.java,
                fixture.roomId,
            ),
        )

    private fun questions(actor: CorrectionSession): Map<String, Any?> =
        document(
            mvc
                .perform(get("/api/rooms/{code}/submission/clarifications", actor.code).cookie(actor.cookie()))
                .andExpect(status().isOk)
                .andReturn()
                .response,
        )

    @Suppress("UNCHECKED_CAST")
    private fun question(document: Map<String, Any?>): Map<String, Any?> =
        (document.getValue("questions") as List<Map<String, Any?>>).single()

    private fun questionId(actor: CorrectionSession): UUID = UUID.fromString(question(questions(actor)).getValue("question_id").toString())

    private fun answer(
        actor: CorrectionSession,
        questionId: UUID,
        roundId: UUID,
        requestId: UUID = UUID.randomUUID(),
        revision: Int = 1,
        choice: String = "A",
        directText: String? = null,
        authenticated: Boolean = true,
    ): MockHttpServletRequestBuilder {
        val body =
            linkedMapOf<String, Any?>(
                "request_id" to requestId,
                "revision_round_id" to roundId,
                "expected_revision" to revision,
                "choice" to choice,
            )
        directText?.let { body["direct_text"] = it }
        return post("/api/rooms/{code}/submission/clarifications/{question}/answer", actor.code, questionId)
            .header("Origin", ORIGIN)
            .apply { if (authenticated) cookie(actor.cookie()) }
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(body))
    }

    private fun orderedRequests(
        fixture: CompletedCorrectionFixture,
        first: MockHttpServletRequestBuilder,
        second: MockHttpServletRequestBuilder,
    ): Pair<Int, Int> {
        val written = CountDownLatch(1)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val pid = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val firstTask =
            executor.submit<Int> {
                requireNotNull(
                    newTransaction().execute {
                        jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE id = ? FOR UPDATE", UUID::class.java, fixture.roomId)
                        val code =
                            mvc
                                .perform(first)
                                .andReturn()
                                .response.status
                        written.countDown()
                        check(release.await(8, TimeUnit.SECONDS))
                        code
                    },
                )
            }
        try {
            assertTrue(written.await(5, TimeUnit.SECONDS))
            val secondTask =
                executor.submit<Int> {
                    requireNotNull(
                        newTransaction().execute { transaction ->
                            pid.set(requireNotNull(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)))
                            started.countDown()
                            val code =
                                mvc
                                    .perform(second)
                                    .andReturn()
                                    .response.status
                            if (code >= 400) transaction.setRollbackOnly()
                            code
                        },
                    )
                }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var blocked = false
            while (!blocked && System.nanoTime() < deadline) {
                blocked = jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean::class.java, pid.get()) == true
                if (!blocked) Thread.sleep(15)
            }
            assertTrue(blocked, "Second request must really wait on the first PostgreSQL transaction")
            release.countDown()
            return firstTask.get(10, TimeUnit.SECONDS) to secondTask.get(10, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun newTransaction() =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            timeout = 15
        }

    companion object {
        private const val EXCERPT = "금요일 3시부터"
        private const val QUESTION = "금요일 시작 시각이 오전인가요, 오후인가요?"
        private const val OPTION_A = "금요일 오전 3시부터 5시까지 가능"
        private const val OPTION_B = "금요일 오후 3시부터 5시까지 가능"
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
