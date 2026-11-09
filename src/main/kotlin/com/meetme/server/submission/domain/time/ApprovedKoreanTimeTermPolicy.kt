package com.meetme.server.submission.domain.time

import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrScope
import java.time.LocalDate

const val APPROVED_KOREAN_TIME_TERM_VERSION = "ko-time-terms-1"

enum class ApprovedKoreanTimeTerm { AM, PM, DAWN, MORNING, LUNCH, DAYTIME, MIDDAY, EVENING, NIGHT, LATE_NIGHT, ALL_DAY }

object ApprovedKoreanTimeTermPolicy {
    fun resolve(
        term: ApprovedKoreanTimeTerm,
        localDate: LocalDate,
    ): IrScope {
        val (startHour, endHour) =
            when (term) {
                ApprovedKoreanTimeTerm.AM -> 0 to 12
                ApprovedKoreanTimeTerm.PM -> 12 to 24
                ApprovedKoreanTimeTerm.DAWN -> 0 to 6
                ApprovedKoreanTimeTerm.MORNING -> 6 to 11
                ApprovedKoreanTimeTerm.LUNCH -> 11 to 15
                ApprovedKoreanTimeTerm.DAYTIME -> 9 to 18
                ApprovedKoreanTimeTerm.MIDDAY -> 11 to 15
                ApprovedKoreanTimeTerm.EVENING -> 17 to 22
                ApprovedKoreanTimeTerm.NIGHT -> 20 to 30
                ApprovedKoreanTimeTerm.LATE_NIGHT -> 22 to 28
                ApprovedKoreanTimeTerm.ALL_DAY -> 0 to 24
            }
        val end = if (endHour >= 24) localDate.plusDays(1).atTime(endHour - 24, 0) else localDate.atTime(endHour, 0)
        return IrScope(IrDimension.TIME, localDate.atTime(startHour, 0), end)
    }

    fun lexemes(term: ApprovedKoreanTimeTerm): Set<String> =
        when (term) {
            ApprovedKoreanTimeTerm.AM -> setOf("오전")
            ApprovedKoreanTimeTerm.PM -> setOf("오후")
            ApprovedKoreanTimeTerm.DAWN -> setOf("새벽")
            ApprovedKoreanTimeTerm.MORNING -> setOf("아침")
            ApprovedKoreanTimeTerm.LUNCH -> setOf("점심")
            ApprovedKoreanTimeTerm.DAYTIME -> setOf("낮")
            ApprovedKoreanTimeTerm.MIDDAY -> setOf("한낮")
            ApprovedKoreanTimeTerm.EVENING -> setOf("저녁")
            ApprovedKoreanTimeTerm.NIGHT -> setOf("밤")
            ApprovedKoreanTimeTerm.LATE_NIGHT -> setOf("심야", "한밤중")
            ApprovedKoreanTimeTerm.ALL_DAY -> setOf("하루종일")
        }
}
