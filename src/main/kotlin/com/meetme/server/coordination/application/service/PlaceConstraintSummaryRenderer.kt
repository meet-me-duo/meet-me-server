package com.meetme.server.coordination.application.service

import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.PublicPlaceConstraintDescription
import com.meetme.server.submission.domain.dsl.SchedulePlaceConstraintKind
import org.springframework.context.MessageSource
import java.util.Locale
import java.util.UUID

class PlaceConstraintSummaryRenderer(
    private val messageSource: MessageSource,
) {
    fun render(
        timeSummary: String,
        participantIds: Set<UUID>,
        meetingMode: MeetingMode,
        descriptions: List<PublicPlaceConstraintDescription>,
        locale: Locale,
    ): String {
        if (meetingMode != MeetingMode.IN_PERSON) return timeSummary
        val clauses =
            descriptions
                .filter { it.participantId in participantIds && it.publicPlaceName != null }
                .mapNotNull { value ->
                    val name = requireNotNull(value.publicPlaceName)
                    when (value.kind) {
                        SchedulePlaceConstraintKind.TRAVEL_TIME_LIMIT ->
                            value.maxMinutes?.let { minutes ->
                                val mode =
                                    value.travelMode
                                        ?.let {
                                            messageSource.getMessage(
                                                "place.constraint.mode.${it.name.lowercase(Locale.ROOT)}",
                                                null,
                                                locale,
                                            ) +
                                                " "
                                        }.orEmpty()
                                messageSource.getMessage("place.constraint.travel", arrayOf<Any>(name, mode, minutes), locale)
                            }
                        SchedulePlaceConstraintKind.MEETING_PLACE ->
                            messageSource.getMessage(
                                "place.constraint.meeting",
                                arrayOf<Any>(name),
                                locale,
                            )
                        SchedulePlaceConstraintKind.UNRESOLVED -> null
                    }
                }.distinct()
                .sorted()
        return if (clauses.isEmpty()) timeSummary else timeSummary + " " + clauses.joinToString("; ")
    }
}
