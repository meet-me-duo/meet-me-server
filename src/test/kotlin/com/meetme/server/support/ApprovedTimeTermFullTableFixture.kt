package com.meetme.server.support

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.submission.domain.dsl.ProposedApprovedTimeTermRef
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTerm
import java.time.LocalDate

/** Explicit synthetic term references. Does not call the new policy while constructing expected values. */
object ApprovedTimeTermFullTableFixture {
    private val f = ApprovedHalfDayTestFixture

    fun ref(
        input: ScheduleDslOfflineInput,
        term: ApprovedKoreanTimeTerm,
        lexeme: String,
        date: LocalDate = f.day,
        conditionId: String = "half",
    ) = ProposedApprovedTimeTermRef(
        f.ir.first,
        conditionId,
        term,
        date,
        f.evidence(input, lexeme),
        listOf(f.evidence(input, date.toString())),
    )
}
