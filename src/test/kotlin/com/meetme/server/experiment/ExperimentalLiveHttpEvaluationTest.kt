package com.meetme.server.experiment

import com.meetme.server.MeetMeServerApplication
import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderHttpTransport
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.shared.adapter.output.aihttp.AiHttpProviderStatusException
import com.meetme.server.shared.adapter.output.persistence.JdbcAiBudgetExecutionFence
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
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
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Explicitly gated, test-classpath-only live experiment. No normal test starts a container or provider call. */
@EnabledIfEnvironmentVariable(named = "MEETME_LIVE_EVAL", matches = "true")
@SpringBootTest(classes = [MeetMeServerApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ExperimentalLiveHttpEvaluationTest.LiveFixtures::class)
class ExperimentalLiveHttpEvaluationTest {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var json: ObjectMapper
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val events = mutableListOf<Map<String, Any?>>()
    private val roomResults = mutableListOf<Map<String, Any?>>()
    private var infrastructureBlock: Map<String, Any?>? = null

    @Autowired private lateinit var safety: LiveEvaluationSafety

    @Test
    fun `explicitly approved blind payload traverses actual HTTP workers and providers once`() {
        val output = Path.of(required("MEETME_LIVE_OUTPUT")).toAbsolutePath()
        require(output.startsWith(Path.of("/workspace/scratch/live-eval")))
        Files.createDirectories(output)
        Files.writeString(output.resolve("started"), Instant.now().toString(), StandardOpenOption.CREATE_NEW)
        val manifest = mapper.readTree(Files.readAllBytes(Path.of(required("MEETME_LIVE_MANIFEST"))))
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
                            "No answer is invented. Only an exact precommitted participant answer may be sent once.",
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
        require(room.path("participants").size() in 3..4)
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
                        "meeting_mode" to "EITHER",
                        "manual_only" to true,
                        "search_start_date" to range.path("startDate").asText(),
                        "search_end_date" to LocalDate.parse(range.path("endDate").asText()).plusDays(1).toString(),
                    ),
            )
        require(created.status == 201)
        val invite = created.body.path("invite_code").asText()
        require(invite.matches(Regex("[A-Za-z0-9_-]{22}")))
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
                                        "direct_text" to answer,
                                    ),
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
            }
            participantResults +=
                mapOf(
                    "sourceParticipantId" to id,
                    "beforeAnswer" to beforeAnswer,
                    "answerSent" to answered,
                    "finalSnapshot" to snapshot.body,
                )
            stopAfterInfrastructureBarrier()
        }
        safety.guard {
            result["closed"] =
                request("POST", "$path/close", host, body = mapOf("confirm_early" to true)).let {
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

    private fun answerOracle(
        answers: JsonNode?,
        roomId: String,
        participantId: String,
    ): JsonNode? {
        if (answers == null) return null
        require(answers.path("answerPolicy").path("maximumQuestionsPerParticipant").asInt() == 1)
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
    ): String? {
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
                "allowedDecisionFields" to listOf("directAnswer", "offTopicResponse"),
                "policy" to "A question about submitted date/time/boundaries/availability/preferences/location is relevant; " +
                    "unrelated or undisclosed facts, new availability or constraints are off topic. " +
                    "Choose one exact oracle field; never rewrite.",
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
        require(decisionBytes.size <= 1024)
        val decision = json.readTree(decisionBytes)
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
        return text
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
        fun liveEvaluationSafety(jdbc: JdbcTemplate): LiveEvaluationSafety = LiveEvaluationSafety { safetySignals(jdbc) }

        @Bean
        fun liveEvaluationAdmission(safety: LiveEvaluationSafety): LiveEvaluationAdmission = LiveEvaluationAdmission(safety)

        @Bean @Primary
        fun liveReferenceClock(): Clock = Clock.fixed(referenceAt, ZoneOffset.UTC)

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
        ): AiRuntimeDispatchGuardPort =
            AiRuntimeDispatchGuardPort { request, at ->
                admission.authorize({ delegate.authorize(request, at) }) { dispatchAdmission(request) }
            }

        @Bean
        fun observedActualPrecheckTransport(): PrecheckProviderHttpTransport =
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
                            val response = chain.proceed(chain.request())
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
                jdbc
                    .queryForList(
                        "SELECT a.provider,a.failure_code,c.http_status FROM coordination_attempts a " +
                            "LEFT JOIN parser_transport_client_calls c ON c.attempt_id=a.id ORDER BY a.started_at,a.attempt_number",
                    ).forEach { row ->
                        add(
                            LiveEvaluationSignal(
                                if (row["provider"] == "OPENAI") "FINAL_FALLBACK" else "FINAL_PRIMARY",
                                httpStatus = row["http_status"] as? Int,
                                parserFailureCode = row["failure_code"] as? String,
                            ),
                        )
                    }
            }

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
                "meetme.precheck.provider.enabled" to "true",
                "meetme.precheck.decision-routing.enabled" to "true",
                "meetme.precheck.classifier.enabled" to "true",
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
                registry.add("meetme.$provider.api-key") { required(env) }
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
