// PROPOSAL ONLY: not production/test source. Root freezes together with issue123-budget-public-contract.kt.
package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionFencePort
import com.meetme.server.shared.application.port.output.AiBudgetFailure
import com.meetme.server.shared.application.port.output.AiBudgetOutcome
import com.meetme.server.shared.application.port.output.AiBudgetUsage
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import okhttp3.OkHttpClient
import java.net.URI
import java.time.Clock
import java.util.UUID
import java.util.function.LongSupplier

/** Proposed binding meetme.ai.budget.enabled. OFF preserves the inherited legacy path. */
data class AiDispatchGateProperties(val enabled: Boolean = false)

/**
 * Purely local context: never serialized to a provider, header, query or metric.
 * Public data is NOT authority: DurableAiBudgetPort must load/lock the execution and compare every
 * field with current DB state. A browser cannot supply this through an API.
 *
 * PRECHECK: executionId=check.id, generation=check.generation, version=claimed check.version,
 * ownerToken=check.ownerToken. FINAL_ANALYSIS: executionId=invocation.id, generation=0L,
 * version=invocation.runVersion, ownerToken=invocation.ownerToken (mapping subject to core agreement).
 * source/context fingerprints are recomputed from each adapter's frozen actual input, compared with
 * this execution, and then validated against immutable DB source/anchor by the shared DB fence.
 *
 * deadlineNanos is the ORIGINAL in-process cutoff, not a fresh duration per stage/leg. It is also
 * bounded by the permit's DB-derived deadlineAt after claim. No nanoTime value is stored in PG.
 */
data class AiProviderDispatchContext(
    val execution: AiBudgetExecution,
    val deadlineNanos: Long,
    val finalAttemptId: UUID? = null,
    val providerOrdinal: Int = 1,
)

/**
 * Structural wire description, NOT a verified price/quote or an enabled production profile.
 * No default instance/model/endpoint/limit is supplied by the gate.
 * For current direct personal adapters captureLimitBytes=16384 is their existing body limit.
 * A final SDK capture limit is explicitly supplied by its reviewed structural profile/test fixture;
 * no personal16KiB or2048 limit is silently applied to the final batch.
 * Registry proof still independently binds this profileFingerprint and actual whole payload hash.
 */
data class AiPhysicalWireProfile(
    val profileFingerprint: String,
    val method: String,
    val endpoint: URI,
    val captureLimitBytes: Int,
)

/** Immutable body snapshot. copyBody returns a copy; preparation computes the whole-body hash. */
class PreparedAiHttpRequest private constructor() {
    val operation: AiPhysicalOperation get() = TODO("TDD RED: AD01")
    val ordinal: Int get() = TODO("TDD RED: AD01")
    val attemptId: UUID? get() = TODO("TDD RED: AD01")
    val profile: AiPhysicalWireProfile get() = TODO("TDD RED: AD01")
    val wholePayloadFingerprint: String get() = TODO("TDD RED: AD01")
    fun copyBody(): ByteArray = TODO("TDD RED: AD01")

    companion object {
        fun freeze(
            operation: AiPhysicalOperation,
            ordinal: Int,
            attemptId: UUID?,
            profile: AiPhysicalWireProfile,
            body: ByteArray,
        ): PreparedAiHttpRequest = TODO("TDD RED: AD01")
    }
}

/** Opaque local handle. A caller cannot construct a granted reservation or inject a dispatchToken. */
class PreparedAiDispatchGroup internal constructor()

/**
 * Opaque one-operation transport + observation handle; constructor is not public API.
 * client applies original absolute timeout plus application-call and network-exchange one-shot guards.
 * Do not expose a raw permit as an adapter argument or accept caller-created permission booleans.
 */
class GatedAiHttpExchange internal constructor() {
    val client: OkHttpClient get() = TODO("TDD RED: AD02")

    /** No observation before an actual claim. Missing/invalid usage never refunds a hold. */
    fun observe(
        eventId: UUID,
        outcome: AiBudgetOutcome,
        usage: AiBudgetUsage = AiBudgetUsage(),
        failure: AiBudgetFailure? = null,
    ): Boolean = TODO("TDD RED: AD03")
}

