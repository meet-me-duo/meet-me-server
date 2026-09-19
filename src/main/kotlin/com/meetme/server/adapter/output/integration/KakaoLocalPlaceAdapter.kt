package com.meetme.server.adapter.output.integration

import com.meetme.server.application.port.output.NormalizedPlaceSnapshot
import com.meetme.server.application.port.output.PlaceNormalizationResult
import com.meetme.server.application.port.output.PlaceSearchException
import com.meetme.server.application.port.output.PlaceSearchFailureKind
import com.meetme.server.application.port.output.PlaceSearchPort
import com.meetme.server.domain.location.GeoCoordinate
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom

data class KakaoLocalProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://dapi.kakao.com",
)

class KakaoLocalPlaceAdapter(
    private val properties: KakaoLocalProperties,
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : PlaceSearchPort {
    private val httpClient: HttpClient = HttpClient.newBuilder().build()

    override fun normalize(query: String): PlaceNormalizationResult {
        if (properties.apiKey.isBlank()) throw PlaceSearchException(PlaceSearchFailureKind.CONFIGURATION)
        require(query.isNotBlank()) { "Place query must not be blank" }

        val deadline = System.nanoTime() + Duration.ofMillis(TOTAL_TIMEOUT_MILLIS.toLong()).toNanos()
        val documents = mutableListOf<KakaoDocument>()
        for (page in 1..MAX_RESULTS / PAGE_SIZE) {
            val response = requestPage(query, page, deadline)
            documents += response.documents
            if (response.isEnd) break
        }

        val normalizedQuery = normalizeName(query)
        val exactMatches = documents.filter { normalizeName(it.placeName) == normalizedQuery }
        return when (exactMatches.size) {
            0 -> PlaceNormalizationResult.NoExactMatch
            1 -> {
                val match = exactMatches.single()
                PlaceNormalizationResult.Resolved(
                    NormalizedPlaceSnapshot(
                        providerPlaceId = match.id,
                        displayName = match.placeName,
                        coordinate = GeoCoordinate.of(BigDecimal(match.latitude), BigDecimal(match.longitude)),
                    ),
                )
            }
            else -> PlaceNormalizationResult.AmbiguousExactMatch
        }
    }

    private fun requestPage(
        query: String,
        page: Int,
        deadlineNanos: Long,
    ): KakaoPage {
        var lastFailure: PlaceSearchException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val remainingMillis = remainingMillis(deadlineNanos)
            if (remainingMillis <= 0) throw PlaceSearchException(PlaceSearchFailureKind.TIMEOUT, lastFailure)
            val request =
                HttpRequest
                    .newBuilder(pageUri(query, page))
                    .timeout(Duration.ofMillis(minOf(CALL_TIMEOUT_MILLIS.toLong(), remainingMillis)))
                    .header("Authorization", "KakaoAK ${properties.apiKey}")
                    .GET()
                    .build()
            try {
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                when {
                    response.statusCode() in 200..299 -> return parsePage(response.body())
                    response.statusCode() == 429 -> lastFailure = PlaceSearchException(PlaceSearchFailureKind.RATE_LIMIT)
                    response.statusCode() >= 500 -> lastFailure = PlaceSearchException(PlaceSearchFailureKind.SERVER)
                    else -> throw PlaceSearchException(PlaceSearchFailureKind.CLIENT)
                }
            } catch (exception: PlaceSearchException) {
                if (exception.kind == PlaceSearchFailureKind.CLIENT || exception.kind == PlaceSearchFailureKind.INVALID_RESPONSE) {
                    throw exception
                }
                lastFailure = exception
            } catch (exception: HttpTimeoutException) {
                lastFailure = PlaceSearchException(PlaceSearchFailureKind.TIMEOUT, exception)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw PlaceSearchException(PlaceSearchFailureKind.TIMEOUT, exception)
            } catch (exception: java.io.IOException) {
                lastFailure = PlaceSearchException(PlaceSearchFailureKind.SERVER, exception)
            }

            if (attempt < MAX_ATTEMPTS - 1) {
                val maximumBackoffMillis = 500L shl attempt
                val sleepMillis = ThreadLocalRandom.current().nextLong(maximumBackoffMillis + 1)
                if (sleepMillis >= remainingMillis(deadlineNanos)) {
                    throw PlaceSearchException(PlaceSearchFailureKind.TIMEOUT, lastFailure)
                }
                try {
                    Thread.sleep(sleepMillis)
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw PlaceSearchException(PlaceSearchFailureKind.TIMEOUT, exception)
                }
            }
        }
        throw requireNotNull(lastFailure)
    }

    private fun parsePage(json: String): KakaoPage =
        try {
            @Suppress("UNCHECKED_CAST")
            val root = objectMapper.readValue(json, Map::class.java) as Map<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val meta = root["meta"] as? Map<String, Any?> ?: invalidResponse()

            @Suppress("UNCHECKED_CAST")
            val documents = root["documents"] as? List<Map<String, Any?>> ?: invalidResponse()
            KakaoPage(
                isEnd = meta["is_end"] as? Boolean ?: invalidResponse(),
                documents =
                    documents.map { document ->
                        KakaoDocument(
                            id = document["id"]?.toString()?.takeIf(String::isNotBlank) ?: invalidResponse(),
                            placeName = document["place_name"]?.toString()?.takeIf(String::isNotBlank) ?: invalidResponse(),
                            longitude = document["x"]?.toString()?.also(::validateCoordinate) ?: invalidResponse(),
                            latitude = document["y"]?.toString()?.also(::validateCoordinate) ?: invalidResponse(),
                        )
                    },
            )
        } catch (exception: PlaceSearchException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw PlaceSearchException(PlaceSearchFailureKind.INVALID_RESPONSE, exception)
        }

    private fun pageUri(
        query: String,
        page: Int,
    ): URI {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        return URI.create(
            "${properties.baseUrl.trimEnd('/')}/v2/local/search/keyword.json?query=$encodedQuery&page=$page&size=$PAGE_SIZE&sort=accuracy",
        )
    }

    private fun normalizeName(value: String): String =
        Normalizer
            .normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filterNot(Char::isWhitespace)

    private fun validateCoordinate(value: String) {
        value.toBigDecimal()
    }

    private fun remainingMillis(deadlineNanos: Long): Long = Duration.ofNanos(deadlineNanos - System.nanoTime()).toMillis()

    private fun invalidResponse(): Nothing = throw PlaceSearchException(PlaceSearchFailureKind.INVALID_RESPONSE)

    private data class KakaoPage(
        val isEnd: Boolean,
        val documents: List<KakaoDocument>,
    )

    private data class KakaoDocument(
        val id: String,
        val placeName: String,
        val longitude: String,
        val latitude: String,
    )

    companion object {
        const val PAGE_SIZE = 15
        const val MAX_RESULTS = 45
        const val CALL_TIMEOUT_MILLIS = 3_000
        const val MAX_ATTEMPTS = 3
        const val TOTAL_TIMEOUT_MILLIS = 15_000
    }
}
