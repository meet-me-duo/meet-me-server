package com.meetme.server.shared.adapter.output.persistence.operations

import com.meetme.server.shared.application.port.input.operations.AttemptSummary
import com.meetme.server.shared.application.port.input.operations.InvocationDetail
import com.meetme.server.shared.application.port.input.operations.InvocationSummary
import com.meetme.server.shared.application.port.input.operations.LocalClientCall
import com.meetme.server.shared.application.port.input.operations.OperationsCursor
import com.meetme.server.shared.application.port.input.operations.OperationsCursorCodec
import com.meetme.server.shared.application.port.input.operations.OperationsPage
import com.meetme.server.shared.application.port.input.operations.OperatorQueryError
import com.meetme.server.shared.application.port.input.operations.OperatorQueryException
import com.meetme.server.shared.application.port.input.operations.ProviderUsage
import com.meetme.server.shared.application.port.input.operations.RevisionCounts
import com.meetme.server.shared.application.port.input.operations.RoomDetail
import com.meetme.server.shared.application.port.input.operations.RoomSummary
import com.meetme.server.shared.application.port.output.operations.OperationsQueryRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class JdbcOperationsQueryRepository(
    private val jdbc: JdbcTemplate,
) : OperationsQueryRepository {
    override fun listRooms(
        limit: Int,
        cursor: OperationsCursor?,
    ): OperationsPage<RoomSummary> {
        require(limit in 1..100)
        val predicate = if (cursor == null) "" else "WHERE (m.created_at, m.id) < (?, ?)"
        val arguments = cursorArguments(cursor) + (limit + 1)
        val rows =
            jdbc.query(
                "$roomProjection $predicate ORDER BY m.created_at DESC, m.id DESC LIMIT ?",
                roomMapper,
                *arguments.toTypedArray(),
            )
        val items = rows.take(limit)
        val next = if (rows.size > limit) items.last().let { OperationsCursorCodec.encode(OperationsCursor(it.createdAt, it.id)) } else null
        return OperationsPage(items, next)
    }

    override fun roomDetail(
        roomId: UUID,
        limit: Int,
        cursor: OperationsCursor?,
    ): RoomDetail? {
        require(limit in 1..100)
        val room = jdbc.query("$roomProjection WHERE m.id = ?", roomMapper, roomId).firstOrNull() ?: return null
        val predicate = if (cursor == null) "" else "AND (i.started_at, i.id) < (?, ?)"
        val arguments = listOf<Any>(roomId) + cursorArguments(cursor) + (limit + 1)
        val rows =
            jdbc.query(
                "$invocationProjection WHERE r.room_id = ? $predicate ORDER BY i.started_at DESC, i.id DESC LIMIT ?",
                invocationMapper,
                *arguments.toTypedArray(),
            )
        val items = rows.take(limit).map { it.summary }
        val next = if (rows.size > limit) items.last().let { OperationsCursorCodec.encode(OperationsCursor(it.startedAt, it.id)) } else null
        val counts =
            jdbc
                .query(
                    """
                    SELECT count(*) FILTER (WHERE status = 'OPEN') AS open_count,
                        count(*) FILTER (WHERE outcome = 'QUEUED') AS queued_count,
                        count(*) FILTER (WHERE outcome = 'REUSED') AS reused_count
                    FROM input_revision_rounds WHERE room_id = ?
                    """.trimIndent(),
                    RowMapper { rs, _ -> RevisionCounts(rs.getLong("open_count"), rs.getLong("queued_count"), rs.getLong("reused_count")) },
                    roomId,
                ).single()
        val legacy =
            requireNotNull(
                jdbc.queryForObject(
                    """
                    SELECT count(*) FROM coordination_attempts a JOIN coordination_runs r ON r.id = a.coordination_run_id
                    WHERE r.room_id = ? AND a.invocation_id IS NULL
                    """.trimIndent(),
                    Long::class.java,
                    roomId,
                ),
            )
        return RoomDetail(room, items, next, counts, legacy)
    }

    override fun invocationDetail(invocationId: UUID): InvocationDetail? {
        val invocation = jdbc.query("$invocationProjection WHERE i.id = ?", invocationMapper, invocationId).firstOrNull() ?: return null
        val rows =
            jdbc.query(
                """
                SELECT a.id, a.attempt_number, a.provider, a.model, a.policy_version, a.started_at, a.finished_at,
                    a.failure_kind, a.input_tokens, a.output_tokens, a.estimated_cost_usd, o.parser_dispatched
                FROM coordination_attempts a LEFT JOIN parser_transport_observations o
                    ON o.attempt_id = a.id AND o.invocation_id = a.invocation_id AND o.provider = a.provider
                WHERE a.invocation_id = ? ORDER BY a.attempt_number, a.id LIMIT 6
                """.trimIndent(),
                attemptMapper,
                invocationId,
            )
        if (rows.size > 5) throw OperatorQueryException(OperatorQueryError.OPERATOR_UNAVAILABLE)
        var geminiCount = 0
        val attempts =
            rows.map { attempt ->
                val role =
                    if (attempt.provider == "OPENAI") {
                        "FALLBACK"
                    } else if (geminiCount++ == 0) {
                        "INITIAL"
                    } else {
                        "RETRY"
                    }
                val calls =
                    if (attempt.parserDispatched == null) {
                        null
                    } else {
                        jdbc.query(
                            "SELECT call_number,elapsed_nanos,outcome,http_status FROM parser_transport_client_calls WHERE attempt_id=? ORDER BY call_number",
                            RowMapper {
                                rs,
                                _,
                                ->
                                LocalClientCall(
                                    rs.getInt("call_number"),
                                    rs.getLong("elapsed_nanos"),
                                    rs.getString("outcome"),
                                    rs.getObject("http_status", Int::class.javaObjectType),
                                )
                            },
                            attempt.id,
                        )
                    }
                attempt.copy(role = role, clientCalls = calls)
            }
        return InvocationDetail(
            invocation.roomId,
            invocation.summary,
            attempts,
            attempts.groupBy { it.provider }.map { (provider, values) ->
                usage(provider, values)
            },
        )
    }

    private fun cursorArguments(cursor: OperationsCursor?): List<Any> =
        if (cursor == null) emptyList() else listOf(Timestamp.from(cursor.at), cursor.id)

    private fun usage(
        provider: String,
        attempts: List<AttemptSummary>,
    ): ProviderUsage {
        val inputs = attempts.mapNotNull { it.inputTokens }
        val outputs = attempts.mapNotNull { it.outputTokens }
        val costs = attempts.mapNotNull { it.estimatedCostUsd?.let(::BigDecimal) }
        val count = attempts.size.toLong()
        return ProviderUsage(
            provider = provider,
            recordedAttemptCount = count,
            inputKnownCount = inputs.size.toLong(),
            outputKnownCount = outputs.size.toLong(),
            costKnownCount = costs.size.toLong(),
            inputUnknownCount = count - inputs.size,
            outputUnknownCount = count - outputs.size,
            costUnknownCount = count - costs.size,
            knownInputTokens = inputs.takeIf { it.isNotEmpty() }?.fold(0L, Math::addExact),
            knownOutputTokens = outputs.takeIf { it.isNotEmpty() }?.fold(0L, Math::addExact),
            knownCostUsd = costs.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO, BigDecimal::add)?.toPlainString(),
        )
    }

    private fun elapsed(
        start: Instant,
        finish: Instant?,
    ): Long? =
        finish?.let {
            if (it < start) {
                null
            } else {
                try {
                    Duration.between(start, it).toMillis()
                } catch (_: ArithmeticException) {
                    null
                }
            }
        }

    private fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()

    private fun ResultSet.optionalInstant(column: String): Instant? = getTimestamp(column)?.toInstant()

    private fun ResultSet.optionalLong(column: String): Long? = getLong(column).let { if (wasNull()) null else it }

    private val roomMapper =
        RowMapper { rs: ResultSet, _: Int ->
            RoomSummary(
                id = rs.getObject("id", UUID::class.java),
                createdAt = rs.instant("created_at"),
                meetingMode = rs.getString("meeting_mode"),
                timeZoneId = rs.getString("time_zone_id"),
                searchStartDate = rs.getObject("search_start_date", LocalDate::class.java),
                searchEndDate = rs.getObject("search_end_date", LocalDate::class.java),
                expectedParticipants = rs.optionalLong("expected_participants")?.toInt(),
                submissionDeadline = rs.optionalInstant("submission_deadline"),
                manualOnly = rs.getBoolean("manual_only"),
                collectionStatus = rs.getString("collection_status"),
                closureReason = rs.getString("closure_reason"),
                closedAt = rs.optionalInstant("closed_at"),
                participantCount = rs.getLong("participant_count"),
                submittedParticipantCount = rs.getLong("submitted_participant_count"),
                hostSubmitted = rs.getBoolean("host_submitted"),
                currentRunStatus = rs.getString("current_run_status"),
            )
        }

    private val invocationMapper =
        RowMapper { rs: ResultSet, _: Int ->
            val started = rs.instant("started_at")
            val finished = rs.optionalInstant("finished_at")
            InvocationRow(
                rs.getObject("room_id", UUID::class.java),
                InvocationSummary(
                    id = rs.getObject("id", UUID::class.java),
                    runId = rs.getObject("coordination_run_id", UUID::class.java),
                    runVersion = rs.getLong("run_version"),
                    startedAt = started,
                    deadlineAt = rs.instant("deadline_at"),
                    finishedAt = finished,
                    winnerAttemptId = rs.getObject("winner_attempt_id", UUID::class.java),
                    geminiAdmittedAttempts = rs.getInt("gemini_attempts"),
                    lunaAdmittedAttempts = rs.getInt("luna_attempts"),
                    executionOrdinal = rs.getLong("execution_ordinal"),
                    revisionLinked = rs.getBoolean("revision_linked"),
                    recordedElapsedMillis = elapsed(started, finished),
                    parserDispatchCount = rs.optionalLong("dispatch_count"),
                    clientCallCount = rs.optionalLong("client_call_count"),
                    knownClientCallCount = rs.getLong("known_client_call_count"),
                    transportObservedAttemptCount = rs.getLong("observed_count"),
                    transportUnknownAttemptCount = rs.getLong("unknown_count"),
                ),
            )
        }

    private val attemptMapper =
        RowMapper { rs: ResultSet, _: Int ->
            val started = rs.instant("started_at")
            val finished = rs.optionalInstant("finished_at")
            val failure = rs.getString("failure_kind")
            AttemptSummary(
                id = rs.getObject("id", UUID::class.java),
                attemptNumber = rs.getInt("attempt_number"),
                provider = rs.getString("provider"),
                model = rs.getString("model"),
                policyVersion = rs.getString("policy_version"),
                startedAt = started,
                finishedAt = finished,
                failureKind = failure?.let { if (it in safeFailureKinds) it else "OTHER" },
                inputTokens = rs.optionalLong("input_tokens"),
                outputTokens = rs.optionalLong("output_tokens"),
                estimatedCostUsd = rs.getBigDecimal("estimated_cost_usd")?.toPlainString(),
                recordedElapsedMillis = elapsed(started, finished),
                role = "INITIAL",
                parserDispatched = rs.getObject("parser_dispatched", Boolean::class.javaObjectType),
            )
        }

    private data class InvocationRow(
        val roomId: UUID,
        val summary: InvocationSummary,
    )

    companion object {
        private val safeFailureKinds =
            setOf(
                "NETWORK",
                "TIMEOUT",
                "RATE_LIMIT",
                "SERVER",
                "INVALID_RESPONSE",
                "CONFIGURATION",
                "AUTHENTICATION",
                "PERMISSION",
                "BILLING",
                "QUOTA",
                "INVALID_REQUEST",
                "PUBLICATION_FENCED",
            )

        private val roomProjection =
            """
            SELECT m.id, m.created_at, m.meeting_mode, m.time_zone_id, m.search_start_date, m.search_end_date,
                m.expected_participants, m.submission_deadline, m.manual_only, m.collection_status,
                m.closure_reason, m.closed_at, r.status AS current_run_status,
                (SELECT count(*) FROM participants p WHERE p.room_id = m.id) AS participant_count,
                (SELECT count(*) FROM submission_heads h JOIN participants p ON p.id = h.participant_id
                    WHERE h.room_id = m.id AND p.room_id = m.id AND h.latest_version_id IS NOT NULL) AS submitted_participant_count,
                EXISTS(SELECT 1 FROM participants p JOIN submission_heads h ON h.participant_id = p.id
                    WHERE p.room_id = m.id AND h.room_id = m.id AND p.role = 'HOST' AND h.latest_version_id IS NOT NULL) AS host_submitted
            FROM meeting_rooms m LEFT JOIN coordination_runs r ON r.id = m.active_run_id AND r.room_id = m.id
            """.trimIndent()

        private val invocationProjection =
            """
            SELECT i.id, i.coordination_run_id, i.run_version, i.started_at, i.deadline_at, i.finished_at,
                i.winner_attempt_id, i.gemini_attempts, i.luna_attempts, r.room_id,
                transport.observed_count, transport.unknown_count, transport.dispatch_count,
                transport.client_call_count, transport.known_client_call_count,
                (SELECT count(*) FROM analysis_invocations previous WHERE previous.coordination_run_id = i.coordination_run_id
                    AND (previous.started_at, previous.id) <= (i.started_at, i.id)) AS execution_ordinal,
                EXISTS(SELECT 1 FROM input_revision_rounds revision WHERE revision.room_id = r.room_id
                    AND revision.outcome = 'QUEUED' AND revision.resolved_run_id = i.coordination_run_id) AS revision_linked
            FROM analysis_invocations i JOIN coordination_runs r ON r.id = i.coordination_run_id
            LEFT JOIN LATERAL (
                SELECT count(o.attempt_id) AS observed_count,
                    greatest(i.gemini_attempts + i.luna_attempts, count(a.id)) - count(o.attempt_id) AS unknown_count,
                    COALESCE(sum(o.call_count),0) AS known_client_call_count,
                    CASE WHEN count(a.id) > 0 AND count(a.id) = count(o.attempt_id) AND count(a.id) = i.gemini_attempts + i.luna_attempts
                        THEN count(*) FILTER (WHERE o.parser_dispatched) ELSE NULL END AS dispatch_count,
                    CASE WHEN count(a.id) > 0 AND count(a.id) = count(o.attempt_id) AND count(a.id) = i.gemini_attempts + i.luna_attempts
                        THEN sum(o.call_count) ELSE NULL END AS client_call_count
                FROM coordination_attempts a LEFT JOIN parser_transport_observations o
                    ON o.attempt_id=a.id AND o.invocation_id=a.invocation_id AND o.provider=a.provider
                WHERE a.invocation_id=i.id
            ) transport ON true
            """.trimIndent()
    }
}
