package com.meetme.server.experiment

import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.meetingroom.application.port.input.PublicRoomStatus
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Uses only public application HTTP; JDBC reads bind this isolated room and collect numeric metadata. */
class CumulativeExperimentHttpFlow(
    private val port: Int,
    private val jdbc: JdbcTemplate,
    private val tokens: AccessTokenPort,
    private val json: ObjectMapper,
    private val settings: CumulativeExperimentSettings,
) {
    private val ledger = settings.ledger
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val events = mutableListOf<Map<String, Any?>>()
    private val participants = mutableListOf<Map<String, Any?>>()
    private var questions = 0
    private var answers = 0

    fun connectivity(attempt: Int): CumulativeStageResult {
        val fixture = settings.diagnostic
        val actor = host("Connectivity $attempt")
        val created =
            request(
                "POST",
                "/api/rooms",
                actor,
                body =
                    mapOf(
                        "purpose" to "Fresh synthetic Gemini connectivity diagnostic $attempt",
                        "meeting_mode" to fixture.path("meetingMode").asText(),
                        "manual_only" to true,
                        "search_start_date" to fixture.path("searchStartDate").asText(),
                        "search_end_date" to fixture.path("searchEndDateExclusive").asText(),
                    ),
            )
        check(created.status == 201)
        val path = bind(created.body)
        fixture.path("submittedInputs").values().forEachIndexed { index, raw ->
            submit(path, "diagnostic-$index", raw.asText(), answerRoom = null)
        }
        return closeAndRead(path, actor, select = false)
    }

    fun realE2E(): CumulativeStageResult {
        val room = settings.payload.path("rooms").get(0)
        val sourceHost = room.path("host")
        val range = sourceHost.path("dateSearchRange")
        val actor = host("Fresh real-provider E2E")
        val body =
            mutableMapOf<String, Any?>(
                "purpose" to sourceHost.path("input").asText(),
                "meeting_mode" to room.path("meetingMode").asText(),
                "search_start_date" to range.path("startDate").asText(),
                "search_end_date" to range.path("endDate").asText(),
            )
        val deadline = sourceHost.path("submissionDeadline").takeUnless { it.isMissingNode || it.isNull }?.asText()
        body["manual_only"] = deadline == null
        deadline?.let { body["submission_deadline"] = it }
        val created = request("POST", "/api/rooms", actor, body = body)
        check(created.status == 201)
        val path = bind(created.body)
        val answerRoom =
            settings.answers
                .path("rooms")
                .values()
                .single { it.path("roomId") == room.path("roomId") }
        room.path("participants").values().forEach { member ->
            submit(path, member.path("participantId").asText(), member.path("input").asText(), answerRoom)
        }
        return closeAndRead(path, actor, select = true)
    }

    private fun host(name: String): String {
        val principal = AccountPrincipal(UUID.randomUUID(), name)
        jdbc.update("INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,now())", principal.accountId, principal.displayName)
        return "Bearer ${tokens.issue(principal)}"
    }

    private fun bind(created: JsonNode): String {
        val invite = created.path("invite_code").asText()
        check(invite.matches(Regex("[A-Za-z0-9_-]{22}")))
        val id = checkNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code=?", UUID::class.java, invite))
        ledger.bindRoom(id)
        return "/api/rooms/$invite"
    }

    private fun submit(
        path: String,
        sourceId: String,
        raw: String,
        answerRoom: JsonNode?,
    ) {
        val joined = request("POST", "$path/participants", body = mapOf("display_name" to "Synthetic $sourceId"))
        check(joined.status in 200..299)
        val cookie = checkNotNull(joined.cookie)
        val checks = "$path/submission/checks"
        val posted =
            request(
                "POST",
                checks,
                cookie = cookie,
                body =
                    mapOf(
                        "request_id" to UUID.randomUUID(),
                        "expected_revision" to 0,
                        "raw_text" to raw,
                    ),
            )
        check(posted.status == 202)
        var current =
            poll("$checks/current", cookie = cookie) {
                it.body
                    .path("check")
                    .path("status")
                    .asText() !in setOf("QUEUED", "CHECKING", "")
            }.body
        val question = current.path("check").path("question")
        var sent = false
        if (question.isObject) {
            questions++
            if (answerRoom != null && answers < 1) {
                val oracle = answerRoom.path("participants").path(sourceId)
                val text = answerDecision(sourceId, question, oracle)
                if (text != null) {
                    val answered =
                        request(
                            "POST",
                            "$checks/${current.path("check").path("id").asText()}/answer",
                            cookie = cookie,
                            body =
                                mapOf(
                                    "request_id" to UUID.randomUUID(),
                                    "expected_revision" to current.path("candidate").path("revision").asInt(),
                                    "expected_check_version" to current.path("check").path("version").asLong(),
                                    "question_id" to question.path("id").asText(),
                                    "direct_text" to text,
                                ),
                        )
                    check(answered.status == 202)
                    sent = true
                    answers++
                    current =
                        poll("$checks/current", cookie = cookie) {
                            it.body
                                .path("check")
                                .path("status")
                                .asText() !in setOf("QUEUED", "CHECKING", "")
                        }.body
                }
            }
        }
        participants +=
            mapOf(
                "sourceParticipantId" to sourceId,
                "finalStatus" to current.path("check").path("status").asText(),
                "failureCode" to code(current.path("check").path("failure_code")),
                "questionObserved" to question.isObject,
                "questionText" to
                    question
                        .path("text")
                        .takeIf { question.isObject }
                        ?.asText()
                        ?.take(160),
                "answerSent" to sent,
                "interpretation" to
                    current
                        .path("accepted")
                        .path("evidence")
                        .path("interpretation")
                        .asText(),
            )
    }

    private fun closeAndRead(
        path: String,
        actor: String,
        select: Boolean,
    ): CumulativeStageResult {
        val closed = request("POST", "$path/close", actor, body = mapOf("confirm_early" to true))
        check(closed.status == 200)
        val room = poll(path, actor) { it.body.path("public_status").asText() !in setOf("ANALYZING", "COLLECTING", "") }.body
        val candidates = request("GET", "$path/candidates", actor)
        val recommendations = request("GET", "$path/recommendations", actor)
        val unapplied = request("GET", "$path/candidates/unapplied-inputs", actor)
        val completed =
            room.path("public_status").asText() in COMPLETED &&
                candidates.status == 200 &&
                recommendations.status == 200 &&
                unapplied.status == 200
        val options =
            recommendations.body
                .path("options")
                .values()
                .toList()
        val evidence =
            mutableMapOf<String, Any?>(
                "publicStatus" to room.path("public_status").asText(),
                "candidatesStatus" to candidates.status,
                "recommendationsStatus" to recommendations.status,
                "candidateCount" to
                    candidates.body
                        .path("candidates")
                        .takeIf { candidates.status == 200 && it.isArray }
                        ?.size(),
                "candidateQuality" to candidates.body.path("quality").asText(),
                "appliedSubmissions" to candidates.body.path("applied_submissions").asInt(),
                "totalSubmissions" to candidates.body.path("total_submissions").asInt(),
                "recommendationCount" to options.size,
                "questionCount" to questions,
                "answerCount" to answers,
                "participantOutcomes" to participants.toList(),
                "recommendationWindows" to
                    options.map { option ->
                        mapOf(
                            "optionId" to option.path("option_id").asText(),
                            "startAt" to option.path("time_range").path("start_at").asText(),
                            "endAt" to option.path("time_range").path("end_at").asText(),
                            "attendanceCounts" to option.path("variants").values().map { it.path("attendance_count").asInt() },
                        )
                    },
                "unappliedCount" to unapplied.body.takeIf { unapplied.status == 200 && it.isArray }?.size(),
                "unappliedReasons" to
                    unapplied.body.takeIf { it.isArray }?.values()?.map {
                        mapOf(
                            "sourceParticipantId" to it.path("participant_display_name").asText().removePrefix("Synthetic "),
                            "reason" to code(it.path("reason")),
                            "failureScope" to code(it.path("failure_scope")),
                        )
                    },
                "httpEvents" to events.toList(),
            )
        if (completed && select && options.isNotEmpty()) {
            val option = options.first()
            val variant = option.path("variants").values().firstOrNull()
            if (variant != null) {
                // Select the actual returned full interval. The independent fixture declares no meeting duration.
                val start = Instant.parse(option.path("time_range").path("start_at").asText())
                val end = Instant.parse(option.path("time_range").path("end_at").asText())
                check(end > start)
                val confirmed =
                    request(
                        "POST",
                        "$path/recommendations/${option.path("option_id").asText()}/confirmation",
                        actor,
                        body =
                            mapOf(
                                "analysis_id" to recommendations.body.path("analysis_id").asText(),
                                "variant_id" to variant.path("variant_id").asText(),
                                "start_at" to start.toString(),
                                "end_at" to end.toString(),
                            ),
                    )
                evidence["confirmationStatus"] = confirmed.status
                evidence["selectedWindow"] = mapOf("startAt" to start.toString(), "endAt" to end.toString())
                evidence["resultStatus"] = request("GET", "$path/result", actor).status
                evidence["confirmedPublicStatus"] = request("GET", path, actor).body.path("public_status").asText()
            }
        }
        return CumulativeStageResult(completed, appEvidence = evidence)
    }

    private fun answerDecision(
        sourceId: String,
        question: JsonNode,
        oracle: JsonNode,
    ): String? {
        check(oracle.isObject && oracle.propertyNames().toSet() == setOf("directAnswer", "offTopicResponse"))
        val id = question.path("id").asText()
        UUID.fromString(id)
        val requests = settings.outputDirectory.resolve("questions")
        val decisions = settings.outputDirectory.resolve("answer-decisions")
        Files.createDirectories(requests)
        Files.createDirectories(decisions)
        Files.writeString(
            requests.resolve("$id.json"),
            json.writeValueAsString(
                mapOf(
                    "questionId" to id,
                    "sourceParticipantId" to sourceId,
                    "questionText" to question.path("text").asText(),
                    "allowedFields" to listOf("directAnswer", "offTopicResponse"),
                    "oracleSha256" to settings.manifest.path("answerOracleSha256").asText(),
                ),
            ),
            StandardOpenOption.CREATE_NEW,
        )
        val response = decisions.resolve("$id.json")
        val started = System.nanoTime()
        while (!Files.exists(response)) {
            ledger.checkpoint()
            if (System.nanoTime() - started >= Duration.ofSeconds(120).toNanos()) return null
            Thread.sleep(250)
        }
        check(Files.size(response) <= 1024)
        val bytes = Files.readAllBytes(response)
        check(bytes.size <= 1024)
        val decision = json.readTree(bytes)
        check(decision.isObject && decision.propertyNames().toSet() == setOf("questionId", "field"))
        check(decision.path("questionId").asText() == id)
        val field = decision.path("field").asText()
        check(field in setOf("directAnswer", "offTopicResponse"))
        val original =
            Files.readAllBytes(
                java.nio.file.Path
                    .of(settings.manifest.path("answerOraclePath").asText()),
            )
        check(hash(original) == settings.manifest.path("answerOracleSha256").asText())
        val answer = oracle.path(field).asText()
        check(answer.isNotBlank())
        events +=
            mapOf(
                "decisionField" to field,
                "questionId" to id,
                "decisionSha256" to hash(bytes),
                "answerSha256" to hash(answer.toByteArray()),
            )
        return answer
    }

    private fun poll(
        path: String,
        bearer: String? = null,
        cookie: String? = null,
        done: (AppResponse) -> Boolean,
    ): AppResponse {
        val started = System.nanoTime()
        do {
            ledger.checkpoint()
            val result = request("GET", path, bearer, cookie)
            ledger.checkpoint()
            if (done(result)) return result
            Thread.sleep(100)
        } while (System.nanoTime() - started < Duration.ofSeconds(90).toNanos())
        error("Local application observation deadline expired")
    }

    private fun request(
        method: String,
        path: String,
        bearer: String? = null,
        cookie: String? = null,
        body: Any? = null,
    ): AppResponse {
        ledger.checkpoint()
        val builder =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(5))
                .header("Accept-Language", "ko-KR")
        bearer?.let { builder.header("Authorization", it) }
        cookie?.let { builder.header("Cookie", it) }
        if (method == "POST") builder.header("Origin", "http://localhost:18931").header("Content-Type", "application/json")
        builder.method(
            method,
            body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) }
                ?: HttpRequest.BodyPublishers.noBody(),
        )
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        events += mapOf("method" to method, "path" to path, "status" to response.statusCode())
        val parsed = runCatching { json.readTree(response.body()) }.getOrElse { json.createObjectNode().put("nonJson", true) }
        val guest =
            response
                .headers()
                .allValues("set-cookie")
                .firstOrNull { it.startsWith("meet_me_guest=") }
                ?.substringBefore(';')
        check(response.statusCode() in 200..299 || method == "GET") { "Local application HTTP status ${response.statusCode()}" }
        return AppResponse(response.statusCode(), parsed, guest)
    }

    private fun code(node: JsonNode): String? =
        node
            .takeUnless { it.isNull || it.isMissingNode }
            ?.asText()
            ?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class AppResponse(
        val status: Int,
        val body: JsonNode,
        val cookie: String?,
    )

    companion object {
        private val COMPLETED =
            setOf(
                PublicRoomStatus.NO_MATCH,
                PublicRoomStatus.READY,
                PublicRoomStatus.READY_WITH_WARNINGS,
                PublicRoomStatus.CONFIRMED,
            ).map { it.name }.toSet()
    }
}
