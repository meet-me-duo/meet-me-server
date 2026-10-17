package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.participant.application.port.output.GuestOwnershipProof
import com.meetme.server.participant.application.port.output.GuestOwnershipProofPort
import com.meetme.server.participant.application.port.output.GuestProofLevel
import com.meetme.server.shared.adapter.input.web.GuestCookie
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.domain.ClarificationProposal
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** #107/#109 real JWT/current-account/shared-resolver integration. No CurrentAccountPort or owner-port mocks.
 * Only provider calls are replaced to guarantee zero external AI calls. REPLACE_ONLY is injected at the proof port;
 * verified guest credentials, account JWTs, claim, application services, HTTP and PostgreSQL remain real.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClarificationAccountOwnershipPostgresTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var structured: StructuredSubmissionRepository

    @MockitoSpyBean private lateinit var guestProofs: GuestOwnershipProofPort

    @MockitoBean(name = "geminiNaturalLanguageParserAdapter")
    private lateinit var gemini: NaturalLanguageParserPort

    @MockitoBean(name = "lunaParser")
    private lateinit var luna: NaturalLanguageParserPort

    @BeforeEach
    fun cleanDatabase() {
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
    }

    @Test
    fun `real signed account without cookie reads and answers then host explicitly freezes new revision once`() {
        val fixture = completedRoom()
        val first = question(fixture, fixture.member)
        val questionId = UUID.fromString(first.getValue("question_id").toString())
        assertEquals(false, first["editable"])
        assertEquals("M0 original", owner(fixture, fixture.member)["raw_text"])
        val round = openRound(fixture)
        val counts = workCounts()
        val sourceVersion = currentVersion(fixture)

        mvc
            .perform(answer(fixture, fixture.member, questionId, round))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ANSWERED"))
            .andExpect(jsonPath("$.submission.raw_text").value(OPTION_A))
            .andExpect(jsonPath("$.submission.revision").value(2))
        assertEquals(OPTION_A, owner(fixture, fixture.member)["raw_text"])
        assertEquals(2, (owner(fixture, fixture.member).getValue("revision") as Number).toInt())
        assertNotEquals(sourceVersion, currentVersion(fixture))
        assertEquals(
            "M0 original",
            jdbc.queryForObject("SELECT raw_text FROM submission_versions WHERE id = ?", String::class.java, sourceVersion),
        )
        assertEquals(counts, workCounts())
        assertEquals(emptyList<Any>(), questions(fixture, fixture.member)["questions"])
        assertEquals(listOf("H0 original", "M0 original"), snapshotTexts(fixture.sourceRun))
        val requestId = UUID.randomUUID()
        repeat(2) {
            mvc
                .perform(analyze(fixture, round, requestId))
                .andExpect(status().isAccepted)
                .andExpect(jsonPath("$.outcome").value("QUEUED"))
        }
        val after = workCounts()
        for (table in listOf("submission_batches", "coordination_runs", "outbox_events")) {
            assertEquals(counts.getValue(table) + 1, after.getValue(table), table)
        }
        assertEquals(counts["coordination_attempts"], after["coordination_attempts"])
        assertEquals(counts["analysis_invocations"], after["analysis_invocations"])
        val active =
            requireNotNull(jdbc.queryForObject("SELECT active_run_id FROM meeting_rooms WHERE id = ?", UUID::class.java, fixture.roomId))
        assertEquals(listOf("H0 original", OPTION_A).sorted(), snapshotTexts(active))
        assertEquals(listOf("H0 original", "M0 original"), snapshotTexts(fixture.sourceRun))
        verifyNoInteractions(gemini, luna)
    }

    @Test
    fun `another authenticated account cannot borrow valid guest cookie to read or answer`() {
        val fixture = completedRoom(guestMember = true)
        val id = UUID.fromString(question(fixture, fixture.member).getValue("question_id").toString())
        val round = openRound(fixture)
        val outsider = account("outsider").copy(guestCredential = fixture.member.guestCredential)
        val versions = versionIds()
        val counts = workCounts()
        val read =
            mvc
                .perform(auth(get("/api/rooms/{code}/submission/clarifications", fixture.code), outsider))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("PARTICIPANT_REQUIRED"))
                .andReturn()
                .response.contentAsString
        val write =
            mvc
                .perform(answer(fixture, outsider, id, round))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("PARTICIPANT_REQUIRED"))
                .andReturn()
                .response.contentAsString
        assertPrivate(read)
        assertPrivate(write)
        assertEquals(versions, versionIds())
        assertEquals(counts, workCounts())
        assertEquals(id.toString(), question(fixture, fixture.member)["question_id"])
        verifyNoInteractions(gemini, luna)
    }

    @Test
    fun `authenticated host sees own empty list and cannot answer guest question even with guest cookie`() {
        val fixture = completedRoom(guestMember = true)
        val id = UUID.fromString(question(fixture, fixture.member).getValue("question_id").toString())
        val round = openRound(fixture)
        val borrowing = fixture.host.copy(guestCredential = fixture.member.guestCredential)
        assertEquals(emptyList<Any>(), questions(fixture, borrowing)["questions"])
        val versions = versionIds()
        val response =
            mvc
                .perform(answer(fixture, borrowing, id, round))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
                .andReturn()
                .response.contentAsString
        assertPrivate(response)
        assertEquals(versions, versionIds())
        verifyNoInteractions(gemini, luna)
    }

    @Test
    fun `invalid bearer with valid guest cookie is unauthorized without guest fallback`() {
        val fixture = completedRoom(guestMember = true)
        val id = UUID.fromString(question(fixture, fixture.member).getValue("question_id").toString())
        val round = openRound(fixture)
        val invalid = Actor("synthetic.invalid.signature", fixture.member.guestCredential)
        val versions = versionIds()
        val counts = workCounts()
        val requests =
            listOf(
                auth(get("/api/rooms/{code}/submission/clarifications", fixture.code), invalid),
                auth(get("/api/rooms/{code}/submission", fixture.code), invalid),
                answer(fixture, invalid, id, round),
            )
        for (request in requests) {
            val response =
                mvc
                    .perform(request)
                    .andExpect(status().isUnauthorized)
                    .andExpect(jsonPath("$.code").value("AUTH_ACCESS_INVALID"))
                    .andReturn()
                    .response.contentAsString
            assertPrivate(response)
        }
        assertEquals(versions, versionIds())
        assertEquals(counts, workCounts())
        assertEquals(id.toString(), question(fixture, fixture.member)["question_id"])
        verifyNoInteractions(gemini, luna)
    }

    @Test
    fun `verified guest can read but replace only proof cannot read source or submit clarification answer`() {
        val fixture = completedRoom(guestMember = true)
        val id = UUID.fromString(question(fixture, fixture.member).getValue("question_id").toString())
        val round = openRound(fixture)
        assertEquals(true, question(fixture, fixture.member)["editable"])
        assertEquals("M0 original", owner(fixture, fixture.member)["raw_text"])
        val versions = versionIds()
        val counts = workCounts()
        // No production REPLACE_ONLY issuer exists here. Inject only this scope at the real resolver's proof boundary.
        doReturn(GuestOwnershipProof(MeetingRoomId(fixture.roomId), ParticipantId(fixture.memberId), GuestProofLevel.REPLACE_ONLY))
            .`when`(guestProofs)
            .resolve(MeetingRoomId(fixture.roomId), fixture.member.guestCredential, false)
        val requests =
            listOf(
                auth(get("/api/rooms/{code}/submission/clarifications", fixture.code), fixture.member),
                auth(get("/api/rooms/{code}/submission", fixture.code), fixture.member),
                answer(fixture, fixture.member, id, round),
                answer(fixture, fixture.member, id, round, choice = "CANCEL"),
                answer(fixture, fixture.member, id, round, choice = "DIRECT", directText = OPTION_B),
            )
        for (request in requests) {
            val response =
                mvc
                    .perform(request)
                    .andExpect(status().isForbidden)
                    .andExpect(jsonPath("$.code").value("GUEST_PROOF_INSUFFICIENT"))
                    .andReturn()
                    .response.contentAsString
            assertPrivate(response)
        }
        assertEquals(versions, versionIds())
        assertEquals(counts, workCounts())
        verifyNoInteractions(gemini, luna)
    }

    @Test
    fun `verified guest saves option and direct answer revisions with provenance without triggering analysis`() {
        for (choice in listOf("A", "DIRECT")) {
            val fixture = completedRoom(guestMember = true)
            val id = UUID.fromString(question(fixture, fixture.member).getValue("question_id").toString())
            val source = currentVersion(fixture)
            val round = openRound(fixture)
            val versions = versionIds()
            val counts = workCounts()
            val expectedText = if (choice == "A") OPTION_A else OPTION_B
            mvc
                .perform(answer(fixture, fixture.member, id, round, choice, if (choice == "DIRECT") OPTION_B else null))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.submission.raw_text").value(expectedText))
                .andExpect(jsonPath("$.submission.revision").value(2))
            assertEquals(expectedText, owner(fixture, fixture.member)["raw_text"])
            assertEquals(2, (owner(fixture, fixture.member).getValue("revision") as Number).toInt())
            assertEquals(versions.size + 1, versionIds().size)
            assertEquals(
                source,
                jdbc.queryForObject("SELECT source_version_id FROM submission_clarifications WHERE question_id = ?", UUID::class.java, id),
            )
            assertEquals(
                currentVersion(fixture),
                jdbc.queryForObject("SELECT saved_version_id FROM submission_clarifications WHERE question_id = ?", UUID::class.java, id),
            )
            assertEquals(
                choice,
                jdbc.queryForObject("SELECT answer_choice FROM submission_clarifications WHERE question_id = ?", String::class.java, id),
            )
            assertEquals(
                "M0 original",
                jdbc.queryForObject("SELECT raw_text FROM submission_versions WHERE id = ?", String::class.java, source),
            )
            assertEquals(emptyList<Any>(), questions(fixture, fixture.member)["questions"])
            assertEquals(counts, workCounts())
            verifyNoInteractions(gemini, luna)
        }
    }

    @Test
    fun `explicit account claim preserves participant source revision and question then account answers without cookie`() {
        val fixture = completedRoom(guestMember = true)
        val question = question(fixture, fixture.member)
        val id = UUID.fromString(question.getValue("question_id").toString())
        val source = currentVersion(fixture)
        val round = openRound(fixture)
        val claimant = account("claimant")
        val before = workCounts()
        mvc
            .perform(
                auth(
                    post("/api/rooms/{code}/claim", fixture.code).header("Origin", ORIGIN),
                    claimant.copy(guestCredential = fixture.member.guestCredential),
                ),
            ).andExpect(status().isOk)
        assertEquals(
            fixture.memberId,
            jdbc.queryForObject("SELECT participant_id FROM submission_heads WHERE latest_version_id = ?", UUID::class.java, source),
        )
        assertEquals(source, currentVersion(fixture))
        assertEquals(
            null,
            jdbc.queryForObject("SELECT guest_session_id FROM participants WHERE id = ?", UUID::class.java, fixture.memberId),
        )
        assertEquals(
            claimant.accountId,
            jdbc.queryForObject("SELECT user_id FROM participants WHERE id = ?", UUID::class.java, fixture.memberId),
        )
        assertEquals(1, (owner(fixture, claimant).getValue("revision") as Number).toInt())
        assertEquals(id.toString(), question(fixture, claimant)["question_id"])
        mvc.perform(auth(get("/api/rooms/{code}/submission/clarifications", fixture.code), fixture.member)).andExpect(status().isForbidden)
        mvc.perform(auth(get("/api/rooms/{code}/submission", fixture.code), fixture.member)).andExpect(status().isForbidden)
        mvc
            .perform(answer(fixture, claimant, id, round, choice = "B"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.submission.revision").value(2))
        assertEquals(OPTION_B, owner(fixture, claimant)["raw_text"])
        assertEquals(before, workCounts())
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM participants WHERE room_id = ?", Int::class.java, fixture.roomId))
        verifyNoInteractions(gemini, luna)
    }

    private fun account(name: String): Actor {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO account_users(id, display_name, created_at) VALUES (?, ?, now())", id, name)
        return Actor(tokens.issue(AccountPrincipal(id, name)), accountId = id)
    }

    private fun completedRoom(guestMember: Boolean = false): RoomFixture {
        val host = account("host")
        val created =
            mvc
                .perform(
                    auth(
                        post("/api/rooms").header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(
                            """
                            {"purpose":"synthetic account clarification","meeting_mode":"REMOTE","manual_only":true,
                             "search_start_date":"2026-10-17","search_end_date":"2026-10-31"}
                            """.trimIndent(),
                        ),
                        host,
                    ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        val code = document(created).getValue("invite_code").toString()
        val memberAccount = if (guestMember) null else account("member")
        val joined =
            mvc
                .perform(
                    auth(
                        post("/api/rooms/{code}/participants", code)
                            .header("Origin", ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(if (guestMember) """{"display_name":"member"}""" else "{}"),
                        memberAccount ?: Actor(),
                    ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        val member =
            memberAccount
                ?: Actor(
                    guestCredential =
                        requireNotNull(
                            joined.getHeader(HttpHeaders.SET_COOKIE),
                        ).substringAfter("meet_me_guest=").substringBefore(';'),
                )
        mvc.perform(save(code, host, "H0 original")).andExpect(status().isOk)
        mvc.perform(save(code, member, "M0 original")).andExpect(status().isOk)
        mvc
            .perform(
                auth(
                    post(
                        "/api/rooms/{code}/close",
                        code,
                    ).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content("""{"confirm_early":true}"""),
                    host,
                ),
            ).andExpect(status().isOk)
        val roomId = requireNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code = ?", UUID::class.java, code))
        val run = requireNotNull(jdbc.queryForObject("SELECT id FROM coordination_runs WHERE room_id = ?", UUID::class.java, roomId))
        val memberId =
            requireNotNull(
                jdbc.queryForObject("SELECT id FROM participants WHERE room_id = ? AND display_name = 'member'", UUID::class.java, roomId),
            )
        jdbc.update(
            "UPDATE coordination_runs SET status = 'COMPLETED', candidate_quality = 'PARTIAL', version = version + 1 WHERE id = ?",
            run,
        )
        jdbc.update(
            "UPDATE outbox_events SET status = 'PROCESSED', published_at = now(), processed_at = now(), " +
                "processing_lease_until = NULL WHERE aggregate_id = ?",
            run,
        )
        val fixture = RoomFixture(code, host, member, roomId, memberId, run)
        val batch = requireNotNull(jdbc.queryForObject("SELECT batch_id FROM coordination_runs WHERE id = ?", UUID::class.java, run))
        structured.replaceForBatch(
            SubmissionBatchId(batch),
            listOf(
                StructuredSubmissionResult(
                    SubmissionVersionId(currentVersion(fixture)),
                    emptyList(),
                    "AMBIGUOUS_TIME_CONSTRAINT",
                    ClarificationProposal("7~8시", QUESTION, OPTION_A, OPTION_B),
                ),
            ),
            Instant.now(),
        )
        return fixture
    }

    private fun save(
        code: String,
        actor: Actor,
        text: String,
    ) = auth(
        put("/api/rooms/{code}/submission", code)
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapOf("raw_text" to text))),
        actor,
    )

    private fun openRound(fixture: RoomFixture): UUID {
        val response =
            mvc
                .perform(
                    auth(
                        post("/api/rooms/{code}/reopen", fixture.code)
                            .header("Origin", ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                mapper.writeValueAsString(
                                    mapOf(
                                        "request_id" to UUID.randomUUID(),
                                        "source_analysis_id" to fixture.sourceRun,
                                        "expected_generation" to 0,
                                    ),
                                ),
                            ),
                        fixture.host,
                    ),
                ).andExpect(status().isOk)
                .andReturn()
                .response
        return UUID.fromString(obj(document(response).getValue("round")).getValue("id").toString())
    }

    private fun analyze(
        fixture: RoomFixture,
        round: UUID,
        requestId: UUID,
    ) = auth(
        post("/api/rooms/{code}/analysis", fixture.code)
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                mapper.writeValueAsString(
                    mapOf(
                        "revision_round_id" to round,
                        "request_id" to requestId,
                        "force_reparse" to false,
                    ),
                ),
            ),
        fixture.host,
    )

    private fun answer(
        fixture: RoomFixture,
        actor: Actor,
        id: UUID,
        round: UUID,
        choice: String = "A",
        directText: String? = null,
    ) = auth(
        post("/api/rooms/{code}/submission/clarifications/{question}/answer", fixture.code, id)
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                mapper.writeValueAsString(
                    linkedMapOf<String, Any>(
                        "request_id" to UUID.randomUUID(),
                        "revision_round_id" to round,
                        "expected_revision" to 1,
                        "choice" to choice,
                    ).apply { directText?.let { put("direct_text", it) } },
                ),
            ),
        actor,
    )

    private fun questions(
        fixture: RoomFixture,
        actor: Actor,
    ) = document(
        mvc
            .perform(auth(get("/api/rooms/{code}/submission/clarifications", fixture.code), actor))
            .andExpect(status().isOk)
            .andReturn()
            .response,
    )

    private fun question(
        fixture: RoomFixture,
        actor: Actor,
    ) = obj((questions(fixture, actor).getValue("questions") as List<*>).single())

    private fun owner(
        fixture: RoomFixture,
        actor: Actor,
    ) = document(
        mvc
            .perform(auth(get("/api/rooms/{code}/submission", fixture.code), actor))
            .andExpect(status().isOk)
            .andReturn()
            .response,
    )

    private fun currentVersion(fixture: RoomFixture): UUID =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT latest_version_id FROM submission_heads WHERE participant_id = ?",
                UUID::class.java,
                fixture.memberId,
            ),
        )

    private fun versionIds(): List<UUID> =
        jdbc.queryForList("SELECT id FROM submission_versions ORDER BY id", UUID::class.java).map { requireNotNull(it) }

    private fun workCounts(): Map<String, Int> =
        listOf("submission_batches", "coordination_runs", "outbox_events", "coordination_attempts", "analysis_invocations")
            .associateWith { requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $it", Int::class.java)) }

    private fun snapshotTexts(run: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT v.raw_text FROM coordination_runs r JOIN submission_batch_items i ON i.batch_id = r.batch_id " +
                    "JOIN submission_versions v ON v.id = i.submission_version_id WHERE r.id = ? ORDER BY v.raw_text",
                String::class.java,
                run,
            ).map { requireNotNull(it) }

    private fun auth(
        request: MockHttpServletRequestBuilder,
        actor: Actor,
    ): MockHttpServletRequestBuilder =
        request.apply {
            actor.accessToken?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
            actor.guestCredential?.let { cookie(Cookie(GuestCookie.NAME, it)) }
        }

    private fun assertPrivate(body: String) {
        for (secret in listOf("M0 original", QUESTION, OPTION_A, OPTION_B, "AMBIGUOUS_TIME_CONSTRAINT")) assertTrue(!body.contains(secret))
    }

    @Suppress("UNCHECKED_CAST")
    private fun document(response: MockHttpServletResponse): Map<String, Any?> =
        mapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): Map<String, Any?> = value as Map<String, Any?>

    private data class Actor(
        val accessToken: String? = null,
        val guestCredential: String? = null,
        val accountId: UUID? = null,
    )

    private data class RoomFixture(
        val code: String,
        val host: Actor,
        val member: Actor,
        val roomId: UUID,
        val memberId: UUID,
        val sourceRun: UUID,
    )

    companion object {
        private const val ORIGIN = "https://app.meet-me.co.kr"
        private const val QUESTION = "오전 7~8시인가요, 오후 7~8시인가요?"
        private const val OPTION_A = "2026-10-18 오전 7~8시 가능"
        private const val OPTION_B = "2026-10-18 오후 7~8시 가능"
        private const val KEY_ID = "synthetic-issue-107-integration"
        private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val keyDirectory = Files.createTempDirectory("meetme-107-synthetic-auth-")
        private val privateKeyFile =
            keyDirectory.resolve("private.pem").also {
                Files.writeString(
                    it,
                    "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(keys.private.encoded) +
                        "\n-----END PRIVATE KEY-----\n",
                )
            }
        private val verificationFile =
            keyDirectory.resolve("verification.json").also {
                Files.writeString(it, JWKSet(RSAKey.Builder(keys.public as RSAPublicKey).keyID(KEY_ID).build()).toString())
            }
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("meetme.anonymous.allowed-origins") { ORIGIN }
            registry.add("meetme.auth.signing-key-id") { KEY_ID }
            registry.add("meetme.auth.signing-private-key-path") { privateKeyFile.toString() }
            registry.add("meetme.auth.verification-jwks-path") { verificationFile.toString() }
            registry.add("meetme.reliability.enabled") { false }
            registry.add("meetme.data-retention.enabled") { false }
            registry.add("meetme.rate-limit.enabled") { false }
            registry.add("meetme.gemini.api-key") { "" }
            registry.add("meetme.openai.api-key") { "" }
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            postgres.stop()
            Files.deleteIfExists(privateKeyFile)
            Files.deleteIfExists(verificationFile)
            Files.deleteIfExists(keyDirectory)
        }
    }
}
