package com.meetme.server.shared.adapter.output.persistence

import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.precheck.application.port.output.PrecheckOwnershipBindingPort
import com.meetme.server.shared.application.port.output.AiBudgetCoverageEpochQueryPort
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiCurrentBudgetExecution
import com.meetme.server.shared.application.port.output.AiDispatchFingerprints
import com.meetme.server.shared.application.port.output.AiDispatchInputSnapshot
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.shared.domain.InputSupplement
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
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Independent EX-EVIDENCE-FENCE: authorization must use persisted original plus scoped answer. */
@SpringBootTest(properties = ["meetme.reliability.enabled=false", "meetme.data-retention.enabled=false"])
@Import(EvidenceExecutionFencePostgresTest.SyntheticEnvironment::class)
class EvidenceExecutionFencePostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var transactions: PlatformTransactionManager

    @Autowired private lateinit var fence: JdbcAiBudgetExecutionFence

    @Autowired private lateinit var participants: ParticipantRepository

    @Autowired private lateinit var bindings: PrecheckOwnershipBindingPort

    @Autowired private lateinit var credentials: GuestCredentialPort

    @Autowired private lateinit var mapper: ObjectMapper

    @BeforeEach
    fun reset() {
        TIME.set(START)
        EPOCHS.clear()
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
    }

    @Test
    fun `EX-EVIDENCE-FENCE personal dispatch binds the persisted scoped answer`() {
        val fixture = personal()
        val evidence = evidence()
        setCandidateEvidence(fixture.room, evidence)
        val execution = fixture.request.execution.copy(sourceFingerprint = AiDispatchFingerprints.personalSource(RAW, evidence))

        assertNotNull(locked(fixture.request.copy(execution = execution)))
        assertNull(locked(fixture.request), "raw-only hash omits the participant answer")
        val changed = evidence.copy(supplement = evidence.supplement!!.copy(answerText = "오전"))
        assertNull(
            locked(
                fixture.request.copy(execution = execution.copy(sourceFingerprint = AiDispatchFingerprints.personalSource(RAW, changed))),
            ),
        )
    }

    @Test
    fun `EX-EVIDENCE-FENCE personal dispatch fails closed when V2 evidence is absent`() {
        val fixture = personal()
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='PRE_SUBMIT_V2' WHERE id=?", fixture.room)

        assertNull(locked(fixture.request))
    }

    @Test
    fun `EX-EVIDENCE-FENCE final dispatch binds frozen version evidence instead of pending candidate`() {
        val fixture = finalClaim(AiPhysicalOperation.FINAL_PRIMARY)
        val frozenEvidence = evidence()
        setCandidateEvidence(fixture.room, InputEvidence(1, START))
        val version =
            jdbc.queryForObject(
                "SELECT v.id FROM submission_versions v JOIN submission_heads h ON h.id=v.submission_id WHERE h.room_id=? AND v.raw_text IS NOT NULL",
                UUID::class.java,
                fixture.room,
            )!!
        jdbc.update("UPDATE submission_versions SET input_evidence=?::jsonb WHERE id=?", mapper.writeValueAsString(frozenEvidence), version)
        val frozen = AiDispatchInputSnapshot(version.toString(), RAW, "ko-KR", LocalDate.parse("2026-10-18"), frozenEvidence)
        val execution = fixture.request.execution.copy(sourceFingerprint = AiDispatchFingerprints.batchSource(listOf(frozen)))

        assertNotNull(locked(fixture.request.copy(execution = execution)))
        assertNull(locked(fixture.request), "the old raw-only source does not authorize V2 final analysis")
        val pendingSource = AiDispatchFingerprints.batchSource(listOf(frozen.copy(evidence = InputEvidence(1, START))))
        assertNull(locked(fixture.request.copy(execution = execution.copy(sourceFingerprint = pendingSource))))
        val changed = frozenEvidence.copy(supplement = frozenEvidence.supplement!!.copy(answerText = "오전"))
        val staleSource = AiDispatchFingerprints.batchSource(listOf(frozen.copy(evidence = changed)))
        assertNull(locked(fixture.request.copy(execution = execution.copy(sourceFingerprint = staleSource))))
    }

    private fun setCandidateEvidence(
        room: UUID,
        evidence: InputEvidence,
    ) {
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='PRE_SUBMIT_V2' WHERE id=?", room)
        jdbc.update("UPDATE precheck_candidates SET input_evidence=?::jsonb WHERE room_id=?", mapper.writeValueAsString(evidence), room)
    }

    private fun evidence() =
        InputEvidence(
            originalRevision = 1,
            referenceAt = START,
            supplement =
                InputSupplement(
                    UUID.fromString("b58d3ca0-c272-46c9-b37f-4d43949ae734"),
                    1,
                    "오후",
                    4,
                    "어떤 오후 시간인가요?",
                    "오후 7시",
                    "A",
                    START.plusSeconds(1),
                    START,
                ),
            interpretation = InputInterpretation.UNRESOLVED,
            unresolvedReason = "TIME_UNRESOLVED",
        )

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
