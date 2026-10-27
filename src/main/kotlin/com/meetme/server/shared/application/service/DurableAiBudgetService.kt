package com.meetme.server.shared.application.service

import com.meetme.server.shared.application.port.output.AiBudgetAuthorityRegistryPort
import com.meetme.server.shared.application.port.output.AiBudgetBillRequest
import com.meetme.server.shared.application.port.output.AiBudgetCallState
import com.meetme.server.shared.application.port.output.AiBudgetDispatchPermit
import com.meetme.server.shared.application.port.output.AiBudgetDispatchRequest
import com.meetme.server.shared.application.port.output.AiBudgetExecutionFencePort
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetObservation
import com.meetme.server.shared.application.port.output.AiBudgetQuoteRequest
import com.meetme.server.shared.application.port.output.AiBudgetReservation
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiBudgetRoomConfiguration
import com.meetme.server.shared.application.port.output.AiBudgetSettlementRequest
import com.meetme.server.shared.application.port.output.AiBudgetStoredEvent
import com.meetme.server.shared.application.port.output.AiBudgetUnit
import com.meetme.server.shared.application.port.output.AiBudgetUsage
import com.meetme.server.shared.application.port.output.AiCurrentBudgetExecution
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiReservedPhysicalCall
import com.meetme.server.shared.application.port.output.AiRoomBudgetSnapshot
import com.meetme.server.shared.application.port.output.AiRoomBudgetState
import com.meetme.server.shared.application.port.output.AiStoredBudgetCall
import com.meetme.server.shared.application.port.output.AiVerifiedBudgetQuote
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.shared.application.port.output.DurableAiBudgetRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class DurableAiBudgetService(
    private val repository: DurableAiBudgetRepository,
    private val clock: Clock,
    private val fence: AiBudgetExecutionFencePort,
    private val authority: AiBudgetAuthorityRegistryPort,
) : DurableAiBudgetPort {
    @Transactional
    override fun reserve(request: AiBudgetReserveRequest): AiBudgetReservation? {
        if (!valid(request)) return null
        val active = fence.lockAndValidate(request, clock.instant()) ?: return null
        val room = repository.lockRoom(request.execution.roomId) ?: return null
        val now = clock.instant()
        val configuration = repository.configuration(room.roomId) ?: return null
        if (!eligible(room, active, configuration, now)) return null
        val existing = request.calls.map { repository.findPhysical(request.execution, it) }
        if (existing.filterNotNull().any {
                it.execution != request.execution || it.call !in request.calls || it.epochId != active.epochId
            }
        ) {
            return null
        }
        val quotes =
            request.calls.mapIndexed { index, call ->
                val prior = existing[index]
                if (prior != null) {
                    if (prior.call != call) return null
                    prior.quote
                } else {
                    val quote =
                        try {
                            authority.quote(AiBudgetQuoteRequest(request.execution, call, requireNotNull(configuration.policyFingerprint)))
                        } catch (
                            _: Exception,
                        ) {
                            return null
                        } ?: return null
                    if (!validQuote(quote, call, room, configuration, now)) return null
                    quote
                }
            }
        val added =
            quotes
                .mapIndexed { index, quote ->
                    if (existing[index] ==
                        null
                    ) {
                        quote.maximumAmount
                    } else {
                        BigInteger.ZERO
                    }
                }.fold(BigInteger.ZERO, BigInteger::add)
        val nextHeld = room.held + added
        if (!amount(nextHeld) || room.settled + nextHeld >= requireNotNull(room.cap)) return null
        val protection =
            if (request.execution.kind == AiBudgetExecutionKind.PRECHECK && existing.any { it == null }) {
                val proofs =
                    request.calls.mapIndexedNotNull { index, call ->
                        if (existing[index] != null) return@mapIndexedNotNull null
                        val proof =
                            try {
                                authority.protectedFinalReserve(
                                    AiBudgetQuoteRequest(request.execution, call, requireNotNull(configuration.policyFingerprint)),
                                )
                            } catch (_: Exception) {
                                return null
                            } ?: return null
                        if (proof.policyFingerprint != configuration.policyFingerprint ||
                            proof.unit != room.unit ||
                            !amount(proof.amount) ||
                            !now.isBefore(proof.validUntil)
                        ) {
                            return null
                        }
                        proof
                    }
                val proof = proofs.distinct().singleOrNull() ?: return null
                val exposure = room.settled + nextHeld + proof.amount
                if (!amount(exposure) || exposure >= room.cap) return null
                proof
            } else {
                null
            }
        val profiles = quotes.map { profileHash(it) to it }.distinctBy { it.first }.sortedBy { it.first }
        for ((hash, quote) in profiles) if (!repository.insertProfile(hash, quote)) return null
        val beforeInsert = clock.instant()
        if (!beforeInsert.isBefore(active.deadlineAt) ||
            protection?.let { !beforeInsert.isBefore(it.validUntil) } == true ||
            quotes.withIndex().any { (index, quote) -> existing[index] == null && !beforeInsert.isBefore(quote.validUntil) }
        ) {
            return null
        }
        val records =
            request.calls.mapIndexed { index, call ->
                existing[index] ?: AiStoredBudgetCall(
                    UUID.randomUUID(),
                    request.execution,
                    call,
                    quotes[index],
                    active.epochId,
                    AiBudgetCallState.RESERVED,
                    quotes[index].maximumAmount,
                    now,
                ).also {
                    repository.insertCall(it, profileHash(it.quote))
                }
            }
        if (added != BigInteger.ZERO) repository.updateRoom(room.copy(held = nextHeld, version = room.version + 1))
        return AiBudgetReservation(request.execution, records.map { AiReservedPhysicalCall(it.id, it.call) })
    }

    @Transactional
    override fun claim(request: AiBudgetDispatchRequest): AiBudgetDispatchPermit? {
        val intent = AiBudgetReserveRequest(request.execution, listOf(request.call))
        if (!valid(intent)) return null
        val active = fence.lockAndValidate(intent, clock.instant()) ?: return null
        val room = repository.lockRoom(request.execution.roomId) ?: return null
        val now = clock.instant()
        val configuration = repository.configuration(room.roomId) ?: return null
        if (!eligible(room, active, configuration, now)) return null
        val hint = repository.findCall(request.reservationId) ?: return null
        if (hint.execution != request.execution || hint.call != request.call) return null
        val call = repository.findCall(request.reservationId, lock = true) ?: return null
        if (call.execution != request.execution ||
            call.call != request.call ||
            call.epochId != active.epochId ||
            call.state != AiBudgetCallState.RESERVED
        ) {
            return null
        }
        if (!validQuote(call.quote, request.call, room, configuration, now)) return null
        val currentQuote =
            try {
                authority.quote(AiBudgetQuoteRequest(request.execution, request.call, requireNotNull(configuration.policyFingerprint)))
            } catch (
                _: Exception,
            ) {
                return null
            }
        if (currentQuote != call.quote) return null
        val token = UUID.randomUUID()
        val deadline = minOf(active.deadlineAt, call.quote.validUntil)
        if (!clock.instant().isBefore(deadline)) return null
        if (!repository.claim(call.id, token, deadline, now)) return null
        if (!clock.instant().isBefore(deadline)) {
            repository.updateAccounting(call.id, AiBudgetCallState.UNKNOWN, call.hold, null, null, clock.instant())
            return null
        }
        return AiBudgetDispatchPermit(call.id, token, deadline)
    }

    @Transactional
    override fun observe(observation: AiBudgetObservation): Boolean {
        if (!validUsage(observation.usage)) return false
        val (room, call) = accountingCall(observation.reservationId, observation.dispatchToken) ?: return false
        val key = "usage:${observation.eventId}"
        val fingerprint =
            digest(
                observation.outcome.name,
                observation.failure?.name,
                observation.usage.inputTokens?.toString(),
                observation.usage.outputTokens?.toString(),
                observation.usage.reasoningTokens?.toString(),
                observation.usage.cachedInputTokens?.toString(),
                observation.usage.usageComplete.toString(),
            )
        val previous = repository.eventFingerprint(call.id, key)
        if (previous != null) {
            if (previous != fingerprint) freeze(room)
            return previous == fingerprint
        }
        repository.insertEvent(AiBudgetStoredEvent(call.id, key, fingerprint, clock.instant(), observation))
        if (call.state !=
            AiBudgetCallState.SETTLED
        ) {
            repository.updateAccounting(call.id, AiBudgetCallState.UNKNOWN, call.hold, null, null, clock.instant())
        }
        return true
    }

    @Transactional
    override fun settle(request: AiBudgetSettlementRequest): Boolean {
        if (request.evidenceReference.isBlank() ||
            request.evidenceReference.length > 256 ||
            request.evidenceReference.any { it == '\u0000' || it == '\r' || it == '\n' }
        ) {
            return false
        }
        val (room, call) = accountingCall(request.reservationId, request.dispatchToken) ?: return false
        val bill =
            try {
                authority.completeBill(AiBudgetBillRequest(call.id, call.quote.quoteFingerprint, request.evidenceReference))
            } catch (
                _: Exception,
            ) {
                return false
            } ?: return false
        if (!hash(bill.quoteFingerprint) ||
            !hash(bill.evidenceFingerprint) ||
            bill.quoteFingerprint != call.quote.quoteFingerprint ||
            bill.unit != call.quote.unit ||
            bill.amount.signum() < 0
        ) {
            return false
        }
        val fingerprint =
            digest(bill.quoteFingerprint, bill.evidenceFingerprint, bill.amount.toString(), bill.unit.code, bill.unit.scale.toString())
        val key = "bill:${bill.evidenceFingerprint}"
        val previous = repository.eventFingerprint(call.id, key)
        if (previous != null) {
            if (previous != fingerprint) freeze(room)
            return previous == fingerprint &&
                call.state == AiBudgetCallState.SETTLED &&
                call.settled == bill.amount &&
                call.settledEvidence == bill.evidenceFingerprint
        }
        if (call.state == AiBudgetCallState.SETTLED) {
            repository.insertEvent(
                AiBudgetStoredEvent(
                    call.id,
                    key,
                    fingerprint,
                    clock.instant(),
                    billAmount = bill.amount.takeIf(::amount),
                    evidenceFingerprint = bill.evidenceFingerprint,
                    accountingFailure = true,
                ),
            )
            freeze(room)
            return false
        }
        val settled = room.settled + bill.amount
        if (!amount(bill.amount) || !amount(settled)) {
            repository.insertEvent(
                AiBudgetStoredEvent(
                    call.id,
                    key,
                    fingerprint,
                    clock.instant(),
                    billAmount = bill.amount.takeIf(::amount),
                    evidenceFingerprint = bill.evidenceFingerprint,
                    accountingFailure = true,
                ),
            )
            repository.updateAccounting(call.id, AiBudgetCallState.UNKNOWN, call.hold, null, null, clock.instant())
            freeze(room)
            return false
        }
        repository.insertEvent(
            AiBudgetStoredEvent(
                call.id,
                key,
                fingerprint,
                clock.instant(),
                billAmount = bill.amount,
                evidenceFingerprint = bill.evidenceFingerprint,
            ),
        )
        val held = room.held - call.hold
        check(held.signum() >= 0) { "AI_BUDGET_ACCOUNTING_CONFLICT" }
        val frozen =
            room.state == AiRoomBudgetState.FROZEN ||
                bill.amount > call.quote.maximumAmount ||
                room.cap?.let { settled + held >= it } != false
        repository.updateAccounting(
            call.id,
            AiBudgetCallState.SETTLED,
            BigInteger.ZERO,
            bill.amount,
            bill.evidenceFingerprint,
            clock.instant(),
        )
        repository.updateRoom(
            room.copy(
                state = if (frozen) AiRoomBudgetState.FROZEN else room.state,
                settled = settled,
                held = held,
                version =
                    room.version + 1,
            ),
        )
        return true
    }

    @Transactional(readOnly = true)
    override fun snapshot(roomId: UUID): AiRoomBudgetSnapshot? = repository.findRoom(roomId)

    private fun accountingCall(
        id: UUID,
        token: UUID,
    ): Pair<AiRoomBudgetSnapshot, AiStoredBudgetCall>? {
        val hint = repository.findCall(id) ?: return null
        if (!repository.lockExistingRoom(hint.execution.roomId)) return null
        val room = repository.lockRoom(hint.execution.roomId) ?: return null
        val call = repository.findCall(id, lock = true) ?: return null
        if (call.execution.roomId != room.roomId || call.dispatchToken != token || call.state == AiBudgetCallState.RESERVED) return null
        return room to call
    }

    private fun freeze(room: AiRoomBudgetSnapshot) {
        if (room.state !=
            AiRoomBudgetState.FROZEN
        ) {
            repository.updateRoom(room.copy(state = AiRoomBudgetState.FROZEN, version = room.version + 1))
        }
    }

    private fun eligible(
        room: AiRoomBudgetSnapshot,
        active: AiCurrentBudgetExecution,
        config: AiBudgetRoomConfiguration,
        at: Instant,
    ): Boolean =
        room.state == AiRoomBudgetState.READY &&
            room.epochId == active.epochId &&
            at.isBefore(active.deadlineAt) &&
            config.coverageStartedAt?.let { !it.isAfter(at) } == true &&
            hash(config.policyFingerprint) &&
            room.unit?.let(::validUnit) == true &&
            room.cap?.let { amount(it) && it.signum() > 0 } == true &&
            amount(room.held) &&
            amount(room.settled)

    private fun validQuote(
        quote: AiVerifiedBudgetQuote,
        call: AiPhysicalCallSpec,
        room: AiRoomBudgetSnapshot,
        config: AiBudgetRoomConfiguration,
        at: Instant,
    ): Boolean =
        hash(quote.quoteFingerprint) &&
            hash(quote.evidenceFingerprint) &&
            quote.profileFingerprint == call.profileFingerprint &&
            quote.wholePayloadFingerprint == call.wholePayloadFingerprint &&
            quote.policyFingerprint == config.policyFingerprint &&
            quote.unit == room.unit &&
            validUnit(quote.unit) &&
            amount(quote.maximumAmount) &&
            at.isBefore(quote.validUntil)

    private fun valid(request: AiBudgetReserveRequest): Boolean {
        val execution = request.execution
        if (!hash(execution.sourceFingerprint) ||
            !hash(execution.contextFingerprint) ||
            request.calls.isEmpty() ||
            request.calls.size > 2 ||
            request.calls
                .map { it.operation to it.ordinal }
                .distinct()
                .size != request.calls.size
        ) {
            return false
        }
        if (execution.kind == AiBudgetExecutionKind.PRECHECK) {
            if (execution.generation <= 0 ||
                execution.version <= 0 ||
                request.calls.any { it.attemptId != null || it.ordinal != 1 || it.operation in FINAL }
            ) {
                return false
            }
            if (request.calls.size == 2 &&
                request.calls.map { it.operation }.toSet() !=
                setOf(AiPhysicalOperation.PRECHECK_COUNT, AiPhysicalOperation.PRECHECK_GENERATION)
            ) {
                return false
            }
        } else {
            if (execution.generation < 0 ||
                execution.generation != execution.version ||
                request.calls.size != 1 ||
                request.calls.any {
                    it.attemptId == null ||
                        it.operation !in FINAL ||
                        it.ordinal !in (if (it.operation == AiPhysicalOperation.FINAL_PRIMARY) 1..4 else 1..1)
                }
            ) {
                return false
            }
        }
        return request.calls.all { hash(it.wholePayloadFingerprint) && hash(it.profileFingerprint) }
    }

    private fun validUsage(usage: AiBudgetUsage): Boolean =
        listOf(usage.inputTokens, usage.outputTokens, usage.reasoningTokens, usage.cachedInputTokens).all {
            it ==
                null ||
                it >= 0
        }

    private fun validUnit(unit: AiBudgetUnit): Boolean =
        unit.code.isNotBlank() &&
            unit.code.length <= 32 &&
            unit.code == unit.code.trim() &&
            unit.code.none { it == '\u0000' || it == '\r' || it == '\n' } &&
            unit.scale in 0..38

    private fun amount(value: BigInteger): Boolean = value.signum() >= 0 && value <= MAX_AMOUNT

    private fun hash(value: String?): Boolean = value != null && value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun profileHash(quote: AiVerifiedBudgetQuote): String =
        digest(quote.profileFingerprint, quote.policyFingerprint, quote.unit.code, quote.unit.scale.toString())

    private fun digest(vararg values: String?): String {
        val hash = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value?.toByteArray(Charsets.UTF_8)
            hash.update((bytes?.size ?: -1).toString().toByteArray(Charsets.US_ASCII))
            hash.update(':'.code.toByte())
            if (bytes != null) hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val MAX_AMOUNT = BigInteger.TEN.pow(38) - BigInteger.ONE
        private val FINAL = setOf(AiPhysicalOperation.FINAL_PRIMARY, AiPhysicalOperation.FINAL_FALLBACK)
    }
}
