package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule

internal object C5RelativeDateContext {
    fun enrich(
        capsule: ScheduleDslProviderInputCapsule,
        data: Map<String, Any?>,
    ): Map<String, Any?> = ScheduleDslRelativeDateContext.enrich(capsule, data)

    const val INSTRUCTIONS = ScheduleDslRelativeDateContext.INSTRUCTIONS
}
