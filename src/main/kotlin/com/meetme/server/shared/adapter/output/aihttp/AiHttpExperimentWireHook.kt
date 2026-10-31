package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Optional experiment-only limiter. No production bean is registered; absent means no change. */
interface AiHttpExperimentWireHook {
    fun callStarted(call: AiHttpExperimentCall) {}

    fun beforeHeaders(call: AiHttpExperimentCall)

    fun headersEmitted(call: AiHttpExperimentCall) {}

    fun callFinished(
        call: AiHttpExperimentCall,
        httpStatus: Int?,
        failure: AiHttpExperimentTransportFailure?,
    ) {}
}

/** Opaque identities only. Never export requests, payload/profile fingerprints, URLs or credentials. */
data class AiHttpExperimentAdmission(
    val opaqueId: String,
    val operation: AiPhysicalOperation,
)

data class AiHttpExperimentCall(
    val callId: UUID,
    val executionId: UUID,
    val attemptId: UUID?,
    val operation: AiPhysicalOperation,
    val ordinal: Int,
    val admissionId: String? = null,
)

enum class AiHttpExperimentTransportFailure { TIMEOUT, NETWORK }

object AiHttpExperimentAdmissionIds {
    fun of(
        request: AiBudgetReserveRequest,
        call: AiPhysicalCallSpec,
    ): AiHttpExperimentAdmission {
        val execution = request.execution
        val digest = MessageDigest.getInstance("SHA-256")
        // Bind one immutable child, independently of its sibling group and list order.
        listOf(
            "experiment-admission-v1",
            execution.roomId.toString(),
            execution.kind.name,
            execution.executionId.toString(),
            execution.generation.toString(),
            execution.ownerToken.toString(),
            execution.version.toString(),
            execution.sourceFingerprint,
            execution.contextFingerprint,
            call.operation.name,
            call.ordinal.toString(),
            call.wholePayloadFingerprint,
            call.profileFingerprint,
            call.attemptId?.toString() ?: "<null>",
        ).forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return AiHttpExperimentAdmission(digest.digest().joinToString("") { "%02x".format(it) }, call.operation)
    }
}

