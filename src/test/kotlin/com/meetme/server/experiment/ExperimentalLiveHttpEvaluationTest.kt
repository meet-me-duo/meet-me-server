package com.meetme.server.experiment

import com.meetme.server.MeetMeServerApplication
import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.coordination.adapter.output.integration.C5SelectedChoiceContextRun
import com.meetme.server.coordination.adapter.output.integration.C5SelectedChoiceContexts
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderHttpTransport
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.shared.adapter.output.aihttp.AiHttpProviderStatusException
import com.meetme.server.shared.adapter.output.persistence.JdbcAiBudgetExecutionFence
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.SubmissionRules
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Explicitly gated, test-classpath-only live experiment. No normal test starts a container or provider call. */
@EnabledIfEnvironmentVariable(named = "MEETME_LIVE_EVAL", matches = "true")
@SpringBootTest(classes = [MeetMeServerApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ExperimentalLiveHttpEvaluationTest.LiveFixtures::class, C5EvaluationSelectedProviders::class)
@ActiveProfiles("c5-http-evaluation")
class ExperimentalLiveHttpEvaluationTest {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var json: ObjectMapper
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val events = mutableListOf<Map<String, Any?>>()
    private val roomResults = mutableListOf<Map<String, Any?>>()
    private var infrastructureBlock: Map<String, Any?>? = null

    @Autowired private lateinit var application: org.springframework.context.ApplicationContext

    @Autowired private lateinit var journal: C5HttpWireJournal

    @Autowired private lateinit var nativeGate: com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate

    @Autowired private lateinit var selectedAuthority: com.meetme.server.config.SelectedScheduleDslPhysicalAuthority

    @Autowired private lateinit var originalRuntime: AiRuntimeDispatchGuardPort

    @Autowired private lateinit var safety: LiveEvaluationSafety

    @Autowired private lateinit var selectedChoiceContextRun: C5SelectedChoiceContextRun

    @Test
    @EnabledIfEnvironmentVariable(named = "MEETME_NATIVE_SCHEMA_COMPARE", matches = "true")
    fun `accepted family final schema comparison uses one native Luna attempt`() {
        val output = Path.of(required("MEETME_LIVE_OUTPUT"))
        val source = Path.of(required("MEETME_NATIVE_ACCEPTED_RESULTS"))
        val coherent = System.getProperty("meetme.experiment.schema-coherence") == "true"
        require(cap == 1 && (coherent xor (System.getProperty("meetme.experiment.term-precision-schema") == "true")))
        val manifest = json.readTree(Files.readAllBytes(Path.of(required("MEETME_LIVE_MANIFEST"))))
        val remaining = requireNotNull(System.getProperty("meetme.experiment.new-remaining")).toInt()
        require(remaining in 1..20 && remaining == manifest.path("globalRemainingBefore").asInt())
        require(!selectedChoiceContextRun.enabled)
        if (coherent) {
            require(manifest.path("schemaCandidate").asText() == "COHERENT_TEMPORAL")
            require(System.getProperty("meetme.experiment.provider-parity") == "true")
            val expected = requireNotNull(System.getProperty("meetme.experiment.schema-coherence-expected"))
            require(expected == manifest.path("expectedRequest").asText())
            require(sha(Files.readAllBytes(Path.of(expected))) == manifest.path("expectedRequestSha256").asText())
            require(
                System.getProperty("meetme.experiment.schema-coherence-expected-sha") == manifest.path("expectedRequestSha256").asText(),
            )
        }
        val baseline = Path.of(requireNotNull(System.getProperty("meetme.experiment.term-schema-baseline")))
        LiveEvaluationCasePolicy.requireRecipientApproval(manifest, sha(Files.readAllBytes(baseline)), setOf("OpenAI"))
        require(manifest.path("maximumPhysicalCalls").asInt() == 1)
        require(manifest.path("acceptedResultsSha256").asText() == sha(Files.readAllBytes(source)))
        val old = json.readTree(Files.readAllBytes(source)).path("database")
        val room = old.path("meeting_rooms").single()
        val roomId = UUID.fromString(room.path("id").asText())
        val sourceVersions = old.path("submission_versions").values().toList()
        require(sourceVersions.size == 4 && old.path("submission_heads").size() == 4)
        journal.install(nativeGate)
        journal.install(selectedAuthority.gate)
        C5EvaluationCandidateProfile.requireLiveBindings(journal)
        check(!application.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor"))
        journal.arm(roomId)
        val host = old.path("participants").single { it.path("role").asText() == "HOST" }
        val hostAccount = UUID.fromString(host.path("user_id").asText())

        fun timestamp(
            node: JsonNode,
            key: String,
        ): java.sql.Timestamp = java.sql.Timestamp.valueOf(node.path(key).asText())
        val transaction =
            org.springframework.transaction.support.TransactionTemplate(
                application.getBean(org.springframework.transaction.PlatformTransactionManager::class.java),
            )
        transaction.executeWithoutResult {
            check(jdbc.queryForObject("SELECT count(*) FROM meeting_rooms", Int::class.java) == 0)
            jdbc.update(
                """INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
                search_range_source,manual_only,collection_status,created_at,submission_protocol) VALUES (?,?,?,?,?,?,?,'HOST_SPECIFIED',true,'COLLECTING',?,'PRE_SUBMIT_V2')""",
                roomId,
                room.path("invite_code").asText(),
                room.path("purpose").asText(),
                room.path("meeting_mode").asText(),
                room.path("time_zone_id").asText(),
                java.sql.Date.valueOf(room.path("search_start_date").asText()),
                java.sql.Date.valueOf(room.path("search_end_date").asText()),
                timestamp(room, "created_at"),
            )
            old.path("participants").values().forEachIndexed { index, participant ->
                val account =
                    if (participant
                            .path(
                                "user_id",
                            ).isString
                    ) {
                        UUID.fromString(participant.path("user_id").asText())
                    } else {
                        UUID.randomUUID()
                    }
                jdbc.update(
                    "INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,?)",
                    account,
                    "native-comparison-$index",
                    timestamp(participant, "joined_at"),
                )
                jdbc.update(
                    "INSERT INTO participants(id,room_id,user_id,role,display_name,joined_at) VALUES (?,?,?,?,?,?)",
                    UUID.fromString(participant.path("id").asText()),
                    roomId,
                    account,
                    participant.path("role").asText(),
                    "native-comparison-$index",
                    timestamp(participant, "joined_at"),
                )
            }
            old.path("submission_heads").values().forEach { head ->
                jdbc.update(
                    "INSERT INTO submission_heads(id,room_id,participant_id) VALUES (?,?,?)",
                    UUID.fromString(head.path("id").asText()),
                    roomId,
                    UUID.fromString(head.path("participant_id").asText()),
                )
            }
            sourceVersions.forEach { version ->
                jdbc.update(
                    "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at,input_evidence) VALUES (?,?,?,?,?,?,CAST(? AS jsonb))",
                    UUID.fromString(version.path("id").asText()),
                    UUID.fromString(version.path("submission_id").asText()),
                    version.path("revision").asInt(),
                    version.path("raw_text").asText(),
                    version.path("locale").asText(),
                    timestamp(version, "created_at"),
                    json.writeValueAsString(version.path("input_evidence")),
                )
            }
            old.path("submission_heads").values().forEach { head ->
                jdbc.update(
                    "UPDATE submission_heads SET latest_version_id=? WHERE id=?",
                    UUID.fromString(head.path("latest_version_id").asText()),
                    UUID.fromString(head.path("id").asText()),
                )
            }
        }
        val bearer = "Bearer ${tokens.issue(AccountPrincipal(hostAccount, "native-comparison-host"))}"
        val path = "/api/rooms/${room.path("invite_code").asText()}"
        var failureClass: String? = null
        val result =
            linkedMapOf<String, Any?>(
                "scope" to "DIRECT_FINAL_NATIVE_COMPARISON_NOT_NEW_E2E",
                "skipped" to listOf("Jev", "question", "answer POST/recheck"),
            )
        try {
            val closed = request("POST", "$path/close", bearer, body = mapOf("analysis_contract" to "DSL_STRUCTURAL_V5"))
            result["closeStatus"] = closed.status
            require(closed.status == 200)
            val queued = jdbc.queryForMap("SELECT id,batch_id,status,version FROM coordination_runs WHERE room_id=?", roomId)
            require(queued["status"] == "QUEUED")
            val runId = queued.getValue("id") as UUID
            val run =
                com.meetme.server.shared.domain
                    .CoordinationRunId(runId)
            val started = System.nanoTime()
            val deadline = started + Duration.ofSeconds(60).toNanos()
            Files.writeString(
                output.resolve("native-dispatch-start.json"),
                json.writeValueAsString(
                    mapOf(
                        "at" to Instant.now().toString(),
                        "runId" to runId,
                        "batchId" to queued["batch_id"],
                        "maximumPhysical" to 1,
                    ),
                ),
                StandardOpenOption.CREATE_NEW,
            )
            application
                .getBean(com.meetme.server.coordination.application.service.SelectedScheduleDslBatchProcessor::class.java)
                .processLunaOnlyBounded(
                    com.meetme.server.shared.domain
                        .SubmissionBatchId(queued.getValue("batch_id") as UUID),
                    deadline,
                )
            val state = jdbc.queryForMap("SELECT status,version FROM coordination_runs WHERE id=?", runId)
            result["afterFinal"] = state
            if (state["status"] == "MATCHING") {
                val invocation =
                    requireNotNull(
                        application
                            .getBean(
                                com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository::class.java,
                            ).findLatestByRun(run),
                    )
                val route =
                    requireNotNull(
                        application
                            .getBean(
                                com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry::class.java,
                            ).find(runId),
                    )
                result["matched"] =
                    application
                        .getBean(
                            com.meetme.server.coordination.application.service.SelectedScheduleDslMatchingService::class.java,
                        ).processBounded(
                            com.meetme.server.coordination.application.port.output
                                .FinalAnalysisAttemptBinding(route, invocation),
                            requireNotNull(invocation.winnerAttemptId),
                            (state.getValue("version") as Number).toLong(),
                            { System.nanoTime() < deadline },
                        )
            }
            result["productElapsedMs"] = (System.nanoTime() - started) / 1_000_000
            result["nativeFinal"] = jdbc.queryForMap("SELECT id,status,version,candidate_quality FROM coordination_runs WHERE id=?", runId)
            result["room"] = request("GET", path, bearer).body
            result["candidates"] = request("GET", "$path/candidates", bearer).body
            result["unapplied"] = request("GET", "$path/candidates/unapplied-inputs", bearer).body
            result["recommendations"] = request("GET", "$path/recommendations", bearer).body
            if (jdbc.queryForObject("SELECT count(*) FROM schedule_ir_envelopes WHERE run_id=?", Int::class.java, runId) == 1) {
                val stored = jdbc.queryForMap("SELECT version FROM coordination_runs WHERE id=?", runId)
                val route =
                    requireNotNull(
                        application
                            .getBean(
                                com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry::class.java,
                            ).find(runId),
                    )
                result["storedReadRecalculation"] =
                    application
                        .getBean(
                            com.meetme.server.coordination.application.service.ScheduleDslStoredReadService::class.java,
                        ).readStored(run, (stored.getValue("version") as Number).toLong(), route.fingerprint())
            }
        } catch (failure: Throwable) {
            failureClass = failure.javaClass.simpleName
            throw AssertionError("Native final comparison stopped; inspect protected results ($failureClass)")
        } finally {
            Files.writeString(
                output.resolve("results.json"),
                redact(
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(
                        mapOf(
                            "result" to result,
                            "failureClass" to failureClass,
                            "httpEvents" to events,
                            "database" to databaseEvidence(),
                            "nativePhysicalJournal" to journal.snapshot(),
                        ),
                    ),
                ),
                StandardOpenOption.CREATE_NEW,
            )
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MEETME_LOCAL_FIRST_POST_DIAG", matches = "true")
    fun `local first room POST collects in-flight evidence without provider access`() {
        check(localFirstPostDiagnostic)
        check(listOf("TYPESAFE_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY").all { System.getenv(it).isNullOrBlank() })
        check(selectedAuthority.gate.properties.enabled)
        check(
            listOf("provider", "decision-routing", "classifier").all {
                application.environment.getProperty("meetme.precheck.$it.enabled") == "false"
            },
        )
        val output = Path.of(required("MEETME_LIVE_OUTPUT")).toAbsolutePath()
        require(output.startsWith(Path.of("/workspace/scratch/live-eval")))
        val samples = CopyOnWriteArrayList<Map<String, Any?>>()
        val start = System.nanoTime()

        fun sample(phase: String) {
            val stacks =
                Thread
                    .getAllStackTraces()
                    .entries
                    .filter { it.key.name.contains("exec-") || it.key.name == "Test worker" }
                    .associate { it.key.name to it.value.take(22).map(StackTraceElement::toString) }
            val database =
                runCatching {
                    jdbc
                        .queryForList(
                            """
                            SELECT pid, state, wait_event_type, wait_event, query_start, xact_start,
                              pg_blocking_pids(pid)::text AS blocking_pids, left(query, 500) AS query
                            FROM pg_stat_activity
                            WHERE datname=current_database() AND pid<>pg_backend_pid()
                            ORDER BY pid
                            """.trimIndent(),
                        ).map { row -> row.mapValues { (_, value) -> value?.toString() } }
                }.getOrElse { listOf(mapOf("sampleFailureClass" to it.javaClass.simpleName)) }
            samples +=
                mapOf(
                    "phase" to phase,
                    "at" to Instant.now().toString(),
                    "elapsedMsSinceDiagnosticStart" to (System.nanoTime() - start) / 1_000_000,
                    "threadStacks" to stacks,
                    "database" to database,
                )
        }
        val principal = AccountPrincipal(UUID.randomUUID(), "Synthetic local diagnostic host")
        jdbc.update(
            "INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,?)",
            principal.accountId,
            principal.displayName,
            java.sql.Timestamp.from(referenceAt),
        )
        val host = "Bearer ${tokens.issue(principal)}"
        val scheduler =
            java.util.concurrent.Executors
                .newSingleThreadScheduledExecutor()
        var status: Int? = null
        var failureClass: String? = null
        val requestStart = System.nanoTime()
        val tasks =
            listOf(2L, 5L, 8L).map { delay ->
                scheduler.schedule({ sample("IN_FLIGHT_${delay}s") }, delay, java.util.concurrent.TimeUnit.SECONDS)
            }
        try {
            val room = payload.path("rooms").get(0)
            val range = room.path("host").path("dateSearchRange")
            val created =
                request(
                    "POST",
                    "/api/rooms",
                    host,
                    body =
                        mapOf(
                            "purpose" to "Synthetic local first POST diagnosis",
                            "meeting_mode" to "IN_PERSON",
                            "manual_only" to true,
                            "search_start_date" to range.path("startDate").asText(),
                            "search_end_date" to LocalDate.parse(range.path("endDate").asText()).plusDays(1).toString(),
                        ),
                )
            status = created.status
            check(status == 201)
        } catch (failure: Throwable) {
            failureClass = failure.javaClass.simpleName
            throw failure
        } finally {
            val clientElapsedMs = (System.nanoTime() - requestStart) / 1_000_000
            tasks.forEach { it.cancel(false) }
            scheduler.shutdown()
            sample("CLIENT_END_BEFORE_FIXTURE_CLEANUP")
            val report =
                mapOf(
                    "request" to "POST /api/rooms",
                    "localTimeoutSeconds" to 10,
                    "requestElapsedMs" to clientElapsedMs,
                    "requestStartAfterDiagnosticStartMs" to (requestStart - start) / 1_000_000,
                    "returnedStatus" to status,
                    "failureClass" to failureClass,
                    "providerKeysAbsent" to true,
                    "providerFlagsDisabled" to true,
                    "selectedNativeGuardUnchangedEnabled" to selectedAuthority.gate.properties.enabled,
                    "nativePhysicalJournal" to journal.snapshot(),
                    "httpEvents" to events,
                    "visibleRoomCount" to jdbc.queryForObject("SELECT count(*) FROM meeting_rooms", Int::class.java),
                    "samples" to samples.toList(),
                )
            Files.writeString(
                output.resolve("local-first-post-diagnosis.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardOpenOption.CREATE_NEW,
            )
        }
    }

    @Test
    @DisabledIfEnvironmentVariable(named = "MEETME_LOCAL_FIRST_POST_DIAG", matches = "true")
    fun `explicitly approved blind payload traverses actual HTTP workers and providers once`() {
        val output = Path.of(required("MEETME_LIVE_OUTPUT")).toAbsolutePath()
        require(output.startsWith(Path.of("/workspace/scratch/live-eval")))
        Files.createDirectories(output)
        Files.setPosixFilePermissions(
            output,
            java.nio.file.attribute.PosixFilePermissions
                .fromString("rwx------"),
        )
        Files.writeString(output.resolve("started"), Instant.now().toString(), StandardOpenOption.CREATE_NEW)
        val manifest = mapper.readTree(Files.readAllBytes(Path.of(required("MEETME_LIVE_MANIFEST"))))
        LiveEvaluationCasePolicy.requireRecipientApproval(manifest, sha(payloadBytes), setOf("TypeSafeAI", "OpenAI", "GoogleGemini"))
        payload.path("rooms").values().forEach(LiveEvaluationCasePolicy::roomSettings)
        C5EvaluationCandidateProfile.verify()
        require(payload.path("rooms").size() == 1 && cap <= 17) { "One approved family case, at most seventeen physical calls" }
        journal.install(nativeGate)
        journal.install(selectedAuthority.gate)
        C5EvaluationCandidateProfile.requireLiveBindings(journal)
        require(manifest.path("approved").asBoolean(false))
        require(manifest.path("payloadSha256").asText() == sha(payloadBytes))
        require(manifest.path("maximumPhysicalCalls").asInt() == cap)
        require(manifest.path("maximumRooms").asInt() in 1..2)
        require(payload.path("rooms").size() in 1..manifest.path("maximumRooms").asInt())
        require(
            payload.path("rooms").values().all {
                java.time.OffsetDateTime
                    .parse(it.path("referenceAt").asText())
                    .toInstant() ==
                    referenceAt
            },
        )
        val answerBytes = System.getenv("MEETME_LIVE_ANSWERS")?.takeIf { it.isNotBlank() }?.let { Files.readAllBytes(Path.of(it)) }
        require(manifest.path("answersSha256").asText() == (answerBytes?.let(::sha) ?: "NONE"))
        val answers = answerBytes?.let(mapper::readTree)
        if (answers != null) {
            payload.path("rooms").values().forEach { room ->
                room.path("participants").values().forEach { participant ->
                    answerOracle(answers, room.path("roomId").asText(), participant.path("participantId").asText())
                }
            }
        }
        var failureKind: String? = null
        var driverCompleted = false
        try {
            payload.path("rooms").values().forEach { room -> safety.guard { runRoom(room, answers) } }
            driverCompleted = true
        } catch (failure: Throwable) {
            // Never persist a provider exception message, stack trace, token, cookie or response headers.
            failureKind = failure.javaClass.simpleName
            if (failure is LiveEvaluationTechnicalStop) {
                infrastructureBlock =
                    mapOf(
                        "stage" to failure.barrier.stage,
                        "category" to failure.barrier.category,
                        "httpStatus" to failure.barrier.httpStatus,
                        "failureCode" to failure.barrier.failureCode,
                    )
            }
            throw AssertionError("Live experiment stopped; inspect sanitized results.json ($failureKind)")
        } finally {
            val report =
                linkedMapOf<String, Any?>(
                    "schemaVersion" to "meetme-live-http-evaluation/v1",
                    "split" to payload.path("split").asText(),
                    "payloadSha256" to sha(payloadBytes),
                    "manifest" to manifest,
                    "failureKind" to failureKind,
                    "infrastructureBlock" to infrastructureBlock,
                    "precheckTransportFailures" to precheckTransportFailures.toList(),
                    "notes" to
                        listOf(
                            "Classifier minimum confidence 0.8 is a fixed experimental setting, not a validated threshold.",
                            "Answers are either exact precommitted oracle text or explicitly marked TEST_SYNTHETIC branches with expectations frozen before POST.",
                            "Host input is room purpose; host is not an additional availability respondent.",
                            "Payload inclusive search end is converted to the API exclusive end by adding one day.",
                            "Admission identities are an upper-bound guard, not proof that HTTP was sent.",
                            "Precheck wire records and final transport lifecycle rows are separately identified; " +
                                "no observed Luna call means no Luna execution claim.",
                            "Token observations are not complete bills or monetary cost evidence.",
                        ),
                    "rooms" to roomResults,
                    "httpEvents" to events,
                    "admittedCallIdentities" to admissions.toList(),
                    "precheckWireCalls" to wireCalls.toList(),
                    "classifierResults" to classifierResults.toList(),
                    "answerRouting" to answerRouting.toList(),
                    "parserResults" to parserResults.toList(),
                    "parserFailureEvents" to parserFailureEvents.toList(),
                    "physicalStageCounts" to physicalStageCounts(),
                    "nativePhysicalJournal" to journal.snapshot(),
                    "database" to databaseEvidence(),
                )
            Files.writeString(output.resolve("results.json"), redact(json.writerWithDefaultPrettyPrinter().writeValueAsString(report)))
            val summary =
                mapOf(
                    "split" to payload.path("split").asText(),
                    "failureKind" to failureKind,
                    "infrastructureBlock" to infrastructureBlock,
                    "sourceSha256" to manifest.path("sourceSha256"),
                    "configSha256" to manifest.path("configSha256"),
                    "payloadSha256" to manifest.path("payloadSha256"),
                    "answersSha256" to manifest.path("answersSha256"),
                    "roomCount" to roomResults.size,
                    "maximumPhysicalCalls" to cap,
                    "admittedCallIdentityCount" to admissions.size,
                    "physicalStageCounts" to physicalStageCounts(),
                    "answerDecisionCounts" to answerRouting.groupingBy { it.getValue("decision") }.eachCount(),
                    "httpStatusCounts" to events.groupingBy { it.getValue("status") }.eachCount(),
                )
            Files.writeString(output.resolve("summary.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(summary))
            val completedAnalyses =
                roomResults.count { room ->
                    val state = (room["roomAfterAnalysis"] as? JsonNode)?.path("public_status")?.asText()
                    LiveEvaluationSafety.isCompletedAnalysis(
                        state,
                        room["candidatesStatus"] as? Int,
                        room["recommendationsStatus"] as? Int,
                    )
                }
            val analysisCompleted = driverCompleted && completedAnalyses == payload.path("rooms").size()
            val safetyReport =
                mapOf(
                    "schemaVersion" to "meetme-live-safety-report/v1",
                    "split" to payload.path("split").asText(),
                    "outcome" to if (driverCompleted && analysisCompleted && safety.barrier == null) "CONTINUE" else "STOP",
                    "driverCompleted" to driverCompleted,
                    "analysisCompleted" to analysisCompleted,
                    "barrier" to safety.barrier,
                    "sourceCommit" to manifest.path("sourceBaseRevision").asText(),
                    "sourceSha256" to manifest.path("sourceSha256").asText(),
                    "configSha256" to manifest.path("configSha256").asText(),
                    "semanticFailureIsNotProviderSuccess" to true,
                    "completedRooms" to roomResults.size,
                    "completedAnalyses" to completedAnalyses,
                    "continuationBlock" to
                        when {
                            safety.barrier?.category == "INCOMPLETE_ANALYSIS" -> "INCOMPLETE_ANALYSIS"
                            safety.barrier != null -> "COMMON_TECHNICAL_BARRIER"
                            !driverCompleted -> "INCOMPLETE_DRIVER"
                            !analysisCompleted -> "INCOMPLETE_ANALYSIS"
                            else -> null
                        },
                )
            Files.writeString(output.resolve("safety-report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(safetyReport))
        }
    }

    private fun runRoom(
        room: JsonNode,
        answers: JsonNode?,
    ) {
        try {
            runRoomWithChoiceContext(room, answers)
        } finally {
            selectedChoiceContextRun.clear()
        }
    }

    private fun runRoomWithChoiceContext(
        room: JsonNode,
        answers: JsonNode?,
    ) {
        val settings = LiveEvaluationCasePolicy.roomSettings(room)
        require(!selectedChoiceContextRun.enabled || settings.analysisContract == "DSL_STRUCTURAL_V5")
        val name = room.path("roomId").asText()
        val principal = AccountPrincipal(UUID.randomUUID(), "Synthetic live host $name")
        jdbc.update(
            "INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,?)",
            principal.accountId,
            principal.displayName,
            java.sql.Timestamp.from(referenceAt),
        )
        val host = "Bearer ${tokens.issue(principal)}"
        val sourceHost = room.path("host")
        val range = sourceHost.path("dateSearchRange")
        val created =
            request(
                "POST",
                "/api/rooms",
                host,
                body =
                    mapOf(
                        "purpose" to sourceHost.path("input").asText(),
                        "meeting_mode" to settings.meetingMode,
                        "manual_only" to true,
                        "search_start_date" to range.path("startDate").asText(),
                        "search_end_date" to LocalDate.parse(range.path("endDate").asText()).plusDays(1).toString(),
                    ),
            )
        require(created.status == 201)
        val invite = created.body.path("invite_code").asText()
        require(invite.matches(Regex("[A-Za-z0-9_-]{22}")))
        val actualRoomId = jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code=?", UUID::class.java, invite)!!
        selectedChoiceContextRun.begin(actualRoomId)
        journal.arm(actualRoomId)
        val path = "/api/rooms/$invite"
        val result = linkedMapOf<String, Any?>("sourceRoomId" to name, "inviteCode" to invite, "created" to created.body)
        roomResults += result
        val participantResults = mutableListOf<Map<String, Any?>>()
        result["participants"] = participantResults
        room.path("participants").values().forEach { participant ->
            val id = participant.path("participantId").asText()
            val joined = request("POST", "$path/participants", body = mapOf("display_name" to "$name-$id"))
            require(joined.status == 201)
            val cookie = joined.cookie ?: error("Guest cookie missing")
            val checkPath = "$path/submission/checks"
            var snapshot =
                safety.guard {
                    val submitted =
                        request(
                            "POST",
                            checkPath,
                            cookie = cookie,
                            body =
                                mapOf(
                                    "request_id" to UUID.randomUUID(),
                                    "expected_revision" to 0,
                                    "raw_text" to participant.path("input").asText(),
                                ),
                        )
                    require(submitted.status == 202)
                    poll("$checkPath/current", cookie) {
                        it.body
                            .path("check")
                            .path("status")
                            .asText() !in
                            setOf("QUEUED", "CHECKING", "")
                    }
                }
            val beforeAnswer = snapshot.body
            stopAfterInfrastructureBarrier()
            val oracle = answerOracle(answers, name, id)
            val answer =
                if (snapshot.body
                        .path("check")
                        .path("status")
                        .asText() == "NEEDS_CHOICE" &&
                    oracle != null
                ) {
                    awaitAnswerDecision(name, id, snapshot.body, oracle)
                } else {
                    null
                }
            var answered = false
            if (answer != null) {
                val current = snapshot.body
                val pendingChoice = captureChoiceContext(actualRoomId, current, answer)
                snapshot =
                    safety.guard {
                        val answeredResponse =
                            request(
                                "POST",
                                "$checkPath/${current.path("check").path("id").asText()}/answer",
                                cookie = cookie,
                                body =
                                    mapOf(
                                        "request_id" to UUID.randomUUID(),
                                        "expected_revision" to current.path("candidate").path("revision").asInt(),
                                        "expected_check_version" to current.path("check").path("version").asLong(),
                                        "question_id" to
                                            current
                                                .path("check")
                                                .path("question")
                                                .path("id")
                                                .asText(),
                                    ) + answer.bodyFields(),
                            )
                        require(answeredResponse.status == 202)
                        answered = true
                        poll("$checkPath/current", cookie) {
                            it.body
                                .path("check")
                                .path("status")
                                .asText() !in setOf("QUEUED", "CHECKING", "")
                        }
                    }
                if (pendingChoice != null) {
                    val derived =
                        requireNotNull(
                            application
                                .getBean(
                                    PrecheckRepository::class.java,
                                ).find(
                                    UUID.fromString(
                                        snapshot.body
                                            .path("check")
                                            .path("id")
                                            .asText(),
                                    ),
                                ),
                        )
                    val accepted =
                        requireNotNull(
                            application
                                .getBean(
                                    SubmissionRepository::class.java,
                                ).findByParticipant(ParticipantId(derived.candidate.participantId)),
                        )
                    selectedChoiceContextRun.record(pendingChoice.bind(derived, accepted))
                }
            }
            participantResults +=
                mapOf(
                    "sourceParticipantId" to id,
                    "beforeAnswer" to beforeAnswer,
                    "answerSent" to answered,
                    "finalSnapshot" to snapshot.body,
                )
            stopAfterInfrastructureBarrier()
            require(
                snapshot.body
                    .path("check")
                    .path("status")
                    .asText() in setOf("CLEAR", "RECEIVED"),
            ) {
                "Participant remains unresolved; do not close a partial cohort as a whole-family success"
            }
        }
        selectedChoiceContextRun.seal(actualRoomId)
        safety.guard {
            result["closed"] =
                request(
                    "POST",
                    "$path/close",
                    host,
                    body = LiveEvaluationCasePolicy.closeCommand(room),
                ).let {
                    require(it.status == 200)
                    it.body
                }
            result["roomAfterAnalysis"] =
                poll(path, bearer = host, timeout = Duration.ofSeconds(90)) {
                    it.body.path("public_status").asText() !in
                        setOf("ANALYZING", "COLLECTING", "")
                }.body
        }
        val candidates = request("GET", "$path/candidates", host)
        result["candidatesStatus"] = candidates.status
        result["candidates"] = candidates.body
        result["unapplied"] = request("GET", "$path/candidates/unapplied-inputs", host).body
        val recommendations = request("GET", "$path/recommendations", host)
        result["recommendationsStatus"] = recommendations.status
        result["recommendations"] = recommendations.body
        val storedRun =
            jdbc.queryForMap(
                "SELECT id,version FROM coordination_runs WHERE room_id=? ORDER BY created_at DESC LIMIT 1",
                actualRoomId,
            )
        val storedRunId = storedRun.getValue("id") as UUID
        val storedRoutes =
            application.getBean(
                com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry::class.java,
            )
        val storedRoute = requireNotNull(storedRoutes.find(storedRunId))
        val stored = application.getBean(com.meetme.server.coordination.application.service.ScheduleDslStoredReadService::class.java)
        result["freshJdbcRestoreAndRecalculation"] =
            stored.readStored(
                com.meetme.server.shared.domain
                    .CoordinationRunId(storedRunId),
                (storedRun.getValue("version") as Number).toLong(),
                storedRoute.fingerprint(),
            )
        safety.requireCompletedAnalysis(
            (result["roomAfterAnalysis"] as? JsonNode)?.path("public_status")?.asText(),
            candidates.status,
            recommendations.status,
        )
        val option =
            recommendations.body
                .path("options")
                .values()
                .firstOrNull()
        val variant = option?.path("variants")?.values()?.firstOrNull()
        if (recommendations.status == 200 && option != null && variant != null) {
            val start = Instant.parse(option.path("time_range").path("start_at").asText())
            val end = start.plusSeconds(sourceHost.path("meetingDurationMinutes").asLong() * 60)
            if (end <= Instant.parse(option.path("time_range").path("end_at").asText())) {
                val selected =
                    request(
                        "POST",
                        "$path/recommendations/${option.path("option_id").asText()}/confirmation",
                        host,
                        body =
                            mapOf(
                                "analysis_id" to recommendations.body.path("analysis_id").asText(),
                                "variant_id" to variant.path("variant_id").asText(),
                                "start_at" to start.toString(),
                                "end_at" to end.toString(),
                            ),
                    )
                result["selectionStatus"] = selected.status
                result["selection"] = selected.body
                result["confirmedResult"] = request("GET", "$path/result", host).body
            } else {
                result["selectionSkipped"] = "First observed recommendation does not fit the payload meeting duration"
            }
        } else {
            result["selectionSkipped"] = "No observed recommendation to select; no synthetic candidate injected"
        }
        stopAfterInfrastructureBarrier()
    }

    private fun stopAfterInfrastructureBarrier() = safety.checkpoint()

    private fun captureChoiceContext(
        roomId: UUID,
        snapshot: JsonNode,
        answer: LiveEvaluationSyntheticAnswer,
    ): C5SelectedChoiceContexts.Pending? {
        if (!selectedChoiceContextRun.enabled || answer.choiceId == null) return null
        val state = snapshot.path("check")
        val source = requireNotNull(application.getBean(PrecheckRepository::class.java).find(UUID.fromString(state.path("id").asText())))
        require(source.candidate.roomId == roomId && source.candidate.rawText == snapshot.path("candidate").path("raw_text").asText())
        val pending =
            requireNotNull(
                C5SelectedChoiceContexts.prepare(
                    source,
                    snapshot.path("candidate").path("revision").asInt(),
                    state.path("version").asLong(),
                    UUID.fromString(state.path("question").path("id").asText()),
                    answer.choiceId,
                    null,
                    application.getBean(Clock::class.java).instant(),
                ),
            )
        val option =
            state
                .path("question")
                .path("options")
                .values()
                .single { it.path("id").asText() == answer.choiceId }
        require(pending.source.question?.prompt == state.path("question").path("text").asText())
        require(pending.selected.label == option.path("text").asText())
        require(pending.selected.proposedText == option.path("proposed_text").asText())
        require(SubmissionRules.trimRawText(pending.selected.proposedText) == answer.answer)
        return pending
    }

    private fun answerOracle(
        answers: JsonNode?,
        roomId: String,
        participantId: String,
    ): JsonNode? {
        if (answers == null) return null
        require(answers.path("answerPolicy").path("maximumQuestionsPerParticipant").asInt() == 1)
        if (answers.path("answerPolicy").path("mode").asText() == "TEST_SYNTHETIC") {
            return json.createObjectNode().put("mode", "TEST_SYNTHETIC")
        }
        val rooms = answers.path("rooms")
        require(rooms.isArray)
        val room = rooms.values().single { it.path("roomId").asText() == roomId }
        val oracle = room.path("participants").path(participantId)
        require(oracle.isObject && oracle.propertyNames().toSet() == setOf("directAnswer", "offTopicResponse"))
        listOf("directAnswer", "offTopicResponse").forEach { field ->
            require(oracle.path(field).isString && oracle.path(field).asText().isNotBlank())
        }
        return oracle
    }

    private fun awaitAnswerDecision(
        roomId: String,
        participantId: String,
        snapshot: JsonNode,
        oracle: JsonNode,
    ): LiveEvaluationSyntheticAnswer? {
        val question = snapshot.path("check").path("question")
        val questionId = UUID.fromString(question.path("id").asText()).toString()
        val routing = Path.of(required("MEETME_LIVE_OUTPUT")).resolve("question-routing")
        Files.createDirectories(routing)
        val pending = routing.resolve("$questionId.pending.json")
        val decisionFile = routing.resolve("$questionId.decision.json")
        val oracleFile = Path.of(required("MEETME_LIVE_ANSWERS"))
        val oracleSha = sha(Files.readAllBytes(oracleFile))
        val approved = json.readTree(Files.readAllBytes(Path.of(required("MEETME_LIVE_MANIFEST"))))
        require(oracleSha == approved.path("answersSha256").asText())
        val description =
            mapOf(
                "roomId" to roomId,
                "participantId" to participantId,
                "questionId" to questionId,
                "question" to question,
                "originalInput" to snapshot.path("candidate").path("raw_text"),
                "oracleFileSha256" to oracleSha,
                "allowedDecisionFields" to
                    if (oracle.path("mode").asText() == "TEST_SYNTHETIC") {
                        listOf(
                            "mode",
                            "questionId",
                            "questionSha256",
                            "sourceSha256",
                            "revision",
                            "checkVersion",
                            "questionConsistent",
                            "answer",
                            "expectedOutcome",
                        ) + if (selectedChoiceContextRun.enabled) listOf("submissionPath", "choiceId") else emptyList()
                    } else {
                        listOf("directAnswer", "offTopicResponse")
                    },
                "syntheticAnswerMode" to (oracle.path("mode").asText() == "TEST_SYNTHETIC"),
                "questionSha256" to sha(mapper.writeValueAsBytes(question)),
                "sourceSha256" to
                    sha(
                        snapshot
                            .path("candidate")
                            .path("raw_text")
                            .asText()
                            .toByteArray(Charsets.UTF_8),
                    ),
                "revision" to snapshot.path("candidate").path("revision").asLong(),
                "checkVersion" to snapshot.path("check").path("version").asLong(),
                "policy" to
                    if (oracle.path("mode").asText() == "TEST_SYNTHETIC") {
                        "Read the actual input and question; stop on contradictions. " +
                            "Prepare a test-only answer and expected outcome before POST. " +
                            "This is not a participant fact. Keep every question/source hash and revision exact."
                    } else {
                        "A question about submitted date/time/boundaries/availability/preferences/location is relevant; " +
                            "unrelated or undisclosed facts, new availability or constraints are off topic. " +
                            "Choose one exact oracle field; never rewrite."
                    },
            )
        Files.writeString(pending, json.writerWithDefaultPrettyPrinter().writeValueAsString(description), StandardOpenOption.CREATE_NEW)
        val cutoff = System.nanoTime() + Duration.ofSeconds(120).toNanos()
        while (!Files.exists(decisionFile)) {
            if (System.nanoTime() >= cutoff) {
                answerRouting +=
                    mapOf(
                        "roomId" to roomId,
                        "participantId" to participantId,
                        "questionId" to questionId,
                        "decision" to "TIMEOUT_UNANSWERED",
                        "oracleFileSha256" to oracleSha,
                    )
                return null
            }
            Thread.sleep(250)
        }
        val decisionBytes = Files.readAllBytes(decisionFile)
        require(decisionBytes.size <= if (oracle.path("mode").asText() == "TEST_SYNTHETIC") 32768 else 1024)
        val decision = json.readTree(decisionBytes)
        if (oracle.path("mode").asText() == "TEST_SYNTHETIC") {
            require(sha(Files.readAllBytes(oracleFile)) == oracleSha)
            val synthetic =
                LiveEvaluationCasePolicy.syntheticAnswer(
                    decision,
                    snapshot,
                    snapshot.path("candidate").path("raw_text").asText(),
                    allowChoice = selectedChoiceContextRun.enabled,
                )
            val frozen =
                mapOf(
                    "questionId" to questionId,
                    "mode" to "TEST_SYNTHETIC_NOT_PARTICIPANT_FACT",
                    "decisionSha256" to sha(decisionBytes),
                    "sourceSha256" to decision.path("sourceSha256").asText(),
                    "revision" to decision.path("revision").asLong(),
                    "checkVersion" to decision.path("checkVersion").asLong(),
                    "answerSha256" to sha(synthetic.answer.toByteArray(Charsets.UTF_8)),
                    "submissionPath" to if (synthetic.choiceId == null) "direct_text" else "choice_id",
                    "expectedOutcome" to synthetic.expectedOutcome,
                    "frozenBeforeAnswerPostAt" to Instant.now().toString(),
                ) + (synthetic.choiceId?.let { mapOf("choiceId" to it) } ?: emptyMap())
            Files.writeString(
                routing.resolve("$questionId.expected-before-post.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(frozen),
                StandardOpenOption.CREATE_NEW,
            )
            answerRouting += frozen + mapOf("roomId" to roomId, "participantId" to participantId, "decision" to "TEST_SYNTHETIC")
            return synthetic
        }
        require(decision.isObject && decision.propertyNames().toSet() == setOf("questionId", "field"))
        require(decision.path("questionId").asText() == questionId)
        val field = decision.path("field").asText()
        require(field in setOf("directAnswer", "offTopicResponse"))
        require(sha(Files.readAllBytes(oracleFile)) == oracleSha)
        val text = oracle.path(field).asText()
        answerRouting +=
            mapOf(
                "roomId" to roomId,
                "participantId" to participantId,
                "questionId" to questionId,
                "decision" to field,
                "oracleFileSha256" to oracleSha,
                "decisionFileSha256" to sha(decisionBytes),
                "responseTextSha256" to sha(text.toByteArray(Charsets.UTF_8)),
            )
        return LiveEvaluationSyntheticAnswer(text, "PRECOMMITTED_ORACLE:$field")
    }

    private data class Response(
        val status: Int,
        val body: JsonNode,
        val cookie: String?,
    )

    private fun request(
        method: String,
        path: String,
        bearer: String? = null,
        cookie: String? = null,
        body: Any? = null,
    ): Response {
        val builder =
            HttpRequest
                .newBuilder(
                    URI("http://127.0.0.1:$port$path"),
                ).timeout(Duration.ofSeconds(10))
                .header("Accept-Language", "ko-KR")
        bearer?.let { builder.header("Authorization", it) }
        cookie?.let { builder.header("Cookie", it) }
        if (method == "POST") builder.header("Origin", ORIGIN).header("Content-Type", "application/json")
        builder.method(
            method,
            body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody(),
        )
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val parsed =
            try {
                json.readTree(response.body())
            } catch (_: Exception) {
                json.createObjectNode().put("nonJsonBody", true)
            }
        events += mapOf("method" to method, "path" to path, "status" to response.statusCode())
        val guest =
            response
                .headers()
                .allValues("set-cookie")
                .firstOrNull { it.startsWith("meet_me_guest=") }
                ?.substringBefore(';')
        return Response(response.statusCode(), parsed, guest)
    }

    private fun poll(
        path: String,
        cookie: String? = null,
        bearer: String? = null,
        timeout: Duration = Duration.ofSeconds(45),
        done: (Response) -> Boolean,
    ): Response {
        val end = System.nanoTime() + timeout.toNanos()
        do {
            val response = request("GET", path, bearer, cookie)
            require(response.status == 200)
            if (done(response)) return response
            Thread.sleep(250)
        } while (System.nanoTime() < end)
        error("HTTP polling deadline elapsed")
    }

    private fun physicalStageCounts(): Map<String, Any> {
        val counts =
            linkedMapOf<String, Any>(
                "PRECHECK_CLASSIFICATION" to wireCalls.count { it["stage"] == "PRECHECK_CLASSIFICATION" },
                "PRECHECK_COUNT" to wireCalls.count { it["stage"] == "PRECHECK_COUNT" },
                "PRECHECK_GENERATION" to wireCalls.count { it["stage"] == "PRECHECK_GENERATION" },
            )
        val finals =
            jdbc.queryForList(
                "SELECT a.provider, count(*) AS calls FROM parser_transport_client_calls c " +
                    "JOIN coordination_attempts a ON a.id=c.attempt_id GROUP BY a.provider",
            )
        counts["FINAL_PRIMARY"] = finals.firstOrNull { it["provider"] == "GEMINI" }?.get("calls") ?: 0
        counts["FINAL_FALLBACK"] = finals.firstOrNull { it["provider"] == "OPENAI" }?.get("calls") ?: 0
        counts["evidence"] =
            "Precheck requestHeadersEnd (headers emitted); final actual SDK client lifecycle rows (not provider billing proof)"
        return counts
    }

    private fun databaseEvidence(): Map<String, Any?> =
        listOf(
            "meeting_rooms",
            "participants",
            "submission_heads",
            "schedule_ir_envelopes",
            "schedule_ir_inputs",
            "schedule_ir_execution_bindings",
            "final_analysis_routes",
            "precheck_candidates",
            "precheck_checks",
            "precheck_executions",
            "submission_versions",
            "structured_submission_results",
            "submission_batches",
            "submission_batch_items",
            "coordination_runs",
            "coordination_attempts",
            "parser_transport_observations",
            "parser_transport_client_calls",
            "outbox_events",
            "analysis_invocations",
            "recommendation_analyses",
            "recommendation_options",
            "recommendation_variants",
            "recommendation_variant_participants",
            "recommendation_selections",
        ).associateWith { table ->
            // Table names are compile-time constants, never payload SQL. Remove ownership credentials before serialization.
            jdbc.queryForList("SELECT * FROM $table").map { row ->
                row
                    .filterKeys { key ->
                        key !in
                            setOf("owner_token", "owner_binding", "credential_digest", "fingerprint")
                    }.mapValues { (_, value) ->
                        when (value) {
                            null, is Number, is Boolean, is String -> value
                            is org.postgresql.util.PGobject ->
                                value.value?.let { raw ->
                                    runCatching<Any?> { json.readTree(raw) }.getOrElse { raw }
                                }
                            else -> value.toString()
                        }
                    }
            }
        }

    @TestConfiguration(proxyBeanMethods = false)
    class LiveFixtures(
        private val dispatchAdmission: (AiBudgetReserveRequest) -> Boolean = ::admit,
    ) {
        @Bean
        fun nativeComparisonBackgroundStop(): org.springframework.beans.factory.config.BeanFactoryPostProcessor =
            org.springframework.beans.factory.config.BeanFactoryPostProcessor { factory ->
                if (System.getenv("MEETME_NATIVE_SCHEMA_COMPARE") == "true") {
                    val registry = factory as org.springframework.beans.factory.support.BeanDefinitionRegistry
                    val name = "org.springframework.context.annotation.internalScheduledAnnotationProcessor"
                    if (registry.containsBeanDefinition(name)) registry.removeBeanDefinition(name)
                }
            }

        @Bean
        fun liveEvaluationSafety(jdbc: JdbcTemplate): LiveEvaluationSafety = LiveEvaluationSafety { safetySignals(jdbc) }

        @Bean
        fun familyCaseWireJournal(): C5HttpWireJournal =
            C5HttpWireJournal(
                Path.of(required("MEETME_LIVE_OUTPUT")),
                maximumPhysicalCalls = cap,
                carriedRemaining = 0,
                newRemaining = System.getProperty("meetme.experiment.new-remaining", "20").toInt(),
                priorJevUpperBound = java.math.BigDecimal("0.04072887"),
                secretValues = listOf("TYPESAFE_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY").mapNotNull(System::getenv),
            )

        @Bean
        fun liveEvaluationAdmission(safety: LiveEvaluationSafety): LiveEvaluationAdmission = LiveEvaluationAdmission(safety)

        @Bean @Primary
        fun liveReferenceClock(): Clock = LiveEvaluationCasePolicy.progressingClock(referenceAt, Clock.systemUTC())

        @Bean @Primary
        fun liveSyntheticTokens(clock: Clock): AccessTokenPort =
            Rs256AccessTokenAdapter(
                keys.private,
                mapOf(
                    "live-eval-local" to keys.public,
                ),
                "live-eval-local",
                clock,
                "https://synthetic.invalid",
                "meetme-web",
            )

        @Bean @Primary
        fun boundedActualDispatch(
            delegate: JdbcAiBudgetExecutionFence,
            admission: LiveEvaluationAdmission,
            journal: C5HttpWireJournal? = null,
        ): AiRuntimeDispatchGuardPort {
            journal?.sourceFenceConnected()
            return AiRuntimeDispatchGuardPort { request, at ->
                val original = delegate.authorize(request, at)
                if (original == null) {
                    null
                } else {
                    journal?.registerOriginalGrant(request)
                    if (journal != null &&
                        request.execution.kind ==
                        com.meetme.server.shared.application.port.output.AiBudgetExecutionKind.FINAL_ANALYSIS
                    ) {
                        // Only the current native final operation owns its bounded retries/fallback.
                        // The original fence and physical caps still authorize every child request.
                        if (dispatchAdmission(request)) original else null
                    } else {
                        admission.authorize({ original }) { dispatchAdmission(request) }
                    }
                }
            }
        }

        @Bean
        fun observedActualPrecheckTransport(journal: C5HttpWireJournal? = null): PrecheckProviderHttpTransport =
            PrecheckProviderHttpTransport(
                OkHttpClient
                    .Builder()
                    .eventListenerFactory { call ->
                        val event = java.util.concurrent.ConcurrentHashMap<String, Any>()
                        event["stage"] = wireStage(call.request().url.host, call.request().url.encodedPath)
                        event["startedAt"] = Instant.now().toString()
                        precheckCalls[call] = event
                        object : EventListener() {
                            override fun requestHeadersEnd(
                                call: Call,
                                request: Request,
                            ) {
                                event["requestHeadersEmitted"] = true
                                wireCalls += event
                            }

                            override fun responseHeadersEnd(
                                call: Call,
                                response: okhttp3.Response,
                            ) {
                                event["httpStatus"] = response.code
                            }

                            override fun callEnd(call: Call) {
                                precheckCalls.remove(call)
                            }

                            override fun callFailed(
                                call: Call,
                                ioe: java.io.IOException,
                            ) {
                                event["failureKind"] = ioe.javaClass.simpleName
                                precheckTransportFailures += event.toMap()
                                precheckCalls.remove(call)
                            }
                        }
                    }.addNetworkInterceptor { chain ->
                        val stage = wireStage(chain.request().url.host, chain.request().url.encodedPath)
                        val event = requireNotNull(precheckCalls[chain.call()])
                        try {
                            if (journal != null) {
                                val bytes = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readByteArray()
                                journal.captureRequest(journal.currentCall(), bytes)
                            }
                            val response = chain.proceed(chain.request())
                            journal?.let { it.captureResponse(it.currentCall(), response.peekBody(262145).bytes()) }
                            event["httpStatus"] = response.code
                            // Successful bounded response only; never retain error bodies or credentials.
                            if (response.code == 200) {
                                val tree = runCatching { mapper.readTree(response.peekBody(262144).bytes()) }.getOrNull()
                                tree?.path("usage")?.takeUnless { it.isMissingNode || it.isNull }?.let { event["usage"] = it }
                                if (stage ==
                                    "PRECHECK_CLASSIFICATION"
                                ) {
                                    tree?.path("answers")?.takeUnless { it.isMissingNode }?.let {
                                        event["jevRawAnswers"] =
                                            it
                                    }
                                }
                            }
                            response
                        } catch (failure: Exception) {
                            event["failureKind"] = failure.javaClass.simpleName
                            if (failure is AiHttpProviderStatusException) {
                                journal?.let { it.captureResponse(it.currentCall(), failure.responseBytes) }
                            }
                            throw failure
                        }
                    }.build(),
            )

        companion object {
            @Bean @JvmStatic
            fun liveObservationalProviderResults(safety: ObjectProvider<LiveEvaluationSafety>): BeanPostProcessor =
                object : BeanPostProcessor {
                    override fun postProcessAfterInitialization(
                        bean: Any,
                        beanName: String,
                    ): Any =
                        when (bean) {
                            is AmbiguityClassificationPort ->
                                AmbiguityClassificationPort { request ->
                                    safety.getObject().checkpoint()
                                    bean.classify(request).also { classifierResults += mapOf("bean" to beanName, "result" to it) }
                                }
                            is NaturalLanguageParserPort ->
                                NaturalLanguageParserPort { request ->
                                    safety.getObject().checkpoint()
                                    try {
                                        bean.parse(request).also {
                                            parserResults += mapOf("bean" to beanName, "usage" to it.usage, "results" to it.results)
                                        }
                                    } catch (failure: Exception) {
                                        val causes = generateSequence<Throwable>(failure) { it.cause }.take(16).toList()
                                        parserFailureEvents +=
                                            mapOf(
                                                "stage" to if (beanName == "lunaParser") "FINAL_FALLBACK" else "FINAL_PRIMARY",
                                                "failureCode" to
                                                    ((failure as? NaturalLanguageParserException)?.kind?.name ?: "UNCLASSIFIED"),
                                                "exceptionClass" to failure.javaClass.name,
                                                "causeClasses" to causes.map { it.javaClass.name },
                                                "httpStatus" to
                                                    causes
                                                        .filterIsInstance<AiHttpProviderStatusException>()
                                                        .firstOrNull()
                                                        ?.status,
                                                "transportFailureClass" to
                                                    causes
                                                        .filterIsInstance<java.io.IOException>()
                                                        .firstOrNull()
                                                        ?.javaClass
                                                        ?.name,
                                            )
                                        throw failure
                                    }
                                }
                            else -> bean
                        }
                }
        }
    }

    companion object {
        private fun safetySignals(jdbc: JdbcTemplate): List<LiveEvaluationSignal> =
            buildList {
                wireCalls.toList().forEach { event ->
                    add(LiveEvaluationSignal(event["stage"].toString(), httpStatus = event["httpStatus"] as? Int))
                }
                precheckTransportFailures.toList().forEach { event ->
                    add(LiveEvaluationSignal(event["stage"].toString(), transportFailureClass = event["failureKind"] as? String))
                }
                parserFailureEvents.toList().forEach { event ->
                    add(
                        LiveEvaluationSignal(
                            event["stage"].toString(),
                            httpStatus = event["httpStatus"] as? Int,
                            parserFailureCode = event["failureCode"] as? String,
                            transportFailureClass = event["transportFailureClass"] as? String,
                        ),
                    )
                }
                jdbc
                    .queryForList(
                        "SELECT failure_code FROM precheck_executions WHERE failure_code IS NOT NULL ORDER BY queued_at",
                    ).forEach { row ->
                        add(LiveEvaluationSignal("PRECHECK", parserFailureCode = row["failure_code"] as? String))
                    }
                val recoveredFinal =
                    jdbc.queryForObject(
                        "SELECT count(*) FROM coordination_runs r JOIN analysis_invocations i ON i.coordination_run_id=r.id " +
                            "WHERE r.status='COMPLETED' AND i.winner_attempt_id IS NOT NULL",
                        Int::class.java,
                    )!! > 0
                jdbc
                    .queryForList(
                        "SELECT a.provider,a.failure_code,c.http_status FROM coordination_attempts a " +
                            "LEFT JOIN parser_transport_client_calls c ON c.attempt_id=a.id ORDER BY a.started_at,a.attempt_number",
                    ).forEach { row ->
                        // Errors remain in the journal/database; a completed bounded operation has recovered.
                        // An unsuccessful operation still blocks the next driver step.
                        if (!recoveredFinal) {
                            add(
                                LiveEvaluationSignal(
                                    if (row["provider"] == "OPENAI") "FINAL_FALLBACK" else "FINAL_PRIMARY",
                                    httpStatus = row["http_status"] as? Int,
                                    parserFailureCode = row["failure_code"] as? String,
                                ),
                            )
                        }
                    }
            }

        private val localFirstPostDiagnostic = System.getenv("MEETME_LOCAL_FIRST_POST_DIAG") == "true"
        private const val ORIGIN = "http://localhost:18931"
        private val mapper = JsonMapper.builder().build()
        private val keys by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
        private val payloadBytes by lazy {
            Files
                .readAllBytes(
                    Path.of(required("MEETME_LIVE_PAYLOAD")),
                ).also { require(it.size <= 262144) }
        }
        private val payload by lazy { mapper.readTree(payloadBytes) }
        private val referenceAt by lazy {
            Instant.parse(
                payload
                    .path("rooms")
                    .get(0)
                    .path("referenceAt")
                    .asText()
                    .let {
                        java.time.OffsetDateTime
                            .parse(it)
                            .toInstant()
                            .toString()
                    },
            )
        }
        private val cap by lazy { required("MEETME_LIVE_MAX_PHYSICAL_CALLS").toInt().also { require(it in 1..42) } }
        private val admissions = CopyOnWriteArrayList<Map<String, Any>>()
        private val admitted = mutableSetOf<String>()
        private val wireCalls = CopyOnWriteArrayList<MutableMap<String, Any>>()
        private val precheckCalls = java.util.concurrent.ConcurrentHashMap<Call, MutableMap<String, Any>>()
        private val precheckTransportFailures = CopyOnWriteArrayList<Map<String, Any>>()

        private fun wireStage(
            host: String,
            path: String,
        ): String =
            when {
                host == "api.typesafe.ai" -> "PRECHECK_CLASSIFICATION"
                path.endsWith("/input_tokens") -> "PRECHECK_COUNT"
                else -> "PRECHECK_GENERATION"
            }

        private val classifierResults = CopyOnWriteArrayList<Map<String, Any>>()
        private val answerRouting = CopyOnWriteArrayList<Map<String, Any>>()
        private val parserResults = CopyOnWriteArrayList<Map<String, Any>>()
        private val parserFailureEvents = CopyOnWriteArrayList<Map<String, Any?>>()
        private val postgres by lazy {
            PostgreSQLContainer(
                "postgres:18-alpine",
            ).withLabel("meetme.experiment", "live-eval").apply { start() }
        }
        private val redis by lazy {
            GenericContainer(
                DockerImageName.parse("redis:8-alpine"),
            ).withExposedPorts(6379).withLabel("meetme.experiment", "live-eval").apply {
                start()
            }
        }

        @Synchronized
        private fun admit(request: AiBudgetReserveRequest): Boolean {
            val pending =
                request.calls.map { call ->
                    "${request.execution.roomId}:${request.execution.executionId}:${request.execution.generation}:" +
                        "${call.operation}:${call.ordinal}:${call.attemptId}:${call.wholePayloadFingerprint}"
                }
            if ((admitted + pending).size > cap) return false
            request.calls.zip(pending).forEach { (call, identity) ->
                if (admitted.add(identity)) {
                    admissions +=
                        mapOf(
                            "roomId" to request.execution.roomId,
                            "executionId" to request.execution.executionId,
                            "stage" to call.operation.name,
                            "ordinal" to call.ordinal,
                            "identitySha256" to sha(identity.toByteArray()),
                        )
                }
            }
            return true
        }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            require(System.getenv("MEETME_LIVE_EVAL") == "true")
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
            mapOf(
                "server.address" to "127.0.0.1",
                "meetme.anonymous.allowed-origins" to ORIGIN,
                "meetme.anonymous.cookie-secure" to "false",
                "meetme.reliability.enabled" to "true",
                "meetme.rate-limit.enabled" to "false",
                "meetme.data-retention.enabled" to "false",
                "meetme.precheck.provider.enabled" to (!localFirstPostDiagnostic).toString(),
                "meetme.precheck.decision-routing.enabled" to (!localFirstPostDiagnostic).toString(),
                "meetme.precheck.classifier.enabled" to (!localFirstPostDiagnostic).toString(),
                "meetme.precheck.classifier.minimum-confidence" to "0.8",
                "meetme.ai.budget.enabled" to "false",
                "meetme.precheck.evidence-enabled" to "true",
                "logging.level.root" to "ERROR",
            ).forEach { (key, value) -> registry.add(key) { value } }
            listOf(
                "gemini" to "GEMINI_API_KEY",
                "openai" to "OPENAI_API_KEY",
                "typesafe" to "TYPESAFE_API_KEY",
            ).forEach { (provider, env) ->
                registry.add("meetme.$provider.api-key") { if (localFirstPostDiagnostic) "" else required(env) }
            }
        }

        @JvmStatic @AfterAll
        fun stop() {
            postgres.stop()
            redis.stop()
        }

        private fun required(name: String): String =
            requireNotNull(
                System.getenv(name)?.takeIf {
                    it.isNotBlank()
                },
            ) { "Missing required runtime variable: $name" }

        private fun sha(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun redact(text: String): String =
            listOf("GEMINI_API_KEY", "OPENAI_API_KEY", "TYPESAFE_API_KEY").fold(text) { value, name ->
                System
                    .getenv(name)
                    ?.takeIf {
                        it.length >
                            3
                    }?.let { value.replace(it, "[REDACTED]") }
                    ?: value
            }
    }
}
