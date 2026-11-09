package com.meetme.server.experiment

import com.meetme.server.coordination.adapter.output.integration.C5EvaluationSelectedHttpTransport
import com.meetme.server.coordination.adapter.output.integration.C5EvaluationStrictJsonBoundary
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderRequest
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModeCalculation
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslStructuralRepository
import com.meetme.server.submission.adapter.output.persistence.ScheduleIrJdbcDocumentStore
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Offline adapter for existing bytes. It has no send/admission/credential/ledger entry point. */
internal class C5CollectedResponseReplay(
    private val mapper: ObjectMapper,
    private val transport: C5EvaluationSelectedHttpTransport,
) {
    enum class Origin { SYNTHETIC, SAVED_PROVIDER_RESPONSE }

    data class Record(
        val correlationId: UUID,
        val origin: Origin,
        val provider: AnalysisProvider,
        val model: String,
        val requestSha256: String,
        val responseSha256: String,
        val callLimitMs: Long = 40_000,
        val totalLimitMs: Long = 60_000,
        val retries: Int = 0,
        val fallbacks: Int = 0,
    )

    data class Parsed(
        val record: Record,
        val response: ScheduleDslProviderResponse,
        val snapshot: ScheduleEvidenceSnapshot,
        val envelope: ScheduleDslStructuralEnvelope,
    )

    data class Restored(
        val record: Record,
        val envelope: ScheduleDslStructuralEnvelope,
        val calculation: ScheduleDslContextModeCalculation,
    )

    fun parse(
        record: Record,
        requestBytes: ByteArray,
        responseBytes: ByteArray,
        request: SelectedScheduleDslProviderRequest,
    ): Parsed {
        require(requestBytes.isNotEmpty() && requestBytes.size <= 262_144) { "OFFLINE_REQUEST_BYTE_LIMIT" }
        val responseLimit = if (record.provider == AnalysisProvider.GEMINI) 1_048_576 else 262_144
        require(responseBytes.isNotEmpty() && responseBytes.size <= responseLimit) { "OFFLINE_RESPONSE_BYTE_LIMIT" }
        val frozenRequest = requestBytes.copyOf()
        val frozenResponse = responseBytes.copyOf()
        require(record.correlationId != UUID(0, 0)) { "OFFLINE_CORRELATION_REQUIRED" }
        require(record.callLimitMs == 40_000L && record.totalLimitMs == 60_000L && record.retries == 0 && record.fallbacks == 0) {
            "OFFLINE_COMPARISON_SETTINGS_MISMATCH"
        }
        require(record.requestSha256 == sha(frozenRequest) && record.responseSha256 == sha(frozenResponse)) {
            "OFFLINE_COLLECTED_HASH_MISMATCH"
        }
        val expected = transport.body(request, record.provider, record.model)
        require(expected.contentEquals(frozenRequest)) { "OFFLINE_REQUEST_BINDING_MISMATCH" }
        val decoded = transport.decodeCollectedResponse(frozenResponse, record.provider, record.model)
        require(decoded.provider == record.provider && decoded.model == record.model) { "OFFLINE_RESPONSE_IDENTITY_MISMATCH" }
        val snapshot = request.inputCapsule.snapshot
        val envelope =
            C5EvaluationStrictJsonBoundary(mapper).parse(
                decoded.responseJson,
                snapshot,
                "schedule-dsl-response-5",
                "schedule-ir-product-6",
                "schedule-ast-1",
                "ko-time-terms-1",
            )
        return Parsed(record, decoded, snapshot, envelope)
    }

    fun persistAndCalculate(
        parsed: Parsed,
        dataSource: DataSource,
        expectedRunVersion: Long,
        processedAt: Instant,
        policy: IrMatchingPolicy,
        mode: MeetingMode,
    ): Restored {
        val jdbc = JdbcTemplate(dataSource)
        TransactionTemplate(DataSourceTransactionManager(dataSource)).executeWithoutResult {
            JdbcScheduleDslStructuralRepository(jdbc, mapper).publish(parsed.envelope, expectedRunVersion, processedAt)
        }
        // A new connection and repository must recover the persisted value, never the parsed in-memory object.
        return dataSource.connection.use { connection ->
            val fresh = JdbcTemplate(SingleConnectionDataSource(connection, true))
            val store = ScheduleIrJdbcDocumentStore(fresh, mapper)
            val snapshot = store.loadSnapshot(parsed.snapshot.context)
            require(
                snapshot.context == parsed.snapshot.context &&
                    snapshot.context.inputs.all { input ->
                        input.sources.all { source ->
                            snapshot.sourceText(input.submissionVersionId, source.sourceId) ==
                                parsed.snapshot.sourceText(input.submissionVersionId, source.sourceId)
                        }
                    },
            ) { "OFFLINE_STORED_SOURCE_MISMATCH" }
            val restored = requireNotNull(JdbcScheduleDslStructuralRepository(fresh, mapper).find(snapshot.context))
            require(restored == parsed.envelope) { "OFFLINE_RESTORED_IR_MISMATCH" }
            val expected = ScheduleDslStructuralCalculator.calculate(parsed.envelope, parsed.snapshot, policy, mode)
            val actual = ScheduleDslStructuralCalculator.calculate(restored, snapshot, policy, mode)
            require(actual == expected) { "OFFLINE_RESTORED_CALCULATION_MISMATCH" }
            Restored(parsed.record, restored, actual)
        }
    }

    companion object {
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
