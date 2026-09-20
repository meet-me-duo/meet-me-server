package com.meetme.server.config

import com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class AnonymousWebSecurityConfiguration(
    private val properties: AnonymousAccessProperties,
) : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        registry
            .addMapping("/api/**")
            .allowedOrigins(*properties.allowedOrigins.toTypedArray())
            .allowedMethods("GET", "POST", "PUT", "OPTIONS")
            .allowedHeaders("Content-Type", "Accept-Language")
            .allowCredentials(true)
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(ExactOriginInterceptor(properties.allowedOrigins)).addPathPatterns("/api/rooms", "/api/rooms/**")
    }
}

private class ExactOriginInterceptor(
    private val allowedOrigins: List<String>,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (request.method == "GET" || request.method == "OPTIONS") {
            return true
        }
        val origin = request.getHeader("Origin")
        if (origin == null || origin !in allowedOrigins) {
            throw RoomLifecycleException(RoomLifecycleErrorCode.ORIGIN_NOT_ALLOWED)
        }
        return true
    }
}
