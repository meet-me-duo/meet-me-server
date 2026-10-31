package com.meetme.server.experiment

import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchException
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentAdmissionIds
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentCall
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentTransportFailure
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentWireHook
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

internal enum class BlindFlowStop { PROVIDER_FAILURE, COVERAGE_UNKNOWN, INVALID_METADATA, DUPLICATE_REQUEST, LIMIT_REACHED }

/** Test-only physical limiter. Source validation is mandatory and is never replaced by the manifest. */
internal class BlindFlowBudget(
    private val path: Path,
    private val approvalId: String,
    private val manifestSha: String,
    private val sourceSha: String,
    caps: Map<AiPhysicalOperation, Int> = CAPS,
    private val maximumPhysicalRequests: Int = 20,
) : AiHttpExperimentWireHook {
    private val caps = caps.toMap()
    private val allowed = mutableMapOf<String, AiHttpExperimentCall>()
    private val immutable = mutableMapOf<String, String>()
    private val calls = linkedMapOf<UUID, Record>()
    private val consumed = mutableSetOf<String>()
    private var stopped: BlindFlowStop? = null
    private var sourceChecks = 0

    init {
        validateBinding(approvalId, manifestSha, sourceSha)
        require(this.caps.keys == CAPS.keys && this.caps.all { (operation, limit) -> limit in 0..CAPS.getValue(operation) })
        require(maximumPhysicalRequests in 1..20 && maximumPhysicalRequests <= this.caps.values.sum())
        Files.createDirectories(path.parent)
        writeForced(path, json(), createNew = true)
        syncDirectory(path.parent)
    }

    fun sourceFirst(delegate: AiRuntimeDispatchGuardPort): AiRuntimeDispatchGuardPort =
        AiRuntimeDispatchGuardPort { request, at ->
            delegate.authorize(request, at)?.takeIf { register(request) }
        }

    @Synchronized
    private fun register(request: AiBudgetReserveRequest): Boolean {
        if (stopped != null) return false
        if (request.calls.isEmpty() || request.calls.any { it.ordinal != 1 || it.operation == AiPhysicalOperation.FINAL_PRIMARY }) {
            stop(BlindFlowStop.INVALID_METADATA)
            return false
        }
        val registrations =
            request.calls.map { spec ->
                val admission = AiHttpExperimentAdmissionIds.of(request, spec).opaqueId
                val key = "${request.execution.executionId}:${spec.operation}:${spec.ordinal}"
                val metadata =
                    AiHttpExperimentCall(
                        UUID(0, 0),
                        request.execution.executionId,
                        spec.attemptId,
                        spec.operation,
                        spec.ordinal,
                        admission,
                    )
                Triple(key, admission, metadata)
            }
        if (registrations.any { (key, admission, _) -> immutable[key]?.let { it != admission } == true }) {
            stop(BlindFlowStop.INVALID_METADATA)
            return false
        }
        registrations.forEach { (key, admission, metadata) ->
            immutable[key] = admission
            allowed[admission] = metadata
        }
        sourceChecks++
        persist()
        return true
    }

    @Synchronized
    override fun callStarted(call: AiHttpExperimentCall) {
        ensureRunning()
        if (call.callId in calls || call.operation !in caps || call.ordinal != 1) deny(BlindFlowStop.INVALID_METADATA)
        calls[call.callId] = Record(call)
        persist()
    }

    @Synchronized
    override fun beforeHeaders(call: AiHttpExperimentCall) {
        ensureRunning()
        val record = calls[call.callId] ?: deny(BlindFlowStop.COVERAGE_UNKNOWN)
        val admission = call.admissionId ?: deny(BlindFlowStop.INVALID_METADATA)
        val bound = allowed[admission] ?: deny(BlindFlowStop.INVALID_METADATA)
        if (call.copy(callId = UUID(0, 0)) != bound || record.call.copy(admissionId = admission) != call) {
            deny(BlindFlowStop.INVALID_METADATA)
        }
        if (record.admitted || admission in consumed) deny(BlindFlowStop.DUPLICATE_REQUEST)
        if (calls.values.any { it.admitted && !it.finished }) deny(BlindFlowStop.COVERAGE_UNKNOWN)
        val cap = caps[call.operation] ?: deny(BlindFlowStop.INVALID_METADATA)
        if (calls.values.count { it.admitted && it.call.operation == call.operation } >= cap || consumed.size >= maximumPhysicalRequests) {
            deny(BlindFlowStop.LIMIT_REACHED)
        }
        record.call = call
        record.admitted = true
        consumed += admission
        persist() // Must reach stable storage before OkHttp may emit headers. Never refund an admission.
    }

    @Synchronized
    override fun headersEmitted(call: AiHttpExperimentCall) {
        val record = calls[call.callId]
        if (record == null || !record.admitted || record.emitted || record.call != call) deny(BlindFlowStop.COVERAGE_UNKNOWN)
        record.emitted = true
        persist()
    }

    @Synchronized
    override fun callFinished(
        call: AiHttpExperimentCall,
        httpStatus: Int?,
        failure: AiHttpExperimentTransportFailure?,
    ) {
        val record = calls[call.callId]
        if (record == null || record.finished || record.call.copy(admissionId = call.admissionId) != call) {
            stop(BlindFlowStop.COVERAGE_UNKNOWN)
            return
        }
        record.finished = true
        record.elapsedNanos = (System.nanoTime() - record.startedNanos).coerceAtLeast(0)
        record.status = httpStatus?.takeIf { it in 100..599 }
        record.failure = failure
        if (failure != null || (httpStatus != null && httpStatus !in 200..299)) {
            stopped = stopped ?: BlindFlowStop.PROVIDER_FAILURE
        } else if (httpStatus == null || !record.admitted || !record.emitted) {
            stopped = stopped ?: BlindFlowStop.COVERAGE_UNKNOWN
        }
        persist()
    }

    /** Polling may run while a legitimate provider call is in flight; completion is checked separately. */
    @Synchronized
    fun checkpoint() = ensureRunning()

    @Synchronized
    fun stop(reason: BlindFlowStop) {
        stopped = stopped ?: reason
        persist()
    }

    @Synchronized
    fun assertComplete() {
        if (calls.values.any { !it.admitted || !it.emitted || !it.finished }) stop(BlindFlowStop.COVERAGE_UNKNOWN)
        check(stopped == null) { "BLIND_FLOW_STOPPED" }
    }

    @Synchronized
    fun snapshot(): Map<String, Any?> =
        mapOf(
            "approvalId" to approvalId,
            "manifestSha256" to manifestSha,
            "sourceSha256" to sourceSha,
            "maximumPhysicalRequests" to maximumPhysicalRequests,
            "admissions" to consumed.size,
            "counts" to caps.keys.associate { it.name to calls.values.count { record -> record.admitted && record.call.operation == it } },
            "caps" to caps.mapKeys { it.key.name },
            "actualGemini" to 0,
            "sourceChecks" to sourceChecks,
            "coverageComplete" to calls.values.all { it.admitted && it.emitted && it.finished },
            "stopReason" to stopped?.name,
            "calls" to calls.values.map { it.report() },
        )

    private fun ensureRunning() {
        if (stopped != null) throw AiHttpDispatchException()
    }

    private fun deny(reason: BlindFlowStop): Nothing {
        stop(reason)
        throw AiHttpDispatchException()
    }

    private fun json(): ByteArray = (StandaloneGeminiOnceDiagnostic.JSON.writeValueAsString(snapshot()) + "\n").toByteArray()

    private fun persist() {
        try {
            val pending = path.resolveSibling("${path.fileName}.pending")
            writeForced(pending, json(), createNew = true)
            Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(path.parent)
        } catch (_: Exception) {
            stopped = BlindFlowStop.COVERAGE_UNKNOWN
            throw AiHttpDispatchException()
        }
    }

    private data class Record(
        var call: AiHttpExperimentCall,
        val startedNanos: Long = System.nanoTime(),
        var elapsedNanos: Long? = null,
        var admitted: Boolean = false,
        var emitted: Boolean = false,
        var finished: Boolean = false,
        var status: Int? = null,
        var failure: AiHttpExperimentTransportFailure? = null,
    ) {
        fun report(): Map<String, Any?> =
            mapOf(
                "callId" to call.callId,
                "executionId" to call.executionId,
                "attemptId" to call.attemptId,
                "operation" to call.operation,
                "ordinal" to call.ordinal,
                "admitted" to admitted,
                "headersEmitted" to emitted,
                "finished" to finished,
                "elapsedNanos" to elapsedNanos,
                "httpStatus" to status,
                "failure" to failure,
            )
    }

    companion object {
        private val CAPS =
            mapOf(
                AiPhysicalOperation.PRECHECK_CLASSIFICATION to 12,
                AiPhysicalOperation.PRECHECK_GENERATION to 6,
                AiPhysicalOperation.FINAL_FALLBACK to 2,
            )

        fun claim(
            directory: Path,
            approvalId: String,
            manifestSha: String,
            sourceSha: String,
        ): Path {
            validateBinding(approvalId, manifestSha, sourceSha)
            Files.createDirectories(directory)
            val path = directory.resolve("$approvalId.claim")
            writeForced(path, "manifestSha256=$manifestSha\nsourceSha256=$sourceSha\n".toByteArray(), createNew = true)
            syncDirectory(directory)
            return path
        }

        private fun validateBinding(
            approvalId: String,
            manifestSha: String,
            sourceSha: String,
        ) {
            require(approvalId.matches(Regex("Sentinel_[a-f0-9]{32}")))
            require(manifestSha.matches(Regex("[a-f0-9]{64}")))
            require(sourceSha.matches(Regex("[a-f0-9]{64}")))
        }

        private fun writeForced(
            path: Path,
            bytes: ByteArray,
            createNew: Boolean,
        ) {
            val create = if (createNew) StandardOpenOption.CREATE_NEW else StandardOpenOption.CREATE
            FileChannel.open(path, StandardOpenOption.WRITE, create).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        }

        private fun syncDirectory(path: Path) {
            FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
