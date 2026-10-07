package com.meetme.server.coordination.application.port.output

import com.meetme.server.coordination.domain.InputRevisionRound
import com.meetme.server.shared.domain.MeetingRoomId
import java.util.UUID

interface InputRevisionRoundRepository {
    fun insert(round: InputRevisionRound)

    fun update(round: InputRevisionRound)

    fun findById(
        roomId: MeetingRoomId,
        id: UUID,
    ): InputRevisionRound?

    fun findByReopenRequest(
        roomId: MeetingRoomId,
        requestId: UUID,
    ): InputRevisionRound?

    fun findByAnalyzeRequest(
        roomId: MeetingRoomId,
        requestId: UUID,
    ): InputRevisionRound?
}
