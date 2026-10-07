package com.meetme.server.coordination.adapter.output.integration.evaluation

import tools.jackson.databind.json.JsonMapper
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/** Small evaluation-only durable ledger; the lock is held for the entire live run. */
internal class ApprovalBudgetLedger private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val file: Path,
    private val state: MutableMap<String, Any?>,
    private val entries: MutableList<MutableMap<String, Any?>>,
) : AutoCloseable {
    fun amount(key: String): Long = (state[key] as Number).toLong()

    fun hasPending(): Boolean = entries.any { it["status"] == "RESERVED_PENDING" }

    fun hasDuplicate(
        caseId: String,
        promptSha: String,
    ): Boolean = entries.any { it["case_id"] == caseId && it["prompt_sha256"] == promptSha }

    fun reserve(
        caseId: String,
        promptSha: String,
        inputEstimate: Long,
        inputBound: Long,
        reservation: Long,
    ) {
        require(!hasPending() && !hasDuplicate(caseId, promptSha))
        require(amount("provider_calls") < 25 && amount("accounted_nano_usd") + reservation <= 300_000_000L)
        entries.add(
            linkedMapOf(
                "case_id" to caseId,
                "prompt_sha256" to promptSha,
                "status" to "RESERVED_PENDING",
                "reserved_utc" to Instant.now().toString(),
                "input_token_upper_estimate" to inputEstimate,
                "reserved_input_tokens" to inputBound,
                "reservation_nano_usd" to reservation,
            ),
        )
        state["provider_calls"] = amount("provider_calls") + 1
        state["accounted_nano_usd"] = amount("accounted_nano_usd") + reservation
        persist() // A failed write prevents the caller from reaching the SDK.
    }

    fun complete(
        cost: Long,
        input: Long,
        output: Long,
    ) {
        val entry = entries.last()
        require(entry["status"] == "RESERVED_PENDING")
        val reservation = (entry["reservation_nano_usd"] as Number).toLong()
        require(cost in 0..reservation)
        state["accounted_nano_usd"] = amount("accounted_nano_usd") + cost - reservation
        state["verified_usage_nano_usd"] = amount("verified_usage_nano_usd") + cost
        state["total_input_tokens"] = amount("total_input_tokens") + input
        state["total_billed_output_including_thinking_tokens"] =
            amount("total_billed_output_including_thinking_tokens") + output
        entry["status"] = "USAGE_VERIFIED"
        entry["verified_nano_usd"] = cost
        entry["input_tokens"] = input
        entry["billed_output_including_thinking_tokens"] = output
        entry["finalized_utc"] = Instant.now().toString()
        persist()
    }

    private fun persist() {
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        val bytes = mapper.writeValueAsBytes(state)
        FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use {
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) it.write(buffer)
            it.force(true)
        }
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun close() {
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }

    companion object {
        private val mapper = JsonMapper.builder().build()

        @Suppress("UNCHECKED_CAST")
        fun open(
            approvalId: String,
            directory: String,
            policySha: String,
        ): ApprovalBudgetLedger {
            require(approvalId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")) && directory.isNotBlank())
            val root = Path.of(directory).toAbsolutePath().normalize()
            Files.createDirectories(root)
            val channel = FileChannel.open(root.resolve("$approvalId.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock =
                try {
                    requireNotNull(channel.tryLock())
                } catch (failure: Throwable) {
                    channel.close()
                    throw failure
                }
            try {
                val file = root.resolve("$approvalId.json")
                val state =
                    if (Files.exists(file)) {
                        require(Files.isRegularFile(file) && Files.size(file) <= 1_048_576)
                        mapper.readValue(Files.readAllBytes(file), Map::class.java) as MutableMap<String, Any?>
                    } else {
                        linkedMapOf<String, Any?>(
                            "version" to 1,
                            "approval_id" to approvalId,
                            "policy_sha256" to policySha,
                            "max_calls" to 25,
                            "max_nano_usd" to 300_000_000L,
                            "provider_calls" to 0L,
                            "accounted_nano_usd" to 0L,
                            "verified_usage_nano_usd" to 0L,
                            "total_input_tokens" to 0L,
                            "total_billed_output_including_thinking_tokens" to 0L,
                            "entries" to mutableListOf<MutableMap<String, Any?>>(),
                        )
                    }
                require((state["version"] as Number).toInt() == 1 && state["approval_id"] == approvalId)
                require(state["policy_sha256"] == policySha)
                require((state["max_calls"] as Number).toInt() == 25 && (state["max_nano_usd"] as Number).toLong() == 300_000_000L)
                val entries = state["entries"] as MutableList<MutableMap<String, Any?>>
                val ledger = ApprovalBudgetLedger(channel, lock, file, state, entries)
                require(ledger.amount("provider_calls") == entries.size.toLong() && entries.size <= 25)
                require(entries.all { it["status"] in setOf("RESERVED_PENDING", "USAGE_VERIFIED") })
                require(
                    ledger.amount("accounted_nano_usd") ==
                        entries.sumOf {
                            (it[if (it["status"] == "USAGE_VERIFIED") "verified_nano_usd" else "reservation_nano_usd"] as Number).toLong()
                        },
                )
                require(
                    ledger.amount("verified_usage_nano_usd") ==
                        entries.filter { it["status"] == "USAGE_VERIFIED" }.sumOf {
                            (it["verified_nano_usd"] as Number).toLong()
                        },
                )
                val verifiedEntries = entries.filter { it["status"] == "USAGE_VERIFIED" }
                require(ledger.amount("total_input_tokens") == verifiedEntries.sumOf { (it["input_tokens"] as Number).toLong() })
                require(
                    ledger.amount("total_billed_output_including_thinking_tokens") ==
                        verifiedEntries.sumOf {
                            (it["billed_output_including_thinking_tokens"] as Number).toLong()
                        },
                )
                require(ledger.amount("accounted_nano_usd") in 0..300_000_000L)
                require(ledger.amount("verified_usage_nano_usd") in 0..ledger.amount("accounted_nano_usd"))
                require(ledger.amount("total_input_tokens") in 0..100_000L)
                require(ledger.amount("total_billed_output_including_thinking_tokens") in 0..40_000L)
                return ledger
            } catch (failure: Throwable) {
                lock.release()
                channel.close()
                throw failure
            }
        }
    }
}
