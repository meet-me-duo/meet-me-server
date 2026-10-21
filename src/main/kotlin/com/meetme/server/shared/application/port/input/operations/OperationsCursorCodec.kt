package com.meetme.server.shared.application.port.input.operations

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID

object OperationsCursorCodec {
    fun encode(cursor: OperationsCursor): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("${cursor.at}|${cursor.id}".toByteArray(StandardCharsets.UTF_8))

    fun decode(value: String): OperationsCursor {
        require(value.length in 1..256 && value.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' })
        val parts = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split('|')
        require(parts.size == 2)
        return OperationsCursor(Instant.parse(parts[0]), requireNotNull(uuidOrNull(parts[1])))
    }

    fun uuidOrNull(value: String?): UUID? {
        if (value == null || value.length != 36) return null
        return runCatching { UUID.fromString(value) }.getOrNull()?.takeIf { it.toString().equals(value, ignoreCase = true) }
    }
}
