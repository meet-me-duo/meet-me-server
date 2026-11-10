package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pure synthetic byte streams: diagnostic capture must never increase admission or rewrite model bytes. */
class C5BoundedResponseReaderTest {
    private val admitted = 1024
    private val ceiling = 2 * 1024 * 1024

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `CBR01 accepted boundary bytes are returned verbatim with exactly one capture and no limit diagnostic`(knownLength: Boolean) {
        for (size in listOf(0, admitted)) {
            val original = bytes(size)
            val captures = mutableListOf<ByteArray>()
            val diagnostics = mutableListOf<Map<String, Any>>()
            val actual = C5BoundedResponseReader.read(body(original, knownLength).first, admitted, captures::add, diagnostics::add)
            assertContentEquals(original, actual)
            assertEquals(1, captures.size)
            assertContentEquals(original, captures.single())
            assertTrue(diagnostics.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `CBR02 admission overflow remains invalid after complete bounded raw capture and accurate diagnostic`(knownLength: Boolean) {
        for (size in listOf(admitted + 1, ceiling)) {
            val original = bytes(size)
            val captures = mutableListOf<ByteArray>()
            val diagnostics = mutableListOf<Map<String, Any>>()
            invalid { C5BoundedResponseReader.read(body(original, knownLength).first, admitted, captures::add, diagnostics::add) }
            assertEquals(1, captures.size)
            assertContentEquals(original, captures.single())
            assertEquals(1, diagnostics.size)
            assertDiagnostic(diagnostics.single(), size, if (knownLength) size.toLong() else -1L, complete = true)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `CBR03 beyond capture ceiling is bounded at one proof byte and never represented as complete raw capture`(knownLength: Boolean) {
        val original = bytes(ceiling + 4096)
        val (response, source) = body(original, knownLength)
        val captures = mutableListOf<ByteArray>()
        val diagnostics = mutableListOf<Map<String, Any>>()
        invalid { C5BoundedResponseReader.read(response, admitted, captures::add, diagnostics::add) }
        assertTrue(captures.isEmpty())
        assertEquals((ceiling + 1).toLong(), original.size.toLong() - source.size)
        assertEquals(1, diagnostics.size)
        assertDiagnostic(diagnostics.single(), ceiling + 1, if (knownLength) original.size.toLong() else -1L, complete = false)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `CBR04 observer failure cannot corrupt accepted bytes or turn an accepted response into a failure`(knownLength: Boolean) {
        val original = bytes(admitted)
        var calls = 0
        var diagnostics = 0
        val actual =
            C5BoundedResponseReader.read(
                body(original, knownLength).first,
                admitted,
                responseObserver = { received ->
                    calls++
                    assertContentEquals(original, received)
                    received.fill(0)
                    throw IllegalStateException("synthetic observer failure")
                },
                diagnostics = { diagnostics++ },
            )
        assertContentEquals(original, actual)
        assertEquals(1, calls)
        assertEquals(0, diagnostics)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `CBR05 failing observation callbacks remain independent and cannot replace invalid admission with success`(knownLength: Boolean) {
        for (size in listOf(admitted + 1, ceiling + 1)) {
            var captures = 0
            var diagnostics = 0
            invalid {
                C5BoundedResponseReader.read(
                    body(bytes(size), knownLength).first,
                    admitted,
                    responseObserver = {
                        captures++
                        throw IllegalStateException("synthetic observer failure")
                    },
                    diagnostics = {
                        diagnostics++
                        throw IllegalStateException("synthetic diagnostic failure")
                    },
                )
            }
            assertEquals(if (size <= ceiling) 1 else 0, captures)
            assertEquals(1, diagnostics)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `CBR06 absence of raw observer preserves admission and does not require expanded capture`(knownLength: Boolean) {
        val accepted = bytes(admitted)
        val diagnostics = mutableListOf<Map<String, Any>>()
        assertContentEquals(
            accepted,
            C5BoundedResponseReader.read(body(accepted, knownLength).first, admitted, diagnostics = diagnostics::add),
        )
        assertTrue(diagnostics.isEmpty())
        val original = bytes(ceiling + 4096)
        val (response, source) = body(original, knownLength)
        invalid { C5BoundedResponseReader.read(response, admitted, diagnostics = diagnostics::add) }
        assertTrue(original.size.toLong() - source.size <= admitted + 1L)
        assertEquals(1, diagnostics.size)
        assertEquals("LOCAL_RESPONSE_BYTE_LIMIT", diagnostics.single()["kind"])
        assertEquals(false, diagnostics.single()["captureComplete"])
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `CBR07 maximal supported admission still accepts exactly two MiB without a false limit failure`(knownLength: Boolean) {
        val original = bytes(ceiling)
        val captures = mutableListOf<ByteArray>()
        var diagnostics = 0
        assertContentEquals(
            original,
            C5BoundedResponseReader.read(body(original, knownLength).first, ceiling, captures::add, { diagnostics++ }),
        )
        assertEquals(1, captures.size)
        assertContentEquals(original, captures.single())
        assertEquals(0, diagnostics)
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, 0, 2097153])
    fun `CBR08 unsupported admission bounds fail before observation`(limit: Int) {
        var calls = 0
        assertFailsWith<IllegalArgumentException> {
            C5BoundedResponseReader.read(body(bytes(8), true).first, limit, { calls++ }, { calls++ })
        }
        assertEquals(0, calls)
    }

    private fun invalid(block: () -> Unit) {
        assertEquals(ParserFailureKind.INVALID_RESPONSE, assertFailsWith<NaturalLanguageParserException>(block = block).kind)
    }

    private fun assertDiagnostic(
        value: Map<String, Any>,
        observed: Int,
        declared: Long,
        complete: Boolean,
    ) {
        assertEquals("LOCAL_RESPONSE_BYTE_LIMIT", value["kind"])
        assertEquals(admitted.toLong(), (value["admittedLimit"] as Number).toLong())
        assertEquals(declared, (value["declaredBytes"] as Number).toLong())
        assertEquals(observed.toLong(), (value["observedBytes"] as Number).toLong())
        assertEquals(complete, value["captureComplete"])
    }

    private fun bytes(size: Int) = ByteArray(size) { ((it * 31 + 7) % 256).toByte() }

    private fun body(
        bytes: ByteArray,
        knownLength: Boolean,
    ): Pair<ResponseBody, Buffer> {
        val source = Buffer().write(bytes)
        val response =
            object : ResponseBody() {
                override fun contentType(): MediaType? = null

                override fun contentLength(): Long = if (knownLength) bytes.size.toLong() else -1L

                override fun source(): BufferedSource = source
            }
        return response to source
    }
}
