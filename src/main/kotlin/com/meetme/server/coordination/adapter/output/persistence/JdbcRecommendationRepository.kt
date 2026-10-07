package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.output.RecommendationQuery
import com.meetme.server.coordination.application.port.output.RecommendationRepository
import com.meetme.server.coordination.domain.RecommendationAnalysis
import com.meetme.server.coordination.domain.RecommendationSelection
import com.meetme.server.coordination.domain.StoredRecommendationOption
import com.meetme.server.coordination.domain.location.GeoCoordinate
import com.meetme.server.coordination.domain.matching.CompatiblePlaceArea
import com.meetme.server.coordination.domain.matching.RecommendationOption
import com.meetme.server.coordination.domain.matching.RecommendationVariant
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcRecommendationRepository(
    private val jdbc: JdbcTemplate,
) : RecommendationRepository {
    override fun publish(analysis: RecommendationAnalysis) {
        jdbc.update(
            "INSERT INTO recommendation_analyses(coordination_run_id, room_id, protocol) VALUES (?, ?, ?)",
            analysis.analysisId,
            analysis.roomId,
            analysis.protocol,
        )
        if (analysis.options.isEmpty()) return
        jdbc.batchUpdate(
            "INSERT INTO recommendation_options(id, coordination_run_id, room_id, ordinal, primary_rank, start_at, end_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            analysis.options.map {
                arrayOf<Any?>(
                    it.id,
                    analysis.analysisId,
                    analysis.roomId,
                    it.ordinal,
                    it.primaryRank,
                    it.option.window.startInclusive
                        .atOffset(ZoneOffset.UTC),
                    it.option.window.endExclusive
                        .atOffset(ZoneOffset.UTC),
                )
            },
        )
        val variants =
            analysis.options.flatMap { option ->
                option.option.variants.mapIndexed { index, variant -> Triple(option, option.variantIds[index], variant) }
            }
        jdbc.batchUpdate(
            "INSERT INTO recommendation_variants(id, option_id, coordination_run_id, room_id, meeting_mode, " +
                "area_key, place_name, latitude, longitude, preference_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            variants.map { (option, id, variant) ->
                arrayOf<Any?>(
                    id,
                    option.id,
                    analysis.analysisId,
                    analysis.roomId,
                    variant.meetingMode.name,
                    variant.representativeArea?.key,
                    variant.representativeArea?.displayName ?: variant.representativePlace?.let { "공통 가능 지역" },
                    variant.representativePlace?.latitude,
                    variant.representativePlace?.longitude,
                    variant.preferenceCount,
                )
            },
        )
        variants
            .asSequence()
            .flatMap { (option, id, variant) ->
                variant.participantIds.asSequence().map { participant ->
                    arrayOf<Any?>(id, option.id, analysis.analysisId, analysis.roomId, participant.value)
                }
            }.chunked(500)
            .forEach { arguments ->
                jdbc.batchUpdate(
                    "INSERT INTO recommendation_variant_participants(" +
                        "variant_id, option_id, coordination_run_id, room_id, participant_id) " +
                        "VALUES (?, ?, ?, ?, ?)",
                    arguments,
                )
            }
    }

    override fun find(
        analysisId: UUID,
        query: RecommendationQuery,
    ): RecommendationAnalysis? {
        val metadata =
            jdbc
                .queryForList(
                    "SELECT a.room_id, a.protocol, count(o.id) AS total_count, " +
                        "count(o.id) FILTER (WHERE o.primary_rank IS NULL) AS alternative_count " +
                        "FROM recommendation_analyses a LEFT JOIN recommendation_options o " +
                        "ON o.coordination_run_id = a.coordination_run_id " +
                        "WHERE a.coordination_run_id = ? GROUP BY a.room_id, a.protocol",
                    analysisId,
                ).firstOrNull() ?: return null
        val parameters = mutableListOf<Any>(analysisId)
        val filters = mutableListOf("coordination_run_id = ?")
        if (query.primaryOnly) filters += "primary_rank IS NOT NULL"
        if (query.alternativesOnly) filters += "primary_rank IS NULL"
        if (query.afterOrdinal >= 0) {
            filters += "ordinal > ?"
            parameters += query.afterOrdinal
        }
        query.optionId?.let {
            filters += "id = ?"
            parameters += it
        }
        val limit =
            query.limit
                ?.let {
                    require(it in 1..101)
                    parameters += it
                    " LIMIT ?"
                }.orEmpty()
        val options =
            jdbc.query(
                "SELECT * FROM recommendation_options WHERE " + filters.joinToString(" AND ") + " ORDER BY ordinal" + limit,
                { rs, _ ->
                    StoredRecommendationOption(
                        rs.getObject("id", UUID::class.java),
                        rs.getInt("ordinal"),
                        (rs.getObject("primary_rank") as? Number)?.toInt(),
                        RecommendationOption(
                            InstantTimeRange(
                                rs.getObject("start_at", OffsetDateTime::class.java).toInstant(),
                                rs.getObject("end_at", OffsetDateTime::class.java).toInstant(),
                            ),
                            emptyList(),
                        ),
                        emptyList(),
                    )
                },
                *parameters.toTypedArray(),
            )
        val selectedIds = options.map { it.id }

        fun analysis(values: List<StoredRecommendationOption>) =
            RecommendationAnalysis(
                analysisId,
                metadata.getValue("room_id") as UUID,
                values,
                metadata.getValue("protocol") as String,
                (metadata.getValue("total_count") as Number).toInt(),
                (metadata.getValue("alternative_count") as Number).toInt(),
            )
        if (selectedIds.isEmpty()) return analysis(emptyList())
        val selectedFilter = "coordination_run_id = ? AND option_id IN (" + selectedIds.joinToString { "?" } + ")"
        val selectedParameters = arrayOf<Any>(analysisId, *selectedIds.toTypedArray())
        val participants =
            jdbc
                .query(
                    "SELECT variant_id, participant_id FROM recommendation_variant_participants " +
                        "WHERE " + selectedFilter + " ORDER BY participant_id::text",
                    { rs, _ ->
                        rs.getObject("variant_id", UUID::class.java) to
                            ParticipantId(rs.getObject("participant_id", UUID::class.java))
                    },
                    *selectedParameters,
                ).groupBy({ it.first }, { it.second })
        val variants =
            jdbc
                .query(
                    "SELECT * FROM recommendation_variants WHERE " + selectedFilter + " ORDER BY id::text",
                    { rs, _ ->
                        val id = rs.getObject("id", UUID::class.java)
                        val name = rs.getString("place_name")
                        val area = rs.getString("area_key")?.let { CompatiblePlaceArea(it, requireNotNull(name)) }
                        val coordinate = rs.getBigDecimal("latitude")?.let { GeoCoordinate.of(it, rs.getBigDecimal("longitude")) }
                        Triple(
                            rs.getObject("option_id", UUID::class.java),
                            id,
                            RecommendationVariant(
                                MeetingMode.valueOf(rs.getString("meeting_mode")),
                                participants[id].orEmpty(),
                                area,
                                coordinate,
                                rs.getInt("preference_count"),
                            ),
                        )
                    },
                    *selectedParameters,
                ).groupBy { it.first }
        return analysis(
            options.map { option ->
                val values =
                    variants[option.id].orEmpty().sortedWith(
                        compareByDescending<Triple<UUID, UUID, RecommendationVariant>> { it.third.participantIds.size }
                            .thenByDescending { it.third.preferenceCount }
                            .thenBy { it.third.participantIds.joinToString("|") { participant -> participant.value.toString() } }
                            .thenBy { it.third.meetingMode.name }
                            .thenBy {
                                it.third.representativeArea
                                    ?.key
                                    .orEmpty()
                            },
                    )
                option.copy(option = option.option.copy(variants = values.map { it.third }), variantIds = values.map { it.second })
            },
        )
    }

    override fun containsAlternativeOrdinal(
        analysisId: UUID,
        ordinal: Int,
    ): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM recommendation_options WHERE coordination_run_id = ? AND ordinal = ? " +
                "AND primary_rank IS NULL)",
            Boolean::class.java,
            analysisId,
            ordinal,
        ) == true

    override fun findSelection(analysisId: UUID): RecommendationSelection? =
        jdbc
            .query(
                "SELECT * FROM recommendation_selections WHERE coordination_run_id = ?",
                { rs, _ ->
                    RecommendationSelection(
                        analysisId,
                        rs.getObject("option_id", UUID::class.java),
                        rs.getObject("variant_id", UUID::class.java),
                        InstantTimeRange(
                            rs.getObject("start_at", OffsetDateTime::class.java).toInstant(),
                            rs.getObject("end_at", OffsetDateTime::class.java).toInstant(),
                        ),
                        rs.getObject("confirmed_at", OffsetDateTime::class.java).toInstant(),
                    )
                },
                analysisId,
            ).firstOrNull()

    override fun insertSelection(selection: RecommendationSelection) {
        jdbc
            .update(
                "INSERT INTO recommendation_selections(coordination_run_id, room_id, option_id, variant_id, " +
                    "start_at, end_at, confirmed_at) " +
                    "SELECT ?, room_id, ?, ?, ?, ?, ? FROM recommendation_analyses WHERE coordination_run_id = ?",
                selection.analysisId,
                selection.optionId,
                selection.variantId,
                selection.selectedWindow.startInclusive.atOffset(ZoneOffset.UTC),
                selection.selectedWindow.endExclusive.atOffset(ZoneOffset.UTC),
                selection.confirmedAt.atOffset(ZoneOffset.UTC),
                selection.analysisId,
            ).also { check(it == 1) }
    }
}
