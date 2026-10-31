package com.meetme.server.experiment

import com.meetme.server.MeetMeServerApplication
import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchResult
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.domain.PrecheckChoice
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import com.sun.net.httpserver.HttpServer
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Deliberately synthetic native UI fixture, completely separate from the real-provider evaluation test. */
fun main() {
    require(System.getenv("MEETME_NATIVE_SYNTHETIC") == "true") { "Explicit synthetic native opt-in required" }
    val stopFile = Path.of(requireNotNull(System.getProperty("meetme.experimental.native.stop-file"))).toAbsolutePath()
    require(stopFile.startsWith(Path.of("/workspace/scratch/live-eval")) && !Files.exists(stopFile))
    val postgres = PostgreSQLContainer("postgres:18-alpine").withLabel("meetme.experiment", "v2-native").apply { start() }
    val redis =
        GenericContainer(
            DockerImageName.parse("redis:8-alpine"),
        ).withExposedPorts(6379).withLabel("meetme.experiment", "v2-native").apply {
            start()
        }
    mapOf(
        "server.address" to "127.0.0.1",
        "server.port" to "18932",
        "spring.datasource.url" to postgres.jdbcUrl,
        "spring.datasource.username" to postgres.username,
        "spring.datasource.password" to postgres.password,
        "spring.flyway.enabled" to "true",
        "spring.data.redis.host" to redis.host,
        "spring.data.redis.port" to redis.getMappedPort(6379).toString(),
        "meetme.anonymous.allowed-origins" to "http://127.0.0.1:4189,http://localhost:4189",
        "meetme.anonymous.cookie-secure" to "false",
        "meetme.precheck.evidence-enabled" to "true",
        "meetme.reliability.enabled" to "true",
        "meetme.rate-limit.enabled" to "false",
        "meetme.data-retention.enabled" to "false",
        "meetme.precheck.provider.enabled" to "false",
        "meetme.precheck.decision-routing.enabled" to "false",
        "meetme.precheck.classifier.enabled" to "false",
        "meetme.ai.budget.enabled" to "false",
        "meetme.gemini.api-key" to "",
        "meetme.openai.api-key" to "",
        "meetme.typesafe.api-key" to "",
        "logging.level.root" to "ERROR",
    ).forEach { (key, value) -> System.setProperty(key, value) }
    val context = SpringApplicationBuilder(MeetMeServerApplication::class.java, V2NativeSyntheticBeans::class.java).run()
    val json = context.getBean(ObjectMapper::class.java)
    val jdbc = context.getBean(JdbcTemplate::class.java)
    val tokens = context.getBean(AccessTokenPort::class.java)
    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    val executor = Executors.newFixedThreadPool(2)
    val control = HttpServer.create(InetSocketAddress("127.0.0.1", 18933), 0)
    var activeRoom: UUID? = null

    fun api(
        method: String,
        path: String,
        bearer: String? = null,
        cookie: String? = null,
        body: Any? = null,
    ): Pair<JsonNode, String?> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:18932$path")).timeout(Duration.ofSeconds(5))
        bearer?.let { request.header("Authorization", it) }
        cookie?.let { request.header("Cookie", it) }
        if (method == "POST") request.header("Origin", "http://127.0.0.1:4189").header("Content-Type", "application/json")
        request.method(
            method,
            body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody(),
        )
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Synthetic fixture HTTP preparation failed: ${response.statusCode()}" }
        return json.readTree(response.body()) to
            response
                .headers()
                .allValues("set-cookie")
                .firstOrNull { it.startsWith("meet_me_guest=") }
                ?.substringBefore(';')
    }

    fun reset(): Map<String, Any?> {
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
        val principal = AccountPrincipal(UUID.randomUUID(), "Synthetic V2 host")
        jdbc.update("INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,now())", principal.accountId, principal.displayName)
        val access = tokens.issue(principal)
        val created =
            api(
                "POST",
                "/api/rooms",
                "Bearer $access",
                body =
                    mapOf(
                        "purpose" to "합성 V2 네이티브 브라우저 검증",
                        "meeting_mode" to "EITHER",
                        "manual_only" to true,
                        "search_start_date" to "2026-11-12",
                        "search_end_date" to "2026-11-13",
                    ),
            ).first
        val invite = created.path("invite_code").asText()
        val room = requireNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code=?", UUID::class.java, invite))
        activeRoom = room
        repeat(2) { index ->
            val cookie =
                requireNotNull(
                    api(
                        "POST",
                        "/api/rooms/$invite/participants",
                        body =
                            mapOf(
                                "display_name" to "합성 기존 참여자 ${index + 1}",
                            ),
                    ).second,
                )
            api(
                "POST",
                "/api/rooms/$invite/submission/checks",
                cookie = cookie,
                body =
                    mapOf(
                        "request_id" to UUID.randomUUID(),
                        "expected_revision" to 0,
                        "raw_text" to "합성 고정 가능 ${index + 1}",
                    ),
            )
            val cutoff = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (api(
                    "GET",
                    "/api/rooms/$invite/submission/checks/current",
                    cookie = cookie,
                ).first.path("check").path("status").asText() !=
                "CLEAR"
            ) {
                check(System.nanoTime() < cutoff) { "Synthetic precheck did not finish" }
                Thread.sleep(100)
            }
        }
        return mapOf(
            "fixture" to "experimental-v2-native",
            "synthetic" to true,
            "realModelCalls" to false,
            "apiOrigin" to "http://127.0.0.1:18932",
            "webOrigin" to "http://127.0.0.1:4189",
            "inviteCode" to invite,
            "roomId" to room,
            "host" to
                mapOf(
                    "access_token" to access,
                    "expires_at" to Instant.now().plusSeconds(900).toString(),
                    "user" to mapOf("id" to principal.accountId, "display_name" to principal.displayName),
                ),
            "flow" to
                mapOf(
                    "raw_text" to NATIVE_RAW,
                    "answer_text" to "오후",
                    "expected" to "NEEDS_CHOICE -> RECEIVED/UNRESOLVED; close -> real matching from synthetic final parser",
                ),
        )
    }

    control.executor = executor
    control.createContext("/") { exchange ->
        try {
            require(exchange.remoteAddress.address.isLoopbackAddress && exchange.requestHeaders.getFirst("Origin") == null)
            val result: Any =
                when (exchange.requestMethod to exchange.requestURI.path) {
                    "GET" to "/health" -> mapOf("fixture" to "experimental-v2-native", "synthetic" to true, "realModelCalls" to false)
                    "POST" to "/reset" -> reset()
                    "GET" to "/state" ->
                        mapOf(
                            "roomId" to activeRoom,
                            "questions" to
                                jdbc.queryForList(
                                    "SELECT c.raw_text,k.status,k.question,c.input_evidence FROM precheck_candidates c " +
                                        "JOIN precheck_checks k ON k.candidate_id=c.id ORDER BY c.created_at",
                                ),
                            "received" to jdbc.queryForList("SELECT raw_text,input_evidence FROM submission_versions ORDER BY created_at"),
                            "frozen" to
                                jdbc.queryForList(
                                    "SELECT v.raw_text,v.input_evidence FROM submission_batch_items i " +
                                        "JOIN submission_versions v ON v.id=i.submission_version_id",
                                ),
                        )
                    else -> throw IllegalArgumentException("Unknown synthetic control route")
                }
            val bytes = json.writeValueAsBytes(result)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (failure: Throwable) {
            val bytes = json.writeValueAsBytes(mapOf("error" to "synthetic-control-failed", "kind" to failure.javaClass.simpleName))
            exchange.sendResponseHeaders(409, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } finally {
            exchange.close()
        }
    }
    val stopped = AtomicBoolean()

    fun cleanup() {
        if (stopped.compareAndSet(false, true)) {
            control.stop(0)
            executor.shutdownNow()
            context.close()
            redis.stop()
            postgres.stop()
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread({ cleanup() }, "experimental-v2-native-cleanup"))
    try {
        control.start()
        println("EXPERIMENTAL_V2_NATIVE_SYNTHETIC_READY api=127.0.0.1:18932 control=127.0.0.1:18933")
        while (!Files.exists(stopFile)) Thread.sleep(250)
    } finally {
        cleanup()
    }
}

private const val NATIVE_RAW = "목요일 7시 이후 가능. 금요일은 불가."

@TestConfiguration(proxyBeanMethods = false)
class V2NativeSyntheticBeans {
    @Bean @Primary
    fun nativeSyntheticTokens(clock: Clock): AccessTokenPort {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return Rs256AccessTokenAdapter(
            keys.private,
            mapOf("native-local" to keys.public),
            "native-local",
            clock,
            "https://synthetic.invalid",
            "meetme-web",
        )
    }

    @Bean @Primary
    fun nativeSyntheticPrecheck(): PreSubmissionCheckProvider =
        PreSubmissionCheckProvider { request ->
            when {
                request.evidence?.supplement != null -> PrecheckProviderResult(PrecheckVerdict.UNRESOLVED)
                request.rawText == NATIVE_RAW ->
                    PrecheckProviderResult(
                        PrecheckVerdict.NEEDS_CHOICE,
                        "7시는 오전인가요, 오후인가요?",
                        "7시",
                        listOf(PrecheckChoice("AM", "오전", "오전"), PrecheckChoice("PM", "오후", "오후")),
                    )
                else -> PrecheckProviderResult(PrecheckVerdict.CLEAR)
            }
        }

    companion object {
        @Bean @JvmStatic
        fun nativeSyntheticFinalOnly(): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any =
                    if (bean is NaturalLanguageParserPort) {
                        NaturalLanguageParserPort { request ->
                            NaturalLanguageBatchResult(
                                request.inputs.map { input ->
                                    val id = SubmissionVersionId(UUID.fromString(input.inputRef))
                                    if (input.evidence?.interpretation == InputInterpretation.UNRESOLVED) {
                                        StructuredSubmissionResult(id, emptyList(), "AMBIGUOUS_TIME_CONSTRAINT")
                                    } else {
                                        StructuredSubmissionResult(
                                            id,
                                            listOf(
                                                StructuredCondition.TimeWindow(
                                                    TimePolarity.AVAILABLE,
                                                    request.searchStartDate,
                                                    null,
                                                    LocalTime.of(18, 0),
                                                    LocalTime.of(21, 0),
                                                ),
                                                StructuredCondition.SpecificPlace("합성 공통 지역", areaKey = "AREA_1", areaName = "합성 공통 지역"),
                                            ),
                                            null,
                                        )
                                    }
                                },
                                ParserUsage(null, null, null),
                            )
                        }
                    } else {
                        bean
                    }
            }
    }
}
