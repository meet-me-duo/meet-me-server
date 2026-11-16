package com.meetme.server.shared.operations

import com.meetme.server.shared.application.service.operations.OperatorViewMasker
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

/** OPS241: a following duration range belongs to the schedule rather than the contact number. */
class OperatorRangeContactMaskingTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "전화 +49 30 1234 5678 20-30분 가능|전화 [전화번호 숨김] 20-30분 가능",
            "전화 +49 30 1234 5678 1-2시간 가능|전화 [전화번호 숨김] 1-2시간 가능",
            "전화 +49 30 1234 5678 20~30분 가능|전화 [전화번호 숨김] 20~30분 가능",
            "전화 +49 30 1234 5678 20–30분 가능|전화 [전화번호 숨김] 20–30분 가능",
        ],
    )
    fun `duration ranges after international contact remain exactly unchanged`(
        input: String,
        expected: String,
    ) {
        assertEquals(expected, OperatorViewMasker().text(input))
    }
}
