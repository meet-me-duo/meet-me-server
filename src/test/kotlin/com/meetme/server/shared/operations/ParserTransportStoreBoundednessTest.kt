package com.meetme.server.shared.operations

import com.meetme.server.coordination.adapter.output.persistence.ParserTransportStore
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Local datasource acquisition has a separate finite budget; the business pool is unchanged. */
class ParserTransportStoreBoundednessTest {
    @Test
    fun `transport pool has explicit finite connection statement and driver budgets without altering main pool`() {
        val main = main("jdbc:postgresql://127.0.0.1:9/synthetic_transport")
        val store = ParserTransportStore(main)
        try {
            assertNotSame(main, store.source)
            assertEquals(2, store.source.maximumPoolSize)
            assertEquals(0, store.source.minimumIdle)
            assertEquals(250L, store.source.connectionTimeout)
            assertEquals(-1L, store.source.initializationFailTimeout)
            assertEquals("1", store.source.dataSourceProperties["connectTimeout"].toString())
            assertEquals("2", store.source.dataSourceProperties["socketTimeout"].toString())
            assertEquals(1, store.jdbc.queryTimeout)
            assertEquals(30_000L, main.connectionTimeout)
            assertEquals(10, main.maximumPoolSize)
            assertFalse(main.isRunning)
        } finally {
            store.destroy()
        }
        assertTrue(store.source.isClosed)
        assertFalse(main.isClosed)
        main.close()
    }

    @Test
    fun `stalled loopback database cannot hold telemetry connection acquisition for the business pool timeout`() {
        ServerSocket().use { endpoint ->
            endpoint.bind(InetSocketAddress("127.0.0.1", 0))
            val main = main("jdbc:postgresql://127.0.0.1:${endpoint.localPort}/synthetic_transport")
            val store = ParserTransportStore(main)
            try {
                val started = System.nanoTime()
                assertFailsWith<SQLException> { store.source.connection.use { } }
                val elapsedMillis = (System.nanoTime() - started) / 1_000_000
                assertTrue(elapsedMillis < 2_000, "Telemetry connection acquisition must be bounded independently: ${elapsedMillis}ms")
                assertFalse(main.isRunning)
            } finally {
                store.destroy()
                main.close()
            }
        }
    }

    private fun main(url: String) =
        HikariDataSource().apply {
            jdbcUrl = url
            username = "synthetic_transport"
            password = "synthetic-placeholder"
            maximumPoolSize = 10
            connectionTimeout = 30_000
        }
}
