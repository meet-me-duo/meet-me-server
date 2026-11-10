package com.meetme.server.coordination.application.service

import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.PublicPlaceConstraintDescription
import com.meetme.server.submission.domain.dsl.SchedulePlaceConstraintKind
import com.meetme.server.submission.domain.dsl.ScheduleTravelMode
import org.junit.jupiter.api.Test
import org.springframework.context.support.ResourceBundleMessageSource
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Public summary contract: show common constraints without certifying routes or revealing individual sources. */
class TravelPlaceSummaryRendererTest {
    private val renderer =
        PlaceConstraintSummaryRenderer(
            ResourceBundleMessageSource().apply {
                setBasename("messages")
                setDefaultEncoding("UTF-8")
            },
        )

    @Test
    fun `TPS01 public origin and travel upper bound accompany the unchanged time and pending attendance explanation`() {
        val actual = render(listOf(travel(FIRST, PUBLIC_ORIGIN, 45)))

        assertTrue(actual.startsWith(BASE), "The place description cannot replace the verified time or its qualification")
        assertTrue(actual.contains(PUBLIC_ORIGIN))
        assertTrue(actual.contains("45분 이내"), "A maximum duration must retain its bound rather than become a guessed near area")
        assertTrue(actual.contains("기준"))
        assertTrue(actual.contains("이동"))
        assertFalse(actual.contains("근처"))
        assertFalse(actual.contains("도보"), "An omitted travel mode is not walking")
        assertFalse(actual.contains("대중교통"), "An omitted travel mode is not transit")
        assertFalse(actual.contains("자동차"))
        assertFalse(actual.contains(FIRST.toString()), "Public descriptions do not identify the participant who supplied a condition")
    }

    @Test
    fun `TPS02 a partial attendance variant excludes absent participants constraints and deduplicates equivalent public conditions`() {
        val actual =
            render(
                listOf(
                    travel(FIRST, PUBLIC_ORIGIN, 45),
                    travel(SECOND, PUBLIC_ORIGIN, 45),
                    travel(EXCLUDED, EXCLUDED_ORIGIN, 20),
                ),
            )

        assertEquals(1, Regex(Regex.escape(PUBLIC_ORIGIN)).findAll(actual).count())
        assertFalse(actual.contains(EXCLUDED_ORIGIN))
        assertFalse(actual.contains("20분"))
        assertFalse(actual.contains(EXCLUDED.toString()))
    }

    @Test
    fun `TPS03 independent selected participant requirements remain together without becoming alternatives or an invented venue`() {
        val actual =
            render(
                listOf(
                    travel(FIRST, PUBLIC_ORIGIN, 45),
                    travel(SECOND, SECOND_ORIGIN, 30),
                ),
            )

        assertTrue(actual.contains(PUBLIC_ORIGIN))
        assertTrue(actual.contains(SECOND_ORIGIN))
        assertTrue(actual.contains("45분 이내"))
        assertTrue(actual.contains("30분 이내"))
        assertFalse(actual.contains("또는"), "Independent requirements from two participants apply together")
        assertFalse(actual.contains("모임 장소 조건:"), "Travel origins do not appoint a meeting venue")
    }

    @Test
    fun `TPS04 private or unregistered origins do not disclose their individual duration or transport through the public summary`() {
        val actual = render(listOf(travel(FIRST, null, 37, ScheduleTravelMode.CAR)))

        assertEquals(BASE, actual, "A source-reading boundary that withholds the name must retain the existing generic explanation")
        assertFalse(actual.contains("37"))
        assertFalse(actual.contains("자동차"))
        assertFalse(actual.contains("null"))
    }

    @Test
    fun `TPS05 remote variants retain the original explanation without exposing offline conditions`() {
        val time = "2027년 2월 3일 14:00~16:00 온라인 시간 후보"
        val actual =
            renderer.render(
                time,
                setOf(FIRST, SECOND),
                MeetingMode.REMOTE,
                listOf(travel(FIRST, PUBLIC_ORIGIN, 45, ScheduleTravelMode.WALK)),
                Locale.KOREA,
            )

        assertEquals(time, actual)
    }

    @Test
    fun `TPS06 an explicit meeting place is not rewritten as a travel origin or a duration constraint`() {
        val actual =
            render(
                listOf(
                    PublicPlaceConstraintDescription(
                        FIRST,
                        SchedulePlaceConstraintKind.MEETING_PLACE,
                        PUBLIC_ORIGIN,
                        null,
                        null,
                    ),
                ),
            )

        assertTrue(actual.startsWith(BASE))
        assertTrue(actual.contains("모임 장소 조건: $PUBLIC_ORIGIN"))
        assertFalse(actual.contains("이동시간"))
        assertFalse(actual.contains("분 이내"))
        assertFalse(actual.contains("확인 완료"))
    }

    @Test
    fun `TPS07 an explicitly supplied travel mode is retained in the public constraint`() {
        val actual = render(listOf(travel(FIRST, PUBLIC_ORIGIN, 45, ScheduleTravelMode.TRANSIT)))

        assertTrue(actual.contains(PUBLIC_ORIGIN))
        assertTrue(actual.contains("대중교통"))
        assertTrue(actual.contains("45분 이내"))
        assertFalse(actual.contains("도보"))
        assertFalse(actual.contains("자동차"))
    }

    @Test
    fun `TPS08 unresolved place meaning retains generic pending text even if a public name is present`() {
        val actual =
            render(
                listOf(
                    PublicPlaceConstraintDescription(
                        FIRST,
                        SchedulePlaceConstraintKind.UNRESOLVED,
                        PUBLIC_ORIGIN,
                        null,
                        null,
                    ),
                ),
            )

        assertEquals(BASE, actual)
    }

    @Test
    fun `TPS09 public summary is stable under declaration order and repeated equivalent declarations`() {
        val declarations =
            listOf(
                travel(FIRST, PUBLIC_ORIGIN, 45),
                travel(SECOND, SECOND_ORIGIN, 30),
                travel(EXCLUDED, EXCLUDED_ORIGIN, 20),
            )

        assertEquals(render(declarations), render(declarations.reversed()))
        assertEquals(render(declarations), render(declarations + declarations))
    }

    @Test
    fun `TPS10 existing summaries remain byte identical when no selected participant has a publishable condition`() {
        assertEquals(BASE, render(emptyList()))
        assertEquals(BASE, render(listOf(travel(EXCLUDED, EXCLUDED_ORIGIN, 20))))
    }

    private fun render(descriptions: List<PublicPlaceConstraintDescription>): String =
        renderer.render(BASE, setOf(FIRST, SECOND), MeetingMode.IN_PERSON, descriptions, Locale.KOREA)

    private fun travel(
        participant: UUID,
        name: String?,
        minutes: Int,
        mode: ScheduleTravelMode? = null,
    ) = PublicPlaceConstraintDescription(participant, SchedulePlaceConstraintKind.TRAVEL_TIME_LIMIT, name, minutes, mode)

    companion object {
        private val FIRST = UUID(0, 1)
        private val SECOND = UUID(0, 2)
        private val EXCLUDED = UUID(0, 3)
        private const val PUBLIC_ORIGIN = "가상공원역"
        private const val SECOND_ORIGIN = "가상호수역"
        private const val EXCLUDED_ORIGIN = "가상언덕역"
        private const val BASE = "2027년 2월 3일 14:00~16:00. 2명의 시간 조건이 맞으며 장소와 실제 대면 참석 여부는 미확정입니다."
    }
}
