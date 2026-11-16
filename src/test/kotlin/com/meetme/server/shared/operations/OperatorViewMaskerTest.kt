package com.meetme.server.shared.operations

import com.meetme.server.shared.application.service.operations.OperatorViewMasker
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.UUID
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Issue241: explicit operator-view privacy policy; all names, contacts and identities here are synthetic. */
class OperatorViewMaskerTest {
    private val masker = OperatorViewMasker()

    @ParameterizedTest
    @MethodSource("names")
    fun `OVM01 name fields trim and retain exactly the first Unicode code point`(
        input: String?,
        expected: String,
    ) {
        assertEquals(expected, masker.name(input))
    }

    @ParameterizedTest
    @MethodSource("phones")
    fun `OVM02 recognized phone spans are replaced without changing surrounding text`(
        input: String,
        expected: String,
    ) {
        assertEquals(expected, masker.text(input))
    }

    @ParameterizedTest
    @MethodSource("emails")
    fun `OVM03 clear email syntax including plus tags and Unicode domains is redacted`(
        input: String,
        expected: String,
    ) {
        assertEquals(expected, masker.text(input))
    }

    @ParameterizedTest
    @MethodSource("noncontactText")
    fun `OVM04 dates clocks time zones ranges durations and nonphone numbers remain byte exact`(input: String) {
        assertEquals(input, masker.text(input))
    }

    @Test
    fun `OVM05 all contacts in one source are redacted while its time conditions remain intact`() {
        val input = "가나다: 01012345678 / 02-123-4567, ops+team@example.com 및 <owner@예시.한국>. 2044-02-29 19:15–22:40, 60분 가능"
        val expected =
            "가나다: [전화번호 숨김] / [전화번호 숨김], [이메일 숨김] 및 <[이메일 숨김]>. 2044-02-29 19:15–22:40, 60분 가능"
        assertEquals(expected, masker.text(input))
    }

    @Test
    fun `OVM06 masking a structured name must not replace that name inside free form source text`() {
        assertEquals("가**", masker.name("가나다"))
        assertEquals("A****", masker.name("Alice"))
        val original = "가나다와 Alice는 일요일 오후 4시 30분부터 가능해요. 이름 대신 시간을 바꾸지 마세요."
        assertEquals(original, masker.text(original))
    }

    @Test
    fun `OVM07 aliases expose exactly eight uppercase hexadecimal display characters and no full identity`() {
        val participants = listOf(PARTICIPANT_A, PARTICIPANT_B, PARTICIPANT_C)
        val aliases = participants.map { masker.participantAlias(ROOM_A, it) }
        aliases.forEach { alias ->
            assertTrue(Regex("참가자 [0-9A-F]{8}").matches(alias), alias)
            assertFalse(alias.contains(ROOM_A.toString()))
            participants.forEach { assertFalse(alias.contains(it.toString())) }
        }
        assertEquals(aliases.size, aliases.toSet().size, "The fixed distinct participants must not collapse to one alias")
    }

    @Test
    fun `OVM08 identical room and participant identities retain their alias across instances and unrelated masking`() {
        val first = masker.participantAlias(ROOM_A, PARTICIPANT_A)
        masker.name("Synthetic Operator")
        masker.text("010-1234-5678 ops@example.com")
        assertEquals(first, masker.participantAlias(ROOM_A, PARTICIPANT_A))
        assertEquals(first, OperatorViewMasker().participantAlias(ROOM_A, PARTICIPANT_A))
    }

    @Test
    fun `OVM09 the same participant identity gets a different display alias in another room`() {
        assertNotEquals(
            masker.participantAlias(ROOM_A, PARTICIPANT_A),
            masker.participantAlias(ROOM_B, PARTICIPANT_A),
        )
    }

    @Test
    fun `OVM10 roster insertion subset selection pagination and call order cannot renumber existing aliases`() {
        val original =
            listOf(PARTICIPANT_A, PARTICIPANT_B).associateWith {
                masker.participantAlias(ROOM_A, it)
            }
        val afterJoin =
            listOf(PARTICIPANT_C, PARTICIPANT_B, PARTICIPANT_A).associateWith {
                masker.participantAlias(ROOM_A, it)
            }
        original.forEach { (identity, alias) -> assertEquals(alias, afterJoin.getValue(identity)) }
        assertEquals(original.getValue(PARTICIPANT_B), masker.participantAlias(ROOM_A, PARTICIPANT_B))
        val reversedInstance = OperatorViewMasker()
        assertEquals(original.getValue(PARTICIPANT_B), reversedInstance.participantAlias(ROOM_A, PARTICIPANT_B))
        assertEquals(original.getValue(PARTICIPANT_A), reversedInstance.participantAlias(ROOM_A, PARTICIPANT_A))
    }

