package com.meetme.server.shared.operations

import com.meetme.server.shared.application.service.operations.OperatorViewMasker
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

/** OPS241: explicit international contact forms must be hidden without consuming schedule context. */
class OperatorInternationalContactMaskingTest {
    private val masker = OperatorViewMasker()

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "+82 2 1234 5678|[전화번호 숨김]",
            "+82-2-1234-5678|[전화번호 숨김]",
            "+49 30 1234 5678|[전화번호 숨김]",
            "+49-30-1234-5678|[전화번호 숨김]",
            "+33 1 23 45 67 89|[전화번호 숨김]",
            "+33-1-23-45-67-89|[전화번호 숨김]",
            "+81 3 1234 5678|[전화번호 숨김]",
        ],
    )
    fun `explicit international phone with separated country code is hidden`(
        input: String,
        expected: String,
    ) {
        assertEquals(expected, masker.text(input))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "전화 +82 2 1234 5678 19:30 가능|전화 [전화번호 숨김] 19:30 가능",
            "전화 +49 30 1234 5678 19시 가능|전화 [전화번호 숨김] 19시 가능",
            "전화 +33 1 23 45 67 89 19:30-21:00 가능|전화 [전화번호 숨김] 19:30-21:00 가능",
            "2044-02-29 +49 30 1234 5678 UTC+09:00|2044-02-29 [전화번호 숨김] UTC+09:00",
        ],
    )
    fun `international contact redaction preserves following date timezone and clock exactly`(
        input: String,
        expected: String,
    ) {
        assertEquals(expected, masker.text(input))
    }

    @Test
    fun `numeric expressions short plus numbers dates and offsets are not phones`() {
        val text = "2044-02-29 19:30-21:00 UTC+09:00, 90분, +123, 1 + 234 = 235, +09:00, +1 2 3"
        assertEquals(text, masker.text(text))
    }
}