/**
 * Shared adapter mechanism. ON always uses the durable port/default-deny authority, never the
 * old AiCallBudgetPort stage-reservation verified boolean. No monetary amount is accepted here.
 * OFF methods return an unmodified legacy transport and do not create a stage/physical double debit.
 */
class AiHttpDispatchGate(
    val properties: AiDispatchGateProperties,
    private val budget: DurableAiBudgetPort,
    private val clock: Clock,
    private val nanoTime: LongSupplier = LongSupplier(System::nanoTime),
) {
    /**
     * Direct personal generator prepares count+generation once, reserves TWO child legs atomically.
     * Classifier/direct final may prepare a single leg. Every returned reservation/call must exactly
     * match the requested execution/spec list; wrong/extra/duplicate child entries deny before I/O.
     * Missing context/proof/deadline/coverage denies; null context remains legal only when OFF.
     * RESERVED but never claimed legs remain held unless the core later adds an explicit safe-cancel
     * contract; no implicit cancellation/refund is introduced by this file.
     */
    fun prepareGroup(
        context: AiProviderDispatchContext?,
        calls: List<PreparedAiHttpRequest>,
    ): PreparedAiDispatchGroup = TODO("TDD RED: AD04")

    /**
     * A single physical leg selected from the prepared group. First application interceptor:
     * validate bytes/method/authority/path, fresh remaining deadline -> durable child claim CAS ->
     * tighten original call deadline -> proceed. Thus denial occurs BEFORE connection/request I/O.
     * Network interceptor independently rechecks method/endpoint/body hash and consumes exactly one
     * network exchange before request bytes. Same-Call 503/auth follow-up fails before its second send.
     * A second SDK/direct Call using the exchange fails in its application interceptor before connect.
     */
    fun forPreparedCall(
        baseClient: OkHttpClient,
        group: PreparedAiDispatchGroup,
        operation: AiPhysicalOperation,
    ): GatedAiHttpExchange = TODO("TDD RED: AD05")

    /**
     * SDK-compatible lazy single-call mode: SDK serializes its actual request before application
     * interception. Bounded-snapshot the body there, freeze the exact whole-wire spec, then perform
     * reserve(single child)+claim BEFORE connect/proceed. The second application invocation cannot
     * create another reservation. No SDK replacement or prompt-only quote is needed.
     * Only replayable bounded JSON bodies are accepted; no body read/recreation changes the send.
     * Shared source/context binding was already computed from the same frozen SDK input snapshot;
     * network bytes must still equal the quoted snapshot after later interceptors.
     */
    fun forLazySingleCall(
        baseClient: OkHttpClient,
        context: AiProviderDispatchContext?,
        operation: AiPhysicalOperation,
        profile: AiPhysicalWireProfile,
    ): GatedAiHttpExchange = TODO("TDD RED: AD06")
}

