package com.meetme.server.shared.application.port.output

import java.time.Instant
import java.util.Collections

/** Current execution authority only. No price, quote, accounting epoch or paid budget is implied. */
fun interface AiRuntimeDispatchGuardPort {
    fun authorize(
        request: AiBudgetReserveRequest,
        at: Instant,
    ): Instant?
}

/** Frozen wire identities only. They grant no price, monetary policy, or dispatch permit. */
object AiRuntimeDispatchProfiles {
    val fingerprints: Map<AiPhysicalOperation, String> =
        Collections.unmodifiableMap(
            mapOf(
                AiPhysicalOperation.PRECHECK_CLASSIFICATION to "c8d01d4a04aa077e0488400dc3b4791753b8428db48c6712aa78f903608a4dfc",
                AiPhysicalOperation.PRECHECK_COUNT to "658a65563b34d43f854cc47dcd93d274d23789745e718b5f7ef49e1b59e8e307",
                AiPhysicalOperation.PRECHECK_GENERATION to "86e4593b78ec903e1c9b4bbbffe204d2b8343e1752d2b6353703ce3fb9b65714",
                AiPhysicalOperation.FINAL_PRIMARY to "0a3fc94ba00d368b4e7c9a41d0b231faab5058aa893dc11082ef786fc0a08d04",
                AiPhysicalOperation.FINAL_FALLBACK to "9c5021916c80178f58d235e55bbbad4889875560b710a505fbde5dedd1a824ab",
            ),
        )
}
