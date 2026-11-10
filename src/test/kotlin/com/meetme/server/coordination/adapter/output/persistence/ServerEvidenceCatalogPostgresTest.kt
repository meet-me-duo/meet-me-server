package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.service.FinalAnalysisRouteSelectionService
import com.meetme.server.coordination.application.service.FrozenSubmissionReader
import com.meetme.server.coordination.domain.analysis.FinalAnalysisContractSelection
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslStructuralRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.dsl.EvidenceLocationDerivation
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslLocatedEvidence
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.FinalAnalysisRouteTestFixture as R
import com.meetme.server.support.ScopedTermEvidenceTestFixture as S
import com.meetme.server.support.TravelPlaceTestFixture as P

/** Independent native-row expectations. A valid reference proves location, not natural-language correctness. */
@SpringBootTest
@Import(FinalAnalysisRoutePostgresTest.RouteTestClock::class)
class ServerEvidenceCatalogPostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var rooms: MeetingRoomRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var transactions: PlatformTransactionManager

    private fun input(raw: String = RAW) = S.input(raw)

    private fun snapshot(raw: String = RAW) = H.snapshot(input(raw))

    @BeforeEach
    fun seed() {
        seed(RAW)
    }

    private fun seed(raw: String) {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        H.seed(jdbc, mapper, input(raw))
    }

    private fun publish(envelope: ScheduleDslStructuralEnvelope) =
        JdbcScheduleDslStructuralRepository(jdbc, mapper).publish(envelope, 1, R.selectedAt)

    private fun saved() = assertNotNull(jdbc.queryForObject("SELECT envelope::text FROM schedule_ir_envelopes", String::class.java))

    private fun format() = jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java)

    private fun fresh(raw: String = RAW): ScheduleDslStructuralEnvelope =
        dataSource.connection.use { connection ->
            val independent = JdbcTemplate(SingleConnectionDataSource(connection, true))
            assertNotNull(JdbcScheduleDslStructuralRepository(independent, mapper).find(snapshot(raw).context))
        }

    @Test
    fun `SEPG01 format11 retains the second repeated native context through independent restore and calculation`() {
        val document = envelope()
        val expectedProof = document.locatedEvidence.single { it.submissionVersionId == H.ir.first }.evidence
        assertEquals(RAW.codePointCount(0, RAW.lastIndexOf(REPEATED)), expectedProof.span.startCodePoint)
        assertEquals(EvidenceLocationDerivation.SERVER_CATALOG_REFERENCE, expectedProof.derivation)
        assertNotNull(expectedProof.catalogId)

        publish(document)

        assertEquals(11, format())
        val restored = fresh()
        assertEquals(document, restored)
        assertEquals(ServerEvidenceCatalog.VERSION, restored.evidenceCatalogVersion)
        assertEquals(expectedProof, restored.locatedEvidence.single { it.submissionVersionId == H.ir.first }.evidence)
        val calculated = ScheduleDslStructuralCalculator.calculate(restored, snapshot(), S.policy, MeetingMode.REMOTE)
        assertEquals(listOf(OVERLAP), calculated.recommendations.options.map { it.window })
        assertEquals(
            2,
            calculated.recommendations.options
                .single()
                .variants
                .single()
                .participantIds.size,
        )
        val json = saved()
        assertTrue(json.contains("evidenceCatalogVersion"))
        assertTrue(json.contains(expectedProof.catalogId!!))
        assertFalse(json.contains(RAW))
        assertFalse(json.contains(REPEATED))
        assertFalse(json.contains("\"quote\""))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM analysis_invocations", Int::class.java))
    }

    @Test
    fun `SEPG02 corrupted native span digest reference or owner cannot be restored or silently repaired`() {
        publish(envelope())
        val original = saved()
        for (fault in listOf("span", "digest", "catalog-id", "foreign-owner", "missing-id")) {
            val tree = mapper.readTree(original) as ObjectNode
            val witness = tree.path("locatedEvidence").path(0) as ObjectNode
            val evidence = witness.path("evidence") as ObjectNode
            val span = evidence.path("span") as ObjectNode
            when (fault) {
                "span" -> span.put("startCodePoint", span.path("startCodePoint").intValue() + 1)
                "digest" -> span.put("contentSha256", "0".repeat(64))
                "catalog-id" -> evidence.put("catalogId", "ev:" + "0".repeat(32))
                "foreign-owner" -> witness.put("submissionVersionId", H.ir.second.toString())
                "missing-id" -> evidence.remove("catalogId")
            }
            replace(tree)
            val corrupted = saved()
            assertFailsWith<RuntimeException>(fault) { fresh() }
            assertEquals(corrupted, saved(), "A read must not repair $fault")
        }
    }

    @Test
    fun `SEPG03 format downgrade missing marker and unsupported catalog version are rejected`() {
        publish(envelope())
        val original = saved()
        for (fault in listOf("format7", "format10", "marker", "unknown-version")) {
            val tree = mapper.readTree(original) as ObjectNode
            when (fault) {
                "marker" -> tree.remove("evidenceCatalogVersion")
                "unknown-version" -> tree.put("evidenceCatalogVersion", "server-evidence-catalog-unknown")
            }
            replace(
                tree,
                if (fault == "format7") {
                    7
                } else if (fault == "format10") {
                    10
                } else {
                    11
                },
            )
            val corrupted = saved()
            assertFailsWith<RuntimeException>(fault) { fresh() }
            assertEquals(corrupted, saved())
        }
    }

    @Test
    fun `SEPG04 changing a native source after publication invalidates fresh source bound references`() {
        publish(envelope())
        val original = saved()
        jdbc.update("UPDATE submission_versions SET raw_text=? WHERE id=?", RAW.replace("14:00", "15:00"), H.ir.first)

        assertFailsWith<RuntimeException> { fresh() }

        assertEquals(original, saved())
    }

    @Test
    fun `SEPG05 unbound or foreign catalog witnesses cannot publish under the new or old marker`() {
        val good = envelope()
        val first = good.locatedEvidence.first()
        val invalid =
            listOf(
                good.copy(evidenceCatalogVersion = null),
                good.copy(evidenceCatalogVersion = "server-evidence-catalog-unknown"),
                good.copy(
                    locatedEvidence =
                        listOf(first.copy(evidence = first.evidence.copy(catalogId = "ev:" + "f".repeat(32)))) +
                            good.locatedEvidence.drop(1),
                ),
            )
        for (document in invalid) {
            assertFailsWith<RuntimeException> { publish(document) }
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM schedule_ir_envelopes", Int::class.java))
        }
    }

    @Test
    fun `SEPG06 legacy formats keep existing readers and omit catalog fields`() {
        for (expected in listOf(7, 8, 9, 10)) {
            val raw =
                if (expected == 9) {
                    S.RAW
                } else if (expected == 8) {
                    "2027-01-08 가능."
                } else {
                    P.RAW
                }
            seed(raw)
            val legacy =
                when (expected) {
                    9 -> P.parse(S.document(), raw, enabled = false, objectMapper = mapper, scopedTerms = true)
                    10 -> P.parse(objectMapper = mapper)
                    8 -> P.parse(wholeDay(raw), raw, enabled = false, objectMapper = mapper)
                    else -> P.parse(P.legacyDocument(), enabled = false, objectMapper = mapper)
                }
            publish(legacy)
            assertEquals(expected, format())
            assertEquals(legacy, fresh(raw))
            assertFalse(saved().contains("evidenceCatalogVersion"))
            assertFalse(saved().contains("catalogId"))
        }
    }

    @Test
    fun `SEPG07 opt in generation contract is frozen and survives disabling selector on reload`() {
        val selected = freeze(serverReferences = true)
        val immutable = R.rows(jdbc, "final_analysis_routes")
        val choice = selected.choice as FinalAnalysisRouteChoice.DslStructuralV5
        assertEquals("schedule-dsl-evidence-response-1", choice.identity.responseContractVersion)

        selector(false).requireExisting(H.ir.run, FinalAnalysisContractSelection.DSL_STRUCTURAL_V5)

        val fresh = JdbcFinalAnalysisRouteRegistry(jdbc, mapper, Clock.fixed(R.selectedAt, ZoneOffset.UTC))
        assertEquals(selected, fresh.find(H.ir.run))
        assertEquals(immutable, R.rows(jdbc, "final_analysis_routes"))
    }

    @Test
    fun `SEPG08 default generation contract remains old when a later selector enables references`() {
        val selected = freeze(serverReferences = false)
        val immutable = R.rows(jdbc, "final_analysis_routes")
        val choice = selected.choice as FinalAnalysisRouteChoice.DslStructuralV5
        assertEquals("schedule-dsl-response-5", choice.identity.responseContractVersion)

        selector(true).requireExisting(H.ir.run, FinalAnalysisContractSelection.DSL_STRUCTURAL_V5)

        assertEquals(selected, routes.find(H.ir.run))
        assertEquals(immutable, R.rows(jdbc, "final_analysis_routes"))
    }

    private fun selector(enabled: Boolean) = FinalAnalysisRouteSelectionService(runs, routes, serverEvidenceReferences = enabled)

    private fun freeze(serverReferences: Boolean) =
        assertNotNull(runs.findById(CoordinationRunId(H.ir.run))).let { run ->
            val room = assertNotNull(rooms.findById(MeetingRoomId(H.ir.room)))
            TransactionTemplate(transactions).executeWithoutResult {
                selector(serverReferences).freeze(
                    run,
                    room,
                    FrozenSubmissionReader.read(submissions, run.batch),
                    FinalAnalysisContractSelection.DSL_STRUCTURAL_V5,
                )
            }
            assertNotNull(routes.find(H.ir.run))
        }

    /** Typed manually declared support keeps persistence assertions independent from model/compiler syntax. */
    private fun envelope(): ScheduleDslStructuralEnvelope {
        val native = input()
        val source = snapshot()
        val catalog = ServerEvidenceCatalog.create(source)
        val repeated =
            catalog.entries
                .filter { it.inputId == H.ir.first && it.text == REPEATED }
                .maxBy { it.span.startCodePoint }
        val peer = catalog.entries.single { it.inputId == H.ir.second && it.text == H.SECOND }

        fun proof(entry: com.meetme.server.submission.domain.dsl.ServerEvidenceEntry) =
            ProposedIrEvidence(
                entry.span.sourceId,
                entry.span.revision,
                entry.span.startCodePoint,
                entry.span.endCodePoint,
                entry.text,
            )
        val firstClaim =
            H
                .claim(native, "possible", scope = H.scope("2027-01-08T14:00", "2027-01-08T16:00"))
                .copy(evidence = listOf(proof(repeated)))
        val firstProgram =
            H.program(listOf(H.ref(native, "root", "possible").copy(evidence = listOf(proof(repeated)))), "root")
        val proposed = H.proposal(native, listOf(firstClaim), firstProgram, emptyList())
        val approved = ScheduleDslApprovedTermsValidator().validate(proposed, source)
        val context = ScheduleDslContextModeValidator().validateDeclaration(approved, emptyList(), source)
        return ScheduleDslStructuralEnvelope(
            SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
            SCHEDULE_AST_VERSION,
            source.context,
            context,
            listOf(
                ScheduleDslLocatedEvidence(H.ir.first, catalog.resolve(H.ir.first, repeated.id)),
                ScheduleDslLocatedEvidence(H.ir.second, catalog.resolve(H.ir.second, peer.id)),
            ),
            emptyList(),
            context.calculationProjection,
            evidenceCatalogVersion = ServerEvidenceCatalog.VERSION,
        )
    }

    private fun wholeDay(raw: String) =
        S.document().also { document ->
            val result = S.results(document).first()
            result["approved_terms"] = emptyList<Any>()
            @Suppress("UNCHECKED_CAST")
            val condition = (result["conditions"] as List<MutableMap<String, Any?>>).single()
            val evidence = listOf(P.locator(raw))
            condition["scope"] = mapOf("dimension" to "TIME", "start" to "2027-01-08", "end" to null)
            condition["evidence"] = evidence
            @Suppress("UNCHECKED_CAST")
            val nodes = P.fields(result["program"])["nodes"] as List<MutableMap<String, Any?>>
            nodes.forEach { it["evidence"] = evidence }
        }

    private fun replace(
        tree: ObjectNode,
        version: Int = 11,
    ) {
        jdbc.update(
            "UPDATE schedule_ir_envelopes SET envelope=CAST(? AS jsonb),format_version=?",
            mapper.writeValueAsString(tree),
            version,
        )
    }

    companion object {
        private const val REPEATED = "2027-01-08 14:00~16:00 가능."
        private const val RAW = "🙂 합성 기록.\n$REPEATED\n$REPEATED"
        private val OVERLAP = InstantTimeRange(Instant.parse("2027-01-08T05:00:00Z"), Instant.parse("2027-01-08T07:00:00Z"))
        private val postgres = mappedPortReadyPostgres().apply { start() }

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("meetme.reliability.enabled") { false }
            registry.add("meetme.data-retention.enabled") { false }
            registry.add("meetme.rate-limit.enabled") { false }
            registry.add("meetme.gemini.api-key") { "" }
            registry.add("meetme.openai.api-key") { "" }
            registry.add("meetme.typesafe.api-key") { "" }
        }

        @JvmStatic @AfterAll
        fun stop() {
            postgres.stop()
        }
    }
}
