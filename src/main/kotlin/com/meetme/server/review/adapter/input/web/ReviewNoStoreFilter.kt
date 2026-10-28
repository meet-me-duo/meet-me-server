package com.meetme.server.review.adapter.input.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Apply the private acknowledgment cache boundary before authentication and body decoding. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ReviewNoStoreFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !REVIEW_PATH.matches(request.requestURI.removePrefix(request.contextPath))

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        response.setHeader("Cache-Control", "no-store")
        filterChain.doFilter(request, response)
    }

    companion object {
        private val REVIEW_PATH = Regex("^/api/rooms/[^/]+/review-prompt(?:/.*)?$")
    }
}
