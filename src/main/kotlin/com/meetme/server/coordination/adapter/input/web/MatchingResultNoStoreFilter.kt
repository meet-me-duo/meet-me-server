package com.meetme.server.coordination.adapter.input.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.server.RequestPath
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.pattern.PathPatternParser

/** Keep result semantics tied to the requesting client, including errors before handler execution. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class MatchingResultNoStoreFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = RequestPath.parse(request.requestURI, request.contextPath).pathWithinApplication()
        return RESULT_PATHS.none { it.matches(path) }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        response.setHeader("Cache-Control", "no-store")
        filterChain.doFilter(request, response)
    }

    companion object {
        private val RESULT_PATHS =
            listOf(
                "/api/rooms/{inviteCode}/candidates",
                "/api/rooms/{inviteCode}/recommendations",
                "/api/rooms/{inviteCode}/recommendations/alternatives",
                "/api/rooms/{inviteCode}/candidates/{candidateId}/confirmation",
                "/api/rooms/{inviteCode}/recommendations/{optionId}/confirmation",
                "/api/rooms/{inviteCode}/result",
            ).map { PathPatternParser.defaultInstance.parse(it) }
    }
}
