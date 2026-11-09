package com.meetme.server.experiment

import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentAdmissionIds
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentCall
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentTransportFailure
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Synthetic component grants are fixtures, never native permission. No model, HTTP or database is used. */
class C5HttpWireJournalTest {
    @TempDir lateinit var directory: Path

    private val room = UUID(0, 71)
    private val response = "{\"status\":\"completed\",\"usage\":{\"input_tokens\":7,\"output_tokens\":2}}".toByteArray()

    @Test
    fun `CJ01 unarmed or missing original grant refuses the SDK start without consuming a physical slot`() {
        for (fault in listOf("unarmed", "missing-grant")) {
            val journal = journal(fault)
            val wire = wire(1)
            if (fault != "unarmed") journal.arm(room)
            assertFailsWith<IllegalStateException> { journal.callStarted(wire.call) }
            assertEquals(0, number(journal, "physicalCount"))
        }
    }

    @Test
    fun `CJ02 exact source grant payload and opaque admission preserve private request and response before completion`() {
        val path = directory.resolve("exact")
        val journal = C5HttpWireJournal(path)
        journal.arm(room)
        val wire = wire(2)
        ready(journal, wire)
        assertEquals(wire.call, journal.currentCall())
        assertTrue(files(path).any { Files.readAllBytes(it).contentEquals(wire.bytes) })
        journal.headersEmitted(wire.call)
        journal.responseObserved(wire.call, 200, mapOf("x-request-id" to "synthetic-request-2"))
        journal.captureResponse(wire.call, response)
        journal.callFinished(wire.call, 200, null)
        journal.checkpoint()
        assertEquals(1, number(journal, "physicalCount"))
        assertEquals(0, number(journal, "unknownCount"))
        assertEquals("COMPLETED", calls(journal).single()["state"])
        assertEquals(200, (calls(journal).single()["httpStatus"] as Number).toInt())
        assertTrue(files(path).any { Files.readAllBytes(it).contentEquals(response) })
        Files.walk(path).use { paths ->
            paths.forEach {
                assertFalse(Files.isSymbolicLink(it))
                val permissions = if (Files.isDirectory(it)) "rwx------" else "rw-------"
                assertEquals(PosixFilePermissions.fromString(permissions), Files.getPosixFilePermissions(it))
            }
        }
    }

    @Test
    fun `CJ03 foreign admission refuses before start and changed actual payload stops before headers without refund`() {
        val foreign = journal("foreign-admission")
        foreign.arm(room)
        val original = wire(3)
        register(foreign, original)
        assertFailsWith<IllegalStateException> { foreign.callStarted(original.call.copy(admissionId = "f".repeat(64))) }
        assertEquals(0, number(foreign, "physicalCount"))

        val mutated = journal("wrong-body")
        mutated.arm(room)
        val next = wire(4)
        register(mutated, next)
        mutated.callStarted(next.call)
        assertFailsWith<IllegalStateException> { mutated.captureRequest(next.call, "{\"different\":true}".toByteArray()) }
        assertEquals(1, number(mutated, "physicalCount"))
        assertTrue(calls(mutated).none { it["state"] == "EMITTED" })
        assertFailsWith<IllegalStateException> { mutated.checkpoint() }
    }

    @Test
    fun `CJ04 completed call or a new call replaying its native admission cannot resend`() {
        for (newCallId in listOf(false, true)) {
            val journal = journal("replay-$newCallId")
            journal.arm(room)
            val first = wire(5)
            complete(journal, first)
            val replay = if (newCallId) first.call.copy(callId = UUID(0, 5005)) else first.call
            assertFailsWith<IllegalStateException> { journal.callStarted(replay) }
            assertEquals(1, number(journal, "physicalCount"))
            assertEquals("COMPLETED", calls(journal).single()["state"])
        }
    }

    @Test
    fun `CJ05 a second SDK call cannot overlap the first pending physical request`() {
        val journal = journal("pending")
        journal.arm(room)
        val first = wire(6)
        ready(journal, first)
        val next = wire(7)
        register(journal, next)
        assertFailsWith<IllegalStateException> { journal.callStarted(next.call) }
        assertEquals(1, number(journal, "physicalCount"))
        assertEquals(first.call.callId.toString(), calls(journal).single()["callId"])
    }

