package com.meetme.server.experiment

import com.meetme.server.MeetMeServerApplication
import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
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
import java.util.UUID

/** Default OFF. This is a primary connectivity diagnostic with synthetic prechecks, not a blind quality evaluation. */
@EnabledIfEnvironmentVariable(named = "MEETME_PRIMARY_SMOKE_MODE", matches = "PRIMARY_ONLY")
@SpringBootTest(classes = [MeetMeServerApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PrimaryGeminiSmokeApplication::class, PrimaryGeminiSmokeFixtures::class)
class PrimaryGeminiTechnicalSmokeTest {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var json: ObjectMapper

    @Autowired private lateinit var safety: LiveEvaluationSafety

    @Autowired private lateinit var observations: PrimaryGeminiSmokeObservations

    @Test
    fun `explicit approved primary-only smoke uses real closure source fence and at most one primary identity`() {
        val manifestBytes = Files.readAllBytes(Path.of(required("MEETME_PRIMARY_SMOKE_MANIFEST")))
        val manifest = json.readTree(manifestBytes)
        require(manifest.path("approved").asBoolean(false))
        require(manifest.path("mode").asText() == "PRIMARY_ONLY" && manifest.path("maximumPhysicalCalls").asInt() == 1)
        require(manifest.path("syntheticPrecheck").asText() == "CLEAR_TEST_DOUBLE")
        val project = Path.of("/workspace/meet-me-analysis")
        val files = manifest.path("files")
        require(files.isArray)
        val supplied = files.values().map { it.path("path").asText() }.toSet()
        val critical =
            setOf(
                "src/test/kotlin/com/meetme/server/experiment/PrimaryGeminiTechnicalSmokeTest.kt",
                "src/test/kotlin/com/meetme/server/experiment/PrimaryGeminiSmokeFixtures.kt",
                "src/test/kotlin/com/meetme/server/experiment/PrimaryGeminiSmokeDiagnostic.kt",
                "src/test/kotlin/com/meetme/server/experiment/LiveEvaluationSafety.kt",
                "/workspace/scratch/analysis-gradle",
                "/workspace/scratch/live-eval/live-eval.init.gradle",
                "/workspace/scratch/live-eval/harness-safety-followup-20261029/run-primary-smoke.py",
            )
        require(supplied.containsAll(critical))
        Files.walk(project.resolve("src/main")).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { require(project.relativize(it).toString() in supplied) }
        }
        files.values().forEach { record ->
            val path = Path.of(record.path("path").asText()).let { if (it.isAbsolute) it else project.resolve(it) }
            require(sha(Files.readAllBytes(path)) == record.path("sha256").asText())
        }
        val git = ProcessBuilder("git", "rev-parse", "HEAD").directory(project.toFile()).start()
        val head =
            git.inputStream
                .bufferedReader()
                .readText()
                .trim()
        require(git.waitFor() == 0 && head == manifest.path("sourceBaseRevision").asText())
        val clean = ProcessBuilder("git", "status", "--porcelain").directory(project.toFile()).start()
        val changes = clean.inputStream.bufferedReader().readText()
        require(clean.waitFor() == 0 && changes.isBlank())
        val output = Path.of(required("MEETME_PRIMARY_SMOKE_OUTPUT")).toAbsolutePath().normalize()
        require(output.startsWith(Path.of("/workspace/scratch/live-eval")))
        require(manifest.path("outputDirectory").isTextual)
        PrimaryGeminiSmokeClaim(Path.of("/workspace/scratch/live-eval/primary-manifest-claims")).acquire(
            manifest.path("planId").asText(),
            Path.of(manifest.path("outputDirectory").asText()),
            output,
            sha(manifestBytes),
        )
        Files.createDirectories(output)
        Files.writeString(output.resolve("started"), "PRIMARY_ONLY", StandardOpenOption.CREATE_NEW)
        val diagnostic =
            PrimaryGeminiSmokeDiagnostic(output.resolve("primary-smoke-results.json"), json, safety, observations) {
                partialSnapshot()
            }
        try {
            val report = diagnostic.execute { PrimaryGeminiSmokeHttpFlow(port, jdbc, tokens, json, safety).run() }
            check(report["analysisCompleted"] == true)
        } catch (_: Throwable) {
            // JUnit must not print a provider exception's private message or cause chain.
            throw AssertionError("Primary-only diagnostic stopped; inspect safe result metadata")
        }
    }

    private fun partialSnapshot(): Map<String, Any?> =
        mapOf(
            "attempts" to jdbc.queryForList("SELECT provider,model,failure_code FROM coordination_attempts"),
            "precheckExecutions" to jdbc.queryForList("SELECT provider,model,failure_code FROM precheck_executions"),
            "clientCalls" to jdbc.queryForList("SELECT call_number,http_status,outcome FROM parser_transport_client_calls"),
            "frozenVersionCount" to jdbc.queryForObject("SELECT count(*) FROM submission_batch_items", Long::class.java),
        )

    companion object {
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) =
            PrimaryGeminiSmokeInfrastructure.properties(registry) { required("GEMINI_API_KEY") }

        @JvmStatic @AfterAll
        fun stop() = PrimaryGeminiSmokeInfrastructure.stop()

        private fun required(name: String): String =
            requireNotNull(
                System.getenv(name)?.takeIf {
                    it.isNotBlank()
                },
            ) { "Missing runtime setting: $name" }

        private fun sha(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class PrimaryGeminiSmokeApplication {
    @Bean
    fun primarySafety(observations: PrimaryGeminiSmokeObservations): LiveEvaluationSafety = LiveEvaluationSafety { observations.signals() }

    @Bean
    fun primaryAdmission(safety: LiveEvaluationSafety): LiveEvaluationAdmission = LiveEvaluationAdmission(safety)

    @Bean @Primary
    fun primarySyntheticTokens(clock: Clock): AccessTokenPort {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return Rs256AccessTokenAdapter(
            keys.private,
            mapOf("primary-local" to keys.public),
            "primary-local",
            clock,
            "https://synthetic.invalid",
            "meetme-web",
        )
    }

    companion object {
        @Bean @JvmStatic
        fun primaryRoleScope(
            fixtures: ObjectProvider<PrimaryGeminiSmokeFixtures>,
            safety: ObjectProvider<LiveEvaluationSafety>,
        ): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any =
                    when (bean) {
                        is NaturalLanguageParserPort ->
                            if (beanName == "geminiNaturalLanguageParserAdapter") {
                                fixtures.getObject().primaryParser(bean, safety.getObject())
                            } else {
                                fixtures.getObject().excludedParser(bean, safety.getObject())
                            }
                        is AmbiguityClassificationPort ->
                            AmbiguityClassificationPort {
                                throw AmbiguityClassificationException(
                                    "CONFIGURATION",
                                )
                            }
                        else -> bean
                    }
            }
    }
}

object PrimaryGeminiSmokeInfrastructure {
    private val postgres by lazy {
        PostgreSQLContainer(
            "postgres:18-alpine",
        ).withLabel("meetme.experiment", "primary-smoke").apply { start() }
    }
    private val redis by lazy {
        GenericContainer(
            DockerImageName.parse("redis:8-alpine"),
        ).withExposedPorts(6379).withLabel("meetme.experiment", "primary-smoke").apply {
            start()
        }
    }

    fun properties(
        registry: DynamicPropertyRegistry,
        geminiKey: () -> String = { "" },
    ) {
        postgres.start()
        redis.start()
        val connectionSettings =
            mapOf(
                "spring.datasource.url" to postgres.jdbcUrl,
                "spring.datasource.username" to postgres.username,
                "spring.datasource.password" to postgres.password,
                "spring.data.redis.host" to redis.host,
                "spring.data.redis.port" to redis.getMappedPort(6379),
            )
        connectionSettings.forEach { (name, value) -> registry.add(name) { value } }
        registry.add("meetme.gemini.api-key") { geminiKey() }
        mapOf(
            "server.address" to "127.0.0.1",
            "spring.flyway.enabled" to "true",
            "meetme.reliability.enabled" to "true",
            "meetme.rate-limit.enabled" to "false",
            "meetme.data-retention.enabled" to "false",
            "meetme.precheck.provider.enabled" to "true",
            "meetme.precheck.decision-routing.enabled" to "true",
            "meetme.precheck.classifier.enabled" to "true",
            "meetme.precheck.evidence-enabled" to "true",
            "meetme.ai.budget.enabled" to "false",
            "meetme.openai.api-key" to "",
            "meetme.typesafe.api-key" to "",
            "meetme.anonymous.allowed-origins" to "http://localhost:18931",
            "meetme.anonymous.cookie-secure" to "false",
        ).forEach { (key, value) -> registry.add(key) { value } }
    }

    fun stop() {
        redis.stop()
        postgres.stop()
    }
}

class PrimaryGeminiSmokeHttpFlow(
    private val port: Int,
    private val jdbc: JdbcTemplate,
    private val tokens: AccessTokenPort,
    private val json: ObjectMapper,
    private val safety: LiveEvaluationSafety,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun run(): Map<String, Any?> {
        val host = AccountPrincipal(UUID.randomUUID(), "Synthetic primary diagnostic")
        jdbc.update("INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,now())", host.accountId, host.displayName)
        val bearer = "Bearer ${tokens.issue(host)}"
        val created =
            call(
                "POST",
                "/api/rooms",
                bearer,
                body =
                    mapOf(
                        "purpose" to "합성 primary-only 연결 진단",
                        "meeting_mode" to "REMOTE",
                        "manual_only" to true,
                        "search_start_date" to "2026-11-12",
                        "search_end_date" to "2026-11-13",
                    ),
            ).first
        val path = "/api/rooms/${created.path("invite_code").asText()}"
        repeat(3) { index ->
            val cookie = requireNotNull(call("POST", "$path/participants", body = mapOf("display_name" to "진단 참여자 $index")).second)
            call(
                "POST",
                "$path/submission/checks",
                cookie = cookie,
                body =
                    mapOf(
                        "request_id" to UUID.randomUUID(),
                        "expected_revision" to 0,
                        "raw_text" to "2026년 11월 12일 오후 6시부터 8시까지 비대면으로 가능해요.",
                    ),
            )
            poll("$path/submission/checks/current", cookie = cookie) { it.path("check").path("status").asText() == "CLEAR" }
        }
        call("POST", "$path/close", bearer, body = mapOf("confirm_early" to true))
        val room = poll(path, bearer = bearer) { it.path("public_status").asText() !in setOf("COLLECTING", "ANALYZING") }
        return mapOf(
            "mode" to "PRIMARY_ONLY",
            "syntheticPrecheck" to "CLEAR_TEST_DOUBLE",
            "maximumPhysicalCalls" to 1,
            "room" to room,
            "analysisCompleted" to
                (room.path("public_status").asText() in setOf("NO_MATCH", "READY", "READY_WITH_WARNINGS", "CONFIRMED")),
            "precheckExecutions" to jdbc.queryForList("SELECT provider,model,failure_code FROM precheck_executions"),
            "frozenVersions" to
                jdbc
                    .queryForList(
                        "SELECT v.input_evidence FROM submission_batch_items i JOIN submission_versions v ON v.id=i.submission_version_id",
                    ).map {
                        it.mapValues { (_, v) ->
                            v?.toString()
                        }
                    },
            "attempts" to jdbc.queryForList("SELECT provider,model,failure_code FROM coordination_attempts"),
            "clientCalls" to jdbc.queryForList("SELECT call_number,http_status,outcome FROM parser_transport_client_calls"),
        )
    }

    private fun poll(
        path: String,
        bearer: String? = null,
        cookie: String? = null,
        done: (JsonNode) -> Boolean,
    ): JsonNode =
        PrimaryGeminiSmokePolling(safety).until(
            Duration.ofSeconds(90),
            { call("GET", path, bearer, cookie).first },
            done,
        )

    private fun call(
        method: String,
        path: String,
        bearer: String? = null,
        cookie: String? = null,
        body: Any? = null,
    ): Pair<JsonNode, String?> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(5))
        bearer?.let { request.header("Authorization", it) }
        cookie?.let { request.header("Cookie", it) }
        if (method == "POST") request.header("Origin", "http://localhost:18931").header("Content-Type", "application/json")
        request.method(
            method,
            body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody(),
        )
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "Synthetic primary diagnostic API response ${response.statusCode()}" }
        return json.readTree(response.body()) to
            response
                .headers()
                .allValues("set-cookie")
                .firstOrNull { it.startsWith("meet_me_guest=") }
                ?.substringBefore(';')
    }
}
