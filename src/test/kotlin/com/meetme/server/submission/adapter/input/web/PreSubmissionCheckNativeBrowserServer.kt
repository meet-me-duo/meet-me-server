package com.meetme.server.submission.adapter.input.web

import com.meetme.server.MeetMeServerApplication
import com.meetme.server.account.adapter.input.web.AuthEndToEndPostgresTest
import com.meetme.server.meetingroom.application.service.DeadlineClosureScheduler
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.application.service.PreSubmissionCheckWorker
import com.meetme.server.precheck.domain.PrecheckChoice
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

/** Test-classpath-only process. No production routes, credentials, provider or infrastructure. */
fun main() {
    val stopFile = Path.of(requireNotNull(System.getProperty("meetme.issue107.native.stop-file"))).toAbsolutePath()
    require(stopFile.startsWith(Path.of("/tmp")) || stopFile.startsWith(Path.of("/workspace")))
    require(!Files.exists(stopFile)) { "Remove only this fixture's stale stop file before starting" }
    val settings = mutableMapOf<String, String>()
    AuthEndToEndPostgresTest.properties(
        object : DynamicPropertyRegistry {
            override fun add(
                name: String,
                valueSupplier: Supplier<Any>,
            ) {
                settings[name] = valueSupplier.get().toString()
            }
        },
    )
    val url = requireNotNull(settings["spring.datasource.url"])
    require(URI(url.removePrefix("jdbc:")).host in setOf("localhost", "127.0.0.1", "[::1]"))
    val apiOrigin = "http://localhost:18920"
    val webOrigin = "http://localhost:18921"
    settings.putAll(
        mapOf(
            "server.address" to "127.0.0.1",
            "server.port" to "18920",
            "meetme.auth.test.redirect-base" to apiOrigin,
            "meetme.anonymous.allowed-origins" to webOrigin,
            "meetme.anonymous.cookie-secure" to "false",
            "meetme.auth.web-callback-uri" to "$webOrigin/auth/callback",
            "meetme.reliability.enabled" to "false",
            "meetme.rate-limit.enabled" to "false",
            "meetme.data-retention.enabled" to "false",
            "meetme.operations.cleanup-enabled" to "false",
        ),
    )
    settings.forEach { (key, value) -> System.setProperty(key, value) }
    val context =
        SpringApplicationBuilder(
            MeetMeServerApplication::class.java,
            AuthEndToEndPostgresTest.IsolatedAuthProviders::class.java,
            PrecheckNativeBeans::class.java,
        ).run()
    val provider = context.getBean(NativePrecheckProvider::class.java)
    val control =
        NativePrecheckControl(
            context.getBean(ObjectMapper::class.java),
            provider,
            context.getBean(PreSubmissionCheckWorker::class.java),
            context.getBean(DeadlineClosureScheduler::class.java),
            settings,
        )
    val stopped = AtomicBoolean()

    fun cleanup() {
        if (stopped.compareAndSet(false, true)) {
            provider.release()
            control.close()
            context.close()
            AuthEndToEndPostgresTest.stop()
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread({ cleanup() }, "issue107-native-cleanup"))
    try {
        control.start()
        println("ISSUE107_ISOLATED_NATIVE_READY $apiOrigin $webOrigin control=127.0.0.1:18922")
        while (!Files.exists(stopFile)) Thread.sleep(250)
    } finally {
        cleanup()
    }
}

@TestConfiguration(proxyBeanMethods = false)
class PrecheckNativeBeans {
    @Bean
    @Primary
    fun nativePrecheckProvider(): NativePrecheckProvider = NativePrecheckProvider()
}

private data class NativeProviderPlan(
    val verdict: String,
    val prompt: String,
    val excerpt: String?,
    val choices: List<PrecheckChoice>,
    val gate: CountDownLatch,
)

/** Responses are explicitly selected synthetic plans, never a semantic classifier. */
class NativePrecheckProvider : PreSubmissionCheckProvider {
    private val plan = AtomicReference(NativeProviderPlan("CLEAR", "", null, emptyList(), CountDownLatch(0)))
    val calls = AtomicInteger()
    val inFlight = AtomicInteger()
    val requests = CopyOnWriteArrayList<Map<String, String>>()

    fun configure(body: JsonNode) {
        require(inFlight.get() == 0) { "Release the existing provider barrier first" }
        val verdict = body.path("verdict").asText("CLEAR")
        require(verdict in setOf("CLEAR", "NEEDS_CHOICE", "NEEDS_EDIT", "FAILED"))
        val options =
            body.path("options").values().map {
                PrecheckChoice(it.path("id").asText(), it.path("text").asText(), it.path("proposed_text").asText())
            }
        if (verdict == "NEEDS_CHOICE") require(options.size == 2)
        plan.set(
            NativeProviderPlan(
                verdict,
                body.path("question").asText("어떤 해석이 맞나요?"),
                body.path("source_excerpt").takeUnless { it.isMissingNode || it.isNull }?.asText(),
                options,
                CountDownLatch(if (body.path("barrier").asBoolean(false)) 1 else 0),
            ),
        )
    }

    fun release() = plan.get().gate.countDown()

    fun reset() {
        require(inFlight.get() == 0)
        calls.set(0)
        requests.clear()
        plan.set(NativeProviderPlan("CLEAR", "", null, emptyList(), CountDownLatch(0)))
    }

    override fun check(request: PrecheckProviderRequest): PrecheckProviderResult {
        val selected = plan.get()
        calls.incrementAndGet()
        inFlight.incrementAndGet()
        requests.add(
            mapOf(
                "check_id" to request.checkId.toString(),
                "raw_text" to request.rawText,
                "reference_at" to request.referenceAt.toString(),
            ),
        )
        try {
            if (!selected.gate.await(minOf(15_000, request.timeout.toMillis()), TimeUnit.MILLISECONDS)) {
                throw PrecheckProviderException("TIMEOUT")
            }
            if (selected.verdict == "FAILED") throw PrecheckProviderException("CONNECTION_FAILED")
            return PrecheckProviderResult(
                verdict = PrecheckVerdict.valueOf(selected.verdict),
                prompt = selected.prompt.takeIf { selected.verdict == "NEEDS_CHOICE" },
                sourceExcerpt = if (selected.verdict == "NEEDS_CHOICE") selected.excerpt ?: request.rawText.take(200) else null,
                choices = if (selected.verdict == "NEEDS_CHOICE") selected.choices else emptyList(),
                inputTokens = null,
                outputTokens = null,
            )
        } finally {
            inFlight.decrementAndGet()
        }
    }
}

private class NativePrecheckControl(
    private val mapper: ObjectMapper,
    private val provider: NativePrecheckProvider,
    private val worker: PreSubmissionCheckWorker,
    private val deadlineScheduler: DeadlineClosureScheduler,
    private val settings: Map<String, String>,
) {
    private val executor = Executors.newFixedThreadPool(4)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 18922), 0)
    private val roomId = AtomicReference<UUID?>()

    fun start() {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                require(exchange.remoteAddress.address.isLoopbackAddress)
                require(exchange.requestHeaders.getFirst("Origin") == null)
                val path = exchange.requestURI.path
                val body =
                    if (exchange.requestMethod == "POST") {
                        require(exchange.requestHeaders.getFirst("Content-Type")?.startsWith("application/json") == true)
                        val bytes = exchange.requestBody.use { it.readNBytes(16_385) }
                        require(bytes.size <= 16_384)
                        if (bytes.isEmpty()) mapper.createObjectNode() else mapper.readTree(bytes)
                    } else {
                        mapper.createObjectNode()
                    }
                val result: Any =
                    when (exchange.requestMethod to path) {
                        "GET" to "/health" ->
                            mapOf(
                                "fixture" to "issue107-isolated-native-v1",
                                "production" to false,
                                "api_origin" to "http://localhost:18920",
                                "web_origin" to "http://localhost:18921",
                            )
                        "POST" to "/reset" -> reset(body)
                        "POST" to "/provider" -> {
                            provider.configure(body)
                            mapOf("configured" to true)
                        }
                        "POST" to "/provider/release" -> {
                            provider.release()
                            mapOf("released" to true)
                        }
                        "POST" to "/worker/run-one" -> mapOf("ran" to worker.runOne())
                        "POST" to "/room/deadline" -> expire(body)
                        "POST" to "/room/run-deadline-closure" -> {
                            deadlineScheduler.closeDueRooms()
                            mapOf("scanned" to true)
                        }
                        "GET" to "/state" -> state()
                        else -> {
                            respond(exchange, 404, mapOf("error" to "unknown-fixture-control"))
                            return@createContext
                        }
                    }
                respond(exchange, 200, result)
            } catch (failure: Throwable) {
                respond(exchange, 409, mapOf("error" to "fixture-control-rejected", "kind" to failure.javaClass.simpleName))
            }
        }
        server.start()
    }

    fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: Any,
    ) {
        val bytes = mapper.writeValueAsBytes(body)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun connection(): Connection =
        DriverManager.getConnection(
            requireNotNull(settings["spring.datasource.url"]),
            requireNotNull(settings["spring.datasource.username"]),
            requireNotNull(settings["spring.datasource.password"]),
        )

    private fun reset(body: JsonNode): Map<String, String> {
        require(provider.inFlight.get() == 0)
        val protocol = body.path("protocol").asText("PRE_SUBMIT_V1")
        require(protocol in setOf("PRE_SUBMIT_V1", "LEGACY"))
        val scenario = body.path("scenario").asText("synthetic").take(80)
        val id = UUID.randomUUID()
        val participant = UUID.randomUUID()
        val bytes =
            ByteBuffer
                .allocate(16)
                .putLong(id.mostSignificantBits)
                .putLong(id.leastSignificantBits)
                .array()
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val manual = body.path("manual_only").asBoolean(false)
        val expected = body.path("expected_participants").asInt(4)
        require(expected in 2..50)
        val date = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val host =
            connection().use { connection ->
                connection.autoCommit = false
                connection.createStatement().use { it.execute("TRUNCATE meeting_rooms CASCADE") }
                val account =
                    connection
                        .prepareStatement(
                            "SELECT user_id FROM account_identities WHERE provider='GOOGLE' AND subject='shared-synthetic-subject'",
                        ).use { statement ->
                            statement.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
                        } ?: UUID.fromString("00000000-0000-4000-8000-000000000107").also { account ->
                        connection
                            .prepareStatement(
                                "INSERT INTO account_users(id,display_name,created_at) VALUES (?,'Synthetic native host',now())",
                            ).use {
                                it.setObject(1, account)
                                it.executeUpdate()
                            }
                        connection
                            .prepareStatement(
                                "INSERT INTO account_identities(provider,subject,user_id) VALUES ('GOOGLE','shared-synthetic-subject',?)",
                            ).use {
                                it.setObject(1, account)
                                it.executeUpdate()
                            }
                    }
                connection
                    .prepareStatement(
                        """
                        INSERT INTO meeting_rooms(id,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
                          search_range_source,manual_only,expected_participants,submission_deadline,collection_status,created_at,invite_code,submission_protocol)
                        VALUES (?,?,'REMOTE','Asia/Seoul',?,?,'HOST_SPECIFIED',?,?,?,'COLLECTING',now(),?,?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, id)
                        statement.setString(2, "Synthetic pre-submit: $scenario")
                        statement.setObject(3, date)
                        statement.setObject(4, date.plusDays(14))
                        statement.setBoolean(5, manual)
                        statement.setObject(6, if (manual) null else expected)
                        statement.setObject(
                            7,
                            if (!manual &&
                                body.has("deadline_seconds")
                            ) {
                                java.sql.Timestamp.from(Instant.now().plusSeconds(body.path("deadline_seconds").asLong()))
                            } else {
                                null
                            },
                        )
                        statement.setString(8, code)
                        statement.setString(9, protocol)
                        statement.executeUpdate()
                    }
                connection
                    .prepareStatement(
                        "INSERT INTO participants(id,room_id,user_id,role,joined_at,display_name) VALUES (?,?,?,'HOST',now(),'Synthetic native host')",
                    ).use { statement ->
                        statement.setObject(1, participant)
                        statement.setObject(2, id)
                        statement.setObject(3, account)
                        statement.executeUpdate()
                    }
                connection.commit()
                account
            }
        roomId.set(id)
        provider.reset()
        return mapOf(
            "invite_code" to code,
            "room_id" to id.toString(),
            "host_participant_id" to participant.toString(),
            "host_user_id" to host.toString(),
        )
    }

    private fun expire(body: JsonNode): Map<String, Boolean> {
        val seconds = body.path("seconds").asLong(0)
        require(seconds in -60..3_600)
        connection().use { connection ->
            connection.prepareStatement("UPDATE meeting_rooms SET manual_only=false,submission_deadline=? WHERE id=?").use { statement ->
                statement.setObject(1, java.sql.Timestamp.from(Instant.now().plusSeconds(seconds)))
                statement.setObject(2, requireNotNull(roomId.get()))
                statement.executeUpdate()
            }
        }
        return mapOf("updated" to true)
    }

    private fun state(): Map<String, Any> {
        val frozen =
            connection().use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT sv.raw_text FROM submission_batch_items bi
                        JOIN submission_versions sv ON sv.id=bi.submission_version_id
                        JOIN submission_batches b ON b.id=bi.batch_id WHERE b.room_id=? ORDER BY sv.created_at,sv.id
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, roomId.get())
                        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1) ?: "") } }
                    }
            }
        return mapOf(
            "provider_calls" to provider.calls.get(),
            "in_flight" to provider.inFlight.get(),
            "provider_requests" to provider.requests.toList(),
            "frozen_synthetic_texts" to frozen,
        )
    }
}