    @Test
    fun `CJ06 aggregate seventeen and eight four four one stage ceilings are independent upper bounds`() {
        val stages =
            linkedMapOf(
                AiPhysicalOperation.PRECHECK_CLASSIFICATION to 8,
                AiPhysicalOperation.PRECHECK_GENERATION to 4,
                AiPhysicalOperation.FINAL_PRIMARY to 4,
                AiPhysicalOperation.FINAL_FALLBACK to 1,
            )
        val all = journal("all-seventeen")
        all.arm(room)
        var sequence = 20
        for ((operation, limit) in stages) {
            for (ordinal in 1..limit) {
                complete(all, wire(sequence++, operation, if (operation == AiPhysicalOperation.FINAL_PRIMARY) ordinal else 1))
            }
        }
        assertEquals(17, number(all, "physicalCount"))
        assertFailsWith<IllegalStateException> { start(all, wire(99)) }
        assertEquals(17, number(all, "physicalCount"))

        for ((operation, limit) in stages) {
            val stage = journal("stage-${operation.name}")
            stage.arm(room)
            for (ordinal in 1..limit) {
                complete(stage, wire(100 + ordinal, operation, if (operation == AiPhysicalOperation.FINAL_PRIMARY) ordinal else 1))
            }
            assertFailsWith<IllegalStateException> {
                start(stage, wire(200, operation, if (operation == AiPhysicalOperation.FINAL_PRIMARY) limit + 1 else 1))
            }
            assertEquals(limit, number(stage, "physicalCount"))
        }
        val aggregate = C5HttpWireJournal(directory.resolve("aggregate-one"), maximumPhysicalCalls = 1)
        aggregate.arm(room)
        complete(aggregate, wire(301))
        assertFailsWith<IllegalStateException> { start(aggregate, wire(302)) }
        assertEquals(1, number(aggregate, "physicalCount"))
    }

    @Test
    fun `CJ07 five carried slots are charged before the sixth request uses the separately authorized new tranche`() {
        val journal = journal("tranches")
        journal.arm(room)
        repeat(6) { complete(journal, wire(400 + it)) }
        assertEquals(List(5) { "CARRIED23" } + "NEW20", calls(journal).map { it["tranche"] })
        assertEquals(0, number(journal, "carriedRemaining"))
        assertEquals(19, number(journal, "newRemaining"))
        assertEquals(6, number(journal, "physicalCount"))
    }

    @Test
    fun `CJ08 timeout retains its spent slot and UNKNOWN outcome instead of being refunded as a failed success`() {
        val journal = journal("timeout")
        journal.arm(room)
        val wire = wire(501)
        ready(journal, wire)
        journal.headersEmitted(wire.call)
        journal.callFinished(wire.call, null, AiHttpExperimentTransportFailure.TIMEOUT)
        assertEquals(1, number(journal, "physicalCount"))
        assertEquals(1, number(journal, "unknownCount"))
        assertEquals(4, number(journal, "carriedRemaining"))
        assertEquals(20, number(journal, "newRemaining"))
        assertEquals("UNKNOWN", calls(journal).single()["state"])
        assertEquals(null, calls(journal).single()["httpStatus"])
    }

    @Test
    fun `CJ09 prior Jev upper bound plus the next conservative hold cannot cross the separate one dollar ceiling`() {
        val journal = C5HttpWireJournal(directory.resolve("jev-limit"), priorJevUpperBound = BigDecimal("0.995"))
        journal.arm(room)
        assertFailsWith<IllegalStateException> { start(journal, wire(601)) }
        assertEquals(0, number(journal, "physicalCount"))
        assertEquals(5, number(journal, "carriedRemaining"))
        assertEquals(20, number(journal, "newRemaining"))
    }

    @Test
    fun `CJ10 secret or post-response capture failure blocks the next step without rewriting the observed provider result`() {
        val secret = "SYNTHETIC_API_KEY_NEVER_REAL"
        val prePath = directory.resolve("request-secret")
        val pre = C5HttpWireJournal(prePath, secretValues = listOf(secret))
        pre.arm(room)
        val withSecret = wire(701, bytes = "{\"text\":\"$secret\"}".toByteArray())
        register(pre, withSecret)
        pre.callStarted(withSecret.call)
        assertFailsWith<IllegalStateException> { pre.captureRequest(withSecret.call, withSecret.bytes) }
        assertEquals(1, number(pre, "physicalCount"))
        assertTrue(calls(pre).none { it["state"] == "EMITTED" })
        assertFailsWith<IllegalStateException> { pre.checkpoint() }
        assertNoSecret(prePath, secret)

        for (fault in listOf("response-secret", "write-failure")) {
            val path = directory.resolve(fault)
            val journal = C5HttpWireJournal(path, secretValues = listOf(secret))
            journal.arm(room)
            val entry = wire(702)
            ready(journal, entry)
            journal.headersEmitted(entry.call)
            journal.responseObserved(entry.call, 200, emptyMap())
            if (fault == "write-failure") {
                Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
                Files.write(path, "synthetic regular file replacing only this disposable journal directory".toByteArray())
            }
            val original = if (fault == "response-secret") "{\"result\":\"$secret\"}".toByteArray() else response
            val returned =
                run {
                    journal.captureResponse(entry.call, original)
                    journal.callFinished(entry.call, 200, null)
                    original
                }
            assertSame(original, returned)
            assertContentEquals(original, returned)
            assertEquals(true, journal.snapshot()["stopped"])
            assertEquals(200, (calls(journal).single()["httpStatus"] as Number).toInt())
            assertFailsWith<IllegalStateException> { journal.checkpoint() }
            assertFailsWith<IllegalStateException> { start(journal, wire(703)) }
            assertEquals(1, number(journal, "physicalCount"))
            if (Files.isDirectory(path)) assertNoSecret(path, secret)
        }
    }