/** Completes the original public listener contract; none of its content is copied into the hook. */
internal class AiHttpExperimentListenerFactory(
    private val originalFactory: EventListener.Factory,
    private val hook: AiHttpExperimentWireHook,
    private val context: AiProviderDispatchContext,
    private val operation: AiPhysicalOperation,
) : EventListener.Factory {
    private val traces = ConcurrentHashMap<Call, Trace>()

    fun bind(
        call: Call,
        admission: AiHttpExperimentAdmission,
    ) {
        val trace = traces[call] ?: throw AiHttpDispatchException()
        trace.metadata.updateAndGet { it.copy(admissionId = admission.opaqueId) }
    }

    override fun create(call: Call): EventListener {
        val original = originalFactory.create(call)
        val trace =
            Trace(
                AiHttpExperimentCall(
                    UUID.randomUUID(),
                    context.execution.executionId,
                    context.finalAttemptId,
                    operation,
                    context.providerOrdinal,
                ),
            )
        traces[call] = trace
        return object : EventListener() {
            override fun callStart(call: Call) {
                original.callStart(call)
                try {
                    hook.callStarted(trace.metadata.get())
                } catch (failure: Exception) {
                    traces.remove(call, trace)
                    throw failure
                }
            }

            override fun requestHeadersStart(call: Call) {
                original.requestHeadersStart(call)
                hook.beforeHeaders(trace.metadata.get())
            }

            override fun requestHeadersEnd(
                call: Call,
                request: Request,
            ) {
                original.requestHeadersEnd(call, request)
                hook.headersEmitted(trace.metadata.get())
            }

            override fun responseHeadersEnd(
                call: Call,
                response: Response,
            ) {
                original.responseHeadersEnd(call, response)
                trace.httpStatus = response.code.takeIf { it in 100..599 }
            }

            override fun callEnd(call: Call) {
                original.callEnd(call)
                finish(call, null)
            }

            override fun callFailed(
                call: Call,
                ioe: IOException,
            ) {
                original.callFailed(call, ioe)
                val kind =
                    if (generateSequence<Throwable>(ioe) { it.cause }.take(16).any {
                            it is InterruptedIOException || it is HttpTimeoutException || it is TimeoutException
                        }
                    ) {
                        AiHttpExperimentTransportFailure.TIMEOUT
                    } else {
                        AiHttpExperimentTransportFailure.NETWORK
                    }
                finish(call, kind)
            }

            private fun finish(
                call: Call,
                failure: AiHttpExperimentTransportFailure?,
            ) {
                if (!trace.finished.compareAndSet(false, true)) return
                try {
                    hook.callFinished(trace.metadata.get(), trace.httpStatus, failure)
                } finally {
                    traces.remove(call, trace)
                }
            }

            override fun proxySelectStart(
                call: Call,
                url: HttpUrl,
            ) = original.proxySelectStart(call, url)

            override fun proxySelectEnd(
                call: Call,
                url: HttpUrl,
                proxies: List<Proxy>,
            ) = original.proxySelectEnd(call, url, proxies)

            override fun dnsStart(
                call: Call,
                domainName: String,
            ) = original.dnsStart(call, domainName)

            override fun dnsEnd(
                call: Call,
                domainName: String,
                inetAddressList: List<InetAddress>,
            ) = original.dnsEnd(call, domainName, inetAddressList)

            override fun connectStart(
                call: Call,
                inetSocketAddress: InetSocketAddress,
                proxy: Proxy,
            ) = original.connectStart(call, inetSocketAddress, proxy)

            override fun secureConnectStart(call: Call) = original.secureConnectStart(call)

            override fun secureConnectEnd(
                call: Call,
                handshake: Handshake?,
            ) = original.secureConnectEnd(call, handshake)

            override fun connectEnd(
                call: Call,
                inetSocketAddress: InetSocketAddress,
                proxy: Proxy,
                protocol: Protocol?,
            ) = original.connectEnd(call, inetSocketAddress, proxy, protocol)

            override fun connectFailed(
                call: Call,
                inetSocketAddress: InetSocketAddress,
                proxy: Proxy,
                protocol: Protocol?,
                ioe: IOException,
            ) = original.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)

            override fun connectionAcquired(
                call: Call,
                connection: Connection,
            ) = original.connectionAcquired(call, connection)

            override fun connectionReleased(
                call: Call,
                connection: Connection,
            ) = original.connectionReleased(call, connection)

            override fun requestBodyStart(call: Call) = original.requestBodyStart(call)

            override fun requestBodyEnd(
                call: Call,
                byteCount: Long,
            ) = original.requestBodyEnd(call, byteCount)

            override fun requestFailed(
                call: Call,
                ioe: IOException,
            ) = original.requestFailed(call, ioe)

            override fun responseHeadersStart(call: Call) = original.responseHeadersStart(call)

            override fun responseBodyStart(call: Call) = original.responseBodyStart(call)

            override fun responseBodyEnd(
                call: Call,
                byteCount: Long,
            ) = original.responseBodyEnd(call, byteCount)

            override fun responseFailed(
                call: Call,
                ioe: IOException,
            ) = original.responseFailed(call, ioe)

            override fun canceled(call: Call) = original.canceled(call)

            override fun satisfactionFailure(
                call: Call,
                response: Response,
            ) = original.satisfactionFailure(call, response)

            override fun cacheHit(
                call: Call,
                response: Response,
            ) = original.cacheHit(call, response)

            override fun cacheMiss(call: Call) = original.cacheMiss(call)

            override fun cacheConditionalHit(
                call: Call,
                cachedResponse: Response,
            ) = original.cacheConditionalHit(call, cachedResponse)
        }
    }

    private class Trace(
        metadata: AiHttpExperimentCall,
    ) {
        val metadata = AtomicReference(metadata)
        val finished = AtomicBoolean()

        @Volatile var httpStatus: Int? = null
    }
}
