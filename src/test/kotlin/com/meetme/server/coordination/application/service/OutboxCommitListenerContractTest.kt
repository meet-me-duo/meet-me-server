package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.event.OutboxEnqueued
import com.meetme.server.shared.domain.OutboxEventId
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertNotNull

/** OC01/OC05: best-effort wake-up must not perform provider/Redis work on the committing caller. */
class OutboxCommitListenerContractTest {
    private val event = OutboxEnqueued(OutboxEventId(UUID.randomUUID()))
    private val relay = mock(OutboxRelay::class.java)

    @Test
    fun `OC01 committed notification only submits work and the task invokes the targeted relay`() {
        val pending = AtomicReference<Runnable>()
        val listener = OutboxCommitListener(relay, Executor { pending.set(it) })

        listener.onCommitted(event)

        verify(relay, never()).relayCommitted(event.eventId)
        assertNotNull(pending.get()).run()
        verify(relay).relayCommitted(event.eventId)
    }

    @Test
    fun `OC05 executor rejection cannot escape the after commit handler`() {
        val listener = OutboxCommitListener(relay, Executor { throw RejectedExecutionException("synthetic bounded queue saturation") })

        listener.onCommitted(event)

        verify(relay, never()).relayCommitted(event.eventId)
    }

    @Test
    fun `OC05 publication failure inside the submitted task is isolated from its executor`() {
        val pending = AtomicReference<Runnable>()
        val listener = OutboxCommitListener(relay, Executor { pending.set(it) })
        doThrow(IllegalStateException("synthetic Redis failure")).`when`(relay).relayCommitted(event.eventId)

        listener.onCommitted(event)
        assertNotNull(pending.get()).run()

        verify(relay).relayCommitted(event.eventId)
    }
}
