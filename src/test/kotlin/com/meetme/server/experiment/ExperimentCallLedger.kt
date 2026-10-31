package com.meetme.server.experiment

import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentAdmission
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentAdmissionIds
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentCall
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentTransportFailure
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentWireHook
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One approval owns one durable conservative balance. This is never registered in production. */
class ExperimentCallLedger(
    path: Path,
    private val runId: UUID,
    private val sourceSha256: String,
    private val configSha256: String,
    private val approvedManifestSha256: String,
    private val maximumPhysicalCalls: Int = 10,
    private val persistOverride: ((ExperimentLedgerSnapshot) -> Unit)? = null,
) : AiHttpExperimentWireHook {
    private val path = path.toAbsolutePath().normalize()
    private val lockPath = this.path.resolveSibling("${this.path.fileName}.lock")
    private val initialized = this.path.resolveSibling("${this.path.fileName}.initialized")
    private val pending = this.path.resolveSibling("${this.path.fileName}.pending")
    private val mapper = JsonMapper.builder().build()
    private var localFailure: ExperimentStopReason? = null
    private var lastKnown = State()

    init {
        require(maximumPhysicalCalls in 1..10)
        require(listOf(sourceSha256, configSha256, approvedManifestSha256).all { it.matches(HASH) })
        locked(false) { Unit }
    }

    fun reserve(admissions: List<AiHttpExperimentAdmission>) = locked(true, false) { state -> reserveInto(state, null, admissions) }

    fun bindRoom(roomId: UUID) =
        locked(true, false) { state ->
            if (state.phase !in ACTIVE_PHASES || state.activeRoom != null && state.activeRoom != roomId) {
                stop(state, ExperimentStopReason.PHASE_VIOLATION)
            }
            state.activeRoom = roomId
        }

    fun reserveForExecution(
        roomId: UUID,
        executionId: UUID,
        admissions: List<AiHttpExperimentAdmission>,
    ) = locked(true, false) { state ->
        // Stale experimental rooms can only lose permission; this never creates source authority.
        if (state.activeRoom != roomId || state.phase !in ACTIVE_PHASES) throw ExperimentCallStop()
        reserveInto(state, executionId, admissions)
    }

    fun checkpointForRoom(roomId: UUID) =
        locked(false) { state ->
            checkOpen(state)
            if (state.activeRoom != roomId || state.phase !in ACTIVE_PHASES || state.connectivityFailed) throw ExperimentCallStop()
        }

    override fun callStarted(call: AiHttpExperimentCall) =
        locked(true) { state ->
            validateCall(state, call)
            val existing = state.calls[call.callId]
            if (existing == null) {
                if (state.calls.size >= 256) stop(state, ExperimentStopReason.OBSERVATION_UNAVAILABLE, false)
                state.calls[call.callId] = CallRow(call, diagnostic = diagnosticOrdinal(state))
            } else {
                associate(state, existing, call)
            }
            checkOpen(state)
        }

    override fun beforeHeaders(call: AiHttpExperimentCall) =
        locked(true, false) { state ->
            val row = state.calls[call.callId] ?: stop(state, ExperimentStopReason.OBSERVATION_UNAVAILABLE, false)
            associate(state, row, call)
            if (state.phase !in ACTIVE_PHASES || state.connectivityFailed) throw ExperimentCallStop()
            val admission = state.admissions[call.admissionId] ?: stop(state, ExperimentStopReason.UNRESERVED_HEADERS, false)
            if (admission.room != state.activeRoom ||
                admission.execution != null &&
                admission.execution != call.executionId ||
                admission.operation != call.operation
            ) {
                stop(state, ExperimentStopReason.UNRESERVED_HEADERS, false)
            }
            if (admission.consumedBy != null || row.headersStarted) stop(state, ExperimentStopReason.REUSED_ADMISSION, false)
            admission.consumedBy = call.callId
            row.headersStarted = true
        }

    override fun headersEmitted(call: AiHttpExperimentCall) =
        locked(true) { state ->
            val row = state.calls[call.callId] ?: stop(state, ExperimentStopReason.OBSERVATION_UNAVAILABLE, false)
            associate(state, row, call)
            if (!row.headersStarted) stop(state, ExperimentStopReason.OBSERVATION_UNAVAILABLE, false)
            row.headersEmitted = true
        }

    override fun callFinished(
        call: AiHttpExperimentCall,
        httpStatus: Int?,
        failure: AiHttpExperimentTransportFailure?,
    ) = locked(true) { state ->
        val row = state.calls[call.callId] ?: stop(state, ExperimentStopReason.OBSERVATION_UNAVAILABLE, false)
        associate(state, row, call)
        if (httpStatus != null && httpStatus !in 100..599) stop(state, ExperimentStopReason.OBSERVATION_UNAVAILABLE, false)
        if (row.httpStatus != null && httpStatus != null && row.httpStatus != httpStatus) {
            stop(state, ExperimentStopReason.OBSERVATION_UNAVAILABLE, false)
        }
        row.httpStatus = httpStatus ?: row.httpStatus
        row.transport = failure ?: row.transport
        row.settled = true
        if (row.headersStarted && !row.headersEmitted) stop(state, ExperimentStopReason.UNKNOWN_EMISSION, false)
        // Wire evidence remains provisional until a typed provider outcome arrives. It cannot authorize a new request.
    }

    fun technicalFailure(
        callId: UUID,
        code: ExperimentTechnicalCode,
    ) = locked(true) { state ->
        val row = state.calls[callId] ?: stop(state, ExperimentStopReason.OBSERVATION_UNAVAILABLE, false)
        finalizeTyped(state, row, code)
    }

    fun providerFailure(
        callId: UUID,
        failureCode: String?,
    ) {
        val code = ExperimentFailureNormalization.normalizeKnownFailure(failureCode)
        if (code == null) observationUnavailable() else technicalFailure(callId, code)
    }

    fun providerFailureForExecution(
        executionId: UUID,
        operation: AiPhysicalOperation,
        attemptId: UUID?,
        ordinal: Int,
        failureCode: String?,
    ) {
        locked(true) { state ->
            val code =
                ExperimentFailureNormalization.normalizeKnownFailure(failureCode)
                    ?: stop(state, ExperimentStopReason.OBSERVATION_UNAVAILABLE, false)
            val matches =
                state.calls.values.filter {
                    it.call.executionId == executionId &&
                        it.call.operation == operation &&
                        it.call.attemptId == attemptId &&
                        it.call.ordinal == ordinal
                }
            if (matches.size != 1 || !matches.single().settled) {
                stop(state, ExperimentStopReason.OBSERVATION_UNAVAILABLE, false)
            }
            finalizeTyped(state, matches.single(), code)
        }
    }

    fun failureForCall(callId: UUID): ExperimentTechnicalCode? = locked(false) { it.calls[callId]?.typed }

    private fun finalizeTyped(
        state: State,
        row: CallRow,
        code: ExperimentTechnicalCode,
    ) {
        if (row.typed == code) return
        if (row.typed != null) stop(state, ExperimentStopReason.OBSERVATION_UNAVAILABLE, false)
        row.typed = code
        if (state.phase == ExperimentPhase.CONNECTIVITY && row.diagnostic == state.connectivityAttempts) {
            state.connectivityFailed = true
        }
        if (state.calls.values.count { it.typed == code } >= 2) stop(state, ExperimentStopReason.REPEATED_TECHNICAL_ERROR)
    }

    fun observationUnavailable() {
        locked<Unit>(true) { state -> stop(state, ExperimentStopReason.OBSERVATION_UNAVAILABLE, false) }
    }

    fun beginConnectivityAttempt(): Int =
        locked(true, false) { state ->
            if (state.phase !in setOf(ExperimentPhase.IDLE, ExperimentPhase.BETWEEN_DIAGNOSTICS) || state.connectivityAttempts >= 2) {
                stop(state, ExperimentStopReason.PHASE_VIOLATION)
            }
            requireSettled(state)
            state.connectivityAttempts++
            state.connectivityFailed = false
            state.phase = ExperimentPhase.CONNECTIVITY
            state.activeRoom = null
            state.connectivityAttempts
        }

    fun finishConnectivityAttempt(success: Boolean) =
        locked(true, false) { state ->
            if (state.phase != ExperimentPhase.CONNECTIVITY) stop(state, ExperimentStopReason.PHASE_VIOLATION)
            requireSettled(state)
            val calls = state.calls.values.filter { it.diagnostic == state.connectivityAttempts }
            if (calls.isEmpty()) stop(state, ExperimentStopReason.OBSERVATION_UNAVAILABLE, false)
            if (success) {
                if (state.connectivityFailed || calls.any { it.code() != null } || calls.none { it.headersEmitted }) {
                    stop(state, ExperimentStopReason.CONNECTIVITY_FAILED)
                }
                state.connectivitySucceeded = true
                state.phase = ExperimentPhase.CONNECTED
            } else if (state.connectivityAttempts >= 2) {
                stop(state, ExperimentStopReason.CONNECTIVITY_FAILED)
            } else {
                state.phase = ExperimentPhase.BETWEEN_DIAGNOSTICS
            }
            state.activeRoom = null
            state.connectivityFailed = false
        }

    fun beginE2E() =
        locked(true, false) { state ->
            if (state.phase != ExperimentPhase.CONNECTED || !state.connectivitySucceeded || state.e2eRooms != 0) {
                stop(state, ExperimentStopReason.PHASE_VIOLATION)
            }
            requireSettled(state)
            state.e2eRooms++
            state.phase = ExperimentPhase.E2E
            state.activeRoom = null
        }

    fun finishE2E() =
        locked(true, false) { state ->
            if (state.phase != ExperimentPhase.E2E) stop(state, ExperimentStopReason.PHASE_VIOLATION)
            requireSettled(state)
            state.phase = ExperimentPhase.FINISHED
            state.activeRoom = null
        }

    fun checkpoint() = locked(false) { state -> checkOpen(state) }

    fun snapshot(): ExperimentLedgerSnapshot = locked(false) { state -> snapshotOf(state) }

    private fun reserveInto(
        state: State,
        execution: UUID?,
        admissions: List<AiHttpExperimentAdmission>,
    ) {
        if (state.phase !in ACTIVE_PHASES || state.activeRoom == null) stop(state, ExperimentStopReason.PHASE_VIOLATION)
        if (admissions.isEmpty() ||
            admissions.map { it.opaqueId }.distinct().size != admissions.size ||
            admissions.any { !it.opaqueId.matches(HASH) }
        ) {
            stop(state, ExperimentStopReason.OBSERVATION_UNAVAILABLE, false)
        }
        admissions.forEach { admission ->
            state.admissions[admission.opaqueId]?.let {
                if (it.room != state.activeRoom || it.execution != execution || it.operation != admission.operation) {
                    stop(state, ExperimentStopReason.OBSERVATION_UNAVAILABLE, false)
                }
            }
        }
        val fresh = admissions.filter { it.opaqueId !in state.admissions }
        if (fresh.isEmpty()) return // Revalidation of the same prepared call grants no second transmission.
        if (state.connectivityFailed) throw ExperimentCallStop()
        if (state.calls.values.any { it.settled && it.code() != null && it.typed == null }) {
            stop(state, ExperimentStopReason.OBSERVATION_UNAVAILABLE, false)
        }
        if (state.phase == ExperimentPhase.CONNECTIVITY &&
            (
                fresh.any { it.operation != AiPhysicalOperation.FINAL_PRIMARY } ||
                    state.admissions.values.count { it.diagnostic == state.connectivityAttempts } + fresh.size > 1
            )
        ) {
            throw ExperimentCallStop()
        }
        if (state.admissions.size + fresh.size > maximumPhysicalCalls) stop(state, ExperimentStopReason.BUDGET_EXHAUSTED)
        fresh.forEach {
            state.admissions[it.opaqueId] = AdmissionRow(it.operation, state.activeRoom, execution, diagnosticOrdinal(state))
        }
    }

    private fun validateCall(
        state: State,
        call: AiHttpExperimentCall,
    ) {
        if (call.ordinal <= 0 || call.admissionId?.let { !it.matches(HASH) } == true) {
            stop(state, ExperimentStopReason.OBSERVATION_UNAVAILABLE, false)
        }
    }

    private fun associate(
        state: State,
        row: CallRow,
        call: AiHttpExperimentCall,
    ) {
        validateCall(state, call)
        if (row.call.executionId != call.executionId ||
            row.call.attemptId != call.attemptId ||
            row.call.operation != call.operation ||
            row.call.ordinal != call.ordinal ||
            row.call.admissionId != null &&
            call.admissionId != null &&
            row.call.admissionId != call.admissionId
        ) {
            stop(state, ExperimentStopReason.OBSERVATION_UNAVAILABLE, false)
        }
        if (call.admissionId != null) row.call = call
    }

    private fun diagnosticOrdinal(state: State) = if (state.phase == ExperimentPhase.CONNECTIVITY) state.connectivityAttempts else 0

    private fun requireSettled(state: State) {
        if (!state.coverageKnown || state.calls.values.any { !it.settled }) stop(state, ExperimentStopReason.UNKNOWN_EMISSION, false)
        if (state.calls.values.any { it.code() != null && it.typed == null }) {
            stop(state, ExperimentStopReason.OBSERVATION_UNAVAILABLE, false)
        }
    }

    private fun checkOpen(state: State) {
        if (state.stopReason != null || state.connectivityFailed) throw ExperimentCallStop()
    }

    private fun stop(
        state: State,
        reason: ExperimentStopReason,
        coverage: Boolean = state.coverageKnown,
    ): Nothing {
        state.stopReason = state.stopReason ?: reason
        state.coverageKnown = state.coverageKnown && coverage
        state.phase = ExperimentPhase.STOPPED
        throw ExperimentCallStop()
    }

    private fun snapshotOf(state: State): ExperimentLedgerSnapshot =
        ExperimentLedgerSnapshot(
            runId,
            sourceSha256,
            configSha256,
            approvedManifestSha256,
            maximumPhysicalCalls,
            state.admissions.size,
            state.calls.size,
            state.calls.values.count { it.headersStarted },
            state.calls.values.count { it.headersEmitted },
            state.calls.values
                .mapNotNull { it.code() }
                .groupingBy { it }
                .eachCount(),
            state.connectivityAttempts,
            state.connectivitySucceeded,
            state.e2eRooms,
            state.stopReason,
            state.phase,
            state.activeRoom,
            state.coverageKnown,
        )

    private fun <T> locked(
        write: Boolean,
        allowStopped: Boolean = true,
        action: (State) -> T,
    ): T =
        synchronized(LOCKS.computeIfAbsent(path) { Any() }) {
            try {
                Files.createDirectories(path.parent)
                FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                    channel.lock().use {
                        val state = loadOrInitialize()
                        if (Files.exists(pending) || localFailure != null) {
                            state.stopReason = state.stopReason ?: localFailure ?: ExperimentStopReason.PERSISTENCE_UNAVAILABLE
                            state.coverageKnown = false
                            state.phase = ExperimentPhase.STOPPED
                        }
                        lastKnown = copyState(state)
                        if (!write) return@use action(state)
                        if (!allowStopped && state.stopReason != null) throw ExperimentCallStop()
                        if (Files.exists(pending)) throw ExperimentCallStop()
                        Files.writeString(pending, "PENDING", StandardOpenOption.CREATE_NEW)
                        force(pending)
                        var failure: ExperimentCallStop? = null
                        var result: Any? = null
                        try {
                            result = action(state)
                        } catch (caught: ExperimentCallStop) {
                            failure = caught
                        }
                        persistOverride?.invoke(snapshotOf(state))
                        atomicWrite(state)
                        Files.delete(pending)
                        forceDirectory()
                        lastKnown = copyState(state)
                        failure?.let { throw it }
                        @Suppress("UNCHECKED_CAST")
                        result as T
                    }
                }
            } catch (failure: ExperimentCallStop) {
                throw failure
            } catch (_: Exception) {
                localFailure = ExperimentStopReason.PERSISTENCE_UNAVAILABLE
                lastKnown.stopReason = lastKnown.stopReason ?: localFailure
                lastKnown.coverageKnown = false
                lastKnown.phase = ExperimentPhase.STOPPED
                throw ExperimentCallStop()
            }
        }

    private fun loadOrInitialize(): State {
        if (!Files.exists(path)) {
            if (Files.exists(initialized)) throw ExperimentCallStop()
            Files.writeString(initialized, runId.toString(), StandardOpenOption.CREATE_NEW)
            force(initialized)
            val state = State()
            persistOverride?.invoke(snapshotOf(state))
            atomicWrite(state)
            return state
        }
        if (!Files.exists(initialized)) throw ExperimentCallStop()
        return try {
            val document = mapper.readTree(Files.readAllBytes(path))
            val node = document.path("state")
            require(document.path("sha256").asText() == digest(mapper.writeValueAsBytes(node)))
            if (node.path("schema").asText() != SCHEMA ||
                node.path("runId").asText() != runId.toString() ||
                node.path("sourceSha256").asText() != sourceSha256 ||
                node.path("configSha256").asText() != configSha256 ||
                node.path("approvedManifestSha256").asText() != approvedManifestSha256 ||
                node.path("maximumPhysicalCalls").asInt() != maximumPhysicalCalls
            ) {
                throw ExperimentCallStop()
            }
            decode(node)
        } catch (_: Exception) {
            throw ExperimentCallStop()
        }
    }

    private fun atomicWrite(state: State) {
        val temporary = path.resolveSibling("${path.fileName}.${UUID.randomUUID()}.tmp")
        val encoded = encode(state)
        val bytes = mapper.writeValueAsBytes(mapOf("state" to encoded, "sha256" to digest(mapper.writeValueAsBytes(encoded))))
        FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) it.write(buffer)
            it.force(true)
        }
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        forceDirectory()
    }

    private fun force(file: Path) = FileChannel.open(file, StandardOpenOption.WRITE).use { it.force(true) }

    private fun forceDirectory() = FileChannel.open(path.parent, StandardOpenOption.READ).use { it.force(true) }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun encode(state: State): Map<String, Any?> =
        mapOf(
            "schema" to SCHEMA,
            "runId" to runId.toString(),
            "sourceSha256" to sourceSha256,
            "configSha256" to configSha256,
            "approvedManifestSha256" to approvedManifestSha256,
            "maximumPhysicalCalls" to maximumPhysicalCalls,
            "phase" to state.phase.name,
            "activeRoom" to state.activeRoom?.toString(),
            "connectivityAttempts" to state.connectivityAttempts,
            "connectivitySucceeded" to state.connectivitySucceeded,
            "connectivityFailed" to state.connectivityFailed,
            "e2eRooms" to state.e2eRooms,
            "stopReason" to state.stopReason?.name,
            "coverageKnown" to state.coverageKnown,
            "admissions" to
                state.admissions.map { (id, row) ->
                    mapOf(
                        "id" to id,
                        "operation" to row.operation.name,
                        "room" to row.room?.toString(),
                        "execution" to row.execution?.toString(),
                        "diagnostic" to row.diagnostic,
                        "consumedBy" to row.consumedBy?.toString(),
                    )
                },
            "calls" to
                state.calls.values.map { row ->
                    mapOf(
                        "callId" to row.call.callId.toString(),
                        "execution" to row.call.executionId.toString(),
                        "attempt" to row.call.attemptId?.toString(),
                        "operation" to row.call.operation.name,
                        "ordinal" to row.call.ordinal,
                        "admission" to row.call.admissionId,
                        "diagnostic" to row.diagnostic,
                        "headersStarted" to row.headersStarted,
                        "headersEmitted" to row.headersEmitted,
                        "settled" to row.settled,
                        "httpStatus" to row.httpStatus,
                        "transport" to row.transport?.name,
                        "typed" to row.typed?.name,
                    )
                },
        )

    private fun decode(node: JsonNode): State {
        val state = State()
        state.phase = ExperimentPhase.valueOf(node.path("phase").asText())
        state.activeRoom = uuid(node, "activeRoom")
        state.connectivityAttempts = node.path("connectivityAttempts").asInt(-1)
        state.connectivitySucceeded = boolean(node, "connectivitySucceeded")
        state.connectivityFailed = boolean(node, "connectivityFailed")
        state.e2eRooms = node.path("e2eRooms").asInt(-1)
        state.stopReason = text(node, "stopReason")?.let(ExperimentStopReason::valueOf)
        state.coverageKnown = boolean(node, "coverageKnown")
        require(state.connectivityAttempts in 0..2 && state.e2eRooms in 0..1)
        require(node.path("admissions").isArray && node.path("calls").isArray)
        node.path("admissions").forEach {
            val id = it.path("id").asText()
            require(id.matches(HASH) && id !in state.admissions)
            state.admissions[id] =
                AdmissionRow(
                    AiPhysicalOperation.valueOf(it.path("operation").asText()),
                    uuid(it, "room"),
                    uuid(it, "execution"),
                    it.path("diagnostic").asInt(),
                    uuid(it, "consumedBy"),
                )
        }
        node.path("calls").forEach {
            val id = UUID.fromString(it.path("callId").asText())
            require(id !in state.calls)
            state.calls[id] =
                CallRow(
                    AiHttpExperimentCall(
                        id,
                        UUID.fromString(it.path("execution").asText()),
                        uuid(it, "attempt"),
                        AiPhysicalOperation.valueOf(it.path("operation").asText()),
                        it.path("ordinal").asInt(),
                        text(it, "admission"),
                    ),
                    it.path("diagnostic").asInt(),
                    boolean(it, "headersStarted"),
                    boolean(it, "headersEmitted"),
                    boolean(it, "settled"),
                    it.path("httpStatus").takeUnless { v -> v.isNull }?.asInt(),
                    text(it, "transport")?.let(AiHttpExperimentTransportFailure::valueOf),
                    text(it, "typed")?.let(ExperimentTechnicalCode::valueOf),
                )
        }
        require(state.admissions.size <= maximumPhysicalCalls && state.calls.size <= 256)
        require(state.calls.values.count { it.headersEmitted } <= state.calls.values.count { it.headersStarted })
        require(state.calls.values.count { it.headersStarted } <= state.admissions.size)
        require(state.calls.values.all { it.call.ordinal > 0 && it.diagnostic in 0..2 })
        state.admissions.forEach { (id, admission) ->
            admission.consumedBy?.let { consumed ->
                require(state.calls[consumed]?.let { it.headersStarted && it.call.admissionId == id } == true)
            }
        }
        return state
    }

    private fun text(
        node: JsonNode,
        key: String,
    ): String? = node.path(key).takeUnless { it.isNull || it.isMissingNode }?.asText()

    private fun uuid(
        node: JsonNode,
        key: String,
    ): UUID? = text(node, key)?.let(UUID::fromString)

    private fun boolean(
        node: JsonNode,
        key: String,
    ): Boolean {
        require(node.path(key).isBoolean)
        return node.path(key).asBoolean()
    }

    private fun copyState(state: State): State = decode(mapper.readTree(mapper.writeValueAsBytes(encode(state))))

    private class State {
        var phase = ExperimentPhase.IDLE
        var activeRoom: UUID? = null
        var connectivityAttempts = 0
        var connectivitySucceeded = false
        var connectivityFailed = false
        var e2eRooms = 0
        var stopReason: ExperimentStopReason? = null
        var coverageKnown = true
        val admissions = linkedMapOf<String, AdmissionRow>()
        val calls = linkedMapOf<UUID, CallRow>()
    }

    private data class AdmissionRow(
        val operation: AiPhysicalOperation,
        val room: UUID?,
        val execution: UUID?,
        val diagnostic: Int,
        var consumedBy: UUID? = null,
    )

    private data class CallRow(
        var call: AiHttpExperimentCall,
        val diagnostic: Int,
        var headersStarted: Boolean = false,
        var headersEmitted: Boolean = false,
        var settled: Boolean = false,
        var httpStatus: Int? = null,
        var transport: AiHttpExperimentTransportFailure? = null,
        var typed: ExperimentTechnicalCode? = null,
    ) {
        fun code(): ExperimentTechnicalCode? =
            typed ?: httpFailure(httpStatus) ?: when (transport) {
                AiHttpExperimentTransportFailure.TIMEOUT -> ExperimentTechnicalCode.TIMEOUT
                AiHttpExperimentTransportFailure.NETWORK -> ExperimentTechnicalCode.NETWORK
                null -> null
            }
    }

    companion object {
        private const val SCHEMA = "meetme-experiment-call-ledger/v1"
        private val HASH = Regex("[0-9a-f]{64}")
        private val LOCKS = ConcurrentHashMap<Path, Any>()
        private val ACTIVE_PHASES = setOf(ExperimentPhase.CONNECTIVITY, ExperimentPhase.E2E)

        private fun httpFailure(status: Int?): ExperimentTechnicalCode? =
            when (status) {
                null, in 200..299 -> null
                401, 407 -> ExperimentTechnicalCode.AUTHENTICATION
                402 -> ExperimentTechnicalCode.BILLING
                403 -> ExperimentTechnicalCode.PERMISSION
                408 -> ExperimentTechnicalCode.TIMEOUT
                429 -> ExperimentTechnicalCode.RATE_LIMIT
                in 500..599 -> ExperimentTechnicalCode.SERVER
                400, 404, 405, 409, 422 -> ExperimentTechnicalCode.INVALID_REQUEST
                else -> ExperimentTechnicalCode.INVALID_RESPONSE
            }
    }
}

