package com.meetme.server.domain.matching

import com.meetme.server.domain.location.GeoCoordinate
import java.math.BigDecimal
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class AllowedCircle(
    val center: GeoCoordinate,
    val radiusMeters: Int = 1_000,
) {
    init {
        require(radiusMeters > 0) { "Radius must be positive" }
    }
}

data class ParticipantAllowedRegion(
    val alternatives: List<AllowedCircle>,
) {
    init {
        require(alternatives.isNotEmpty()) { "At least one place alternative is required" }
    }
}

object GeoMatcher {
    fun distanceMeters(
        first: GeoCoordinate,
        second: GeoCoordinate,
    ): Double {
        val firstLatitude = first.latitude.toDouble().toRadians()
        val secondLatitude = second.latitude.toDouble().toRadians()
        val latitudeDelta = secondLatitude - firstLatitude
        val longitudeDelta = (second.longitude.toDouble() - first.longitude.toDouble()).toRadians()
        val haversine =
            sin(latitudeDelta / 2).let { it * it } +
                cos(firstLatitude) * cos(secondLatitude) * sin(longitudeDelta / 2).let { it * it }
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(haversine), sqrt(max(0.0, 1.0 - haversine)))
    }

    fun contains(
        region: ParticipantAllowedRegion,
        point: GeoCoordinate,
    ): Boolean = region.alternatives.any { distanceMeters(it.center, point) <= it.radiusMeters + EPSILON_METERS }

    fun representativePoint(regions: List<ParticipantAllowedRegion>): GeoCoordinate? {
        if (regions.isEmpty()) return null
        val allCircles = regions.flatMap { it.alternatives }
        var low = 0.0
        var high = allCircles.maxOf { it.radiusMeters }.toDouble()
        var best = feasiblePoint(regions, low) ?: return null

        repeat(24) {
            val margin = (low + high) / 2.0
            val feasible = feasiblePoint(regions, margin)
            if (feasible == null) {
                high = margin
            } else {
                low = margin
                best = feasible
            }
        }
        return best
    }

    private fun feasiblePoint(
        regions: List<ParticipantAllowedRegion>,
        margin: Double,
    ): GeoCoordinate? {
        val circles =
            regions.flatMap { region ->
                region.alternatives.mapNotNull { circle ->
                    val radius = circle.radiusMeters - margin
                    if (radius < -EPSILON_METERS) null else EffectiveCircle(circle.center, max(0.0, radius))
                }
            }
        if (circles.isEmpty()) return null

        val referenceLatitude = circles.map { it.center.latitude.toDouble() }.average().toRadians()
        val projected = circles.map { it.toProjected(referenceLatitude) }
        val candidates = mutableListOf<ProjectedPoint>()
        candidates += projected.map { ProjectedPoint(it.x, it.y) }
        for (firstIndex in projected.indices) {
            for (secondIndex in firstIndex + 1 until projected.size) {
                candidates += circleIntersections(projected[firstIndex], projected[secondIndex])
            }
        }

        return candidates
            .asSequence()
            .map { it.toCoordinate(referenceLatitude) }
            .filter { point ->
                regions.all { region ->
                    region.alternatives.any { circle ->
                        distanceMeters(circle.center, point) <= circle.radiusMeters - margin + EPSILON_METERS
                    }
                }
            }.sortedWith(compareBy({ it.latitude }, { it.longitude }))
            .firstOrNull()
    }

    private fun circleIntersections(
        first: ProjectedCircle,
        second: ProjectedCircle,
    ): List<ProjectedPoint> {
        val deltaX = second.x - first.x
        val deltaY = second.y - first.y
        val distance = sqrt(deltaX * deltaX + deltaY * deltaY)
        if (distance <= EPSILON_METERS) return emptyList()
        if (distance > first.radius + second.radius + EPSILON_METERS) return emptyList()
        if (distance < kotlin.math.abs(first.radius - second.radius) - EPSILON_METERS) return emptyList()

        val along = (first.radius * first.radius - second.radius * second.radius + distance * distance) / (2 * distance)
        val heightSquared = max(0.0, first.radius * first.radius - along * along)
        val height = sqrt(heightSquared)
        val baseX = first.x + along * deltaX / distance
        val baseY = first.y + along * deltaY / distance
        val offsetX = -deltaY * height / distance
        val offsetY = deltaX * height / distance
        return listOf(
            ProjectedPoint(baseX + offsetX, baseY + offsetY),
            ProjectedPoint(baseX - offsetX, baseY - offsetY),
        ).distinct()
    }

    private data class EffectiveCircle(
        val center: GeoCoordinate,
        val radius: Double,
    ) {
        fun toProjected(referenceLatitude: Double): ProjectedCircle =
            ProjectedCircle(
                x = EARTH_RADIUS_METERS * center.longitude.toDouble().toRadians() * cos(referenceLatitude),
                y = EARTH_RADIUS_METERS * center.latitude.toDouble().toRadians(),
                radius = radius,
            )
    }

    private data class ProjectedCircle(
        val x: Double,
        val y: Double,
        val radius: Double,
    )

    private data class ProjectedPoint(
        val x: Double,
        val y: Double,
    ) {
        fun toCoordinate(referenceLatitude: Double): GeoCoordinate =
            GeoCoordinate.of(
                BigDecimal.valueOf((y / EARTH_RADIUS_METERS).toDegrees()),
                BigDecimal.valueOf((x / (EARTH_RADIUS_METERS * cos(referenceLatitude))).toDegrees()),
            )
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    private fun Double.toDegrees(): Double = this * 180.0 / PI

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val EPSILON_METERS = 0.02
}
