package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.MeetingRoom

/** All callers are transactional writers: lock the room before its exact run. */
internal object ActiveRunLock {
    fun acquire(
        rooms: MeetingRoomRepository,
        runs: CoordinationRunRepository,
        requested: CoordinationRun,
    ): Pair<MeetingRoom, CoordinationRun>? {
        val room = rooms.findByIdForUpdate(requested.roomId) ?: return null
        if (room.activeRunId != requested.id.value || room.activeRevisionRoundId != null) return null
        val current = runs.findByIdForUpdate(requested.id) ?: return null
        if (current.isConfirmed) return null
        check(current.roomId == room.id && current.batch.id == requested.batch.id) { "Active run identity is invalid" }
        return room to current
    }
}
