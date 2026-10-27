package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.shared.application.port.output.AiBudgetDispatchPermit
import com.meetme.server.shared.application.port.output.AiBudgetDispatchRequest
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetFailure
import com.meetme.server.shared.application.port.output.AiBudgetObservation
import com.meetme.server.shared.application.port.output.AiBudgetOutcome
import com.meetme.server.shared.application.port.output.AiBudgetReservation
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiBudgetUsage
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.ForwardingSink
import okio.buffer
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.LongSupplier

@ConfigurationProperties(prefix = "meetme.ai.budget")
data class AiDispatchGateProperties(
    val enabled: Boolean = false,
)

/** Structural configuration only; this does not establish a price or authorize a dispatch. */
data class AiPhysicalWireProfile(
    val profileFingerprint: String,
    val method: String,
    val endpoint: URI,
    val captureLimitBytes: Int,
)

class PreparedAiHttpRequest private constructor(
    val operation: AiPhysicalOperation,
    val ordinal: Int,
    val attemptId: UUID?,
    val profile: AiPhysicalWireProfile,
    private val body: ByteArray,
) {
    val wholePayloadFingerprint: String = digest(body)

    fun copyBody(): ByteArray = body.copyOf()

    internal fun spec() = AiPhysicalCallSpec(operation, ordinal, wholePayloadFingerprint, profile.profileFingerprint, attemptId)

    companion object {
        // Admission of actual whole bytes, not a tokenizer or monetary proof. Existing capture/profile identity stays frozen.
        private const val MAX_FINAL_WIRE_BYTES = 256 * 1024

        fun freeze(
            operation: AiPhysicalOperation,
            ordinal: Int,
            attemptId: UUID?,
            profile: AiPhysicalWireProfile,
            body: ByteArray,
        ): PreparedAiHttpRequest {
            if (profile.captureLimitBytes <= 0 ||
                body.size > profile.captureLimitBytes ||
                (
                    operation in setOf(AiPhysicalOperation.FINAL_PRIMARY, AiPhysicalOperation.FINAL_FALLBACK) &&
                        body.size > MAX_FINAL_WIRE_BYTES
                ) ||
                profile.profileFingerprint.isBlank() ||
                ordinal <= 0
            ) {
                denyDispatch()
            }
            return PreparedAiHttpRequest(operation, ordinal, attemptId, profile, body.copyOf())
        }
    }
}

class PreparedAiDispatchGroup internal constructor(
    internal val context: AiProviderDispatchContext?,
    internal val calls: List<PreparedAiHttpRequest>,
    internal val reservation: AiBudgetReservation?,
) {
    internal val selected =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<AiPhysicalOperation>()
}

class GatedAiHttpExchange internal constructor(
    val client: OkHttpClient,
    private val record: (UUID, AiBudgetOutcome, AiBudgetUsage, AiBudgetFailure?) -> Boolean,
) {
    fun observe(
        eventId: UUID,
        outcome: AiBudgetOutcome,
        usage: AiBudgetUsage = AiBudgetUsage(),
        failure: AiBudgetFailure? = null,
    ): Boolean = record(eventId, outcome, usage, failure)
}

/** Bounded provider status only; admission denial is a separate exception. */
class AiHttpProviderStatusException(
    val status: Int,
    val retryAfterMillis: Long?,
    internal val responseBytes: ByteArray = byteArrayOf(),
) : IOException("PROVIDER_STATUS")

