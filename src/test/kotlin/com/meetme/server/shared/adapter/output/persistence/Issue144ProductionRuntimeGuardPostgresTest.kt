package com.meetme.server.shared.adapter.output.persistence

import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.precheck.application.port.output.PrecheckOwnershipBindingPort
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.domain.ParticipantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OA02 actual PG claim and ownership guards, without coverage or monetary policy fixtures. */
@SpringBootTest(properties = ["meetme.reliability.enabled=false", "meetme.data-retention.enabled=false"])
@Import(Issue144ProductionRuntimeGuardPostgresTest.TimeConfiguration::class)
class Issue144ProductionRuntimeGuardPostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var fence: JdbcAiBudgetExecutionFence
    @Autowired private lateinit var participants: ParticipantRepository
    @Autowired private lateinit var bindings: PrecheckOwnershipBindingPort
    @Autowired private lateinit var credentials: GuestCredentialPort

    @BeforeEach
    fun reset() {
        TIME.set(START)
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
    }

    private fun authorized(request: AiBudgetReserveRequest): Instant? = assertDoesNotThrow {
        fence.authorize(request, START)
    }

    @Test
    fun `OA02 valid personal execution needs no budget coverage and never promotes its room ledger`() {
        val f = personal()
        val before = jdbc.queryForMap("SELECT * FROM ai_room_budgets WHERE room_id=?", f.room)
        assertEquals("UNCONFIGURED", before["state"])
        assertEquals(START.plusSeconds(15), authorized(f.request))
        assertEquals(START.plusSeconds(15), authorized(f.request), "Guard checks do not manufacture a counted permit")
        for (operation in listOf(AiPhysicalOperation.PRECHECK_COUNT, AiPhysicalOperation.PRECHECK_GENERATION)) {
            val call = f.request.calls.single().copy(operation = operation, profileFingerprint = profiles.getValue(operation))
            assertEquals(START.plusSeconds(15), authorized(f.request.copy(calls = listOf(call))))
        }
        val pair = listOf(AiPhysicalOperation.PRECHECK_COUNT, AiPhysicalOperation.PRECHECK_GENERATION).map {
            f.request.calls.single().copy(operation = it, profileFingerprint = profiles.getValue(it))
        }
        assertEquals(START.plusSeconds(15), authorized(f.request.copy(calls = pair)))
        assertEquals(before, jdbc.queryForMap("SELECT * FROM ai_room_budgets WHERE room_id=?", f.room))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ai_call_reservations", Int::class.java))
    }

    @Test
    fun `OA02 personal authority rejects stale ownership revision claim lifecycle and profile`() {
        for (fault in listOf("OWNER", "VERSION", "GENERATION", "SOURCE", "CONTEXT", "CLOSED", "DEADLINE",
            "SESSION_REVOKED", "NEW_CANDIDATE", "UNCLAIMED", "OLD_CLAIM", "PROFILE", "PAYLOAD", "GROUP")) {
            reset()
            val f = personal()
            var request = f.request
            when (fault) {
                "OWNER" -> request = request.copy(execution = request.execution.copy(ownerToken = UUID.randomUUID()))
                "VERSION" -> request = request.copy(execution = request.execution.copy(version = 4))
                "GENERATION" -> request = request.copy(execution = request.execution.copy(generation = 2))
                "SOURCE" -> request = request.copy(execution = request.execution.copy(sourceFingerprint = hash("wrong source")))
                "CONTEXT" -> request = request.copy(execution = request.execution.copy(contextFingerprint = hash("wrong context")))
                "CLOSED" -> jdbc.update(
                    "UPDATE meeting_rooms SET collection_status='CLOSED',closure_reason='MANUAL',closed_at=? WHERE id=?",
                    timestamp(START), f.room)
                "DEADLINE" -> TIME.set(START.plusSeconds(15))
                "SESSION_REVOKED" -> jdbc.update("UPDATE guest_browser_sessions SET revoked_at=? WHERE id=?", timestamp(START), f.session)
                "NEW_CANDIDATE" -> jdbc.update(
                    "INSERT INTO precheck_candidates(id,room_id,participant_id,revision,raw_text,locale,created_at," +
                        "interpretation_reference_at,owner_binding) SELECT ?,room_id,participant_id,2,'new synthetic'," +
                        "locale,created_at,interpretation_reference_at,owner_binding FROM precheck_candidates WHERE id=?",
                    UUID.randomUUID(), f.candidate)
                "UNCLAIMED" -> jdbc.update("UPDATE precheck_checks SET claimed_at=NULL WHERE id=?", request.execution.executionId)
                "OLD_CLAIM" -> jdbc.update("UPDATE precheck_checks SET claimed_at=? WHERE id=?",
                    timestamp(START.minusSeconds(16)), request.execution.executionId)
                "PROFILE" -> request = request.copy(calls = listOf(request.calls.single().copy(profileFingerprint = hash("foreign"))))
                "PAYLOAD" -> request = request.copy(calls = listOf(request.calls.single().copy(wholePayloadFingerprint = "invalid")))
                "GROUP" -> request = request.copy(calls = request.calls + request.calls.single().copy(
                    operation = AiPhysicalOperation.PRECHECK_GENERATION,
                    profileFingerprint = profiles.getValue(AiPhysicalOperation.PRECHECK_GENERATION)))
            }
            assertNull(authorized(request), fault)
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ai_call_reservations", Int::class.java))
        }
    }

    @Test
    fun `OA02 guest expiry and time spent waiting for the room lock share the exact cutoff`() {
        val f = personal()
        jdbc.update("UPDATE guest_browser_sessions SET expires_at=? WHERE id=?", timestamp(START.plusSeconds(10)), f.session)
        assertEquals(START.plusSeconds(10), authorized(f.request))
        TIME.set(START.plusSeconds(10))
        assertNull(authorized(f.request))
        TIME.set(START)
        val pool = Executors.newSingleThreadExecutor()
        lateinit var waiting: Future<Instant?>
        try {
            TransactionTemplate(transactions).executeWithoutResult {
                jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE id=? FOR UPDATE", UUID::class.java, f.room)
                waiting = pool.submit(Callable { authorized(f.request) })
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var locked = false
                while (System.nanoTime() < until && !waiting.isDone) {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()")
                    locked = jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() " +
                            "AND pid<>pg_backend_pid() AND wait_event_type='Lock'", Int::class.java,
                    )!! > 0
                    if (locked) break
                    Thread.sleep(10)
                }
                assertTrue(locked, "Runtime authority must hold its own room transaction rather than autocommit lock statements")
                TIME.set(START.plusSeconds(10))
            }
            assertNull(assertDoesNotThrow { waiting.get(10, TimeUnit.SECONDS) }, "Re-read the clock after the blocked room lock")
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `OA02 final primary and fallback retain real invocation attempts without coverage adoption`() {
        for (operation in listOf(AiPhysicalOperation.FINAL_PRIMARY, AiPhysicalOperation.FINAL_FALLBACK)) {
            reset()
            val f = finalClaim(operation)
            val before = jdbc.queryForMap("SELECT * FROM ai_room_budgets WHERE room_id=?", f.room)
            val seconds = if (operation == AiPhysicalOperation.FINAL_PRIMARY) 15L else 27L
            assertEquals(START.plusSeconds(seconds), authorized(f.request))
            assertEquals(before, jdbc.queryForMap("SELECT * FROM ai_room_budgets WHERE room_id=?", f.room))
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ai_call_reservations", Int::class.java))
        }
    }

    @Test
    fun `OA02 stale final owner attempt ordinal source and finished invocation are denied`() {
        for (fault in listOf("OWNER", "VERSION", "SOURCE", "CONTEXT", "ATTEMPT", "ORDINAL", "OLD_RUN", "FINISHED", "DEADLINE", "PROFILE")) {
            reset()
            val f = finalClaim(AiPhysicalOperation.FINAL_PRIMARY)
            var request = f.request
            when (fault) {
                "OWNER" -> request = request.copy(execution = request.execution.copy(ownerToken = UUID.randomUUID()))
                "VERSION" -> request = request.copy(execution = request.execution.copy(version = 1, generation = 1))
                "SOURCE" -> request = request.copy(execution = request.execution.copy(sourceFingerprint = hash("foreign batch")))
                "CONTEXT" -> request = request.copy(execution = request.execution.copy(contextFingerprint = hash("foreign context")))
                "ATTEMPT" -> request = request.copy(calls = listOf(request.calls.single().copy(attemptId = UUID.randomUUID())))
                "ORDINAL" -> request = request.copy(calls = listOf(request.calls.single().copy(ordinal = 4)))
                "OLD_RUN" -> jdbc.update("UPDATE meeting_rooms SET active_run_id=NULL WHERE id=?", f.room)
                "FINISHED" -> jdbc.update(
                    "UPDATE analysis_invocations SET finished_at=? WHERE id=?", timestamp(START), request.execution.executionId)
                "DEADLINE" -> TIME.set(START.plusSeconds(15))
                "PROFILE" -> request = request.copy(calls = listOf(request.calls.single().copy(profileFingerprint = hash("foreign final"))))
            }
            assertNull(authorized(request), fault)
        }
    }

    private fun personal(): PersonalFixture {
        val room = room()
        val session = UUID.randomUUID()
        val participant = UUID.randomUUID()
        val credential = "synthetic-budget-guest-$session"
        jdbc.update(
            "INSERT INTO guest_browser_sessions(id,credential_digest,expires_at,created_at) VALUES (?,?,?,?)",
            session,
            credentials.digest(credential),
            timestamp(START.plusSeconds(60)),
            timestamp(START),
        )
        jdbc.update(
            "INSERT INTO participants(id,room_id,guest_session_id,role,display_name,joined_at) VALUES (?,?,?,'HOST','synthetic',?)",
            participant,
            room,
            session,
            timestamp(START),
        )
        val owner = assertNotNull(participants.findById(ParticipantId(participant)))
        val binding = assertDoesNotThrow { bindings.capture(owner, credential) }
        val candidate = UUID.randomUUID()
        val check = UUID.randomUUID()
        val token = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO precheck_candidates(id,room_id,participant_id,revision,raw_text,locale,cr" +
            "eated_at,interpretation_reference_at,owner_binding) VALUES (?,?,?,1,?,'ko-KR',?,?,?)",
            candidate,
            room,
            participant,
            RAW,
            timestamp(START),
            timestamp(START),
            binding,
        )
        jdbc.update(
            "INSERT INTO precheck_checks(id,candidate_id,status,version,generation,retry_count,dea" +
            "dline_at,owner_token,claimed_at) VALUES (?,?,'CHECKING',3,1,0,?,?,?)",
            check,
            candidate,
            timestamp(START.plusSeconds(30)),
            token,
            timestamp(START),
        )
        jdbc.update(
            "INSERT INTO precheck_executions(check_id,generation,queued_at,deadline_at,started_at,owner_token) VALUES (?,1,?,?,?,?)",
            check,
            timestamp(START),
            timestamp(START.plusSeconds(30)),
            timestamp(START),
            token,
        )
        val execution =
            AiBudgetExecution(
                room,
                AiBudgetExecutionKind.PRECHECK,
                check,
                1,
                token,
                3,
                hash(RAW),
                hash(START.toString(), "Asia/Seoul", "ko-KR", "2026-10-18", "2026-10-20"),
            )
        val request =
            AiBudgetReserveRequest(
                execution,
                listOf(
                    AiPhysicalCallSpec(
                        AiPhysicalOperation.PRECHECK_CLASSIFICATION, 1, hash("synthetic-wire"),
                        profiles.getValue(AiPhysicalOperation.PRECHECK_CLASSIFICATION),
                    ),
                ),
            )
        return PersonalFixture(room, session, participant, candidate, request)
    }

    private fun finalClaim(
        operation: AiPhysicalOperation,
        runVersion: Long = 0,
    ): FinalFixture {
        val p = personal()
        val head = UUID.randomUUID()
        val version = UUID.randomUUID()
        jdbc.update("INSERT INTO submission_heads(id,room_id,participant_id) VALUES (?,?,?)", head, p.room, p.participant)
        jdbc.update(
            "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at) VALUES (?,?,1,?,'ko-KR',?)",
            version,
            head,
            RAW,
            timestamp(START),
        )
        jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", version, head)
        // Existing FrozenSubmissionReader requires two distinct frozen owners. This historical manual-only
        // control is a valid second submission and does not add a natural-language provider input.
        val manualSession = UUID.randomUUID()
        val manualParticipant = UUID.randomUUID()
        val manualHead = UUID.randomUUID()
        val manualVersion = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO guest_browser_sessions(id,credential_digest,expires_at,created_at) VALUES (?,?,?,?)",
            manualSession,
            credentials.digest("synthetic-manual-$manualSession"),
            timestamp(START.plusSeconds(60)),
            timestamp(START),
        )
        jdbc.update(
            "INSERT INTO participants(id,room_id,guest_session_id,role,display_name,joined_at) VAL" +
            "UES (?,?,?,'MEMBER','synthetic manual',?)",
            manualParticipant,
            p.room,
            manualSession,
            timestamp(START),
        )
        jdbc.update("INSERT INTO submission_heads(id,room_id,participant_id) VALUES (?,?,?)", manualHead, p.room, manualParticipant)
        jdbc.update(
            "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at) VALUES (?,?,1,NULL,'ko-KR',?)",
            manualVersion,
            manualHead,
            timestamp(START),
        )
        jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", manualVersion, manualHead)
        jdbc.update(
            "INSERT INTO manual_availability_intervals(id,submission_version_id,interval_order,int" +
            "erval_kind,local_date,start_time,end_time) VALUES (?,?,0,'DATED',DATE '2026-10-18',TI" +
            "ME '10:00',TIME '11:00')",
            UUID.randomUUID(),
            manualVersion,
        )
        val batch = UUID.randomUUID()
        val run = UUID.randomUUID()
        val invocation = UUID.randomUUID()
        val token = UUID.randomUUID()
        val attempt = UUID.randomUUID()
        val fallback = operation == AiPhysicalOperation.FINAL_FALLBACK
        jdbc.update("INSERT INTO submission_batches(id,room_id,fixed_at) VALUES (?,?,?)", batch, p.room, timestamp(START))
        jdbc.update("INSERT INTO submission_batch_items(batch_id,submission_version_id) VALUES (?,?)", batch, version)
        jdbc.update("INSERT INTO submission_batch_items(batch_id,submission_version_id) VALUES (?,?)", batch, manualVersion)
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM submission_batch_items WHERE batch_id=?", Int::class.java, batch))
        jdbc.update(
            "INSERT INTO coordination_runs(id,room_id,batch_id,status,created_at,version) VALUES (?,?,?,'STRUCTURING',?,?)",
            run,
            p.room,
            batch,
            timestamp(START),
            runVersion,
        )
        jdbc.update(
            "UPDATE meeting_rooms SET collection_status='CLOSED',closure_reason='MANUAL',closed_at=?,active_run_id=? WHERE id=?",
            timestamp(START),
            run,
            p.room,
        )
        var globalAttempt = 0
        repeat(runVersion.toInt()) { previousVersion ->
            val oldInvocation = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO analysis_invocations(id,coordination_run_id,run_version,started_at,deadli" +
                "ne_at,owner_token,gemini_attempts,luna_attempts,finished_at,policy_version) VALUES (?" +
                ",?,?,?,?,?,3,0,?,'bounded-luna-v1')",
                oldInvocation,
                run,
                previousVersion,
                timestamp(START),
                timestamp(START.plusSeconds(60)),
                UUID.randomUUID(),
                timestamp(START),
            )
            repeat(3) {
                globalAttempt++
                jdbc.update(
                    "INSERT INTO coordination_attempts(id,coordination_run_id,attempt_number,started_at,fi" +
                    "nished_at,failure_code,provider,model,policy_version,invocation_id) VALUES (?,?,?,?,?" +
                    ",'NETWORK','GEMINI','gemini-3.8-flash','bounded-luna-v1',?)",
                    UUID.randomUUID(),
                    run,
                    globalAttempt,
                    timestamp(START),
                    timestamp(START),
                    oldInvocation,
                )
            }
        }
        jdbc.update(
            "INSERT INTO analysis_invocations(id,coordination_run_id,run_version,started_at,deadli" +
            "ne_at,owner_token,gemini_attempts,luna_attempts,policy_version) VALUES (?,?,?,?,?,?,?" +
            ",?,'bounded-luna-v1')",
            invocation,
            run,
            runVersion,
            timestamp(START),
            timestamp(START.plusSeconds(60)),
            token,
            if (fallback)4 else 1,
            if (fallback)1 else 0,
        )
        if (fallback) {
            repeat(4) {
                globalAttempt++
                jdbc.update(
                    "INSERT INTO coordination_attempts(id,coordination_run_id,attempt_number,started_at,fi" +
                    "nished_at,failure_code,provider,model,policy_version,invocation_id) VALUES (?,?,?,?,?" +
                    ",'NETWORK','GEMINI','gemini-3.8-flash','bounded-luna-v1',?)",
                    UUID.randomUUID(),
                    run,
                    globalAttempt,
                    timestamp(START),
                    timestamp(START),
                    invocation,
                )
            }
        }
        globalAttempt++
        jdbc.update(
            "INSERT INTO coordination_attempts(id,coordination_run_id,attempt_number,started_at,pr" +
            "ovider,model,policy_version,invocation_id) VALUES (?,?,?,?,?,?,'bounded-luna-v1',?)",
            attempt,
            run,
            globalAttempt,
            timestamp(START),
            if (fallback)"OPENAI" else "GEMINI",
            if (fallback)"gpt-6-luna" else "gemini-3.8-flash",
            invocation,
        )
        val execution =
            AiBudgetExecution(
                p.room,
                AiBudgetExecutionKind.FINAL_ANALYSIS,
                invocation,
                runVersion,
                token,
                runVersion,
                hash("final-source-v1", version.toString(), RAW),
                hash("final-context-v1", "Asia/Seoul", "2026-10-18", "2026-10-20", version.toString(), "ko-KR", "2026-10-18"),
            )
        return FinalFixture(
            p.room,
            AiBudgetReserveRequest(
                execution,
                listOf(AiPhysicalCallSpec(operation, 1, hash("synthetic-final-wire"), profiles.getValue(operation), attempt)),
            ),
        )
    }

    private fun room(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_sta" +
            "rt_date,search_end_date,search_range_source,manual_only,collection_status,created_at," +
            "version) VALUES (?,?,'synthetic fence','EITHER','Asia/Seoul',DATE '2026-10-18',DATE '" +
            "2026-10-20','HOST_SPECIFIED',true,'COLLECTING',?,0)",
            id,
            id.toString().replace("-", "").take(22),
            timestamp(START),
        )
        return id
    }

    // Independently implemented public framing: ASCII decimal UTF8 byte length + ':' + exact UTF8 bytes.
    private fun hash(vararg components: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        components.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun timestamp(at: Instant) = at.atOffset(ZoneOffset.UTC)

    private data class PersonalFixture(
        val room: UUID,
        val session: UUID,
        val participant: UUID,
        val candidate: UUID,
        val request: AiBudgetReserveRequest,
    )

    private data class FinalFixture(
        val room: UUID,
        val request: AiBudgetReserveRequest,
    )

    @TestConfiguration
    class TimeConfiguration {
        @Bean @Primary
        fun clock(): Clock = object : Clock() {
            override fun instant(): Instant = TIME.get()
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
        }
    }

    companion object {
        private val START = Instant.parse("2026-10-18T00:00:00Z")
        private const val RAW = "synthetic Sunday online meeting condition"
        private val TIME = AtomicReference(START)
        private val profiles = mapOf(
            AiPhysicalOperation.PRECHECK_CLASSIFICATION to "c8d01d4a04aa077e0488400dc3b4791753b8428db48c6712aa78f903608a4dfc",
            AiPhysicalOperation.PRECHECK_COUNT to "658a65563b34d43f854cc47dcd93d274d23789745e718b5f7ef49e1b59e8e307",
            AiPhysicalOperation.PRECHECK_GENERATION to "86e4593b78ec903e1c9b4bbbffe204d2b8343e1752d2b6353703ce3fb9b65714",
            AiPhysicalOperation.FINAL_PRIMARY to "0a3fc94ba00d368b4e7c9a41d0b231faab5058aa893dc11082ef786fc0a08d04",
            AiPhysicalOperation.FINAL_FALLBACK to "9c5021916c80178f58d235e55bbbad4889875560b710a505fbde5dedd1a824ab",
        )
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }
        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
        }
        @JvmStatic @AfterAll
        fun stop() { postgres.stop() }
    }
}
