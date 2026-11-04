package com.meetme.server.meetingroom.adapter.input.web

import com.meetme.server.meetingroom.application.port.input.GetRoomPreviewUseCase
import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

// Public HTML adapter, deliberately outside the REST API and its bearer/cookie interceptors.
@Hidden
@RestController
class RoomPreviewController(
    private val rooms: GetRoomPreviewUseCase,
    private val shell: WebAppShell,
) {
    @GetMapping("/rooms/{*path}", produces = ["text/html;charset=UTF-8"])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "공개 모임 제목 또는 기본 제목을 포함한 Web SPA HTML"),
        ApiResponse(responseCode = "503", description = "현재 Web shell을 안전하게 불러올 수 없음"),
    )
    fun get(
        @PathVariable path: String,
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        val response = render(path)
        return if (request.method == "HEAD") {
            ResponseEntity
                .status(response.statusCode)
                .headers(response.headers)
                .contentLength(
                    response.body
                        .orEmpty()
                        .toByteArray(Charsets.UTF_8)
                        .size
                        .toLong(),
                ).build()
        } else {
            response
        }
    }

    private fun render(path: String): ResponseEntity<String> {
        val html =
            try {
                shell.load().also(::validateShell)
            } catch (_: Exception) {
                logger.warn("Room preview Web shell unavailable")
                return response(503, UNAVAILABLE)
            }
        val code = path.removePrefix("/").takeIf(INVITE_CODE::matches)
        val purpose =
            if (code == null) {
                null
            } else {
                try {
                    rooms.purpose(code)
                } catch (_: Exception) {
                    logger.warn("Room preview name unavailable; serving generic SPA")
                    null
                }
            }
        if (code == null) return response(200, html)
        val title = purpose?.takeIf(String::isNotBlank)?.let { "Meet me - $it" } ?: DEFAULT_TITLE
        val url = "${PublishedWebAppShell.ORIGIN}/rooms/$code"
        val metadata =
            """<meta property="og:title" content="${escape(title)}" />
<meta property="og:url" content="${escape(url)}" />
<meta property="og:type" content="website" />
<link rel="canonical" href="${escape(url)}" />"""
        return response(
            200,
            html.replace(TITLE, "<title>${escape(title)}</title>").replace("</head>", "$metadata\n</head>"),
        )
    }

    private fun response(
        status: Int,
        html: String,
    ): ResponseEntity<String> =
        ResponseEntity
            .status(status)
            .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .body(html)

    private fun validateShell(html: String) {
        check(html.contains(TITLE) && Regex("<title[ >]", RegexOption.IGNORE_CASE).findAll(html).count() == 1)
        check(html.split("</head>").size == 2 && html.contains("<div id=\"root\"></div>"))
        check(!Regex("(?:property=\"og:|rel=\"canonical\")", RegexOption.IGNORE_CASE).containsMatchIn(html))
    }

    private fun escape(value: String): String =
        buildString {
            value.forEach { char ->
                append(
                    when (char) {
                        '&' -> "&amp;"
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        '"' -> "&quot;"
                        '\'' -> "&#39;"
                        else -> char
                    },
                )
            }
        }

    companion object {
        private val logger = LoggerFactory.getLogger(RoomPreviewController::class.java)
        private val INVITE_CODE = Regex("[A-Za-z0-9_-]{22}")
        private const val DEFAULT_TITLE = "Meet me — 스마트 일정 조율"
        private const val TITLE = "<title>$DEFAULT_TITLE</title>"
        private const val UNAVAILABLE =
            "<!doctype html><html lang=\"ko\"><meta charset=\"UTF-8\"><title>$DEFAULT_TITLE</title>" +
                "<p>잠시 후 다시 시도해 주세요.</p></html>"
    }
}

fun interface WebAppShell {
    fun load(): String
}
