package com.meetme.server.shared.operations

import com.meetme.server.shared.application.service.operations.OperatorViewMasker
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

/** OPS241 follow-up: schedule dates after an international contact must not suppress redaction. */
class OperatorTrailingDateContactMaskingTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "전화 +49 30 1234 5678 2044-02-29 19:30 가능|전화 [전화번호 숨김] 2044-02-29 19:30 가능",
            "전화 +49 30 1234 5678 2044/02/29 19:30 가능|전화 [전화번호 숨김] 2044/02/29 19:30 가능",
            "전화 +49 30 1234 5678 2044.02.29 19:30 가능|전화 [전화번호 숨김] 2044.02.29 19:30 가능",
            "전화 +49 30 1234 5678 2044년 2월 29일 19시 가능|전화 [전화번호 숨김] 2044년 2월 29일 19시 가능",
            "전화 +33 1 23 45 67 89 02/29 19:30 가능|전화 [전화번호 숨김] 02/29 19:30 가능",
            "전화 +49 30 1234 5678 2044-02-29T19:30:00+09:00 가능|전화 [전화번호 숨김] 2044-02-29T19:30:00+09:00 가능",
        ],
    )
    fun `trailing date stays byte for byte while only contact is hidden`(
        input: String,
        expected: String,
    ) {
        assertEquals(expected, OperatorViewMasker().text(input))
    }
}
