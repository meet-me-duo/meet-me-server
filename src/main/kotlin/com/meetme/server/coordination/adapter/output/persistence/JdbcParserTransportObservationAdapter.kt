package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.output.ParserClientCallObservation
import com.meetme.server.coordination.application.port.output.ParserClientCallOutcome
import com.meetme.server.coordination.application.port.output.ParserTransportSnapshot
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Repository
class JdbcParserTransportObservationAdapter(
    private val jdbc: JdbcTemplate,
    private val transactionManager: PlatformTransactionManager,
) {
    @Autowired
    constructor(store: ParserTransportStore) : this(store.jdbc, store.transactionManager)

    fun record(snapshot: ParserTransportSnapshot) {
        require(snapshot.calls.size <= ParserTransportSnapshot.MAX_CLIENT_CALLS)
        require(snapshot.parserDispatched || snapshot.calls.isEmpty())
        snapshot.calls.forEachIndexed { index, call ->
            require(call.callNumber == index + 1 && call.elapsedNanos >= 0)
            require(call.httpStatus == null || call.httpStatus in 100..599)
        }
        val transaction =
            TransactionTemplate(transactionManager).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                timeout = 2
            }
        transaction.executeWithoutResult {
            jdbc.execute("SET LOCAL statement_timeout = '1000ms'")
            jdbc.execute("SET LOCAL lock_timeout = '500ms'")
            // Serializes identical/conflicting concurrent snapshots on the durable admission.
            val identities =
                jdbc.queryForList(
                    "SELECT id FROM coordination_attempts WHERE id=? AND invocation_id=? AND provider=? FOR UPDATE",
                    snapshot.context.attemptId,
                    snapshot.context.invocationId,
                    snapshot.provider.name,
                )
            require(identities.size == 1) { "Unlinked transport observation" }
            val existing =
                jdbc
                    .queryForList(
                        "SELECT invocation_id,provider,parser_dispatched,call_count FROM parser_transport_observations WHERE attempt_id=?",
                        snapshot.context.attemptId,
                    ).singleOrNull()
            if (existing != null) {
                require(
                    existing["invocation_id"] == snapshot.context.invocationId &&
                        existing["provider"] == snapshot.provider.name &&
                        existing["parser_dispatched"] == snapshot.parserDispatched &&
                        (existing["call_count"] as Number).toInt() == snapshot.calls.size,
                )
                val calls =
                    jdbc.query(
                        "SELECT call_number,elapsed_nanos,outcome,http_status FROM parser_transport_client_calls WHERE attempt_id=? ORDER BY call_number",
                        {
                            rs,
                            _,
                            ->
                            ParserClientCallObservation(
                                rs.getInt("call_number"),
                                rs.getLong("elapsed_nanos"),
                                ParserClientCallOutcome.valueOf(rs.getString("outcome")),
                                rs.getObject("http_status", Int::class.javaObjectType),
                            )
                        },
                        snapshot.context.attemptId,
                    )
                require(calls == snapshot.calls) { "Conflicting transport observation" }
            } else {
                jdbc.update(
                    "INSERT INTO parser_transport_observations(attempt_id,invocation_id,provider,parser_dispatched,call_count) VALUES (?,?,?,?,?)",
                    snapshot.context.attemptId,
                    snapshot.context.invocationId,
                    snapshot.provider.name,
                    snapshot.parserDispatched,
                    snapshot.calls.size,
                )
                snapshot.calls.forEach { call ->
                    jdbc.update(
                        "INSERT INTO parser_transport_client_calls(attempt_id,call_number,elapsed_nanos,outcome,http_status) VALUES (?,?,?,?,?)",
                        snapshot.context.attemptId,
                        call.callNumber,
                        call.elapsedNanos,
                        call.outcome.name,
                        call.httpStatus,
                    )
                }
            }
        }
    }
}
