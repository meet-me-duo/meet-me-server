package com.meetme.server.experiment

import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentAdmissionIds
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentCall
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentTransportFailure
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentWireHook
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPInputStream

/** One approved case only. Native authority remains mandatory; historical counts and UNKNOWNs are never rewritten. */
class C5HttpWireJournal(
    private val directory: Path,
    private val maximumPhysicalCalls: Int = 17,
    private val carriedRemaining: Int = 5,
    private val newRemaining: Int = 20,
    private val priorJevUpperBound: BigDecimal = BigDecimal("0.00072887"),
    private val secretValues: List<String> = emptyList(),
) : AiHttpExperimentWireHook {
    private val json = JsonMapper.builder().findAndAddModules().build()
    private val grants = linkedMapOf<String, Grant>()
    private val prepared = linkedMapOf<String, ByteArray>()
    private val rows = linkedMapOf<UUID, MutableMap<String, Any?>>()
    private val current = ThreadLocal<AiHttpExperimentCall>()
    private val installed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AiHttpDispatchGate, Boolean>())
    private var room: UUID? = null
    private var finalExecution: Pair<UUID, Long>? = null
    private var stopped = false
    private var sourceFenceConnected = false
    private val journal = directory.resolve("native-physical-calls.json")

    init {
        require(maximumPhysicalCalls in 1..17 && maximumPhysicalCalls <= carriedRemaining + newRemaining)
        require(carriedRemaining in 0..5 && newRemaining in 0..20 && priorJevUpperBound >= BigDecimal.ZERO)
        Files.createDirectories(directory)
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        check(!Files.exists(journal)) { "EXISTING_CASE_JOURNAL_PRESERVE" }
        persist()
    }

    @Synchronized fun install(gate: AiHttpDispatchGate) {
        check(gate.properties.enabled) { "ORIGINAL_NATIVE_WIRE_GATE_REQUIRED" }
        if (installed.add(gate)) gate.installExperimentWireHook(this)
    }

    @Synchronized fun sourceFenceConnected() {
        sourceFenceConnected = true
    }

    @Synchronized fun requireInstalled() {
        checkpoint()
        check(installed.size == 2 && sourceFenceConnected) { "BOTH_ORIGINAL_GATES_AND_SOURCE_FENCE_REQUIRED" }
    }

    @Synchronized fun arm(roomId: UUID) {
        checkpoint()
        check(room == null || room == roomId) { "ONE_APPROVED_ROOM_ONLY" }
        room = roomId
        persist()
    }

    @Synchronized fun registerOriginalGrant(request: AiBudgetReserveRequest) {
        checkpoint()
        check(room != null && room == request.execution.roomId) { "UNAPPROVED_ROOM" }
        check(request.calls.isNotEmpty())
        if (request.calls.any { it.operation == AiPhysicalOperation.FINAL_PRIMARY || it.operation == AiPhysicalOperation.FINAL_FALLBACK }) {
            val identity = request.execution.executionId to request.execution.generation
            check(finalExecution == null || finalExecution == identity) { "ONE_CURRENT_NATIVE_FINAL_OPERATION_ONLY" }
            finalExecution = identity
        }
        request.calls.forEach { call ->
            check(call.operation in CAPS && call.wholePayloadFingerprint.matches(HASH))
            val key = key(request.execution.executionId, call.operation, call.ordinal)
            val grant =
                Grant(
                    request.execution.generation,
                    call.wholePayloadFingerprint,
                    AiHttpExperimentAdmissionIds.of(request, call).opaqueId,
                    request.execution.sourceFingerprint,
                    request.execution.contextFingerprint,
                )
            check(grants[key] == null || grants[key] == grant) { "NATIVE_GRANT_CHANGED" }
            grants[key] = grant
        }
    }

    @Synchronized fun prepareRequest(
        executionId: UUID,
        operation: AiPhysicalOperation,
        ordinal: Int,
        bytes: ByteArray,
    ) {
        checkpoint()
        val key = key(executionId, operation, ordinal)
        check(prepared[key] == null || prepared.getValue(key).contentEquals(bytes)) { "PREPARED_REQUEST_CHANGED" }
        prepared[key] = bytes.copyOf()
    }

    @Synchronized override fun callStarted(call: AiHttpExperimentCall) {
        checkpoint()
        val grant = grants[key(call)] ?: error("NO_ORIGINAL_NATIVE_GRANT")
        check(call.admissionId == null || call.admissionId == grant.admission) { "FOREIGN_NATIVE_ADMISSION" }
        check(room != null && rows.values.none { it["settled"] != true }) { "SINGLE_ACTIVE_PHYSICAL_CALL" }
        check(
            call.callId !in rows &&
                rows.values.none {
                    it["executionId"] == call.executionId.toString() &&
                        it["operation"] == call.operation.name &&
                        it["ordinal"] == call.ordinal &&
                        it["generation"] == grant.generation
                },
        ) { "DUPLICATE_PHYSICAL_IDENTITY" }
        check(rows.size < maximumPhysicalCalls && rows.size < carriedRemaining + newRemaining) { "PHYSICAL_CAP" }
        check(rows.values.count { it["operation"] == call.operation.name } < CAPS.getValue(call.operation)) { "STAGE_CAP" }
        val jev = rows.values.count { it["operation"] == AiPhysicalOperation.PRECHECK_CLASSIFICATION.name }
        if (call.operation == AiPhysicalOperation.PRECHECK_CLASSIFICATION) {
            check(priorJevUpperBound + HOLD * BigDecimal(jev + 1) <= BigDecimal.ONE) { "JEV_SEPARATE_USD1_CAP" }
        }
        rows[call.callId] =
            linkedMapOf(
                "callId" to call.callId.toString(),
                "executionId" to call.executionId.toString(),
                "attemptId" to call.attemptId?.toString(),
                "generation" to grant.generation,
                "operation" to call.operation.name,
                "ordinal" to call.ordinal,
                "tranche" to if (rows.size < carriedRemaining) "CARRIED23" else "NEW20",
                "state" to "RESERVED",
                "startedAt" to Instant.now().toString(),
                "settled" to false,
                "headersEmitted" to false,
                "sourceFingerprint" to grant.source,
                "contextFingerprint" to grant.context,
                "wholePayloadFingerprint" to grant.payload,
            )
        current.set(call)
        persist()
    }

    @Synchronized fun captureRequest(
        call: AiHttpExperimentCall,
        bytes: ByteArray,
    ) {
        try {
            checkpoint()
            val row = rows[call.callId] ?: error("NO_CALL")
            val grant = grants[key(call)] ?: error("NO_GRANT")
            check(bytes.size <= 262144 && sha(bytes) == grant.payload) { "ACTUAL_REQUEST_FINGERPRINT_MISMATCH" }
            noSecrets(bytes)
            val path = directory.resolve("call-${call.callId}-request.json")
            if (!Files.exists(path)) privateWrite(path, bytes) else check(Files.readAllBytes(path).contentEquals(bytes))
            row["requestPath"] = path.toString()
            row["requestSha256"] = sha(bytes)
            val root = json.readTree(bytes)
            val schema =
                root
                    .path("text")
                    .path("format")
                    .path("schema")
                    .takeUnless { it.isMissingNode }
                    ?: root.path("generationConfig").path("responseJsonSchema").takeUnless { it.isMissingNode }
            row["model"] = root.path("model").asText().takeIf { it.isNotBlank() }
            row["schemaSha256"] = schema?.let { sha(json.writeValueAsBytes(it)) }
            row["reasoning"] = root.path("reasoning").takeUnless { it.isMissingNode }
            row["generationSettings"] =
                listOf("max_output_tokens", "temperature", "top_p", "store")
                    .mapNotNull { name ->
                        root.path(name).takeUnless { it.isMissingNode }?.let { name to it }
                    }.toMap()
            row["instructionsSha256"] =
                root
                    .path("instructions")
                    .takeIf { it.isString }
                    ?.asText()
                    ?.toByteArray(Charsets.UTF_8)
                    ?.let(::sha)
            row["bodySendCompletion"] = "NOT_OBSERVED"
            persist()
        } catch (failure: Exception) {
            stopped = true
            throw IllegalStateException("PRIVATE_PRETRANSFER_REQUEST_FAILED", failure)
        }
    }

    @Synchronized override fun beforeHeaders(call: AiHttpExperimentCall) {
        checkpoint()
        val row = rows[call.callId] ?: error("NO_CALL")
        val grant = grants[key(call)] ?: error("NO_GRANT")
        check(call.admissionId == grant.admission && row["pretransferAt"] == null) { "NATIVE_ADMISSION_MISMATCH_OR_REUSE" }
        if (row["requestPath"] == null) prepared[key(call)]?.let { captureRequest(call, it) }
        val requestPath = row["requestPath"] as? String ?: error("MISSING_PRETRANSFER_REQUEST")
        check(sha(Files.readAllBytes(Path.of(requestPath))) == grant.payload) { "PRETRANSFER_REQUEST_CHANGED" }
        row["admissionId"] = call.admissionId
        row["pretransferAt"] = Instant.now().toString()
        privateWrite(directory.resolve("call-${call.callId}-pretransfer.json"), json.writeValueAsBytes(row))
        current.set(call)
        persist()
    }

    @Synchronized override fun headersEmitted(call: AiHttpExperimentCall) =
        observe {
            val row = rows.getValue(call.callId)
            check(row["pretransferAt"] != null)
            row["headersEmitted"] = true
            row["headersEmittedAt"] = Instant.now().toString()
        }

    @Synchronized override fun requestObserved(
        call: AiHttpExperimentCall,
        endpointIdentifier: String,
        callTimeoutNanos: Long,
    ) = observe {
        rows.getValue(call.callId)["endpoint"] = endpointIdentifier
        rows.getValue(call.callId)["okHttpConfiguredTimeoutNanos"] = callTimeoutNanos
    }

    @Synchronized override fun responseObserved(
        call: AiHttpExperimentCall,
        httpStatus: Int,
        requestIds: Map<String, String>,
    ) = observe {
        noSecrets(json.writeValueAsBytes(requestIds))
        val row = rows.getValue(call.callId)
        row["httpStatus"] = httpStatus
        row["providerRequestIds"] = requestIds
    }

    @Synchronized override fun callFinished(
        call: AiHttpExperimentCall,
        httpStatus: Int?,
        failure: AiHttpExperimentTransportFailure?,
    ) = observe {
        val row = rows.getValue(call.callId)
        row["httpStatus"] = httpStatus
        row["failure"] = failure?.name
        row["settled"] = true
        row["finishedAt"] = Instant.now().toString()
        row["state"] = if (httpStatus == null || failure != null) "UNKNOWN" else "COMPLETED"
    }

    @Synchronized fun captureResponse(
        call: AiHttpExperimentCall,
        bytes: ByteArray,
    ) = observe {
        val decoded =
            if (bytes.size >= 2 && bytes[0] == 31.toByte() && bytes[1] == 139.toByte()) {
                GZIPInputStream(bytes.inputStream()).use { it.readNBytes(2097153) }
            } else {
                bytes
            }
        check(decoded.size <= 2097152)
        noSecrets(bytes)
        noSecrets(decoded)
        val row = rows.getValue(call.callId)
        val path = directory.resolve("call-${call.callId}-response.json")
        privateWrite(path, decoded)
        row["responsePath"] = path.toString()
        row["responseSha256"] = sha(decoded)
        val root = runCatching { json.readTree(decoded) }.getOrNull()
        root?.path("usage")?.takeUnless { it.isMissingNode || it.isNull }?.let { row["usage"] = it }
    }

    @Synchronized fun captureModel(
        call: AiHttpExperimentCall,
        bytes: ByteArray,
    ) = observe {
        check(bytes.size <= 262144)
        noSecrets(bytes)
        val path = directory.resolve("call-${call.callId}-model-output.json")
        privateWrite(path, bytes)
        rows.getValue(call.callId)["modelOutputPath"] = path.toString()
        rows.getValue(call.callId)["modelOutputSha256"] = sha(bytes)
    }

    @Synchronized fun recordUsage(
        call: AiHttpExperimentCall,
        usage: Any?,
    ) = observe {
        rows.getValue(call.callId)["usage"] = usage
    }

    @Synchronized fun recordError(
        call: AiHttpExperimentCall,
        safeError: Map<String, Any?>,
    ) = observe {
        noSecrets(json.writeValueAsBytes(safeError))
        rows.getValue(call.callId)["providerError"] = safeError
    }

    @Synchronized fun observationFailure() {
        stopped = true
        runCatching { persist() }
    }

    fun currentCall(): AiHttpExperimentCall = current.get() ?: error("NO_CURRENT_NATIVE_CALL")

    @Synchronized fun checkpoint() {
        check(!stopped) { "WIRE_OBSERVATION_FAILED_STOP_NEXT_OPERATION" }
    }

    @Synchronized fun snapshot(): Map<String, Any?> =
        mapOf(
            "roomId" to room?.toString(),
            "physicalCount" to rows.size,
            "unknownCount" to rows.values.count { it["state"] == "UNKNOWN" },
            "carriedRemaining" to (carriedRemaining - minOf(carriedRemaining, rows.size)),
            "newRemaining" to (newRemaining - (rows.size - carriedRemaining).coerceAtLeast(0)),
            "maximumPhysicalCalls" to maximumPhysicalCalls,
            "priorUnknownPreserved" to 10,
            "stopped" to stopped,
            "calls" to rows.values.map { it.toMap() },
            "invoiceVerified" to false,
            "priorJevCalculatedUpperBoundUSD" to priorJevUpperBound.toPlainString(),
            "newJevConservativeHoldUSD" to
                (
                    HOLD *
                        BigDecimal(
                            rows.values.count {
                                it["operation"] == AiPhysicalOperation.PRECHECK_CLASSIFICATION.name
                            },
                        )
                ).toPlainString(),
        )

    private fun observe(action: () -> Unit) {
        try {
            action()
            persist()
        } catch (_: Exception) {
            stopped = true
            runCatching { persist() }
        }
    }

    private fun noSecrets(bytes: ByteArray) {
        val text = bytes.toString(Charsets.UTF_8)
        check(secretValues.filter { it.length > 3 }.none { it in text }) { "SECRET_ECHO_CAPTURE_REFUSED" }
    }

    private fun persist() {
        val tmp =
            Files.createTempFile(
                directory,
                ".wire-",
                ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            )
        try {
            Files.write(tmp, json.writeValueAsBytes(snapshot()), StandardOpenOption.WRITE, StandardOpenOption.SYNC)
            Files.move(tmp, journal, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun privateWrite(
        path: Path,
        bytes: ByteArray,
    ) {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.write(path, bytes, StandardOpenOption.WRITE, StandardOpenOption.SYNC)
    }

    private data class Grant(
        val generation: Long,
        val payload: String,
        val admission: String,
        val source: String,
        val context: String,
    )

    private fun key(call: AiHttpExperimentCall) = key(call.executionId, call.operation, call.ordinal)

    private fun key(
        executionId: UUID,
        operation: AiPhysicalOperation,
        ordinal: Int,
    ) = "$executionId:$operation:$ordinal"

    companion object {
        private val HASH = Regex("[0-9a-f]{64}")
        private val HOLD = BigDecimal("0.01")
        private val CAPS =
            mapOf(
                AiPhysicalOperation.PRECHECK_CLASSIFICATION to 8,
                AiPhysicalOperation.PRECHECK_GENERATION to 4,
                AiPhysicalOperation.FINAL_PRIMARY to 4,
                AiPhysicalOperation.FINAL_FALLBACK to 1,
            )

        private fun sha(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
