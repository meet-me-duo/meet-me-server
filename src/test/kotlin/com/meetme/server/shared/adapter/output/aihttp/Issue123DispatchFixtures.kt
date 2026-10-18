package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.shared.application.port.output.AiBudgetDispatchPermit
import com.meetme.server.shared.application.port.output.AiBudgetDispatchRequest
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetObservation
import com.meetme.server.shared.application.port.output.AiBudgetReservation
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiBudgetSettlementRequest
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import com.meetme.server.shared.application.port.output.AiReservedPhysicalCall
import com.meetme.server.shared.application.port.output.AiRoomBudgetSnapshot
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal val dispatchNow: Instant = Instant.parse("2026-10-18T12:00:00Z")

internal class DispatchClock(
    var current: Instant = dispatchNow,
) : Clock() {
    override fun instant(): Instant = current

    override fun getZone(): ZoneId = ZoneId.of("UTC")

    override fun withZone(zone: ZoneId): Clock = this
}

internal fun dispatchContext(
    final: Boolean = false,
    nanos: Long = System.nanoTime(),
): AiProviderDispatchContext =
    AiProviderDispatchContext(
        AiBudgetExecution(
            UUID(0, 123),
            if (final) AiBudgetExecutionKind.FINAL_ANALYSIS else AiBudgetExecutionKind.PRECHECK,
            UUID(0, 124),
            7,
            UUID(0, 125),
            7,
            "synthetic-source-digest",
            "synthetic-context-digest",
        ),
        nanos + 5_000_000_000,
        if (final) UUID(0, 126) else null,
    )

/** Public-port fake only; not proof of PostgreSQL accounting or actual billing. */
internal class DispatchBudget : DurableAiBudgetPort {
    val reserves = mutableListOf<AiBudgetReserveRequest>()
    val claims = mutableListOf<AiBudgetDispatchRequest>()
    val observations = mutableListOf<AiBudgetObservation>()
    var denyReserve = false
    var denyClaim = false
    var reserveFailure = false
    var observationAccepted = true
    var observationFailure = false
    var onReserve: () -> Unit = {}
    var onClaim: () -> Unit = {}
    var mutateReservation: (AiBudgetReservation) -> AiBudgetReservation = { it }
    var permitDeadline: Instant = dispatchNow.plusSeconds(30)
    private val consumed = mutableSetOf<UUID>()

    override fun reserve(request: AiBudgetReserveRequest): AiBudgetReservation? {
        reserves += request
        onReserve()
        if (reserveFailure) error("synthetic-accounting-unavailable")
        if (denyReserve) return null
        return mutateReservation(
            AiBudgetReservation(
                request.execution,
                request.calls.map { call ->
                    val execution = request.execution
                    val physicalIdentity =
                        listOf(
                            execution.roomId,
                            execution.kind,
                            execution.executionId,
                            execution.generation,
                            call.operation,
                            call.ordinal,
                        ).joinToString(":")
                    AiReservedPhysicalCall(UUID.nameUUIDFromBytes(physicalIdentity.toByteArray(Charsets.UTF_8)), call)
                },
            ),
        )
    }

    override fun claim(request: AiBudgetDispatchRequest): AiBudgetDispatchPermit? {
        claims += request
        onClaim()
        if (denyClaim || !consumed.add(request.reservationId)) return null
        return AiBudgetDispatchPermit(request.reservationId, UUID(124, request.reservationId.leastSignificantBits), permitDeadline)
    }

    override fun observe(observation: AiBudgetObservation): Boolean {
        observations += observation
        if (observationFailure) error("synthetic-accounting-unavailable")
        return observationAccepted
    }

    override fun settle(request: AiBudgetSettlementRequest): Boolean = error("No trusted bill in dispatch fixture")

    override fun snapshot(roomId: UUID): AiRoomBudgetSnapshot? = null
}

internal class DispatchEndpoint(
    private val response: (String, Int) -> Pair<Int, String> = { _, _ -> 200 to "{}" },
    private val retryAfter: Boolean = false,
) : AutoCloseable {
    data class Received(
        val path: String,
        val body: ByteArray,
    )

    val requests: MutableList<Received> = Collections.synchronizedList(mutableListOf())
    private val count = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val origin: URI get() = URI.create("http://127.0.0.1:${server.address.port}")

    fun uri(path: String = "/v1/test"): URI = origin.resolve(path)

    init {
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.use { it.readAllBytes() }
            requests += Received(exchange.requestURI.path, body)
            val (status, text) = response(exchange.requestURI.path, count.incrementAndGet())
            if (retryAfter) exchange.responseHeaders.add("Retry-After", "0")
            exchange.responseHeaders.add("Content-Type", "application/json")
            val bytes = text.toByteArray()
            try {
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    override fun close() = server.stop(0)
}

internal fun dispatchHash(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun gate(
    budget: DispatchBudget,
    enabled: Boolean = true,
    clock: Clock = DispatchClock(),
    nanos: AtomicLong? = null,
) = AiHttpDispatchGate(
    AiDispatchGateProperties(enabled),
    budget,
    clock,
    java.util.function.LongSupplier { nanos?.get() ?: System.nanoTime() },
)

/** Public canonical length framing, independently computed without calling production helpers. */
internal fun dispatchFramedHash(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (part in parts) {
        val data = part.toByteArray(Charsets.UTF_8)
        digest.update(data.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(':'.code.toByte())
        digest.update(data)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
