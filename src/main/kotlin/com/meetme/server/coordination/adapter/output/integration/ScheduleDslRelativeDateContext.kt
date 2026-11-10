package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import java.time.ZoneId

/** Optional request-only calendar facts, without source text or availability claims. */
object ScheduleDslRelativeDateContext {
    fun enrich(
        capsule: ScheduleDslProviderInputCapsule,
        data: Map<String, Any?>,
    ): Map<String, Any?> {
        val context = capsule.snapshot.context
        val zone = ZoneId.of(context.timeZone)
        val sources =
            context.inputs.sortedBy { it.submissionVersionId.toString() }.flatMap { input ->
                input.sources.sortedBy { it.sourceId }.map { source ->
                    linkedMapOf(
                        "input_ref" to input.submissionVersionId.toString(),
                        "source_id" to source.sourceId,
                        "revision" to source.revision,
                        "reference_at" to source.referenceAt.toString(),
                        "input_local_date" to
                            source.referenceAt
                                .atZone(zone)
                                .toLocalDate()
                                .toString(),
                    )
                }
            }
        return data +
            (
                "relative_date_context" to
                    linkedMapOf(
                        "version" to "relative-date-context-1",
                        "time_zone" to context.timeZone,
                        "search_range" to
                            linkedMapOf(
                                "start_inclusive" to context.searchStart.toString(),
                                "end_exclusive" to context.searchEnd.toString(),
                                "last_included_date" to context.searchEnd.minusDays(1).toString(),
                            ),
                        "sources" to sources,
                    )
            )
    }

    const val INSTRUCTIONS =
        "Relative date context (relative-date-context-1): " +
            "Use the row matching each input_ref/source_id/revision to anchor relative expressions to that source's " +
            "frozen reference_at and input_local_date in time_zone, rather than the current processing date. " +
            "search_range describes the requested meeting search period: start_inclusive and last_included_date are included; " +
            "end_exclusive is not included. It does not replace a source's input date, assert availability, supply native evidence, " +
            "or remove out-of-range declarations. Preserve genuine ambiguity rather than inventing a target month or personal fact."
}
