package com.meetme.server.shared.application.port.output

import java.time.Instant
import java.util.UUID

/** Only the transaction that inserts a new room may initialize its managed coverage. */
fun interface NewRoomAiBudgetPort {
    fun initialize(
        roomId: UUID,
        createdAt: Instant,
    )
}

/** Internal configuration failure only; no financial policy or caller data is exposed as an API error. */
class NewRoomAiBudgetUnavailableException : IllegalStateException("Managed AI room budget is unavailable")
