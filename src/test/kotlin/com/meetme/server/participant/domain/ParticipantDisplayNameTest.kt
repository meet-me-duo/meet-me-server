package com.meetme.server.participant.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class ParticipantDisplayNameTest {
    @Test
    fun `표시 이름은 앞뒤 공백을 제거해 저장한다`() {
        assertEquals("민수", ParticipantDisplayName.of("  민수  ").value)
    }

    @Test
    fun `표시 이름은 공백 제거 후 비어 있으면 거부한다`() {
        assertThrows<IllegalArgumentException> { ParticipantDisplayName.of(" \t\n ") }
    }

    @Test
    fun `표시 이름은 Unicode 코드 포인트 50자까지 허용한다`() {
        val fiftyEmoji = "😀".repeat(50)

        assertEquals(fiftyEmoji, ParticipantDisplayName.of(fiftyEmoji).value)
    }

    @Test
    fun `표시 이름은 Unicode 코드 포인트 51자부터 거부한다`() {
        assertThrows<IllegalArgumentException> { ParticipantDisplayName.of("😀".repeat(51)) }
    }
}
