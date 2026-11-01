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
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID

internal data class ActivationElevenApproval(
    val approvalId: String,
    val source40: String,
    val sourceFingerprint64: String,
    val configHash64: String,
    val scenarioHash64: String,
)

internal enum class ActivationElevenStop {
    INVALID_METADATA,
    DUPLICATE_REQUEST,
    LIMIT_REACHED,
    COVERAGE_UNKNOWN,
    PROVIDER_FAILURE,
    SCHEMA_FAILURE,
    SEMANTIC_FAILURE,
    PRIVACY_FAILURE,
}

internal enum class ActivationElevenValidation {
    ACCEPTED,
    SCHEMA_FAILURE,
    SEMANTIC_FAILURE,
    PRIVACY_FAILURE,
}

/** Test-only, one-use wire ceiling. Neither a price quote nor permission to incur a charge. */
internal class ActivationElevenBudget private constructor(
    private val approval: ActivationElevenApproval,
    private val path: Path,
) : AiHttpExperimentWireHook {
    private val allowed = mutableMapOf<String, AiHttpExperimentCall>()
    private val immutable = mutableMapOf<String, String>()
    private val calls = linkedMapOf<UUID, Record>()
    private val consumed = mutableSetOf<String>()
    private var stopped: ActivationElevenStop? = null

    fun sourceFirst(delegate: AiRuntimeDispatchGuardPort): AiRuntimeDispatchGuardPort =
        AiRuntimeDispatchGuardPort { request, at ->
            delegate.authorize(request, at)?.takeIf { register(request) }
        }

    @Synchronized
    private fun register(request: AiBudgetReserveRequest): Boolean {
        if (stopped != null) return false
        if (request.calls.size != 1 || request.calls.any { it.ordinal != 1 || it.operation !in CAPS }) {
            stop(ActivationElevenStop.INVALID_METADATA)
            return false
        }
        val spec = request.calls.single()
        val admission = AiHttpExperimentAdmissionIds.of(request, spec).opaqueId
        val key = "${request.execution.executionId}:${spec.operation}:${spec.ordinal}"
        if (immutable[key]?.let { it != admission } == true) {
            stop(ActivationElevenStop.INVALID_METADATA)
            return false
        }
        val metadata =
            AiHttpExperimentCall(UUID(0, 0), request.execution.executionId, spec.attemptId, spec.operation, spec.ordinal, admission)
        if (allowed[admission]?.let { it != metadata } == true) {
            stop(ActivationElevenStop.INVALID_METADATA)
            return false
        }
        immutable[key] = admission
        allowed[admission] = metadata
        return true
    }

    @Synchronized
    override fun callStarted(call: AiHttpExperimentCall) {
        checkpoint()
        if (call.callId in calls || call.ordinal != 1 || call.operation !in CAPS || call.admissionId != null) {
            deny(ActivationElevenStop.DUPLICATE_REQUEST)
        }
        calls[call.callId] = Record(call)
        persist()
    }

    @Synchronized
    override fun beforeHeaders(call: AiHttpExperimentCall) {
        checkpoint()
        val record = calls[call.callId] ?: deny(ActivationElevenStop.COVERAGE_UNKNOWN)
        val admission = call.admissionId ?: deny(ActivationElevenStop.INVALID_METADATA)
        val binding = allowed[admission] ?: deny(ActivationElevenStop.INVALID_METADATA)
        if (record.call != call.copy(admissionId = null) || call.copy(callId = UUID(0, 0)) != binding) {
            deny(ActivationElevenStop.INVALID_METADATA)
        }
        if (record.admitted || admission in consumed) deny(ActivationElevenStop.DUPLICATE_REQUEST)
        if (calls.values.any { it.admitted && (!it.finished || !it.validated) }) deny(ActivationElevenStop.COVERAGE_UNKNOWN)
        if (consumed.size >= MAXIMUM ||
            calls.values.count { it.admitted && it.call.operation == call.operation } >= CAPS.getValue(call.operation)
        ) {
            deny(ActivationElevenStop.LIMIT_REACHED)
        }
        record.call = call
        record.admitted = true
        consumed += admission
        // Durability is a prerequisite to allowing headers, never a retrospective metric.
        persist()
    }

    @Synchronized
    override fun headersEmitted(call: AiHttpExperimentCall) {
        checkpoint()
        val record = calls[call.callId] ?: deny(ActivationElevenStop.COVERAGE_UNKNOWN)
        if (!record.admitted || record.emitted || record.call != call) deny(ActivationElevenStop.COVERAGE_UNKNOWN)
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
            stop(ActivationElevenStop.COVERAGE_UNKNOWN)
            return
        }
        record.finished = true
        record.status = httpStatus?.takeIf { it in 100..599 }
        record.failure = failure
        when {
            failure != null || (httpStatus != null && httpStatus !in 200..299) -> stopped = stopped ?: ActivationElevenStop.PROVIDER_FAILURE
            httpStatus == null || !record.admitted || !record.emitted -> stopped = stopped ?: ActivationElevenStop.COVERAGE_UNKNOWN
        }
        persist()
    }

    @Synchronized
    fun validateFinished(
        callId: UUID,
        verdict: ActivationElevenValidation,
    ) {
        checkpoint()
        val record = calls[callId] ?: deny(ActivationElevenStop.COVERAGE_UNKNOWN)
        if (!record.admitted ||
            !record.emitted ||
            !record.finished ||
            record.validated ||
            record.failure != null ||
            record.status !in 200..299
        ) {
            deny(ActivationElevenStop.COVERAGE_UNKNOWN)
        }
        if (verdict != ActivationElevenValidation.ACCEPTED) {
            stop(ActivationElevenStop.valueOf(verdict.name))
            return
        }
        record.validated = true
        persist()
    }

    @Synchronized
    fun checkpoint() {
        if (stopped != null) throw AiHttpDispatchException()
    }

    @Synchronized
    fun assertComplete() {
        if (calls.isEmpty() || calls.values.any { !it.admitted || !it.emitted || !it.finished || !it.validated }) {
            stop(ActivationElevenStop.COVERAGE_UNKNOWN)
        }
        checkpoint()
    }

    @Synchronized
    fun stop(reason: ActivationElevenStop) {
        stopped = stopped ?: reason
        persist()
    }

    @Synchronized
    fun snapshot(): Map<String, Any?> =
        linkedMapOf(
            "approvalId" to approval.approvalId,
            "source40" to approval.source40,
            "sourceFingerprint64" to approval.sourceFingerprint64,
            "configHash64" to approval.configHash64,
            "scenarioHash64" to approval.scenarioHash64,
            "maximumPhysicalRequests" to MAXIMUM,
            "admissions" to consumed.size,
            "counts" to
                CAPS.mapKeys { it.key.name }.mapValues { (name, _) ->
                    calls.values.count { it.admitted && it.call.operation.name == name }
                },
            "caps" to CAPS.mapKeys { it.key.name },
            "actualGemini" to calls.values.count { it.emitted && it.call.operation == AiPhysicalOperation.FINAL_PRIMARY },
            "stopReason" to stopped?.name,
            "coverageComplete" to
                (stopped == null && calls.isNotEmpty() && calls.values.all { it.admitted && it.emitted && it.finished && it.validated }),
            "calls" to calls.values.map { it.report() },
            "billedAmount" to null,
            "monetaryCeilingVerified" to false,
        )

    private fun deny(reason: ActivationElevenStop): Nothing {
        stop(reason)
        throw AiHttpDispatchException()
    }

    private fun persist(createNew: Boolean = false) {
        try {
            safeDirectory(path.parent, create = false)
            if (!createNew) check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            val bytes = StandaloneGeminiOnceDiagnostic.JSON.writeValueAsBytes(snapshot())
            if (createNew) {
                forcedNew(path, bytes)
            } else {
                val temporary = Files.createTempFile(path.parent, ".activation-eleven-", ".tmp", PRIVATE_FILE)
                try {
                    FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) channel.write(buffer)
                        channel.force(true)
                    }
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    syncDirectory(path.parent)
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
        } catch (_: Exception) {
            stopped = stopped ?: ActivationElevenStop.COVERAGE_UNKNOWN
            throw AiHttpDispatchException()
        }
    }

    private data class Record(
        var call: AiHttpExperimentCall,
        var admitted: Boolean = false,
        var emitted: Boolean = false,
        var finished: Boolean = false,
        var validated: Boolean = false,
        var status: Int? = null,
        var failure: AiHttpExperimentTransportFailure? = null,
    ) {
        fun report(): Map<String, Any?> =
            mapOf(
                "callId" to call.callId.toString(),
                "executionId" to call.executionId.toString(),
                "operation" to call.operation.name,
                "ordinal" to call.ordinal,
                "admissionId" to call.admissionId,
                "admitted" to admitted,
                "headersEmitted" to emitted,
                "finished" to finished,
                "validated" to validated,
                "httpStatus" to status,
                "failure" to failure?.name,
            )
    }

    companion object {
        private const val MAXIMUM = 11
        private val PRIVATE_FILE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        private val CAPS =
            linkedMapOf(
                AiPhysicalOperation.PRECHECK_CLASSIFICATION to 6,
                AiPhysicalOperation.PRECHECK_GENERATION to 3,
                AiPhysicalOperation.FINAL_PRIMARY to 1,
                AiPhysicalOperation.FINAL_FALLBACK to 1,
            )

        fun open(
            approval: ActivationElevenApproval,
            claimsDirectory: Path,
            ledgerPath: Path,
        ): ActivationElevenBudget {
            require(approval.approvalId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{7,127}")))
            require(approval.source40.matches(Regex("[0-9a-f]{40}")))
            listOf(approval.sourceFingerprint64, approval.configHash64, approval.scenarioHash64).forEach {
                require(it.matches(Regex("[0-9a-f]{64}")))
            }
            val directory = claimsDirectory.toAbsolutePath().normalize()
            val path = ledgerPath.toAbsolutePath().normalize()
            safeDirectory(directory, create = true)
            val identity =
                MessageDigest
                    .getInstance(
                        "SHA-256",
                    ).digest(approval.approvalId.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            // Burn approval before output. A failed or uncertain start cannot retry under another path.
            forcedNew(directory.resolve("$identity.claim"), "$approval\n${path}\n".toByteArray())
            safeDirectory(path.parent, create = true)
            return ActivationElevenBudget(approval, path).also { it.persist(createNew = true) }
        }

        private fun safeDirectory(
            path: Path,
            create: Boolean,
        ) {
            val absolute = path.toAbsolutePath().normalize()
            var current: Path? = absolute
            while (current != null) {
                check(!Files.isSymbolicLink(current))
                current = current.parent
            }
            if (create) Files.createDirectories(absolute)
            check(Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS))
        }

        private fun forcedNew(
            path: Path,
            bytes: ByteArray,
        ) {
            FileChannel
                .open(
                    path,
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PRIVATE_FILE,
                ).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
            syncDirectory(path.parent)
        }

        private fun syncDirectory(path: Path) {
            FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
