package com.meetme.server.shared.adapter.output.persistence

import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.precheck.application.port.output.PrecheckOwnershipBindingPort
import com.meetme.server.shared.application.port.output.AiBudgetCoverageEpochQueryPort
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiCurrentBudgetExecution
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.domain.ParticipantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DB06/10/17: actual persisted claims, participant repository and guest ownership binding. Only coverage is synthetic. */
@SpringBootTest(properties = ["meetme.reliability.enabled=false", "meetme.data-retention.enabled=false"])
@Import(Issue123ExecutionFencePostgresTest.SyntheticEnvironment::class)
class Issue123ExecutionFencePostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var transactions: PlatformTransactionManager

    @Autowired private lateinit var fence: JdbcAiBudgetExecutionFence

    @Autowired private lateinit var participants: ParticipantRepository

    @Autowired private lateinit var bindings: PrecheckOwnershipBindingPort

    @Autowired private lateinit var credentials: GuestCredentialPort

    @BeforeEach
    fun reset() {
        TIME.set(START)
        EPOCHS.clear()
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
    }

    @Test
    fun `DB10 actual precheck claim with real guest session and exact immutable context is allowed`() {
        val fixture = personal()
        val current = locked(fixture.request)
        assertNotNull(current)
        assertEquals(EPOCHS[fixture.room], current.epochId)
        assertEquals(START.plusSeconds(15), current.deadlineAt)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "OWNER",
            "VERSION",
            "GENERATION",
            "SOURCE",
            "CONTEXT",
            "CHECK",
            "CLOSED",
            "DEADLINE",
            "SESSION_REVOKED",
            "SESSION_EXPIRED",
            "ACCOUNT_CLAIM",
            "NEW_CANDIDATE",
            "NO_COVERAGE",
        ],
    )
    fun `DB10 stale personal identity ownership source and lifecycle never become dispatch authority`(fault: String) {
        val f = personal()
        var request = f.request
        when (fault) {
            "OWNER" -> request = request.copy(execution = request.execution.copy(ownerToken = UUID.randomUUID()))
            "VERSION" -> request = request.copy(execution = request.execution.copy(version = 2))
            "GENERATION" -> request = request.copy(execution = request.execution.copy(generation = 2))
            "SOURCE" -> request = request.copy(execution = request.execution.copy(sourceFingerprint = hash("wrong-source")))
            "CONTEXT" -> request = request.copy(execution = request.execution.copy(contextFingerprint = hash("wrong-context")))
            "CHECK" -> request = request.copy(execution = request.execution.copy(executionId = UUID.randomUUID()))
            "CLOSED" ->
                jdbc.update(
                    "UPDATE meeting_rooms SET collection_status='CLOSED',closure_reason='MANUAL',closed_at=? WHERE id=?",
                    timestamp(START),
                    f.room,
                )
            "DEADLINE" -> TIME.set(START.plusSeconds(15))
            "SESSION_REVOKED" -> jdbc.update("UPDATE guest_browser_sessions SET revoked_at=? WHERE id=?", timestamp(START), f.session)
            "SESSION_EXPIRED" ->
                TIME.set(START.plusSeconds(20)).also {
                    jdbc.update("UPDATE guest_browser_sessions SET expires_at=? WHERE id=?", timestamp(START.plusSeconds(10)), f.session)
                }
            "ACCOUNT_CLAIM" -> {
                val user = UUID.randomUUID()
                jdbc.update("INSERT INTO account_users(id,display_name,created_at) VALUES (?,'synthetic',?)", user, timestamp(START))
                jdbc.update("UPDATE participants SET guest_session_id=NULL,user_id=? WHERE id=?", user, f.participant)
            }
            "NEW_CANDIDATE" ->
                jdbc.update(
                    "INSERT INTO precheck_candidates(id,room_id,participant_id,revision,raw_text,locale,created_at,interpretation_reference_at,owner_binding) SELECT ?,room_id,participant_id,2,'new synthetic condition',locale,created_at,interpretation_reference_at,owner_binding FROM precheck_candidates WHERE id=?",
                    UUID.randomUUID(),
                    f.candidate,
                )
            "NO_COVERAGE" -> EPOCHS.remove(f.room)
        }
        assertNull(locked(request), "Existing ownership and current revision/claim checks must remain authoritative: $fault")
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_reservations", Int::class.java))
    }

    @Test
    fun `DB10 precheck effective expiry includes real guest validity before execution deadline`() {
        val f = personal()
        jdbc.update("UPDATE guest_browser_sessions SET expires_at=? WHERE id=?", timestamp(START.plusSeconds(10)), f.session)
        val current = assertNotNull(locked(f.request))
        assertEquals(START.plusSeconds(10), current.deadlineAt)
    }

    @Test
    fun `DB17 current final primary allows normal closed structuring run at version zero`() {
        val f = finalClaim(AiPhysicalOperation.FINAL_PRIMARY)
        val current = assertNotNull(locked(f.request))
        assertEquals(EPOCHS[f.room], current.epochId)
        assertTrue(current.deadlineAt.isAfter(START))
        assertEquals(START.plusSeconds(15), current.deadlineAt)
    }

    @Test
    fun `DB17 current final fallback has its own provider attempt and shares the same tracked epoch`() {
        val f = finalClaim(AiPhysicalOperation.FINAL_FALLBACK)
        val current = assertNotNull(locked(f.request))
        assertEquals(EPOCHS[f.room], current.epochId)
        assertTrue(current.deadlineAt.isAfter(START))
        assertEquals(START.plusSeconds(27), current.deadlineAt)
    }

    @Test
    fun `DB17 a later invocation uses provider ordinal one despite global attempt number seven`() {
        val fixture = finalClaim(AiPhysicalOperation.FINAL_PRIMARY, runVersion = 2)
        assertEquals(2L, fixture.request.execution.version)
        assertEquals(
            1,
            fixture.request.calls
                .single()
                .ordinal,
        )
        assertEquals(
            7,
            jdbc.queryForObject(
                "SELECT attempt_number FROM coordination_attempts WHERE id=?",
                Int::class.java,
                fixture.request.calls
                    .single()
                    .attemptId,
            ),
        )
        assertNotNull(locked(fixture.request))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "OWNER",
            "VERSION",
            "GENERATION",
            "SOURCE",
            "CONTEXT",
            "ATTEMPT",
            "PROVIDER",
            "ORDINAL",
            "OLD_RUN",
            "FINISHED",
            "DEADLINE",
            "NO_COVERAGE",
        ],
    )
    fun `DB17 stale or mismatched final invocation attempt never reuses current room authority`(fault: String) {
        val f = finalClaim(AiPhysicalOperation.FINAL_PRIMARY)
        var request = f.request
        when (fault) {
            "OWNER" -> request = request.copy(execution = request.execution.copy(ownerToken = UUID.randomUUID()))
            "VERSION" -> request = request.copy(execution = request.execution.copy(version = 1))
            "GENERATION" -> request = request.copy(execution = request.execution.copy(generation = 1))
            "SOURCE" -> request = request.copy(execution = request.execution.copy(sourceFingerprint = hash("wrong-batch")))
            "CONTEXT" -> request = request.copy(execution = request.execution.copy(contextFingerprint = hash("wrong-range")))
            "ATTEMPT" -> request = request.copy(calls = listOf(request.calls.single().copy(attemptId = UUID.randomUUID())))
            "PROVIDER" ->
                request =
                    request.copy(
                        calls = listOf(request.calls.single().copy(operation = AiPhysicalOperation.FINAL_FALLBACK)),
                    )
            "ORDINAL" -> request = request.copy(calls = listOf(request.calls.single().copy(ordinal = 7)))
            "OLD_RUN" -> jdbc.update("UPDATE meeting_rooms SET active_run_id=NULL WHERE id=?", f.room)
            "FINISHED" ->
                jdbc.update(
                    "UPDATE analysis_invocations SET finished_at=? WHERE id=?",
                    timestamp(START),
                    request.execution.executionId,
                )
            "DEADLINE" -> TIME.set(START.plusSeconds(15))
            "NO_COVERAGE" -> EPOCHS.remove(f.room)
        }
        assertNull(locked(request), "Final claim mismatch must deny: $fault")
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_reservations", Int::class.java))
    }

    private fun locked(request: AiBudgetReserveRequest): AiCurrentBudgetExecution? =
        assertDoesNotThrow {
            TransactionTemplate(transactions).execute { fence.lockAndValidate(request, START) }
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
            "INSERT INTO precheck_candidates(id,room_id,participant_id,revision,raw_text,locale,created_at,interpretation_reference_at,owner_binding) VALUES (?,?,?,1,?,'ko-KR',?,?,?)",
            candidate,
            room,
            participant,
            RAW,
            timestamp(START),
            timestamp(START),
            binding,
        )
        jdbc.update(
            "INSERT INTO precheck_checks(id,candidate_id,status,version,generation,retry_count,deadline_at,owner_token,claimed_at) VALUES (?,?,'CHECKING',3,1,0,?,?,?)",
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
                    AiPhysicalCallSpec(AiPhysicalOperation.PRECHECK_CLASSIFICATION, 1, hash("synthetic-wire"), hash("synthetic-profile")),
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
            "INSERT INTO participants(id,room_id,guest_session_id,role,display_name,joined_at) VALUES (?,?,?,'MEMBER','synthetic manual',?)",
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
            "INSERT INTO manual_availability_intervals(id,submission_version_id,interval_order,interval_kind,local_date,start_time,end_time) VALUES (?,?,0,'DATED',DATE '2026-10-18',TIME '10:00',TIME '11:00')",
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
                "INSERT INTO analysis_invocations(id,coordination_run_id,run_version,started_at,deadline_at,owner_token,gemini_attempts,luna_attempts,finished_at,policy_version) VALUES (?,?,?,?,?,?,3,0,?,'bounded-luna-v1')",
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
                    "INSERT INTO coordination_attempts(id,coordination_run_id,attempt_number,started_at,finished_at,failure_code,provider,model,policy_version,invocation_id) VALUES (?,?,?,?,?,'NETWORK','GEMINI','gemini-3.8-flash','bounded-luna-v1',?)",
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
            "INSERT INTO analysis_invocations(id,coordination_run_id,run_version,started_at,deadline_at,owner_token,gemini_attempts,luna_attempts,policy_version) VALUES (?,?,?,?,?,?,?,?,'bounded-luna-v1')",
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
                    "INSERT INTO coordination_attempts(id,coordination_run_id,attempt_number,started_at,finished_at,failure_code,provider,model,policy_version,invocation_id) VALUES (?,?,?,?,?,'NETWORK','GEMINI','gemini-3.8-flash','bounded-luna-v1',?)",
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
            "INSERT INTO coordination_attempts(id,coordination_run_id,attempt_number,started_at,provider,model,policy_version,invocation_id) VALUES (?,?,?,?,?,?,'bounded-luna-v1',?)",
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
                listOf(AiPhysicalCallSpec(operation, 1, hash("synthetic-final-wire"), hash("synthetic-profile"), attempt)),
            ),
        )
    }

    private fun room(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,search_range_source,manual_only,collection_status,created_at,version) VALUES (?,?,'synthetic fence','EITHER','Asia/Seoul',DATE '2026-10-18',DATE '2026-10-20','HOST_SPECIFIED',true,'COLLECTING',?,0)",
            id,
            id.toString().replace("-", "").take(22),
            timestamp(START),
        )
        EPOCHS[id] = UUID.randomUUID()
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
    class SyntheticEnvironment {
        @Bean @Primary
        fun budgetClock(): Clock =
            object : Clock() {
                override fun instant(): Instant = TIME.get()

                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this
            }

        @Bean @Primary
        fun syntheticCoverage(): AiBudgetCoverageEpochQueryPort =
            AiBudgetCoverageEpochQueryPort { room, started -> if (started.isBefore(START))null else EPOCHS[room] }
    }

    companion object {
        private val START = Instant.parse("2026-10-18T00:00:00Z")
        private const val RAW = "일요일 오후 온라인 합성 조건"
        private val TIME = AtomicReference(START)
        private val EPOCHS = ConcurrentHashMap<UUID, UUID>()
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
        }

        @JvmStatic @AfterAll
        fun stopPostgres() {
            postgres.stop()
        }
    }
}
