package com.meetme.server.experiment

import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.meetingroom.application.port.input.PublicRoomStatus
import com.meetme.server.meetingroom.domain.MeetingMode
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
class BlindFlowHttpFlow(
    private val port: Int,
    private val jdbc: JdbcTemplate,
    private val tokens: AccessTokenPort,
    private val json: ObjectMapper,
    private val output: java.nio.file.Path,
    private val answer: ((String, JsonNode) -> JsonNode)? = null,
    private val checkpoint: () -> Unit,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val events = mutableListOf<Map<String, Any?>>()
    private val participants = mutableListOf<Map<String, Any?>>()
    private var questions = 0
    private var answers = 0
    private var participantCookie: String? = null
    private lateinit var meetingMode: MeetingMode
    private var stage = "NOT_STARTED"

    fun progress(): Map<String, Any?> =
        mapOf(
            "stage" to stage,
            "questionCount" to questions,
            "answerCount" to answers,
            "participantOutcomes" to participants.toList(),
        )

    fun run(
        input: JsonNode,
        meetingMode: MeetingMode,
    ): Map<String, Any?> {
        check(input.path("participants").size() == 3)
        stage = "CREATING_ROOM"
        this.meetingMode = meetingMode
        val actor = host("Independent synthetic room")
        val created =
            request(
                "POST",
                "/api/rooms",
                actor,
                body =
                    mapOf(
                        "purpose" to input.path("title").asText(),
                        "meeting_mode" to meetingMode.name,
                        "manual_only" to true,
                        "search_start_date" to input.path("startDate").asText(),
                        "search_end_date" to input.path("endDate").asText(),
                    ),
            )
        check(created.status == 201)
        val path = bind(created.body)
        stage = "COLLECTING_INPUTS"
        input.path("participants").values().forEach { member ->
            submit(path, member.path("id").asText(), member.path("rawInput").asText())
        }
        stage = "FINAL_ANALYSIS"
        return closeAndRead(path, actor, select = true).appEvidence.also { stage = "COMPLETED" }
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
        check(id.toString().isNotBlank())
        check(jdbc.queryForObject("SELECT meeting_mode FROM meeting_rooms WHERE id=?", String::class.java, id) == meetingMode.name)
        return "/api/rooms/$invite"
    }

    private fun submit(
        path: String,
        sourceId: String,
        raw: String,
    ) {
        val joined = request("POST", "$path/participants", body = mapOf("display_name" to "Synthetic $sourceId"))
        check(joined.status in 200..299)
        val cookie = checkNotNull(joined.cookie)
        if (participantCookie == null) participantCookie = cookie
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
            run {
                val decision = answerDecision(sourceId, question)
                run {
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
                                    "choice_id" to decision.path("choiceId").takeIf { it.isTextual }?.asText(),
                                    "direct_text" to decision.path("directText").takeIf { it.isTextual }?.asText(),
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
        check(!current.path("check").path("question").isObject || !sent) { "Repeated question after answer" }
        check(current.path("check").path("failure_code").isNull || current.path("check").path("failure_code").isMissingNode)
        check(current.path("check").path("status").asText() in setOf("CLEAR", "RECEIVED", "UNRESOLVED"))
        write("participant-$sourceId.json", mapOf("sourceId" to sourceId, "rawInput" to raw, "current" to current))
        check(current.path("accepted").path("raw_text").asText() == raw) { "Original input changed" }
        check(current.path("submission_complete").asBoolean()) { "Input not received" }
        participants +=
            mapOf(
                "sourceParticipantId" to sourceId,
                "finalStatus" to current.path("check").path("status").asText(),
                "failureCode" to code(current.path("check").path("failure_code")),
                "questionObserved" to question.isObject,
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
        val candidates = request("GET", "$path/candidates?attendance_support=conditional-v1", actor)
        val recommendations = request("GET", "$path/recommendations?attendance_support=conditional-v1", actor)
        val unapplied = request("GET", "$path/candidates/unapplied-inputs", actor)
        val anonymous = request("GET", "$path/candidates/unapplied-inputs")
        val member = request("GET", "$path/candidates/unapplied-inputs", cookie = checkNotNull(participantCookie))
        write("host-candidates.json", candidates.body)
        write("host-recommendations.json", recommendations.body)
        write("host-unapplied.json", unapplied.body)
        write("guest-unapplied.json", member.body)
        write("anonymous-unapplied.json", anonymous.body)
        check(anonymous.status == 401)
        check(member.status == 401 && member.body.path("code").asText() == "ACCOUNT_REQUIRED")
        val completed =
            room.path("public_status").asText() in COMPLETED &&
                candidates.status == 200 &&
                recommendations.status == 200 &&
                unapplied.status == 200
        val frozenCount =
            jdbc.queryForObject(
                "SELECT count(*) FROM submission_batch_items b JOIN submission_batches s ON s.id=b.batch_id JOIN meeting_rooms r ON r.id=s.room_id WHERE r.invite_code=?",
                Long::class.java,
                path.substringAfterLast('/'),
            )
        check(frozenCount == 3L) { "Missing received frozen input" }
        val options =
            recommendations.body
                .path("options")
                .values()
                .toList()
        val evidence =
            mutableMapOf<String, Any?>(
                "publicStatus" to room.path("public_status").asText(),
                "meetingMode" to meetingMode.name,
                "anonymousUnappliedStatus" to anonymous.status,
                "guestUnappliedStatus" to member.status,
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
            stage = "CONFIRMING"
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
                        "$path/recommendations/${option.path("option_id").asText()}/confirmation?attendance_support=conditional-v1",
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
                val selected = request("GET", "$path/result?attendance_support=conditional-v1", actor)
                evidence["resultStatus"] = selected.status
                val selection = selected.body.path("selection")
                evidence["selectionPersisted"] =
                    selection.path("analysis_id") == recommendations.body.path("analysis_id") &&
                    selection.path("option_id") == option.path("option_id") &&
                    selection.path("variant_id") == variant.path("variant_id") &&
                    selection.path("start_at").asText() == option.path("time_range").path("start_at").asText() &&
                    selection.path("end_at").asText() == option.path("time_range").path("end_at").asText()
                write("host-result.json", selected.body)
                evidence["confirmedPublicStatus"] = request("GET", path, actor).body.path("public_status").asText()
                check(confirmed.status == 200 && selected.status == 200)
                check(evidence["selectionPersisted"] == true && evidence["confirmedPublicStatus"] == "CONFIRMED")
            }
        }
        evidence["analysisCompleted"] = completed
        write("flow.json", evidence)
        check(completed)
        return CumulativeStageResult(completed, appEvidence = evidence)
    }

    private fun answerDecision(
        sourceId: String,
        question: JsonNode,
    ): JsonNode {
        val id = question.path("id").asText()
        UUID.fromString(id)
        write("questions/$id.json", mapOf("questionId" to id, "sourceParticipantId" to sourceId, "question" to question))
        val decision =
            answer?.invoke(sourceId, question)?.also { write("answer-decisions/$id.json", it) }
                ?: independentAnswer(id)
        check(decision.path("questionId").asText() == id)
        val choice = decision.path("choiceId").isTextual
        val direct = decision.path("directText").isTextual
        check(choice xor direct)
        if (direct) check(decision.path("directText").asText().isNotBlank())
        return decision
    }

    private fun independentAnswer(id: String): JsonNode {
        val response = output.resolve("answer-decisions/$id.json")
        val started = System.nanoTime()
        while (!Files.exists(response)) {
            checkpoint()
            check(System.nanoTime() - started < Duration.ofMinutes(10).toNanos()) { "Independent answer deadline" }
            Thread.sleep(250)
        }
        check(Files.size(response) <= 4096)
        return json.readTree(Files.readAllBytes(response))
    }

    private fun write(
        name: String,
        value: Any?,
    ) {
        val path = output.resolve(name)
        Files.createDirectories(path.parent)
        Files.writeString(path, json.writeValueAsString(value), StandardOpenOption.CREATE_NEW)
    }

    private fun poll(
        path: String,
        bearer: String? = null,
        cookie: String? = null,
        done: (AppResponse) -> Boolean,
    ): AppResponse {
        val started = System.nanoTime()
        do {
            checkpoint()
            val result = request("GET", path, bearer, cookie)
            checkpoint()
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
        checkpoint()
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