    @Test
    fun `OVM11 already redacted contact text remains stable when the view projection is applied again`() {
        val once = masker.text("연락 010-1234-5678, owner+room@example.com. 19시부터 2시간")
        assertEquals("연락 [전화번호 숨김], [이메일 숨김]. 19시부터 2시간", once)
        assertEquals(once, masker.text(once))
    }

    companion object {
        private val ROOM_A = UUID.fromString("11111111-2222-4333-8444-555555555555")
        private val ROOM_B = UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa")
        private val PARTICIPANT_A = UUID.fromString("abcdef01-2345-4678-8abc-def012345678")
        private val PARTICIPANT_B = UUID.fromString("01234567-89ab-4cde-8f01-23456789abcd")
        private val PARTICIPANT_C = UUID.fromString("aabbccdd-eeff-4012-8345-6789abcdef01")

        @JvmStatic
        fun names(): Stream<Arguments> =
            Stream.of(
                Arguments.of(null, "이름 없음"),
                Arguments.of("", "이름 없음"),
                Arguments.of(" \t\r\n ", "이름 없음"),
                Arguments.of("\u00a0\u2003\u3000", "이름 없음"),
                Arguments.of("가나다", "가**"),
                Arguments.of("김", "김"),
                Arguments.of("Alice", "A****"),
                Arguments.of("  Alice \t", "A****"),
                Arguments.of("\u2003김 새봄\u3000", "김***"),
                Arguments.of("😀민수", "😀**"),
                Arguments.of("👩‍💻민", "👩***"),
                Arguments.of("e\u0301va", "e***"),
                Arguments.of("홍😀동", "홍**"),
                Arguments.of("张伟", "张*"),
                Arguments.of("𝒜bc", "𝒜**"),
            )

        @JvmStatic
        fun phones(): Stream<Arguments> =
            Stream.of(
                Arguments.of("연락:010-1234-5678", "연락:[전화번호 숨김]"),
                Arguments.of("01012345678", "[전화번호 숨김]"),
                Arguments.of("010 1234 5678", "[전화번호 숨김]"),
                Arguments.of("011-234-5678", "[전화번호 숨김]"),
                Arguments.of("(02-123-4567), 점심 가능", "([전화번호 숨김]), 점심 가능"),
                Arguments.of("031-1234-5678", "[전화번호 숨김]"),
                Arguments.of("02 123 4567", "[전화번호 숨김]"),
                Arguments.of("+82-10-1234-5678", "[전화번호 숨김]"),
                Arguments.of("+82 10 1234 5678 19시 가능", "[전화번호 숨김] 19시 가능"),
                Arguments.of("+821012345678", "[전화번호 숨김]"),
                Arguments.of("+1 (415) 555-0123 19:30 가능", "[전화번호 숨김] 19:30 가능"),
                Arguments.of("+14155550123", "[전화번호 숨김]"),
                Arguments.of("+44 20 7946 0958, 오후 가능", "[전화번호 숨김], 오후 가능"),
            )

        @JvmStatic
        fun emails(): Stream<Arguments> =
            Stream.of(
                Arguments.of("ops@example.com", "[이메일 숨김]"),
                Arguments.of("first.last+planning@example.com", "[이메일 숨김]"),
                Arguments.of("meet_me-ops+1@sub.example.com", "[이메일 숨김]"),
                Arguments.of("operator+daily@예시.한국", "[이메일 숨김]"),
                Arguments.of("<ops@example.com>. 30분 가능", "<[이메일 숨김]>. 30분 가능"),
            )

        @JvmStatic
        fun noncontactText(): Stream<Arguments> =
            Stream.of(
                Arguments.of(""),
                Arguments.of(" \t\n\r "),
                Arguments.of("2044-02-29 / 2044.02.29 / 2044년 2월 29일"),
                Arguments.of("20440229 2044022919 20440229190 204402291915"),
                Arguments.of("2044-02-29T19:15:00+09:00 ~ 2044-03-01T02:30:00Z"),
                Arguments.of("오후 7시 30분부터 11시까지, UTC+09:00 및 -05:00"),
                Arguments.of("1시간 30분, 60분, 30~90분, 1-2시간, 7–11시"),
                Arguments.of("1234567890 12345678901 10000000000분, 수요일@19시"),
            )
    }
}
