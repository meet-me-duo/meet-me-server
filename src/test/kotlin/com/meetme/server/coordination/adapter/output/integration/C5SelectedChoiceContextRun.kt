package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import java.util.UUID

/** One prospective HTTP test room; never durable evidence, admission or historical recovery. */
internal class C5SelectedChoiceContextRun(
    val enabled: Boolean,
) {
    private var roomId: UUID? = null
    private var sealed = false
    private val selections = mutableListOf<C5SelectedChoiceContexts.Bound>()

    @Synchronized
    fun begin(room: UUID) {
        if (!enabled) return
        require(roomId == null)
        roomId = room
    }

    @Synchronized
    fun record(bound: C5SelectedChoiceContexts.Bound) {
        require(enabled && roomId != null && !sealed)
        require(selections.size < 4 && selections.none { it.versionId == bound.versionId })
        selections += bound
    }

    @Synchronized
    fun seal(room: UUID) {
        if (!enabled) return
        require(roomId == room && !sealed)
        sealed = true
    }

    @Synchronized
    fun resolve(capsule: ScheduleDslProviderInputCapsule): C5SelectedChoiceContexts? {
        if (!enabled) return null
        require(roomId == capsule.snapshot.context.roomId && sealed)
        return C5SelectedChoiceContexts.freeze(capsule, selections)
    }

    @Synchronized
    fun clear() {
        selections.clear()
        sealed = false
        roomId = null
    }
}