    @Test
    fun `CJ11 fresh twelve call cap spends only the remaining new tranche and rejects the thirteenth before its slot`() {
        val journal =
            C5HttpWireJournal(
                directory.resolve("fresh-twelve"),
                maximumPhysicalCalls = 12,
                carriedRemaining = 0,
                newRemaining = 20,
                priorJevUpperBound = BigDecimal("0.04072887"),
            )
        journal.arm(room)
        repeat(8) { complete(journal, wire(800 + it, AiPhysicalOperation.PRECHECK_CLASSIFICATION)) }
        repeat(4) { complete(journal, wire(900 + it, AiPhysicalOperation.PRECHECK_GENERATION)) }
        assertEquals(List(12) { "NEW20" }, calls(journal).map { it["tranche"] })
        assertEquals(12, number(journal, "physicalCount"))
        assertEquals(0, number(journal, "carriedRemaining"))
        assertEquals(8, number(journal, "newRemaining"))

        assertEquals(
            "PHYSICAL_CAP",
            assertFailsWith<IllegalStateException> { start(journal, wire(1000, AiPhysicalOperation.FINAL_PRIMARY)) }.message,
        )
        assertEquals(12, number(journal, "physicalCount"))
        assertEquals(0, number(journal, "carriedRemaining"))
        assertEquals(8, number(journal, "newRemaining"))
    }

    private data class Wire(
        val grant: AiBudgetReserveRequest,
        val call: AiHttpExperimentCall,
        val bytes: ByteArray,
    )

    private fun wire(
        number: Int,
        operation: AiPhysicalOperation = AiPhysicalOperation.PRECHECK_CLASSIFICATION,
        ordinal: Int = 1,
        bytes: ByteArray = "{\"synthetic_case\":$number}".toByteArray(),
    ): Wire {
        val final = operation in setOf(AiPhysicalOperation.FINAL_PRIMARY, AiPhysicalOperation.FINAL_FALLBACK)
        val executionId = if (final) UUID(0, 9000) else UUID(0, 1000 + number.toLong())
        val attempt = if (final) UUID(0, 2000 + number.toLong()) else null
        val spec = AiPhysicalCallSpec(operation, ordinal, sha(bytes), "b".repeat(64), attempt)
        val grant =
            AiBudgetReserveRequest(
                AiBudgetExecution(
                    room,
                    if (final) AiBudgetExecutionKind.FINAL_ANALYSIS else AiBudgetExecutionKind.PRECHECK,
                    executionId,
                    1,
                    UUID(0, 7000),
                    0,
                    "c".repeat(64),
                    "d".repeat(64),
                ),
                listOf(spec),
            )
        val call =
            AiHttpExperimentCall(
                UUID(0, number.toLong()),
                executionId,
                attempt,
                operation,
                ordinal,
                AiHttpExperimentAdmissionIds.of(grant, spec).opaqueId,
            )
        return Wire(grant, call, bytes)
    }

    private fun journal(name: String) = C5HttpWireJournal(directory.resolve(name))

    private fun register(
        journal: C5HttpWireJournal,
        wire: Wire,
    ) {
        journal.registerOriginalGrant(wire.grant)
        journal.prepareRequest(wire.call.executionId, wire.call.operation, wire.call.ordinal, wire.bytes)
    }

    private fun start(
        journal: C5HttpWireJournal,
        wire: Wire,
    ) {
        register(journal, wire)
        journal.callStarted(wire.call)
    }

    private fun ready(
        journal: C5HttpWireJournal,
        wire: Wire,
    ) {
        start(journal, wire)
        journal.captureRequest(wire.call, wire.bytes)
        journal.beforeHeaders(wire.call)
    }

    private fun complete(
        journal: C5HttpWireJournal,
        wire: Wire,
    ) {
        ready(journal, wire)
        journal.headersEmitted(wire.call)
        journal.responseObserved(wire.call, 200, emptyMap())
        journal.captureResponse(wire.call, response)
        journal.callFinished(wire.call, 200, null)
    }

    private fun number(
        journal: C5HttpWireJournal,
        key: String,
    ) = (journal.snapshot().getValue(key) as Number).toInt()

    @Suppress("UNCHECKED_CAST")
    private fun calls(journal: C5HttpWireJournal) = journal.snapshot().getValue("calls") as List<Map<String, Any?>>

    private fun files(path: Path): List<Path> = Files.walk(path).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }

    private fun assertNoSecret(
        path: Path,
        secret: String,
    ) {
        assertTrue(files(path).none { Files.readString(it).contains(secret) })
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
