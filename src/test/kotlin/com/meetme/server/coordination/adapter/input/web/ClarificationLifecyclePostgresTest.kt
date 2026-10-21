package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.meetingroom.application.port.output.RoomDataRetentionPort
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.domain.ClarificationProposal
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// I107-06/07/09/14: independent lifecycle, hostile HTTP, native schema and retention contracts.
class ClarificationLifecyclePostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var structured: StructuredSubmissionRepository

    @Autowired private lateinit var retention: RoomDataRetentionPort

    @Test
    fun `first source proposal is immutable across replacement and cancelled proposal never reappears`() {
        val fixture = completedRoom()
        seed(fixture)
        val original = questions(fixture.member)
        seed(fixture, proposal = ClarificationProposal("다른 구절", "다른 질문", "다른 전체 원문 A", "다른 전체 원문 B"))
        assertEquals(original, questions(fixture.member))
        val round = openRound(fixture)
        mvc.perform(answer(fixture.member, questionId(fixture.member), round, choice = "CANCEL")).andExpect(status().isOk)
        seed(fixture)
        assertEquals(emptyList<Any>(), questions(fixture.member)["questions"])
        assertEquals(1, questionCount(fixture))
        assertEquals(1, (owner(fixture.member).getValue("revision") as Number).toInt())
    }

    @Test
    fun `answer generated version stays safely ambiguous without asking another question`() {
        val fixture = completedRoom()
        seed(fixture)
        val round = openRound(fixture)
        mvc
            .perform(answer(fixture.member, questionId(fixture.member), round, choice = "DIRECT", text = "여전히 모호한 입력"))
            .andExpect(status().isOk)
        mvc.perform(analyze(fixture, round)).andExpect(status().isAccepted)
        val nextRun = activeRun(fixture.member)
        completePartial(nextRun)
        seed(fixture, nextRun)
        assertEquals(emptyList<Any>(), questions(fixture.member)["questions"])
        assertEquals(1, questionCount(fixture))
        assertEquals("여전히 모호한 입력", owner(fixture.member)["raw_text"])
        assertEquals(
            "AMBIGUOUS_TIME_CONSTRAINT",
            jdbc.queryForObject(
                "SELECT rejection_code FROM structured_submission_results WHERE submission_version_id = ?",
                String::class.java,
                currentVersion(fixture),
            ),
        )
    }

    @Test
    fun `answered original copied into a different revision cannot bypass repeated question suppression`() {
        val fixture = completedRoom()
        seed(fixture)
        val round = openRound(fixture)
        mvc.perform(answer(fixture.member, questionId(fixture.member), round, choice = "CANCEL")).andExpect(status().isOk)
        mvc.perform(save(fixture.member, "중간 수정", round, 1)).andExpect(status().isOk)
        mvc.perform(save(fixture.member, "M0 original", round, 2)).andExpect(status().isOk)
        mvc.perform(analyze(fixture, round)).andExpect(status().isAccepted)
        val nextRun = activeRun(fixture.member)
        completePartial(nextRun)
        seed(fixture, nextRun)
        assertEquals(3, (owner(fixture.member).getValue("revision") as Number).toInt())
        assertEquals(emptyList<Any>(), questions(fixture.member)["questions"])
        assertEquals(1, questionCount(fixture))
    }

    @Test
    fun `at most three source questions are created per participant in one room`() {
        val fixture = completedRoom()
        var source = fixture.sourceRun
        seed(fixture, source)
        repeat(3) { index ->
            assertEquals(1, (questions(fixture.member).getValue("questions") as List<*>).size)
            val round = openRound(fixture, generation = index.toLong(), source = source)
            mvc.perform(answer(fixture.member, questionId(fixture.member), round, choice = "CANCEL")).andExpect(status().isOk)
            mvc.perform(save(fixture.member, "새로운 모호한 원문 ${index + 1}", round, index + 1)).andExpect(status().isOk)
            mvc.perform(analyze(fixture, round)).andExpect(status().isAccepted)
            source = activeRun(fixture.member)
            completePartial(source)
            seed(fixture, source)
        }
        assertEquals(3, questionCount(fixture))
        assertEquals(emptyList<Any>(), questions(fixture.member)["questions"])
        assertEquals(0, (room(fixture.host).getValue("remaining_correction_analyses") as Number).toInt())
    }

    @Test
    fun `active completed source no longer ambiguous fences an old question id`() {
        val fixture = completedRoom()
        seed(fixture)
        val questionId = questionId(fixture.member)
        val round = openRound(fixture)
        // Same immutable source can be re-parsed successfully; the previous question must not regain authority.
        seed(fixture, rejection = null, proposal = null)
        val versions = versionIds()
        val original = owner(fixture.member)
        val counts = workCounts()
        assertEquals(emptyList<Any>(), questions(fixture.member)["questions"])
        for (choice in listOf("A", "CANCEL")) {
            mvc
                .perform(answer(fixture.member, questionId, round, choice = choice))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
        }
        assertEquals(original, owner(fixture.member))
        assertEquals(versions, versionIds())
        assertEquals(counts, workCounts())
    }

    @Test
    fun `unknown fields wrong scalar types null and numeric enum are rejected before any write`() {
        val fixture = completedRoom()
        seed(fixture)
        val questionId = questionId(fixture.member)
        val round = openRound(fixture)
        val base =
            linkedMapOf<String, Any?>(
                "request_id" to UUID.randomUUID(),
                "revision_round_id" to round,
                "expected_revision" to 1,
                "choice" to "DIRECT",
                "direct_text" to "전체 수정 원문",
            )
        val invalid =
            listOf(
                base + ("unknown" to "injected"),
                base + ("choice" to 0),
                base + ("choice" to true),
                base + ("choice" to null),
                base + ("direct_text" to 123),
                base + ("direct_text" to listOf("text")),
                base + ("direct_text" to mapOf("text" to "replacement")),
                base + ("direct_text" to null),
                base + ("request_id" to 1),
                base + ("revision_round_id" to false),
                base + ("expected_revision" to "1"),
                base + ("expected_revision" to 1.5),
                base + ("expected_revision" to true),
                base + ("expected_revision" to null),
                base - "request_id",
                base - "revision_round_id",
                base - "expected_revision",
                base - "choice",
            )
        val original = owner(fixture.member)
        val versions = versionIds()
        val counts = workCounts()
        for (body in invalid) {
            mvc
                .perform(
                    post("/api/rooms/{code}/submission/clarifications/{question}/answer", fixture.member.code, questionId)
                        .header(
                            "Origin",
                            ORIGIN,
                        ).cookie(fixture.member.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)),
                ).andExpect(status().isBadRequest)
            assertEquals(original, owner(fixture.member))
            assertEquals(versions, versionIds())
            assertEquals(counts, workCounts())
        }
    }

    @Test
    fun `direct validation preserves internal whitespace and non ECMAScript trim codepoints`() {
        val fixture = completedRoom()
        seed(fixture)
        val round = openRound(fixture)
        val text = "\u0085  첫 줄\n  두 번째 줄  \u0085"
        mvc
            .perform(answer(fixture.member, questionId(fixture.member), round, choice = "DIRECT", text = "\uFEFF\u00A0$text\u3000"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.submission.raw_text").value(text))
        assertEquals(text, owner(fixture.member)["raw_text"])
    }

    @Test
    fun `A option and direct input both honor batch ten thousand boundary without recording rejected answer`() {
        for (choice in listOf("A", "DIRECT")) {
            val fixture = largeRoom()
            seed(fixture, proposal = ClarificationProposal("x", "어느 시간인가요?", "가".repeat(101), "나".repeat(100)))
            val id = questionId(fixture.member)
            val round = openRound(fixture)
            val original = owner(fixture.member)
            val versions = versionIds()
            val counts = workCounts()
            mvc
                .perform(answer(fixture.member, id, round, choice = choice, text = if (choice == "DIRECT") "가".repeat(101) else null))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("SUBMISSION_BATCH_TEXT_LIMIT_EXCEEDED"))
            assertEquals(original, owner(fixture.member))
            assertEquals(versions, versionIds())
            assertEquals(counts, workCounts())
            assertEquals(id, questionId(fixture.member))
            mvc
                .perform(answer(fixture.member, id, round, choice = "DIRECT", text = "😀".repeat(100)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.submission.raw_text").value("😀".repeat(100)))
        }
    }

    @Test
    fun `retention removes pending and answered provenance without deleting fresh room data`() {
        val expired = completedRoom()
        seed(expired)
        val round = openRound(expired)
        mvc.perform(answer(expired.member, questionId(expired.member), round)).andExpect(status().isOk)
        val fresh = completedRoom()
        seed(fresh)
        val freshQuestions = questions(fresh.member)
        val now = Instant.now()
        jdbc.update(
            "UPDATE meeting_rooms SET created_at = ?, closed_at = ? WHERE id = ?",
            now.minus(32, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC),
            now.minus(31, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC),
            expired.roomId,
        )
        assertEquals(1, retention.deleteExpiredRooms(now.minus(30, ChronoUnit.DAYS), 10, now))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM meeting_rooms WHERE id = ?", Int::class.java, expired.roomId))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM submission_clarifications", Int::class.java))
        assertEquals(freshQuestions, questions(fresh.member))
        assertEquals(1, questionCount(fresh))
    }

    @Test
    fun `native OpenAPI documents clarification request response and errors and exports exact response bytes`() {
        assertEquals("owner-clarification-v1", room(completedRoom().host)["clarification_protocol"])
        val response =
            mvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val spec = document(response)
        val paths = obj(spec.getValue("paths"))
        val schemas = obj(obj(spec.getValue("components")).getValue("schemas"))
        assertNotNull(obj(obj(schemas.getValue("RoomResponse")).getValue("properties"))["clarification_protocol"])
        val prefix = "/api/rooms/{inviteCode}/submission/clarifications"
        val read = obj(obj(paths.getValue(prefix)).getValue("get"))
        val write = obj(obj(paths.getValue("$prefix/{questionId}/answer")).getValue("post"))
        val request = deref(content(obj(write.getValue("requestBody"))), schemas)
        assertTrue((request["required"] as List<*>).containsAll(listOf("request_id", "revision_round_id", "expected_revision", "choice")))
        val properties = obj(request.getValue("properties"))
        for (field in listOf("request_id", "revision_round_id")) assertEquals("uuid", obj(properties.getValue(field))["format"])
        assertEquals("integer", obj(properties.getValue("expected_revision"))["type"])
        assertEquals(setOf("A", "B", "DIRECT", "CANCEL"), (obj(properties.getValue("choice")).getValue("enum") as List<*>).toSet())
        val directType = obj(properties.getValue("direct_text"))["type"]
        assertTrue(directType == "string" || (directType as? List<*>)?.contains("string") == true)
        val envelope = deref(content(obj(obj(read.getValue("responses")).getValue("200"))), schemas)
        val question = deref(obj(obj(obj(envelope.getValue("properties")).getValue("questions")).getValue("items")), schemas)
        val fields = obj(question.getValue("properties"))
        assertTrue(
            fields.keys.containsAll(listOf("question_id", "source_revision", "excerpt", "question", "option_a", "option_b", "editable")),
        )
        assertEquals("uuid", obj(fields.getValue("question_id"))["format"])
        for (operation in listOf(read, write)) {
            for (code in listOf("401", "403", "404", "409")) {
                val problem = deref(content(obj(obj(operation.getValue("responses")).getValue(code))), schemas)
                assertNotNull(obj(problem.getValue("properties"))["code"])
            }
        }
        for (code in listOf("400", "429", "503")) assertNotNull(obj(write.getValue("responses"))[code])
        System.getenv("MEETME_ISSUE107_OPENAPI_EXPORT")?.takeIf(String::isNotBlank)?.let {
            val target = Path.of(it)
            target.parent?.let(Files::createDirectories)
            Files.write(target, response.contentAsByteArray)
        }
    }

    private fun seed(
        fixture: CompletedCorrectionFixture,
        runId: UUID = fixture.sourceRun,
        proposal: ClarificationProposal? = DEFAULT_PROPOSAL,
        rejection: String? = "AMBIGUOUS_TIME_CONSTRAINT",
    ) {
        val batch = requireNotNull(jdbc.queryForObject("SELECT batch_id FROM coordination_runs WHERE id = ?", UUID::class.java, runId))
        val conditions =
            if (rejection == null) {
                listOf(
                    StructuredCondition.TimeWindow(
                        TimePolarity.AVAILABLE,
                        LocalDate.of(2026, 10, 9),
                        null,
                        LocalTime.of(15, 0),
                        LocalTime.of(17, 0),
                    ),
                )
            } else {
                emptyList()
            }
        structured.replaceForBatch(
            SubmissionBatchId(batch),
            listOf(StructuredSubmissionResult(SubmissionVersionId(currentVersion(fixture)), conditions, rejection, proposal)),
            Instant.parse("2026-10-07T05:00:00Z"),
        )
    }

    private fun currentVersion(fixture: CompletedCorrectionFixture): UUID =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT h.latest_version_id FROM submission_heads h JOIN participants p ON p.id = h.participant_id " +
                    "WHERE p.room_id = ? AND p.display_name = 'member'",
                UUID::class.java,
                fixture.roomId,
            ),
        )

    private fun questionCount(fixture: CompletedCorrectionFixture): Int =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM submission_clarifications c JOIN submission_versions v ON v.id = c.source_version_id " +
                    "JOIN submission_heads h ON h.id = v.submission_id WHERE h.room_id = ?",
                Int::class.java,
                fixture.roomId,
            ),
        )

    private fun questions(actor: CorrectionSession) =
        document(
            mvc
                .perform(
                    get("/api/rooms/{code}/submission/clarifications", actor.code).cookie(actor.cookie()),
                ).andExpect(status().isOk)
                .andReturn()
                .response,
        )

    private fun questionId(actor: CorrectionSession): UUID =
        UUID.fromString(obj((questions(actor).getValue("questions") as List<*>).single()).getValue("question_id").toString())

    private fun answer(
        actor: CorrectionSession,
        id: UUID,
        round: UUID,
        choice: String = "A",
        text: String? = null,
    ) = post("/api/rooms/{code}/submission/clarifications/{question}/answer", actor.code, id)
        .header("Origin", ORIGIN)
        .cookie(actor.cookie())
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            mapper.writeValueAsString(
                linkedMapOf<String, Any?>(
                    "request_id" to UUID.randomUUID(),
                    "revision_round_id" to round,
                    "expected_revision" to (owner(actor).getValue("revision") as Number).toInt(),
                    "choice" to choice,
                ).apply {
                    if (text !=
                        null
                    ) {
                        this["direct_text"] = text
                    }
                },
            ),
        )

    private fun largeRoom(): CompletedCorrectionFixture {
        val hostCookie = accounts.newAccount("host")
        val created =
            mvc
                .perform(
                    post(
                        "/api/rooms",
                    ).cookie(hostCookie)
                        .header(
                            "Origin",
                            ORIGIN,
                        ).contentType(
                            MediaType.APPLICATION_JSON,
                        ).content(
                            """
                            {"purpose":"synthetic boundary","meeting_mode":"REMOTE","host_display_name":"host",
                             "manual_only":true,"search_start_date":"2026-10-07","search_end_date":"2026-10-12"}
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        val code = document(created).getValue("invite_code").toString()
        // Historical direct-submission/correction oracle: migrated rooms retain LEGACY.
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='LEGACY' WHERE invite_code=?", code)
        val host =
            CorrectionSession(
                code,
                hostCookie.value,
            )

        fun join(name: String): CorrectionSession {
            val joined =
                mvc
                    .perform(
                        post(
                            "/api/rooms/{code}/participants",
                            code,
                        ).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(
                            mapper.writeValueAsString(
                                mapOf(
                                    "display_name" to name,
                                ),
                            ),
                        ),
                    ).andExpect(status().isCreated)
                    .andReturn()
                    .response
            return CorrectionSession(
                code,
                requireNotNull(joined.getHeader(HttpHeaders.SET_COOKIE)).substringAfter("meet_me_guest=").substringBefore(';'),
            )
        }
        val member = join("member")
        mvc.perform(save(host, "H".repeat(400))).andExpect(status().isOk)
        mvc.perform(save(member, "x")).andExpect(status().isOk)
        repeat(19) { mvc.perform(save(join("member-$it"), "😀".repeat(500))).andExpect(status().isOk) }
        mvc
            .perform(
                post(
                    "/api/rooms/{code}/close",
                    code,
                ).header(
                    "Origin",
                    ORIGIN,
                ).cookie(host.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"confirm_early":true}"""),
            ).andExpect(status().isOk)
        val room = requireNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code = ?", UUID::class.java, code))
        val run = requireNotNull(jdbc.queryForObject("SELECT id FROM coordination_runs WHERE room_id = ?", UUID::class.java, room))
        completePartial(run)
        return CompletedCorrectionFixture(host, member, room, run)
    }

    private fun content(container: Map<String, Any?>): Map<String, Any?> {
        val media = obj(container.getValue("content"))
        return obj(obj(media["application/json"] ?: media["application/problem+json"] ?: media.values.single()).getValue("schema"))
    }

    private fun deref(
        schema: Map<String, Any?>,
        schemas: Map<String, Any?>,
    ): Map<String, Any?> = schema["\$ref"]?.let { obj(schemas.getValue(it.toString().substringAfterLast('/'))) + schema } ?: schema

    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): Map<String, Any?> = value as Map<String, Any?>

    companion object {
        private val DEFAULT_PROPOSAL = ClarificationProposal("3시", "오전인가요 오후인가요?", "2026-10-09 오전 3~5시 가능", "2026-10-09 오후 3~5시 가능")
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
