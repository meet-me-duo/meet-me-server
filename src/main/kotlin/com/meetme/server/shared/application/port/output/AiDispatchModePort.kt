package com.meetme.server.shared.application.port.output

/** Application-side mode only; no transport or price-authority types cross this boundary. */
fun interface AiDispatchModePort {
    fun isEnabled(): Boolean
}
