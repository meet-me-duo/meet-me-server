package com.meetme.server.config

import com.meetme.server.coordination.adapter.output.integration.KakaoLocalPlaceAdapter
import com.meetme.server.coordination.adapter.output.integration.KakaoLocalProperties
import com.meetme.server.coordination.application.port.output.PlaceSearchPort
import com.meetme.server.shared.application.port.output.ApplicationMetricsPort
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("meetme.kakao-local")
data class KakaoLocalConfigurationProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://dapi.kakao.com",
)

@Configuration
@EnableConfigurationProperties(KakaoLocalConfigurationProperties::class)
class KakaoLocalConfiguration {
    @Bean
    fun placeSearchPort(
        properties: KakaoLocalConfigurationProperties,
        metrics: ApplicationMetricsPort,
    ): PlaceSearchPort =
        KakaoLocalPlaceAdapter(
            KakaoLocalProperties(properties.apiKey, properties.baseUrl),
            metrics = metrics,
        )
}