/*
ADDITIVE EXISTING REQUEST FIELDS (append nullable default; no provider payload inclusion):
  PrecheckProviderRequest(..., dispatchContext: AiProviderDispatchContext? = null)
  AmbiguityClassificationRequest(..., dispatchContext: AiProviderDispatchContext? = null)
  NaturalLanguageBatchRequest(..., dispatchContext: AiProviderDispatchContext? = null)

Actual code should place the SDK-free context DTO in shared.application.port.output (not this adapter
package); this single proposal file groups declarations only to review signatures. No app->adapter
import is approved. The DTO refers only to core execution/UUID/Long, never OkHttp.

ADDITIVE CONSTRUCTOR PARAMETERS (append with a legacy-OFF default seam; managed Spring wiring injects
ONE shared gate bean so global ON cannot accidentally instantiate an OFF adapter):
  OpenAiAmbiguityClassifier(..., dispatchGate: AiHttpDispatchGate? = null)
  GeminiPreSubmissionCheckProvider.runtime(..., dispatchGate: AiHttpDispatchGate? = null)
  GeminiNaturalLanguageParserAdapter(..., dispatchGate: AiHttpDispatchGate? = null)
  OpenAiLunaNaturalLanguageParserAdapter(..., dispatchGate: AiHttpDispatchGate? = null)

The existing private SDK/loopback test constructors forward the SAME gate rather than dropping it.
OFF direct constructors retain historical behavior. ON integration tests instantiate managed factories
or explicitly inject the ON gate; forged/missing context cannot authorize a direct adapter's I/O.
A genuinely missing required managed gate with global ON is CONFIGURATION/denied, never a legacy fallback.

EXPLICIT TWO-STAGE BRANCH:
  TwoStagePreSubmissionCheckProvider(..., physicalDispatch: AiHttpDispatchGate? = null)
  physicalDispatch.properties.enabled == false/null -> exact existing stage-budget reserve/record path.
  true -> separate physical-enforcement path: never call old budget.reserve/record; classifier receives
          unchanged dispatchContext; generator receives same context + remaining timeout. Actual
          adapters prepare/claim/observe their own one/2 legs. No allow-all stage facade, no promotion
          of verified boolean, no parent+child monetary double reservation.
  Classifier error/refusal still generates0; CLEAR skips count/gen; eligible uncertain/review cases
  use count/gen legs. shared stage15 and authoritative claim/late-result fencing stay in force.

FACTORY DEFAULTS:
  New meetme.ai.budget.enabled=false independently of precheck/provider and decision-routing flags.
  Legacy-OFF composition remains unchanged, including classifier-bean absence and DefaultDeny behavior.
  New explicit ON factory wires actual classifier using existing OpenAiProperties (not adapter env reads),
  fixed canonical wire, and minimumConfidence=null default. Unconfigured confidence or registry proof
  yields HTTP0, even with a key. No operational threshold/quote/amount/profile is seeded from a test.

SHARED DB FENCE (implementation owned by provider agent; exact constructor to align with core SQL):
  JdbcAiBudgetExecutionFence : AiBudgetExecutionFencePort
  lockAndValidate(request,at) acquires room -> exact check/run/claim; core locks budget last.
  PRECHECK reuses real participant/binding/current-check/closure/deadline checks; FINAL resolves
  invocation FK -> run/batch/room/current version and exact attempt/provider/ordinal before admission.
  Source/context fingerprints are recomputed from immutable candidate or FrozenSubmissionReader's
  exact versions/anchors. Context/ownerToken caller assertions alone never suffice.
  Coverage epoch lookup must be a core-owned authoritative port/query, not a new valid-zero seed:
  historical or any OFF/untracked interval without positive durable coverage proof denies ON.
  A V17-created row/new room is not automatically READY. In-process off-mark best effort can add UNKNOWN
  evidence but cannot prove coverage after crash/config toggle or alter/break legacy OFF operations.
  DB fence deadline includes existing immutable execution/provider windows; a caller-supplied fresh
  deadlineNanos cannot extend those. PG stores wall deadlines; local gate only tightens monotonic ones.

SOURCE/FLOW ID FREEZE:
  FINAL generation=0 proposed; core must explicitly agree before tests freeze this mapping.
  attemptId final REQUIRED, ordinal FINAL_PRIMARY1..4 per invocation, FINAL_FALLBACK1.
  No new identity on SDK callback/follow-up; actual attempt.number remains the existing run-wide field.

REMAINING ROOT CONTRACT ITEMS (not silently implemented):
  1. Exact coverage-query port/schema plus real-fence constructor dependencies (room/precheck/binding/run/
     invocation/source/attempt repositories) must align with core schema before production skeleton.
  2. Core permits need no caller-visible monetary/verified flags. All quote resolution is registry-owned.
  3. Typed dispatch denial must map non-retryably (CONFIGURATION or new explicit BUDGET_DENIED mapping,
     root/test-author choice) so final budget refusal cannot trigger an extra Luna fallback.
  4. No JSON/timeout/rate/privacy/final AREA/matching policy is changed. Final60/G15/L27/reserve3,
     personal execution30/provider-stage15, final4+1, personal6/retry1/question3 remain the maxima.
*/
