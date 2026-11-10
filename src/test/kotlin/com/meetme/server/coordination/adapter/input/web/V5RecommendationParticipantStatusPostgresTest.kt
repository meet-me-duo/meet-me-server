package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.adapter.output.integration.WeeklyEvidenceProviderContract
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.PlaceSearchPort
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderPort
import com.meetme.server.coordination.application.service.FrozenSubmissionReader
import com.meetme.server.coordination.application.service.MatchingProcessingPersistenceService
import com.meetme.server.coordination.application.service.MatchingProcessor
import com.meetme.server.coordination.application.service.ScheduleDslStoredReadService
import com.meetme.server.coordination.application.service.ScheduleEvidenceSnapshotFactory
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralMatchingPolicy
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.testsupport.AccountPrincipalTestFixture
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslStructuralRepository
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.submission.domain.dsl.ServerEvidenceKind
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.util.AopTestUtils
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Frozen synthetic V5 source -> strict canonical format12 -> JDBC -> authenticated public HTTP. No model actor. */
@Import(V5RecommendationParticipantStatusPostgresTest.StatusClock::class)
class V5RecommendationParticipantStatusPostgresTest : RecommendationPostgresFixture() {
    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var rooms: MeetingRoomRepository

    @Autowired private lateinit var sources: SubmissionRepository

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var flat: StructuredSubmissionRepository

    @Autowired private lateinit var publisher: MatchingProcessingPersistenceService

    @MockitoBean(name = "selectedScheduleDslGeminiProvider")
    private lateinit var selectedGemini: SelectedScheduleDslProviderPort

    @MockitoBean(name = "selectedScheduleDslLunaProvider")
    private lateinit var selectedLuna: SelectedScheduleDslProviderPort

    @MockitoBean(name = "geminiNaturalLanguageParserAdapter")
    private lateinit var legacyGemini: NaturalLanguageParserPort

    @MockitoBean(name = "lunaParser")
    private lateinit var legacyLuna: NaturalLanguageParserPort

    @MockitoBean private lateinit var maps: PlaceSearchPort

    @MockitoBean private lateinit var matcher: MatchingProcessor

    @MockitoSpyBean private lateinit var storedReader: ScheduleDslStoredReadService
    private lateinit var seeded: CompletedCorrectionFixture

