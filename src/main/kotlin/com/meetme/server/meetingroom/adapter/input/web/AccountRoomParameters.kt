package com.meetme.server.meetingroom.adapter.input.web

import com.meetme.server.meetingroom.application.port.input.AccountRoomCursor
import com.meetme.server.meetingroom.application.port.input.AccountRoomsQuery
import com.meetme.server.participant.domain.ParticipantRole
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

/** HTTP date/cursor encodings stay outside the application and domain. */
internal object AccountRoomParameters {
    private val zone = ZoneId.of("Asia/Seoul")

    fun query(
        role: String?,
        createdFrom: String?,
        createdTo: String?,
        query: String?,
        limit: String?,
        cursor: String?,
    ): AccountRoomsQuery =
        runCatching {
            AccountRoomsQuery(
                role = role?.let(ParticipantRole::valueOf),
                createdFrom = createdFrom?.let { date(it).atStartOfDay(zone).toInstant() },
                createdBefore = createdTo?.let { date(it).plusDays(1).atStartOfDay(zone).toInstant() },
                query = query?.trim()?.takeIf(String::isNotEmpty),
                limit = limit?.toInt() ?: 20,
                cursor = cursor?.let(::decodeCursor),
            )
        }.getOrElse { throw IllegalArgumentException("Invalid account room parameters") }

    fun encodeCursor(cursor: AccountRoomCursor): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            "v1|${cursor.createdAt}|${cursor.roomId}".toByteArray(Charsets.UTF_8),
        )

    private fun date(value: String): LocalDate {
        require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        return LocalDate.parse(value).also { require(it.year in 1..9998) }
    }

    private fun decodeCursor(value: String): AccountRoomCursor {
        require(value.length in 1..200 && value.matches(Regex("[A-Za-z0-9_-]+")))
        val parts = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8).split('|')
        require(parts.size == 3 && parts[0] == "v1")
        val createdAt = Instant.parse(parts[1])
        require(createdAt >= Instant.parse("0001-01-01T00:00:00Z") && createdAt < Instant.parse("9999-01-01T00:00:00Z"))
        val roomId = UUID.fromString(parts[2])
        return AccountRoomCursor(createdAt, roomId).also { require(encodeCursor(it) == value) }
    }
}
