package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.submission.domain.dsl.SCHEDULE_CONTEXT_MODE_POLICY_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_STRUCTURAL_UNION_POLICY_VERSION

/** Generic instructions selected by the immutable V5 calculation policy, never by source text. */
object ScheduleDslUnionAvailabilityInstructions {
    fun apply(
        basePrompt: String,
        policyVersion: String,
    ): String {
        if (policyVersion == SCHEDULE_CONTEXT_MODE_POLICY_VERSION) return basePrompt
        require(policyVersion == SCHEDULE_STRUCTURAL_UNION_POLICY_VERSION) { "Unsupported structural instruction policy" }
        require(basePrompt.indexOf(OLD_POLICY) >= 0 && basePrompt.indexOf(OLD_POLICY) == basePrompt.lastIndexOf(OLD_POLICY)) {
            "Missing or repeated structural policy instructions"
        }
        return basePrompt.replace(OLD_POLICY, UNION_POLICY) + "\n\n" + DAY_RESPONSES
    }

    private const val OLD_POLICY =
        "With explicit availability, preferences add weight only inside it; with preferences alone, only their bounds are available."

    private const val UNION_POLICY =
        "Independent AVAILABLE and PREFERRED roots both supply possible time: the server takes their union, then subtracts " +
            "global exclusions. A preference-only response supplies availability without an extra preference bonus. " +
            "When usable availability and preference coexist, preferred eligible time receives extra weight. " +
            "Preserve scoped PREFER(base, preference): its preference stays inside its base. For an explicit possible-only " +
            "restriction, bind affected preferences inside that restricted PREFER base rather than adding them as an " +
            "independent preference root; do not duplicate a scoped preference into an independent root to escape its restriction. " +
            "Absolute unavailability belongs in the exclusion root. Preserve local ALL_OF, EXCEPT and CHOICE relations; " +
            "do not flatten their claims into an unconditional union. The server computes eligibility and scores."

    private const val DAY_RESPONSES =
        "Participant meeting-response defaults: a bare weekday, calendar date, or time expression without an unavailability " +
            "or preference qualifier declares AVAILABLE support. A resolved bare weekday or date without a narrower time " +
            "restriction covers that calendar date and uses WHOLE_DAY with native date evidence and NON_PRECISE resolution. " +
            "An explicitly preferred whole date may likewise use PREFERRED with WHOLE_DAY. Missing numeric clocks alone " +
            "do not make a bare date response UNKNOWN. Distinguish an unqualified meeting response from an event or a " +
            "before/after relation whose actual boundary is unknown; preserve genuine date ambiguity or unknown event " +
            "boundaries as UNKNOWN. Resolve ordinary colloquial date/time spelling semantically while keeping evidence " +
            "quotes exactly as written. Retain assertions, corrections, speaker ownership, hard exclusions and existing " +
            "source/revision validation; do not invent a date, clock, venue, confirmation, or new source."
}