    @Test
    fun `VPS01 native source distinguishes duplicate identities partial windows unknown time and mode`() {
        val f = seed()
        assertEquals(12, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM structured_submission_results", Int::class.java))
        val view = recommendations(f.host, "conditional-v1")
        val full = fullOption(view)
        assertRows(full, travel = false)
        val rows = items(items(full, "variants").first(), "participant_statuses")
        assertEquals(5, rows.size)
        assertEquals(5, rows.map { it["participant_ref"] }.distinct().size)
        rows.forEach { assertTrue(it.getValue("participant_ref").toString().matches(Regex("p[1-9][0-9]*"))) }
        assertEquals(2, rows.count { it["display_name"] == "동명" })
        for (row in rows) assertEquals(setOf("participant_ref", "display_name", "status"), row.keys)
        val public = view.toString()
        for (id in PARTICIPANTS + VERSIONS + HEADS) assertFalse(public.contains(id.toString()))
        for (text in rawTexts()) assertFalse(public.contains(text))
        assertFalse(public.contains("input_evidence"))
        assertFalse(public.contains("schedule-ir-product"))
        assertEquals(view, recommendations(f.member, "conditional-v1"))
    }

    @Test
    fun `VPS02 contradictory flat structured rows cannot replace a restored V5 native interpretation`() {
        val f = seed()
        val before = recommendations(f.host, "conditional-v1")
        val run = requireNotNull(runs.findById(CoordinationRunId(RUN)))
        flat.replaceForBatch(
            run.batch.id,
            VERSIONS.map { version ->
                StructuredSubmissionResult(
                    com.meetme.server.shared.domain
                        .SubmissionVersionId(version),
                    listOf(StructuredCondition.TimeWindow(TimePolarity.UNAVAILABLE, DATE, null, LocalTime.MIN, LocalTime.MIN, true)),
                    "AMBIGUOUS_INPUT",
                )
            },
            AT,
        )
        val saved = snapshots()
        assertEquals(before, recommendations(f.host, "conditional-v1"))
        assertEquals(saved, snapshots())
        assertRows(fullOption(before), travel = false)
    }

    @Test
    fun `VPS03 late membership and a later source head preserve frozen batch statuses`() {
        val f = seed()
        val before = recommendations(f.host, "conditional-v1")
        val newer = UUID(0, 79999)
        jdbc.update(
            "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at) VALUES (?,?,2,?,'ko-KR',?)",
            newer,
            HEADS[2],
            "synthetic later private head 2027-01-07 18:00~22:00",
            Timestamp.from(AT.plusSeconds(1)),
        )
        jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", newer, HEADS[2])
        val late = accounts.newAccount("late")
        jdbc.update(
            "INSERT INTO participants(id,room_id,user_id,role,display_name,joined_at) VALUES (?,?,?,'MEMBER',?,?)",
            UUID(0, 79998),
            ROOM,
            UUID.fromString(late.value.removePrefix(AccountPrincipalTestFixture.PREFIX)),
            "late unsubmitted person",
            Timestamp.from(AT.plusSeconds(1)),
        )
        val saved = snapshots()
        assertEquals(before, recommendations(f.host, "conditional-v1"))
        assertEquals(saved, snapshots())
        assertFalse(before.toString().contains("late unsubmitted person"))
        assertRows(fullOption(before), travel = false)
    }

    @Test
    fun `VPS04 primary and alternatives keep full window mode and stable public references`() {
        val f = seed()
        val primary = recommendations(f.host, "conditional-v1")
        val all = items(primary, "options").toMutableList()
        var cursor: String? = null
        do {
            val page =
                document(
                    mvc
                        .perform(alternatives(f, cursor, limit = 1, attendanceSupport = "conditional-v1"))
                        .andExpect(status().isOk)
                        .andReturn()
                        .response,
                )
            all += items(page, "options")
            cursor = page["next_cursor"] as String?
        } while (cursor != null)
        assertEquals((primary.getValue("total_options") as Number).toInt(), all.size)
        assertTrue(all.size > 3, "Actual alternative paging must execute")
        var referenceByPerson: Map<String, Any?>? = null
        var duplicateReferences: Set<Any?>? = null
        for (option in all) {
            assertRows(option, travel = false)
            for (variant in items(option, "variants")) {
                val rows = items(variant, "participant_statuses")
                val current =
                    rows
                        .filter {
                            it["display_name"] != "동명"
                        }.associate { it["display_name"].toString() to it["participant_ref"] }
                if (referenceByPerson == null) referenceByPerson = current else assertEquals(referenceByPerson, current)
                assertEquals(5, rows.map { it["participant_ref"] }.distinct().size)
                val duplicates = rows.filter { it["display_name"] == "동명" }.map { it["participant_ref"] }.toSet()
                assertEquals(2, duplicates.size)
                if (duplicateReferences == null) duplicateReferences = duplicates else assertEquals(duplicateReferences, duplicates)
            }
        }
    }

    @Test
    fun `VPS05 unverified travel affects only its owner in person and preserves time options`() {
        val f = seed(travel = true)
        val view = recommendations(f.host, "conditional-v1")
        val full = fullOption(view)
        assertRows(full, travel = true)
        val variants = items(full, "variants")
        assertEquals(setOf("REMOTE", "IN_PERSON"), variants.map { it["meeting_mode"] }.toSet())
        val inPerson = variants.single { it["meeting_mode"] == "IN_PERSON" }
        assertEquals(false, inPerson["attendance_confirmed"])
        assertEquals(2, (inPerson.getValue("attendance_count") as Number).toInt())
        assertEquals(
            2,
            (inPerson.getValue("available_participant_names") as List<*>).size,
            "Membership names remain the published time-match cohort",
        )
        assertEquals(
            2,
            items(inPerson, "participant_statuses").count {
                it["status"] == "UNKNOWN"
            },
            "Only travel owner and independently unknown time owner",
        )
    }

    @Test
    fun `VPS06 selecting a shorter interval never changes statuses for the whole published option`() {
        val f = seed()
        val before = recommendations(f.host, "conditional-v1")
        val option = fullOption(before)
        val variant = items(option, "variants").single { it["meeting_mode"] == "REMOTE" }
        val beforeRows = items(variant, "participant_statuses")
        assertEquals("UNAVAILABLE", beforeRows.single { it["display_name"] == "부분시간" }["status"])
        mvc
            .perform(
                selection(
                    f,
                    option,
                    variant,
                    start = "2027-01-07T10:00:00Z",
                    end = "2027-01-07T11:00:00Z",
                    attendanceSupport = "conditional-v1",
                ),
            ).andExpect(status().isOk)
        val after = recommendations(f.host, "conditional-v1")
        assertEquals((before.getValue("state_version") as Number).toLong() + 1, (after.getValue("state_version") as Number).toLong())
        assertEquals(before.filterKeys { it != "state_version" }, after.filterKeys { it != "state_version" })
        assertEquals(
            beforeRows,
            items(items(fullOption(after), "variants").single { it["meeting_mode"] == "REMOTE" }, "participant_statuses"),
        )
    }

    @Test
    fun `VPS07 repeated GETs never write regenerate recommendations parse or call maps`() {
        val f = seed(travel = true)
        val saved = snapshots()
        val first = recommendations(f.host, "conditional-v1")
        assertRows(fullOption(first), travel = true)
        assertEquals(first, recommendations(f.host, "conditional-v1"))
        assertEquals(first, recommendations(f.member, "conditional-v1"))
        mvc.perform(alternatives(f, attendanceSupport = "conditional-v1")).andExpect(status().isOk)
        assertEquals(saved, snapshots())
        verifyNoInteractions(selectedGemini, selectedLuna, legacyGemini, legacyLuna, maps, matcher)
        val readerTarget = AopTestUtils.getUltimateTargetObject<ScheduleDslStoredReadService>(storedReader)
        assertTrue(
            mockingDetails(readerTarget).invocations.none { it.method.name == "readStored" },
            "GET status projection must not run the full recommendation calculator",
        )
    }

    @Test
    fun `VPS08 current authentication and stale analysis failures disclose no participant diagnostics`() {
        val f = seed()
        val saved = snapshots()
        mvc.perform(get("/api/rooms/{code}/recommendations", CODE)).andExpect(status().isUnauthorized)
        val outsider = accounts.newAccount("outside")
        mvc.perform(get("/api/rooms/{code}/recommendations", CODE).cookie(outsider)).andExpect(status().isForbidden)
        mvc.perform(alternatives(f, analysis = UUID.randomUUID(), attendanceSupport = "conditional-v1")).andExpect(status().isConflict)
        // The account fixture itself is the sole extra row; public failures create no analysis or output writes.
        assertEquals(saved.filterKeys { it != "account_users" }, snapshots().filterKeys { it != "account_users" })
        verifyNoInteractions(selectedGemini, selectedLuna, legacyGemini, legacyLuna, maps, matcher)
    }

    @Test
    fun `VPS09 invalid native storage propagates failure without invented unknown rows`() {
        val f = seed()
        jdbc.update(
            "UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,'{evidenceCatalogVersion}',to_jsonb(CAST(? AS text)))",
            "forged",
        )
        val saved = snapshots()
        assertNativeReadFailure(f)
        assertEquals(saved, snapshots())
    }

    @Test
    fun `VPS10 PostgreSQL native read outage propagates without unknown rows`() {
        val f = seed()
        val saved = snapshots()
        jdbc.execute("ALTER TABLE schedule_ir_envelopes RENAME TO status_test_unavailable_envelopes")
        try {
            assertNativeReadFailure(f)
        } finally {
            jdbc.execute("ALTER TABLE status_test_unavailable_envelopes RENAME TO schedule_ir_envelopes")
        }
        assertEquals(saved, snapshots())
    }

    private fun assertNativeReadFailure(f: CompletedCorrectionFixture) {
        val result =
            runCatching {
                mvc
                    .perform(
                        get(
                            "/api/rooms/{code}/recommendations",
                            CODE,
                        ).cookie(f.host.cookie()).param("attendance_support", "conditional-v1"),
                    ).andReturn()
                    .response
            }
        assertTrue(
            result.isFailure || result.getOrThrow().status !in 200..299,
            "Native corruption/outage must propagate instead of a successful UNKNOWN response",
        )
        result.getOrNull()?.let { assertFalse(it.contentAsString.contains("participant_statuses")) }
        verifyNoInteractions(selectedGemini, selectedLuna, legacyGemini, legacyLuna, maps, matcher)
    }

    private fun assertRows(
        option: Map<String, Any?>,
        travel: Boolean,
    ) {
        val range = child(option, "time_range")
        val partialCovered = range["start_at"] == "2027-01-07T10:00:00Z" && range["end_at"] == "2027-01-07T11:00:00Z"
        val january7 = range.getValue("start_at").toString().startsWith("2027-01-07")
        for (variant in items(option, "variants")) {
            val rows = items(variant, "participant_statuses")
            assertEquals(5, rows.size)
            val mode = variant["meeting_mode"]
            assertEquals(if (partialCovered) "AVAILABLE" else "UNAVAILABLE", rows.single { it["display_name"] == "부분시간" }["status"])
            assertEquals("UNKNOWN", rows.single { it["display_name"] == "시간미정" }["status"])
            assertEquals(
                if (january7 &&
                    mode == "IN_PERSON"
                ) {
                    "UNAVAILABLE"
                } else {
                    "AVAILABLE"
                },
                rows.single { it["display_name"] == "온라인제한" }["status"],
            )
            if (travel) {
                assertEquals("AVAILABLE", rows.single { it["display_name"] == "동명" }["status"])
                assertEquals(
                    if (mode == "IN_PERSON") "UNKNOWN" else "AVAILABLE",
                    rows.single { it["display_name"] == "이동조건" }["status"],
                )
            } else {
                assertEquals(listOf("AVAILABLE", "AVAILABLE"), rows.filter { it["display_name"] == "동명" }.map { it["status"] })
            }
        }
    }

    private fun fullOption(view: Map<String, Any?>): Map<String, Any?> {
        fun matches(option: Map<String, Any?>): Boolean =
            child(option, "time_range")["start_at"] == "2027-01-07T09:00:00Z" &&
                child(option, "time_range")["end_at"] == "2027-01-07T13:00:00Z"
        items(view, "options").singleOrNull(::matches)?.let { return it }
        var cursor: String? = null
        do {
            val page =
                document(
                    mvc
                        .perform(alternatives(seeded, cursor, limit = 100, attendanceSupport = "conditional-v1"))
                        .andExpect(status().isOk)
                        .andReturn()
                        .response,
                )
            items(page, "options").singleOrNull(::matches)?.let { return it }
            cursor = page["next_cursor"] as String?
        } while (cursor != null)
        error("The actual persisted full Jan7 window is required regardless of primary rank")
    }

    private fun seed(travel: Boolean = false): CompletedCorrectionFixture {
        val cookies = NAMES.map(accounts::newAccount)
        jdbc.update(
            """
            INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
            search_range_source,manual_only,collection_status,closure_reason,closed_at,created_at,submission_protocol)
            VALUES (?,?,'synthetic public status','EITHER','Asia/Seoul','2027-01-04','2027-01-13','HOST_SPECIFIED',true,
            'CLOSED','MANUAL',?,?,'LEGACY')
            """.trimIndent(),
            ROOM,
            CODE,
            Timestamp.from(AT),
            Timestamp.from(AT.minusSeconds(60)),
        )
        rawTexts(travel).forEachIndexed { index, raw ->
            jdbc.update(
                "INSERT INTO participants(id,room_id,user_id,role,display_name,joined_at) VALUES (?,?,?,?,?,?)",
                PARTICIPANTS[index],
                ROOM,
                UUID.fromString(cookies[index].value.removePrefix(AccountPrincipalTestFixture.PREFIX)),
                if (index == 0) "HOST" else "MEMBER",
                if (travel && index == 0) "이동조건" else NAMES[index],
                Timestamp.from(AT.minusSeconds(50)),
            )
            jdbc.update("INSERT INTO submission_heads(id,room_id,participant_id) VALUES (?,?,?)", HEADS[index], ROOM, PARTICIPANTS[index])
            jdbc.update(
                "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at) VALUES (?,?,1,?,'ko-KR',?)",
                VERSIONS[index],
                HEADS[index],
                raw,
                Timestamp.from(AT.minusSeconds(30)),
            )
            jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", VERSIONS[index], HEADS[index])
        }
        jdbc.update("INSERT INTO submission_batches(id,room_id,fixed_at) VALUES (?,?,?)", BATCH, ROOM, Timestamp.from(AT))
        VERSIONS.forEach { jdbc.update("INSERT INTO submission_batch_items(batch_id,submission_version_id) VALUES (?,?)", BATCH, it) }
        jdbc.update(
            "INSERT INTO coordination_runs(id,room_id,batch_id,status,created_at,version) VALUES (?,?,?,'STRUCTURING',?,1)",
            RUN,
            ROOM,
            BATCH,
            Timestamp.from(AT),
        )
        jdbc.update("UPDATE meeting_rooms SET active_run_id=? WHERE id=?", RUN, ROOM)
        val run = requireNotNull(runs.findById(CoordinationRunId(RUN)))
        val room = requireNotNull(rooms.findById(MeetingRoomId(ROOM)))
        val policy = ScheduleDslStructuralMatchingPolicy.union()
        val snapshot =
            ScheduleEvidenceSnapshotFactory().create(
                run,
                FrozenSubmissionReader.read(sources, run.batch),
                room.timeZone.value,
                room.searchRange.startInclusive,
                room.searchRange.endExclusive,
                policy.version,
            )
        val choice =
            FinalAnalysisRouteChoice.DslStructuralV5(
                IrExecutionIdentity("schedule-dsl-evidence-response-5", "schedule-ir-product-6", policy.version),
                policy,
                snapshot.context,
                MeetingMode.EITHER,
                "ko-time-terms-1",
            )
        routes.freeze(RUN, 1, choice)
        val declared =
            WeeklyEvidenceProviderContract(mapper, allowPlaceConstraints = true).parseProviderResponse(
                mapper.writeValueAsString(nativeDocument(snapshot, travel)),
                snapshot,
                "schedule-ir-product-6",
                "schedule-ast-1",
                "ko-time-terms-1",
            )
        val repository = JdbcScheduleDslStructuralRepository(jdbc, mapper)
        repository.publish(declared, 1, AT)
        val restored = assertNotNull(JdbcScheduleDslStructuralRepository(jdbc, mapper).find(snapshot.context))
        assertEquals(declared, restored)
        val calculation = ScheduleDslStructuralCalculator.calculate(restored, snapshot, policy, MeetingMode.EITHER)
        assertTrue(
            calculation.recommendations.options.any {
                it.window.startInclusive == Instant.parse("2027-01-07T09:00:00Z") &&
                    it.window.endExclusive == Instant.parse("2027-01-07T13:00:00Z")
            },
            "Synthetic full-window actor must actually be published",
        )
        val matching = run.finishStructuring()
        runs.update(matching)
        publisher.completeRecommendations(
            matching.complete(CandidateQuality.PARTIAL, emptyList()),
            emptyList(),
            calculation.recommendations,
        )
        return CompletedCorrectionFixture(
            CorrectionSession(CODE, cookies[0].value),
            CorrectionSession(CODE, cookies[1].value),
            ROOM,
            RUN,
        ).also {
            seeded =
                it
        }
    }

    private fun nativeDocument(
        snapshot: ScheduleEvidenceSnapshot,
        travel: Boolean,
    ): Map<String, Any?> {
        val catalog = ServerEvidenceCatalog.create(snapshot)

        fun pick(
            owner: Int,
            kind: ServerEvidenceKind,
            text: String? = null,
        ) = catalog.entries
            .indexOfFirst {
                it.inputId == VERSIONS[owner] &&
                    it.kind == kind &&
                    (text == null || it.text == text)
            }.also { require(it >= 0) }

        fun node(
            id: String?,
            proof: Int,
            children: List<Any> = emptyList(),
        ) = mapOf(
            "operator" to
                if (id ==
                    null
                ) {
                    "ANY_OF"
                } else {
                    "CLAIM_REF"
                },
            "claim_id" to id,
            "children" to children,
            "interpretation" to "EXPLICIT",
            "assertion" to "ASSERTED",
            "evidence" to listOf(proof),
        )
        val results =
            VERSIONS.indices.map { index ->
                val proof = pick(index, ServerEvidenceKind.CONTEXT)
                val dates = if (index == 2 || index == 3) listOf(DATE) else (0L..4L).map(DATE::plusDays)
                val conditions =
                    dates.mapIndexed { ordinal, day ->
                        val temporal =
                            if (index ==
                                3
                            ) {
                                mapOf("kind" to "UNKNOWN")
                            } else {
                                mapOf(
                                    "kind" to "BOUNDS",
                                    "local_date" to day.toString(),
                                    "date_evidence" to listOf(proof),
                                    "lower" to
                                        mapOf(
                                            "clock" to
                                                if (index ==
                                                    2
                                                ) {
                                                    "19:00"
                                                } else {
                                                    "18:00"
                                                },
                                            "clock_evidence" to
                                                listOf(pick(index, ServerEvidenceKind.CLOCK, if (index == 2) "19:00" else "18:00")),
                                        ),
                                    "upper" to
                                        mapOf(
                                            "clock" to
                                                if (index ==
                                                    2
                                                ) {
                                                    "20:00"
                                                } else {
                                                    "22:00"
                                                },
                                            "clock_evidence" to
                                                listOf(pick(index, ServerEvidenceKind.CLOCK, if (index == 2) "20:00" else "22:00")),
                                        ),
                                )
                            }
                        mapOf(
                            "id" to "c$ordinal",
                            "effect" to "AVAILABLE",
                            "interpretation" to "EXPLICIT",
                            "temporal_resolution" to if (index == 3) "NON_PRECISE" else "PRECISE_INTERVAL",
                            "evidence" to listOf(proof),
                            "assertion" to "ASSERTED",
                            "supersedes" to emptyList<String>(),
                            "temporal" to temporal,
                        )
                    }
                val refs = dates.indices.map { node("c$it", proof) }
                mapOf(
                    "input_ref" to VERSIONS[index].toString(),
                    "conditions" to conditions,
                    "program" to
                        mapOf(
                            "nodes" to emptyList<Any>(),
                            "availability_root" to
                                if (refs.size ==
                                    1
                                ) {
                                    refs.single()
                                } else {
                                    node(null, proof, refs)
                                },
                            "exclusion_root" to null,
                            "preference_root" to null,
                            "history_condition_ids" to emptyList<Any>(),
                        ),
                    "approved_terms" to emptyList<Any>(),
                    "directional_times" to emptyList<Any>(),
                    "mode_restrictions" to
                        if (index ==
                            4
                        ) {
                            listOf(
                                mapOf(
                                    "id" to "remote",
                                    "scope" to mapOf("dimension" to "TIME", "start" to "2027-01-07T18:00", "end" to "2027-01-07T22:00"),
                                    "permitted_modes" to listOf("REMOTE"),
                                    "mode_interpretation" to "EXPLICIT",
                                    "time_interpretation" to "EXPLICIT",
                                    "temporal_resolution" to "PRECISE_INTERVAL",
                                    "evidence" to listOf(proof),
                                    "assertion" to "ASSERTED",
                                    "supersedes" to emptyList<Any>(),
                                    "alternative_group" to null,
                                    "alternative" to null,
                                ),
                            )
                        } else {
                            emptyList<Any>()
                        },
                    "place_constraints" to
                        if (travel &&
                            index == 0
                        ) {
                            listOf(
                                mapOf(
                                    "id" to "travel",
                                    "kind" to "TRAVEL_TIME_LIMIT",
                                    "reference_kind" to "PUBLIC_NAMED_PLACE",
                                    "place_evidence" to pick(index, ServerEvidenceKind.PLACE, "강남역"),
                                    "evidence" to proof,
                                    "max_minutes" to 45,
                                    "travel_mode" to null,
                                    "time_scope" to "ALL_AVAILABLE_TIMES",
                                ),
                            )
                        } else {
                            emptyList<Any>()
                        },
                )
            }
        return mapOf(
            "semantic_version" to "schedule-ir-product-6",
            "ast_version" to "schedule-ast-1",
            "vocabulary_version" to "ko-time-terms-1",
            "evidence_catalog_version" to "server-evidence-catalog-1",
            "date_scope_version" to "meeting-date-scope-1",
            "calendar_facts_version" to "server-calendar-facts-2",
            "evidence_selection_version" to "server-evidence-selection-1",
            "weekly_rules_version" to "weekly-schedule-rule-1",
            "place_constraints_version" to "schedule-place-constraints-1",
            "results" to results,
        )
    }

    private fun rawTexts(travel: Boolean = false): List<String> {
        val full = (0L..4L).joinToString("\n") { "${DATE.plusDays(it)} 18:00~22:00 가능" }
        return listOf(
            full + if (travel) "\n강남역에서 45분 이내인 곳." else "",
            full,
            "2027-01-07 19:00~20:00 가능",
            "2027-01-07 시간 미정",
            full + "\n2027-01-07 18:00~22:00 온라인만 가능",
        )
    }

    private fun snapshots(): Map<String, List<String>> =
        TABLES.associateWith { table ->
            jdbc
                .queryForList(
                    "SELECT to_jsonb(t)::text FROM $table t ORDER BY to_jsonb(t)::text",
                    String::class.java,
                ).map(::requireNotNull)
        }

    @TestConfiguration
    class StatusClock {
        @Bean @Primary
        fun clock(): Clock = Clock.fixed(AT.plusSeconds(1), ZoneOffset.UTC)
    }

    companion object {
        private val ROOM = UUID(0, 71000)
        private val BATCH = UUID(0, 71001)
        private val RUN = UUID(0, 71002)
        private const val CODE = "status-v5-public-00001"
        private val DATE = LocalDate.of(2027, 1, 7)
        private val AT = Instant.parse("2027-01-04T02:00:00Z")
        private val PARTICIPANTS = (1L..5L).map { UUID(0, 71100 + it) }
        private val HEADS = (1L..5L).map { UUID(0, 71200 + it) }
        private val VERSIONS = (1L..5L).map { UUID(0, 71300 + it) }
        private val NAMES = listOf("동명", "동명", "부분시간", "시간미정", "온라인제한")
        private val TABLES =
            listOf(
                "meeting_rooms",
                "account_users",
                "participants",
                "submission_heads",
                "submission_versions",
                "submission_batches",
                "submission_batch_items",
                "coordination_runs",
                "final_analysis_routes",
                "schedule_ir_envelopes",
                "schedule_ir_inputs",
                "structured_submission_results",
                "normalized_places",
                "recommendation_analyses",
                "recommendation_options",
                "recommendation_variants",
                "recommendation_variant_participants",
                "recommendation_selections",
                "coordination_attempts",
                "analysis_invocations",
                "outbox_events",
            )
        private val postgres = mappedPortReadyPostgres().apply { start() }

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stop() = postgres.stop()
    }
}
