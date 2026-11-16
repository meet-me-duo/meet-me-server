package com.meetme.server.shared.operations

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslStructuralResponseParser
import com.meetme.server.shared.adapter.output.persistence.operations.JdbcOperationsQueryRepository
import com.meetme.server.shared.adapter.output.persistence.operations.JdbcOperatorDiagnosticsRepository
import com.meetme.server.shared.application.port.input.operations.OperationsCursorCodec
import com.meetme.server.shared.application.port.input.operations.OperatorRoomDiagnostics
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.shared.domain.InputSupplement
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslStructuralRepository
import com.meetme.server.submission.application.port.input.ClarificationAnswerView
import com.meetme.server.submission.application.port.input.SubmissionView
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.support.FinalAnalysisRouteTestFixture
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.ScopedTermEvidenceTestFixture as S

/** Independent bounded read contract: immutable cohort, actual provenance and presentation-only privacy. */
class OperatorDiagnosticsPostgresTest {
    private val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    private val jdbc = JdbcTemplate(source)
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val operations = JdbcOperationsQueryRepository(jdbc)
    private val repository = JdbcOperatorDiagnosticsRepository(jdbc, mapper, operations)

    @BeforeEach
    fun freshCurrentDatabase() {
        Flyway
            .configure()
            .dataSource(source)
            .load()
            .migrate()
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions CASCADE")
        room(ROOM)
        member(1)
        member(2)
        run(RUN, BATCH, listOf(version(1), version(2)))
        jdbc.update("UPDATE meeting_rooms SET active_run_id=? WHERE id=?", RUN, ROOM)
    }

    @Test
    fun `ODP01 missing rooms and foreign analyses never fall back to current collection`() {
        room(FOREIGN_ROOM)
        member(99, FOREIGN_ROOM)
        run(uuid(99), uuid(199), listOf(version(99)), FOREIGN_ROOM)
        assertNull(repository.analyses(uuid(999), 20, null))
        assertNull(repository.diagnostics(uuid(999), null))
        assertNull(repository.diagnostics(ROOM, uuid(99)))
        assertNull(repository.diagnostics(ROOM, uuid(999)))
    }

