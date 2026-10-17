package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.output.ParserTransportObservationPort
import com.meetme.server.coordination.application.port.output.ParserTransportSnapshot
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Component
@Primary
class AsyncParserTransportObservationAdapter(
    private val adapter: JdbcParserTransportObservationAdapter,
    private val capacity: Int,
) : ParserTransportObservationPort,
    DisposableBean {
    private val writer =
        ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(capacity),
            { task -> Thread(task, "parser-transport-writer").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )

    @Autowired
    constructor(adapter: JdbcParserTransportObservationAdapter) : this(adapter, 128)

    override fun record(snapshot: ParserTransportSnapshot) {
        val immutable = snapshot.copy(calls = snapshot.calls.toList())
        writer.execute {
            try {
                adapter.record(immutable)
            } catch (_: Exception) {
                // Absent persistence is unknown; never log payload or retry provider work.
            }
        }
    }

    override fun destroy() {
        writer.shutdownNow()
    }
}