enum class ExperimentTechnicalCode {
    CONFIGURATION,
    SERVER,
    AUTHENTICATION,
    PERMISSION,
    BILLING,
    QUOTA,
    NETWORK,
    TIMEOUT,
    RATE_LIMIT,
    INVALID_REQUEST,
    INVALID_RESPONSE,
    REFUSED,
}

enum class ExperimentPhase { IDLE, CONNECTIVITY, BETWEEN_DIAGNOSTICS, CONNECTED, E2E, FINISHED, STOPPED }

enum class ExperimentStopReason {
    BUDGET_EXHAUSTED,
    REPEATED_TECHNICAL_ERROR,
    OBSERVATION_UNAVAILABLE,
    PERSISTENCE_UNAVAILABLE,
    BINDING_MISMATCH,
    CORRUPT_LEDGER,
    PHASE_VIOLATION,
    CONNECTIVITY_FAILED,
    UNKNOWN_EMISSION,
    REUSED_ADMISSION,
    UNRESERVED_HEADERS,
}

object ExperimentFailureNormalization {
    fun normalizeKnownFailure(code: String?): ExperimentTechnicalCode? =
        when (code) {
            "CONNECTION", "CONNECTION_FAILED", "PROVIDER_UNAVAILABLE" -> ExperimentTechnicalCode.NETWORK
            "RATE_LIMITED" -> ExperimentTechnicalCode.RATE_LIMIT
            else -> ExperimentTechnicalCode.entries.singleOrNull { it.name == code }
        }
}

data class ExperimentLedgerSnapshot(
    val runId: UUID,
    val sourceSha256: String,
    val configSha256: String,
    val approvedManifestSha256: String,
    val maximumPhysicalCalls: Int,
    val admittedCalls: Int,
    val sdkCalls: Int,
    val headersStarted: Int,
    val headersEmitted: Int,
    val errors: Map<ExperimentTechnicalCode, Int>,
    val connectivityAttempts: Int,
    val connectivitySucceeded: Boolean,
    val e2eRooms: Int,
    val stopReason: ExperimentStopReason?,
    val phase: ExperimentPhase,
    val activeRoomId: UUID?,
    val observationCoverageKnown: Boolean,
)

class ExperimentCallStop : IOException("Capped experiment stopped at a protected boundary")

class ExperimentCounterAdmission(
    private val ledger: ExperimentCallLedger,
) {
    fun <T : Any> authorize(
        originalFence: () -> T?,
        request: AiBudgetReserveRequest,
    ): T? {
        val grant = originalFence() ?: return null
        ledger.reserveForExecution(
            request.execution.roomId,
            request.execution.executionId,
            request.calls.map { AiHttpExperimentAdmissionIds.of(request, it) },
        )
        return grant
    }
}