    @Test
    fun `ODP02 run history includes an execution with no model invocation and pages equal timestamps without duplication`() {
        run(uuid(22), uuid(122), listOf(version(1), version(2)), status = "QUEUED")
        run(uuid(23), uuid(123), listOf(version(1), version(2)), status = "ANALYSIS_DELAYED")
        val first = assertNotNull(repository.analyses(ROOM, 2, null))
        assertEquals(listOf(uuid(23), uuid(22)), first.items.map { it.id })
        assertEquals(listOf("ANALYSIS_DELAYED", "QUEUED"), first.items.map { it.status })
        assertTrue(first.items.none { it.active })
        val second = assertNotNull(repository.analyses(ROOM, 2, OperationsCursorCodec.decode(assertNotNull(first.nextCursor))))
        assertEquals(listOf(RUN), second.items.map { it.id })
        assertTrue(second.items.single().active)
        assertNull(second.nextCursor)
        assertEquals(0, count("analysis_invocations"))
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, 0, 101])
    fun `ODP03 a repository page cannot bypass the one through one hundred bound`(limit: Int) {
        assertFailsWith<IllegalArgumentException> { repository.analyses(ROOM, limit, null) }
    }

    @Test
    fun `ODP04 historical selection uses frozen versions while explicit null selects current collection`() {
        newerVersion(1, "newer accepted first")
        newerVersion(2, "newer accepted second")
        member(3)
        run(uuid(22), uuid(122), listOf(uuid(4001), uuid(4002), version(3)))
        jdbc.update("UPDATE meeting_rooms SET active_run_id=? WHERE id=?", uuid(22), ROOM)
        candidate(5001, 1, "pending unaccepted text", revision = 7, status = "NEEDS_EDIT")
        val historical = diagnostics(RUN)
        assertEquals("FROZEN_ANALYSIS", historical.scope)
        assertFalse(assertNotNull(historical.analysis).active)
        assertEquals(setOf("old input 1", "old input 2"), historical.inputs.map { it.rawText }.toSet())
        assertEquals(2, historical.inputs.size)
        val current = assertNotNull(repository.diagnostics(ROOM, null))
        assertEquals("CURRENT_COLLECTION", current.scope)
        assertNull(current.analysis)
        assertEquals(3, current.inputs.size)
        assertEquals(setOf("newer accepted first", "newer accepted second", "old input 3"), current.inputs.map { it.rawText }.toSet())
        assertEquals(
            "pending unaccepted text",
            current.inputs
                .single { it.rawText == "newer accepted first" }
                .checks
                .single()
                .rawText,
        )
        assertTrue(historical.inputs.all { it.checks.isEmpty() })
    }

    @Test
    fun `ODP05 exact V2 supplement preserves consumed answer and original reference while redacting only the view`() {
        val original = "월요일 19시 이후 synthetic@example.test 010-1234-5678"
        val prompt = "몇 시인가요? synthetic@example.test"
        val answer = "20시부터 010-1234-5678"
        val evidence =
            InputEvidence(
                3,
                AT,
                InputSupplement(uuid(701), 3, "월요일", 0, prompt, answer, null, AT.plusSeconds(20), AT.plusSeconds(20)),
                InputInterpretation.CONFIRMED,
            )
        evidence.validateAgainst(original)
        jdbc.update(
            "UPDATE submission_versions SET raw_text=?,created_at=?,input_evidence=CAST(? AS jsonb) WHERE id=?",
            original,
            ts(20),
            mapper.writeValueAsString(evidence),
            version(1),
        )
        val before = storedRows()
        val view = diagnostics(RUN)
        val input = view.inputs.single { it.displayName == "가**" }
        assertEquals(AT, input.referenceAt)
        assertEquals(AT.plusSeconds(20), input.createdAt)
        assertEquals("CONFIRMED", input.interpretation)
        assertEquals("월요일 19시 이후 [이메일 숨김] [전화번호 숨김]", input.rawText)
        val supplement = assertNotNull(input.supplement)
        assertEquals("몇 시인가요? [이메일 숨김]", supplement.prompt)
        assertEquals("월요일", supplement.excerpt)
        assertEquals("20시부터 [전화번호 숨김]", supplement.answer)
        assertEquals(AT.plusSeconds(20), supplement.answeredAt)
        assertTrue(supplement.consumed)
        assertNoContacts(view)
        assertEquals(before, storedRows())
    }

    @Test
    fun `ODP06 mutable superseded check state cannot replace frozen interpretation or invent a historical Jev decision`() {
        val evidence = InputEvidence(1, AT, interpretation = InputInterpretation.CONFIRMED)
        jdbc.update(
            "UPDATE submission_versions SET input_evidence=CAST(? AS jsonb) WHERE id=?",
            mapper.writeValueAsString(evidence),
            version(1),
        )
        candidate(5001, 1, "old input 1", accepted = version(1), status = "SUPERSEDED")
        execution(5001, 1)
        candidate(5002, 1, "new unrelated draft", revision = 2, status = "QUEUED")
        val input = diagnostics(RUN).inputs.single { it.displayName == "가**" }
        assertEquals("CONFIRMED", input.interpretation)
        assertEquals("SUPERSEDED", input.checks.single().currentStatus)
        assertEquals("old input 1", input.checks.single().rawText)
        assertNull(
            input.checks
                .single()
                .attempts
                .single()
                .classification,
        )
    }

    @Test
    fun `ODP07 same version text from another owner cannot attach a foreign accepted check`() {
        room(FOREIGN_ROOM)
        member(99, FOREIGN_ROOM)
        candidate(5099, 99, "foreign private draft", accepted = version(1), roomId = FOREIGN_ROOM)
        val view = diagnostics(RUN)
        assertTrue(view.inputs.all { it.checks.isEmpty() })
        assertFalse(view.toString().contains("foreign private draft"))
    }

    @Test
    fun `ODP08 V1 parent question is joined by scoped ancestry and actual choice without replacing consumed input`() {
        val question = """{"id":"${uuid(
            801,
        )}","prompt":"실제 질문","sourceExcerpt":"original","choices":[{"id":"A","label":"실제 선택지","proposedText":"old input 1"}]}"""
        candidate(5001, 1, "original before replacement", status = "SUPERSEDED", question = question)
        candidate(
            5002,
            1,
            "old input 1",
            revision = 2,
            accepted = version(1),
            parent = uuid(5001),
            sourceQuestion = uuid(801),
            choice = "A",
        )
        val input = diagnostics(RUN).inputs.single { it.displayName == "가**" }
        assertEquals("old input 1", input.rawText)
        assertEquals(setOf("original before replacement", "old input 1"), input.checks.map { it.rawText }.toSet())
        val parent = input.checks.single { it.rawText == "original before replacement" }
        val actual = assertNotNull(parent.question)
        assertEquals("실제 질문", actual.prompt)
        assertEquals("old input 1", actual.answer)
        assertTrue(actual.consumed)
        assertEquals(listOf("실제 선택지"), actual.choices)
    }

    @Test
    fun `ODP09 an ancestry pointer cannot read another participant question`() {
        candidate(5002, 2, "private other participant ancestor", status = "SUPERSEDED")
        candidate(5001, 1, "old input 1", accepted = version(1), parent = uuid(5002))
        val input = diagnostics(RUN).inputs.single { it.displayName == "가**" }
        assertEquals(listOf("old input 1"), input.checks.map { it.rawText })
    }

    @Test
    fun `ODP10 cycles are bounded and cannot duplicate input history`() {
        candidate(5001, 1, "old input 1", accepted = version(1))
        jdbc.update("UPDATE precheck_candidates SET source_candidate_id=id WHERE id=?", uuid(5001))
        val input = diagnostics(RUN).inputs.single { it.displayName == "가**" }
        assertEquals(1, input.checks.size)
        assertTrue(input.checksTruncated)
    }

    @Test
    fun `ODP11 execution attempts expose their bounded limit and retain unknown classification`() {
        candidate(5001, 1, "old input 1", accepted = version(1))
        for (generation in 1..9) execution(5001, generation)
        val check =
            diagnostics(RUN)
                .inputs
                .single { it.displayName == "가**" }
                .checks
                .single()
        assertEquals(8, check.attempts.size)
        assertTrue(check.attemptsTruncated)
        assertEquals(
            8,
            check.attempts
                .map { it.generation }
                .toSet()
                .size,
        )
        assertTrue(check.attempts.all { it.classification == null })
    }

    @Test
    fun `ODP12 participant overflow is explicit and does not omit the cap indicator`() {
        for (n in 3..51) member(n)
        val view = assertNotNull(repository.diagnostics(ROOM, null))
        assertEquals(50, view.inputs.size)
        assertEquals(
            50,
            view.inputs
                .map { it.alias }
                .toSet()
                .size,
        )
        assertTrue(view.inputsTruncated)
    }

    @Test
    fun `ODP13 recommendations return only positively stored members and preserve pending place verification`() {
        member(3)
        jdbc.update("INSERT INTO submission_batch_items(batch_id,submission_version_id) VALUES (?,?)", BATCH, version(3))
        recommendation(1, pendingPlace = true)
        val view = diagnostics(RUN)
        val result = view.results.single()
        assertEquals(AT.plusSeconds(3600), result.startAt)
        assertEquals(AT.plusSeconds(7200), result.endAt)
        assertEquals("IN_PERSON", result.meetingMode)
        assertEquals("합성 장소 [이메일 숨김]", result.placeName)
        assertTrue(result.placeUnverified)
        assertEquals(
            view.inputs
                .filter { it.displayName != "사**" }
                .map { it.alias }
                .toSet(),
            result.availableAliases.toSet(),
        )
        assertFalse(result.availableAliases.contains(view.inputs.single { it.displayName == "사**" }.alias))
        assertFalse(view.resultsTruncated)
    }

    @Test
    fun `ODP14 stored result windows are capped without hiding further alternatives`() {
        for (n in 1..21) recommendation(n)
        val view = diagnostics(RUN)
        assertEquals(20, view.results.size)
        assertTrue(view.resultsTruncated)
        assertTrue(view.results.all { it.availableAliases.size == 2 })
    }

    @Test
    fun `ODP15 completed no match and queued execution remain different durable states`() {
        val completed = diagnostics(RUN)
        assertEquals("COMPLETED", completed.analysis!!.status)
        assertTrue(completed.results.isEmpty())
        run(uuid(22), uuid(122), listOf(version(1), version(2)), status = "QUEUED")
        val queued = diagnostics(uuid(22))
        assertEquals("QUEUED", queued.analysis!!.status)
        assertTrue(queued.results.isEmpty())
        assertEquals(2, queued.inputs.size)
    }

    @Test
    fun `ODP16 repeated diagnostic reads preserve input history results budgets and outbox byte values`() {
        candidate(5001, 1, "old input 1", accepted = version(1))
        execution(5001, 1)
        recommendation(1)
        val before = storedRows()
        val first = diagnostics(RUN)
        assertEquals(first, diagnostics(RUN))
        repository.analyses(ROOM, 20, null)
        repository.diagnostics(ROOM, null)
        assertEquals(before, storedRows())
    }

    @Test
    fun `ODP17 explicit OpenAI first invocation is an initial choice rather than an invented Gemini fallback`() {
        invocation(listOf("OPENAI"), "c5-residual-luna-v2")
        val detail = assertNotNull(operations.invocationDetail(uuid(6001)))
        assertEquals(0, detail.invocation.geminiAdmittedAttempts)
        assertEquals(listOf("INITIAL"), detail.attempts.map { it.role })
    }

    @Test
    fun `ODP18 recorded Gemini retry and OpenAI fallback remain separate roles`() {
        invocation(listOf("GEMINI", "GEMINI", "OPENAI"), "bounded-luna-v1")
        val detail = assertNotNull(operations.invocationDetail(uuid(6001)))
        assertEquals(listOf("INITIAL", "RETRY", "FALLBACK"), detail.attempts.map { it.role })
        assertEquals(3L, detail.invocation.transportUnknownAttemptCount)
        assertTrue(detail.attempts.all { it.parserDispatched == null && it.clientCalls == null })
    }

    @Test
    fun `ODP19 a legacy answer linked by saved version is consumed while its original remains explicit`() {
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='LEGACY' WHERE id=?", ROOM)
        jdbc.update(
            """INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at)
                VALUES (?,?,2,'actual replacement','ko-KR',?)""",
            uuid(4001),
            head(1),
            ts(360),
        )
        jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", uuid(4001), head(1))
        val proposal =
            mapper.writeValueAsString(
                mapOf(
                    "excerpt" to "old input",
                    "question" to "actual legacy question",
                    "optionA" to "actual replacement",
                    "optionB" to "other replacement",
                ),
            )
        jdbc.update(
            """INSERT INTO input_revision_rounds(id,room_id,source_run_id,reopen_request_id,
                generation,expected_generation,status,opened_at)
                VALUES (?,?,?,?,1,0,'OPEN',?)""",
            uuid(7001),
            ROOM,
            RUN,
            uuid(7002),
            ts(350),
        )
        val response =
            mapper.writeValueAsString(
                ClarificationAnswerView(
                    "ANSWERED",
                    SubmissionView(2, "actual replacement", emptyList(), "ko-KR", AT.plusSeconds(360), true, uuid(7001), 1),
                ),
            )
        jdbc.update(
            """INSERT INTO submission_clarifications(question_id,source_version_id,proposal,created_at,request_id,
                revision_round_id,expected_revision,answer_choice,direct_text,saved_version_id,response,answered_at)
                VALUES (?,?,CAST(? AS jsonb),?,?,?,?, 'DIRECT',?,?,CAST(? AS jsonb),?)""",
            uuid(7010),
            version(1),
            proposal,
            ts(310),
            uuid(7020),
            uuid(7001),
            1,
            "actual replacement",
            uuid(4001),
            response,
            ts(360),
        )
        val oldQuestion =
            diagnostics(RUN)
                .inputs
                .single { it.displayName == "가**" }
                .legacyQuestions
                .single()
        assertEquals("actual legacy question", oldQuestion.prompt)
        assertEquals("old input 1", oldQuestion.sourceRawText)
        assertEquals("actual replacement", oldQuestion.answer)
        assertEquals(AT.plusSeconds(360), oldQuestion.answeredAt)
        assertFalse(oldQuestion.consumed)
        run(uuid(22), uuid(122), listOf(uuid(4001), version(2)), fixedAt = 400)
        jdbc.update(
            """UPDATE input_revision_rounds SET status='CONSUMED',consumed_at=?,analyze_request_id=?,
                force_reparse=false,resolved_run_id=?,outcome='QUEUED' WHERE id=?""",
            ts(400),
            uuid(7030),
            uuid(22),
            uuid(7001),
        )
        jdbc.update("UPDATE meeting_rooms SET active_run_id=?,closed_at=? WHERE id=?", uuid(22), ts(400), ROOM)
        val descendant = diagnostics(uuid(22)).inputs.single { it.displayName == "가**" }
        assertEquals("actual replacement", descendant.rawText)
        assertEquals("old input 1", descendant.legacyQuestions.single().sourceRawText)
        assertTrue(descendant.legacyQuestions.single().consumed)
    }

    @Test
    fun `ODP20 a strictly stored C5 declaration remains visible after its analysis becomes inactive without recalculation`() {
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions CASCADE")
        H.seed(jdbc, mapper, S.input())
        val declaration =
            StrictScheduleDslStructuralResponseParser(mapper, allowScopedTermEvidence = true).parse(
                H.json(S.document()),
                S.snapshot(),
                "schedule-dsl-response-5",
                "schedule-ir-product-6",
                SCHEDULE_AST_VERSION,
                "ko-time-terms-1",
            )
        JdbcScheduleDslStructuralRepository(jdbc, mapper).publish(declaration, 1, FinalAnalysisRouteTestFixture.selectedAt)
        val native = FinalAnalysisRouteTestFixture.ir
        jdbc.update("UPDATE coordination_runs SET status='COMPLETED',candidate_quality='COMPLETE',version=2 WHERE id=?", native.run)
        jdbc.update(
            "INSERT INTO coordination_runs(id,room_id,batch_id,status,created_at) VALUES (?,?,?,'QUEUED',?)",
            uuid(998),
            native.room,
            native.batch,
            Timestamp.from(FinalAnalysisRouteTestFixture.selectedAt.plusSeconds(1)),
        )
        jdbc.update("UPDATE meeting_rooms SET active_run_id=? WHERE id=?", uuid(998), native.room)
        val before = storedRows()
        val view = assertNotNull(repository.diagnostics(native.room, native.run))
        assertFalse(assertNotNull(view.analysis).active)
        val input = view.inputs.single { it.rawText == S.RAW }
        val stored = assertNotNull(input.storedInterpretation)
        assertEquals("STORED_DECLARATION", stored.coverage)
        assertTrue(stored.conditions.any { it.effect == "AVAILABLE" && it.interpretation == "EXPLICIT" })
        assertFalse(stored.truncated)
        assertEquals(0, count("analysis_invocations"))
        assertEquals(before, storedRows())
    }

    @Test
    fun `ODP21 an OPEN revision row remains visible even when its active room pointer is absent`() {
        jdbc.update(
            """INSERT INTO input_revision_rounds(id,room_id,source_run_id,reopen_request_id,
                generation,expected_generation,status,opened_at) VALUES (?,?,?,?,1,0,'OPEN',?)""",
            uuid(7001),
            ROOM,
            RUN,
            uuid(7002),
            ts(350),
        )
        val room = assertNotNull(operations.roomDetail(ROOM, 20, null)).room
        assertTrue(room.hasOpenRevisionRound)
        assertNull(jdbc.queryForObject("SELECT active_revision_round_id FROM meeting_rooms WHERE id=?", UUID::class.java, ROOM))
    }

    @Test
    fun `ODP22 room list and detail expose the redacted operational title without altering the stored purpose`() {
        val expected = "합성 모임 [이메일 숨김]"
        assertEquals(
            expected,
            operations
                .listRooms(20, null)
                .items
                .single()
                .title,
        )
        assertEquals(expected, assertNotNull(operations.roomDetail(ROOM, 20, null)).room.title)
        assertEquals(
            "합성 모임 synthetic@example.test",
            jdbc.queryForObject("SELECT purpose FROM meeting_rooms WHERE id=?", String::class.java, ROOM),
        )
    }

    @Test
    fun `ODP23 long acyclic ancestry is explicitly capped at eight checked inputs`() {
        for (revision in 1..9) {
            candidate(
                5000 + revision,
                1,
                if (revision == 9) "old input 1" else "prior checked input $revision",
                revision = revision,
                accepted = if (revision == 9) version(1) else null,
                parent = if (revision == 1) null else uuid(4999 + revision),
                status = if (revision == 9) "CLEAR" else "SUPERSEDED",
            )
        }
        val input = diagnostics(RUN).inputs.single { it.displayName == "가**" }
        assertEquals(8, input.checks.size)
        assertEquals(
            8,
            input.checks
                .map { it.rawText }
                .toSet()
                .size,
        )
        assertTrue(input.checksTruncated)
    }

    private fun diagnostics(runId: UUID) = assertNotNull(repository.diagnostics(ROOM, runId))

    private fun room(roomId: UUID) {
        jdbc.update(
            """INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
                search_range_source,manual_only,collection_status,closure_reason,closed_at,created_at)
                VALUES (?,?,?,'EITHER','Asia/Seoul','2027-01-04','2027-01-11','HOST_SPECIFIED',true,'CLOSED','MANUAL',?,?)""",
            roomId,
            roomId.toString().replace("-", "").takeLast(22),
            "합성 모임 synthetic@example.test",
            ts(100),
            ts(0),
        )
    }

    private fun member(
        n: Int,
        roomId: UUID = ROOM,
    ) {
        jdbc.update(
            "INSERT INTO guest_browser_sessions(id,credential_digest,expires_at,created_at) VALUES (?,?,?,?)",
            uuid(10000 + n),
            "synthetic-diagnostic-$n",
            ts(3600),
            ts(0),
        )
        val name =
            when (n) {
                1 -> "가나다"
                2 -> "라마바"
                3 -> "사아자"
                else -> "합성$n"
            }
        jdbc.update(
            "INSERT INTO participants(id,room_id,guest_session_id,role,display_name,joined_at) VALUES (?,?,?,? ,?,?)",
            participant(n),
            roomId,
            uuid(
                10000 + n,
            ),
            if (n == 1 || n == 99) "HOST" else "MEMBER",
            name,
            ts(0),
        )
        jdbc.update("INSERT INTO submission_heads(id,room_id,participant_id) VALUES (?,?,?)", head(n), roomId, participant(n))
        jdbc.update(
            "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at) VALUES (?,?,1,?,'ko-KR',?)",
            version(n),
            head(n),
            "old input $n",
            ts(10),
        )
        jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", version(n), head(n))
    }

    private fun newerVersion(
        n: Int,
        text: String,
    ) {
        jdbc.update(
            "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at) VALUES (?,?,2,?,'ko-KR',?)",
            uuid(
                4000 + n,
            ),
            head(n),
            text,
            ts(200),
        )
        jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", uuid(4000 + n), head(n))
    }

    private fun run(
        runId: UUID,
        batchId: UUID,
        versions: List<UUID>,
        roomId: UUID = ROOM,
        status: String = "COMPLETED",
        fixedAt: Long = 300,
    ) {
        jdbc.update("INSERT INTO submission_batches(id,room_id,fixed_at) VALUES (?,?,?)", batchId, roomId, ts(fixedAt))
        versions.forEach { jdbc.update("INSERT INTO submission_batch_items(batch_id,submission_version_id) VALUES (?,?)", batchId, it) }
        jdbc.update(
            "INSERT INTO coordination_runs(id,room_id,batch_id,status,candidate_quality,created_at) VALUES (?,?,?,?,?,?)",
            runId,
            roomId,
            batchId,
            status,
            if (status ==
                "COMPLETED"
            ) {
                "COMPLETE"
            } else {
                null
            },
            ts(fixedAt + 1),
        )
    }

    private fun candidate(
        id: Int,
        n: Int,
        text: String,
        revision: Int = 1,
        accepted: UUID? = null,
        parent: UUID? = null,
        sourceQuestion: UUID? = null,
        choice: String? = null,
        roomId: UUID = ROOM,
        status: String = "CLEAR",
        question: String? = null,
    ) {
        jdbc.update(
            """INSERT INTO precheck_candidates(id,room_id,participant_id,revision,raw_text,locale,created_at,
                interpretation_reference_at,owner_binding,accepted_version_id,source_candidate_id,source_question_id,selected_choice_id)
                VALUES (?,?,?,?,?,'ko-KR',?,?,'synthetic-private-owner',?,?,?,?)""",
            uuid(id),
            roomId,
            participant(n),
            revision,
            text,
            ts(10 + revision.toLong()),
            ts(0),
            accepted,
            parent,
            sourceQuestion,
            choice,
        )
        jdbc.update(
            """INSERT INTO precheck_checks(id,candidate_id,status,version,generation,retry_count,deadline_at,question)
                VALUES (?,?,?,1,1,0,?,CAST(? AS jsonb))""",
            uuid(
                id + 10000,
            ),
            uuid(id),
            status,
            ts(40),
            question,
        )
    }

    private fun execution(
        candidateId: Int,
        generation: Int,
    ) {
        jdbc.update(
            """INSERT INTO precheck_executions(check_id,generation,queued_at,deadline_at,started_at,finished_at,provider,model)
                VALUES (?,?,?,?,?,?,'JEV','synthetic-classifier')""",
            uuid(candidateId + 10000),
            generation,
            ts(generation.toLong()),
            ts(generation + 30L),
            ts(generation + 1L),
            ts(generation + 2L),
        )
    }

    private fun recommendation(
        n: Int,
        pendingPlace: Boolean = false,
    ) {
        jdbc.update(
            """INSERT INTO recommendation_analyses(coordination_run_id,room_id,protocol)
                VALUES (?,?,'diverse-time-v1') ON CONFLICT DO NOTHING""",
            RUN,
            ROOM,
        )
        val option = uuid(20000 + n)
        val variant = uuid(21000 + n)
        jdbc.update(
            "INSERT INTO recommendation_options(id,coordination_run_id,room_id,ordinal,start_at,end_at) VALUES (?,?,?,?,?,?)",
            option,
            RUN,
            ROOM,
            n - 1,
            ts(n * 3600L),
            ts(n * 3600L + 3600),
        )
        jdbc.update(
            """INSERT INTO recommendation_variants(id,option_id,coordination_run_id,room_id,meeting_mode,place_name,preference_count)
                VALUES (?,?,?,?,?,?,0)""",
            variant,
            option,
            RUN,
            ROOM,
            if (pendingPlace) "IN_PERSON" else "REMOTE",
            if (pendingPlace) "합성 장소 synthetic@example.test" else null,
        )
        for (member in 1..2) {
            jdbc.update(
                """INSERT INTO recommendation_variant_participants(variant_id,option_id,coordination_run_id,room_id,
                    participant_id,place_unverified) VALUES (?,?,?,?,?,?)""",
                variant,
                option,
                RUN,
                ROOM,
                participant(member),
                pendingPlace && member == 1,
            )
        }
    }

    private fun invocation(
        providers: List<String>,
        policy: String,
    ) {
        val id = uuid(6001)
        jdbc.update(
            """INSERT INTO analysis_invocations(id,coordination_run_id,run_version,started_at,deadline_at,owner_token,
                gemini_attempts,luna_attempts,policy_version) VALUES (?,?,0,?,?,?,?,?,?)""",
            id,
            RUN,
            ts(350),
            ts(410),
            uuid(6002),
            providers.count { it == "GEMINI" },
            providers.count { it == "OPENAI" },
            policy,
        )
        providers.forEachIndexed { index, provider ->
            jdbc.update(
                """INSERT INTO coordination_attempts(id,coordination_run_id,attempt_number,started_at,finished_at,
                    provider,model,policy_version,invocation_id) VALUES (?,?,?,?,?,?,?,?,?)""",
                uuid(6100 + index),
                RUN,
                index + 1,
                ts(351 + index.toLong()),
                ts(352 + index.toLong()),
                provider,
                if (provider == "GEMINI") "gemini-3.8-flash" else "gpt-6-luna",
                policy,
                id,
            )
        }
    }

    private fun storedRows(): Map<String, List<String>> =
        listOf(
            "meeting_rooms",
            "participants",
            "submission_heads",
            "submission_versions",
            "submission_batches",
            "submission_batch_items",
            "precheck_candidates",
            "precheck_checks",
            "precheck_executions",
            "ai_room_budgets",
            "outbox_events",
            "coordination_runs",
            "recommendation_options",
            "recommendation_variants",
            "recommendation_variant_participants",
            "schedule_ir_envelopes",
            "schedule_ir_inputs",
        ).associateWith { table ->
            jdbc
                .queryForList(
                    "SELECT to_jsonb(t)::text FROM $table t ORDER BY to_jsonb(t)::text",
                    String::class.java,
                ).map { requireNotNull(it) }
        }

    private fun assertNoContacts(value: OperatorRoomDiagnostics) {
        val json = mapper.writeValueAsString(value)
        assertFalse(json.contains("synthetic@example.test"))
        assertFalse(json.contains("010-1234-5678"))
        assertFalse(json.contains("synthetic-private-owner"))
    }

    private fun count(table: String): Int = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private fun participant(n: Int) = uuid(1000 + n)

    private fun head(n: Int) = uuid(2000 + n)

    private fun version(n: Int) = uuid(3000 + n)

    private fun uuid(n: Int) = UUID(0, n.toLong())

    private fun ts(seconds: Long) = Timestamp.from(AT.plusSeconds(seconds))

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }
        private val AT = Instant.parse("2027-01-04T00:00:00Z")
        private val ROOM = UUID(0, 1)
        private val FOREIGN_ROOM = UUID(0, 2)
        private val BATCH = UUID(0, 11)
        private val RUN = UUID(0, 21)

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