class AiHttpDispatchGate(
    val properties: AiDispatchGateProperties,
    private val budget: DurableAiBudgetPort,
    private val clock: Clock,
    private val nanoTime: LongSupplier = LongSupplier(System::nanoTime),
) {
    fun prepareGroup(
        context: AiProviderDispatchContext?,
        calls: List<PreparedAiHttpRequest>,
    ): PreparedAiDispatchGroup {
        val frozen = calls.toList()
        if (!properties.enabled) return PreparedAiDispatchGroup(context, frozen, null)
        val current = context ?: denyDispatch()
        remaining(current)
        if (frozen.isEmpty() || frozen.size > 2 || frozen.map { it.operation }.distinct().size != frozen.size) denyDispatch()
        frozen.forEach { validateIdentity(current, it.operation, it.ordinal, it.attemptId) }
        if (frozen.size == 2 &&
            frozen.map { it.operation }.toSet() != setOf(AiPhysicalOperation.PRECHECK_COUNT, AiPhysicalOperation.PRECHECK_GENERATION)
        ) {
            denyDispatch()
        }
        val request = AiBudgetReserveRequest(current.execution, frozen.map { it.spec() })
        val reservation = accounting { budget.reserve(request) } ?: denyDispatch()
        if (reservation.execution != request.execution ||
            reservation.calls.size != frozen.size ||
            reservation.calls
                .map { it.reservationId }
                .distinct()
                .size != frozen.size ||
            reservation.calls.map { it.call }.toSet() != request.calls.toSet()
        ) {
            denyDispatch()
        }
        remaining(current)
        return PreparedAiDispatchGroup(current, frozen, reservation)
    }

    fun forPreparedCall(
        baseClient: OkHttpClient,
        group: PreparedAiDispatchGroup,
        operation: AiPhysicalOperation,
    ): GatedAiHttpExchange {
        if (!properties.enabled) return GatedAiHttpExchange(baseClient) { _, _, _, _ -> true }
        if (!group.selected.add(operation)) denyDispatch()
        val prepared = group.calls.singleOrNull { it.operation == operation } ?: denyDispatch()
        return transport(baseClient, group.context ?: denyDispatch(), prepared.profile) { request ->
            val captured = snapshot(request, prepared.profile)
            if (!captured.contentEquals(prepared.copyBody())) denyDispatch()
            group to prepared
        }
    }

    fun forLazySingleCall(
        baseClient: OkHttpClient,
        context: AiProviderDispatchContext?,
        operation: AiPhysicalOperation,
        profile: AiPhysicalWireProfile,
    ): GatedAiHttpExchange {
        if (!properties.enabled) return GatedAiHttpExchange(baseClient) { _, _, _, _ -> true }
        val current = context ?: denyDispatch()
        remaining(current)
        return transport(baseClient, current, profile) { request ->
            val captured = snapshot(request, profile)
            val prepared = PreparedAiHttpRequest.freeze(operation, current.providerOrdinal, current.finalAttemptId, profile, captured)
            prepareGroup(current, listOf(prepared)) to prepared
        }
    }

    private fun transport(
        base: OkHttpClient,
        context: AiProviderDispatchContext,
        profile: AiPhysicalWireProfile,
        prepare: (Request) -> Pair<PreparedAiDispatchGroup, PreparedAiHttpRequest>,
    ): GatedAiHttpExchange {
        val appUsed = AtomicBoolean()
        val networkUsed = AtomicBoolean()
        val expected = AtomicReference<PreparedAiHttpRequest>()
        val permit = AtomicReference<AiBudgetDispatchPermit>()
        val permitCutoff = AtomicReference<Long>()
        val client =
            base
                .newBuilder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .apply {
                    interceptors().add(
                        0,
                        okhttp3.Interceptor { chain ->
                            if (!appUsed.compareAndSet(false, true)) denyDispatch()
                            remaining(context)
                            val (group, prepared) = prepare(chain.request())
                            remaining(context)
                            val reserved = group.reservation?.calls?.singleOrNull { it.call == prepared.spec() } ?: denyDispatch()
                            val claimed =
                                accounting {
                                    budget.claim(AiBudgetDispatchRequest(reserved.reservationId, context.execution, reserved.call))
                                }
                                    ?: denyDispatch()
                            if (claimed.reservationId != reserved.reservationId) denyDispatch()
                            permit.set(claimed)
                            val wallRemaining = Duration.between(clock.instant(), claimed.deadlineAt)
                            if (wallRemaining.isNegative || wallRemaining.isZero) denyDispatch()
                            val localRemaining = remaining(context)
                            val allowed = if (wallRemaining >= Duration.ofNanos(localRemaining)) localRemaining else wallRemaining.toNanos()
                            permitCutoff.set(nanoTime.asLong + allowed)
                            val timeout = chain.call().timeout()
                            val cutoff = System.nanoTime() + allowed
                            if (!timeout.hasDeadline() || cutoff - timeout.deadlineNanoTime() < 0) timeout.deadlineNanoTime(cutoff)
                            expected.set(prepared)
                            chain.proceed(
                                chain
                                    .request()
                                    .newBuilder()
                                    .method(
                                        profile.method,
                                        prepared.copyBody().toRequestBody(chain.request().body?.contentType()),
                                    ).build(),
                            )
                        },
                    )
                }.addNetworkInterceptor { chain ->
                    if (!networkUsed.compareAndSet(false, true)) denyDispatch()
                    remaining(context)
                    val captured = snapshot(chain.request(), profile)
                    val prepared = expected.get() ?: denyDispatch()
                    if (digest(captured) != prepared.wholePayloadFingerprint) denyDispatch()
                    val granted = permit.get() ?: denyDispatch()
                    val cutoff = permitCutoff.get() ?: denyDispatch()
                    remaining(context)
                    if (clock.instant() >= granted.deadlineAt || cutoff - nanoTime.asLong <= 0) denyDispatch()
                    val response =
                        chain.proceed(
                            chain
                                .request()
                                .newBuilder()
                                .method(profile.method, captured.toRequestBody(chain.request().body?.contentType()))
                                .build(),
                        )
                    if (response.code !in 200..299) {
                        val status = response.code
                        val retry =
                            response
                                .header(
                                    "Retry-After",
                                )?.toLongOrNull()
                                ?.takeIf { it >= 0 && it <= Long.MAX_VALUE / 1000 }
                                ?.times(1000)
                        val errorBytes =
                            try {
                                response.body?.byteStream()?.readNBytes(16384) ?: byteArrayOf()
                            } finally {
                                response.close()
                            }
                        throw AiHttpProviderStatusException(status, retry, errorBytes)
                    }
                    response
                }.build()
        return GatedAiHttpExchange(client) { eventId, outcome, usage, failure ->
            val claimed = permit.get()
            if (claimed == null) {
                true
            } else {
                val accepted =
                    accounting {
                        budget.observe(
                            AiBudgetObservation(claimed.reservationId, claimed.dispatchToken, eventId, outcome, usage, failure),
                        )
                    }
                if (!accepted) denyDispatch()
                remaining(context)
                if (clock.instant() >= claimed.deadlineAt) denyDispatch()
                true
            }
        }
    }

    private fun snapshot(
        request: Request,
        profile: AiPhysicalWireProfile,
    ): ByteArray {
        if (request.method != profile.method || request.url.toUri() != profile.endpoint || profile.captureLimitBytes <= 0) denyDispatch()
        val body = request.body ?: denyDispatch()
        if (body.isOneShot() || body.isDuplex() || body.contentLength() > profile.captureLimitBytes) denyDispatch()
        val buffer = Buffer()
        var size = 0L
        val sink =
            object : ForwardingSink(buffer) {
                override fun write(
                    source: Buffer,
                    byteCount: Long,
                ) {
                    if (byteCount > profile.captureLimitBytes.toLong() - size) denyDispatch()
                    size += byteCount
                    super.write(source, byteCount)
                }
            }.buffer()
        body.writeTo(sink)
        sink.flush()
        return buffer.readByteArray()
    }

    private fun validateIdentity(
        context: AiProviderDispatchContext,
        operation: AiPhysicalOperation,
        ordinal: Int,
        attemptId: UUID?,
    ) {
        val final = operation in setOf(AiPhysicalOperation.FINAL_PRIMARY, AiPhysicalOperation.FINAL_FALLBACK)
        if (final != (context.execution.kind == AiBudgetExecutionKind.FINAL_ANALYSIS) ||
            ordinal != context.providerOrdinal ||
            attemptId != context.finalAttemptId
        ) {
            denyDispatch()
        }
        if (final &&
            (
                attemptId == null ||
                    (operation == AiPhysicalOperation.FINAL_PRIMARY && ordinal !in 1..4) ||
                    (operation == AiPhysicalOperation.FINAL_FALLBACK && ordinal != 1)
            )
        ) {
            denyDispatch()
        }
        if (!final && (attemptId != null || ordinal != 1)) denyDispatch()
    }

    private fun remaining(context: AiProviderDispatchContext): Long =
        (context.deadlineNanos - nanoTime.asLong).also {
            if (it <=
                0
            ) {
                denyDispatch()
            }
        }

    private fun <T> accounting(call: () -> T): T =
        try {
            call()
        } catch (_: Exception) {
            denyDispatch()
        }
}

internal fun digest(body: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }

internal fun denyDispatch(): Nothing = throw AiHttpDispatchException()
