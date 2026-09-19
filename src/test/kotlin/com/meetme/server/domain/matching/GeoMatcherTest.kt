package com.meetme.server.domain.matching

import com.meetme.server.domain.location.GeoCoordinate
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeoMatcherTest {
    @Test
    fun `Haversine 거리로 위도 1도의 거리를 계산한다`() {
        val distance = GeoMatcher.distanceMeters(coordinate("0", "0"), coordinate("1", "0"))

        assertTrue(abs(distance - 111_195.0) < 150.0)
    }

    @Test
    fun `명시 반경 안의 지점만 허용 원에 포함한다`() {
        val region = ParticipantAllowedRegion(listOf(AllowedCircle(coordinate("0", "0"), radiusMeters = 500)))

        assertTrue(GeoMatcher.contains(region, coordinate("0", "0.004")))
        assertFalse(GeoMatcher.contains(region, coordinate("0", "0.005")))
    }

    @Test
    fun `반경을 생략한 장소에는 기본 1km를 적용한다`() {
        val defaultCircle = AllowedCircle(coordinate("0", "0"))

        assertTrue(GeoMatcher.contains(ParticipantAllowedRegion(listOf(defaultCircle)), coordinate("0", "0.008")))
        assertFalse(GeoMatcher.contains(ParticipantAllowedRegion(listOf(defaultCircle)), coordinate("0", "0.010")))
    }

    @Test
    fun `한 참여자의 대안 장소는 허용 원의 합집합이다`() {
        val alternatives =
            ParticipantAllowedRegion(
                listOf(
                    AllowedCircle(coordinate("0", "0"), radiusMeters = 300),
                    AllowedCircle(coordinate("0", "0.02"), radiusMeters = 300),
                ),
            )

        assertTrue(GeoMatcher.contains(alternatives, coordinate("0", "0.02")))
    }

    @Test
    fun `참여자별 대안 장소 합집합 중 공통인 원에서 대표점을 찾는다`() {
        val first =
            ParticipantAllowedRegion(
                listOf(
                    AllowedCircle(coordinate("0", "0"), radiusMeters = 300),
                    AllowedCircle(coordinate("0", "0.02"), radiusMeters = 1_000),
                ),
            )
        val second = ParticipantAllowedRegion(listOf(AllowedCircle(coordinate("0", "0.025"), radiusMeters = 1_000)))

        val representative = assertNotNull(GeoMatcher.representativePoint(listOf(first, second)))

        assertTrue(GeoMatcher.contains(first, representative))
        assertTrue(GeoMatcher.contains(second, representative))
    }

    @Test
    fun `여러 참여자의 허용 영역 교집합이 없으면 대표점을 만들지 않는다`() {
        val first = ParticipantAllowedRegion(listOf(AllowedCircle(coordinate("0", "0"), radiusMeters = 300)))
        val second = ParticipantAllowedRegion(listOf(AllowedCircle(coordinate("0", "0.02"), radiusMeters = 300)))

        assertNull(GeoMatcher.representativePoint(listOf(first, second)))
    }

    @Test
    fun `교집합 대표점은 최소 반경 여유를 최대화하는 결정론적 내부 지점이다`() {
        val first = ParticipantAllowedRegion(listOf(AllowedCircle(coordinate("0", "0"), radiusMeters = 1_000)))
        val second = ParticipantAllowedRegion(listOf(AllowedCircle(coordinate("0", "0.01"), radiusMeters = 1_000)))

        val representative = assertNotNull(GeoMatcher.representativePoint(listOf(first, second)))
        val firstDistance = GeoMatcher.distanceMeters(first.alternatives.single().center, representative)
        val secondDistance = GeoMatcher.distanceMeters(second.alternatives.single().center, representative)

        assertTrue(abs(firstDistance - secondDistance) < 1.0)
        assertTrue(abs(firstDistance - 555.98) < 2.0)
    }

    @Test
    fun `봉천역과 서울대입구역 기본 반경의 공통 영역에 대표점을 만든다`() {
        val bongcheon = ParticipantAllowedRegion(listOf(AllowedCircle(coordinate("37.482416", "126.941896"))))
        val seoulNationalUniversity = ParticipantAllowedRegion(listOf(AllowedCircle(coordinate("37.481210", "126.952712"))))

        val representative = assertNotNull(GeoMatcher.representativePoint(listOf(bongcheon, seoulNationalUniversity)))

        assertTrue(GeoMatcher.contains(bongcheon, representative))
        assertTrue(GeoMatcher.contains(seoulNationalUniversity, representative))
    }

    private fun coordinate(
        latitude: String,
        longitude: String,
    ) = GeoCoordinate.of(BigDecimal(latitude), BigDecimal(longitude))
}
