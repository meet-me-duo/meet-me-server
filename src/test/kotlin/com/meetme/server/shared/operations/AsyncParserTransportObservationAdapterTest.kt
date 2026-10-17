package com.meetme.server.shared.operations

import com.meetme.server.coordination.adapter.output.persistence.AsyncParserTransportObservationAdapter
import com.meetme.server.coordination.adapter.output.persistence.JdbcParserTransportObservationAdapter
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.ParserClientCallObservation
import com.meetme.server.coordination.application.port.output.ParserClientCallOutcome
import com.meetme.server.coordination.application.port.output.ParserObservationContext
import com.meetme.server.coordination.application.port.output.ParserTransportSnapshot
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Telemetry storage cannot wait on the parser thread or introduce a retry when its queue is full. */
class AsyncParserTransportObservationAdapterTest {
    @Test
    fun `record returns while database write is blocked on a different worker thread`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = mock(JdbcParserTransportObservationAdapter::class.java)
        val callerThread = Thread.currentThread()
        doAnswer {
            assertTrue(Thread.currentThread() !== callerThread)
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            null
        }.`when`(store).record(anyValue())
        val adapter = AsyncParserTransportObservationAdapter(store, 1)
        val caller = Executors.newSingleThreadExecutor()
        try {
            caller.submit { adapter.record(snapshot(1)) }.get(1, TimeUnit.SECONDS)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(1L, release.count, "The JDBC write must still be blocked after record returned")
        } finally {
            release.countDown()
            adapter.destroy()
            caller.shutdownNow()
        }
    }

    @Test
    fun `storage failure is isolated and later accepted observations still execute`() {
        val writes = AtomicInteger()
        val later = CountDownLatch(1)
        val store = mock(JdbcParserTransportObservationAdapter::class.java)
        doAnswer {
            if (writes.incrementAndGet() == 1) error("synthetic-private-store-failure")
            later.countDown()
            null
        }.`when`(store).record(anyValue())
        val adapter = AsyncParserTransportObservationAdapter(store, 2)
        try {
            adapter.record(snapshot(1))
            adapter.record(snapshot(2))
            assertTrue(later.await(2, TimeUnit.SECONDS))
            assertEquals(2, writes.get())
        } finally {
            adapter.destroy()
        }
    }

    @Test
    fun `one blocked write and one queued write reject overflow instead of unbounded storage work`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = mock(JdbcParserTransportObservationAdapter::class.java)
        val seen = Collections.synchronizedList(mutableListOf<ParserTransportSnapshot>())
        doAnswer {
            seen += it.getArgument<ParserTransportSnapshot>(0)
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            null
        }.`when`(store).record(anyValue())
        val adapter = AsyncParserTransportObservationAdapter(store, 1)
        try {
            adapter.record(snapshot(1))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            adapter.record(snapshot(2))
            assertFailsWith<RejectedExecutionException> { adapter.record(snapshot(3)) }
            assertEquals(listOf(snapshot(1)), seen.toList())
        } finally {
            adapter.destroy()
            release.countDown()
        }
    }

    @Test
    fun `shutdown drops queued observations and rejects future records without running them on the caller`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val store = mock(JdbcParserTransportObservationAdapter::class.java)
        val writes = AtomicInteger()
        doAnswer {
            writes.incrementAndGet()
            entered.countDown()
            try {
                release.await(5, TimeUnit.SECONDS)
            } finally {
                ended.countDown()
            }
            null
        }.`when`(store).record(anyValue())
        val adapter = AsyncParserTransportObservationAdapter(store, 1)
        try {
            adapter.record(snapshot(1))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            adapter.record(snapshot(2))
            adapter.destroy()
            assertFailsWith<RejectedExecutionException> { adapter.record(snapshot(3)) }
            release.countDown()
            assertTrue(ended.await(1, TimeUnit.SECONDS))
            assertEquals(1, writes.get())
        } finally {
            release.countDown()
            adapter.destroy()
        }
    }

    @Test
    fun `queued snapshot copies call collection before the caller can mutate it`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val later = CountDownLatch(1)
        val store = mock(JdbcParserTransportObservationAdapter::class.java)
        val seen = Collections.synchronizedList(mutableListOf<ParserTransportSnapshot>())
        doAnswer {
            val observation = it.getArgument<ParserTransportSnapshot>(0)
            seen += observation
            if (observation.context == snapshot(1).context) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            } else {
                later.countDown()
            }
            null
        }.`when`(store).record(anyValue())
        val adapter = AsyncParserTransportObservationAdapter(store, 1)
        val original = ParserClientCallObservation(1, 123, ParserClientCallOutcome.COMPLETED, 200)
        val calls = mutableListOf(original)
        try {
            adapter.record(snapshot(1))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            adapter.record(snapshot(2).copy(parserDispatched = true, calls = calls))
            calls.clear()
            release.countDown()
            assertTrue(later.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(original), seen.last().calls)
        } finally {
            release.countDown()
            adapter.destroy()
        }
    }

    private fun snapshot(id: Long) =
        ParserTransportSnapshot(ParserObservationContext(UUID(0, id), UUID(1, id)), AnalysisProvider.GEMINI, false, emptyList())

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        Mockito.any<T>()
        return null as T
    }
}
