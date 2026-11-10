package com.meetme.server.coordination.adapter.output.integration

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** C4 adapter only. No missing endpoints, time policy defaults or evidence are supplied here. */
internal object ScheduleDslRoomLocalTime {
    fun parse(
        value: String,
        roomTimeZone: String,
    ): LocalDateTime {
        val zone = ZoneId.of(roomTimeZone)
        try {
            // Preserve the established local ISO path; existing validators still check its scope.
            return LocalDateTime.parse(value)
        } catch (_: DateTimeParseException) {
            val offset = OffsetDateTime.parse(value)
            val local = offset.toLocalDateTime()
            val validOffsets = zone.rules.getValidOffsets(local)
            // This IR stores a room-local datetime. Reject an incompatible offset, DST gap or
            // overlap rather than silently shifting dates or losing the selected overlap instant.
            require(validOffsets.size == 1 && validOffsets.single() == offset.offset) {
                "Offset timestamp does not identify an unambiguous local time in the room zone"
            }
            val converted = offset.atZoneSameInstant(zone).toLocalDateTime()
            require(converted == local)
            return converted
        }
    }
}
