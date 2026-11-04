package com.meetme.server.meetingroom.adapter.input.web

import com.meetme.server.meetingroom.application.port.input.GetRoomPreviewUseCase
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomPreviewControllerTest {
    private val first = "abcdefghijklmnopqrstuv"
    private val second = "bcdefghijklmnopqrstuvw"
    private val names = mutableMapOf(first to "가족 모임", second to "친구 모임")
    private val reads = mutableListOf<String>()
    private val mvc =
        MockMvcBuilders
            .standaloneSetup(
                RoomPreviewController(
                    GetRoomPreviewUseCase {
                        reads.add(it)
                        names[it]
                    },
                    WebAppShell { HTML },
                ),
            ).build()

    @Test
    fun `existing rooms get distinct titles and deletion falls back without caching`() {
        val a = html("/rooms/$first")
        assertTrue(a.contains("<title>Meet me - 가족 모임</title>"))
        assertTrue(a.contains("property=\"og:title\" content=\"Meet me - 가족 모임\""))
        assertTrue(a.contains("property=\"og:url\" content=\"https://app.meet-me.co.kr/rooms/$first\""))
        assertTrue(a.contains("rel=\"canonical\" href=\"https://app.meet-me.co.kr/rooms/$first\""))
        assertTrue(html("/rooms/$second").contains("<title>Meet me - 친구 모임</title>"))
        names.remove(first)
        val deleted = html("/rooms/$first")
        assertTrue(deleted.contains("<title>Meet me — 스마트 일정 조율</title>"))
        assertFalse(deleted.contains("가족 모임"))
        assertEquals(listOf(first, second, first), reads)
    }

    @Test
    fun `purpose is escaped for HTML text and quoted attributes without exposing data or adding images`() {
        names[first] = "가족 <script>alert(1)</script> & \"친구\" '모임'"
        val response = html("/rooms/$first")
        val escaped = "가족 &lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;친구&quot; &#39;모임&#39;"
        assertTrue(response.contains("<title>Meet me - $escaped</title>"))
        assertTrue(response.contains("content=\"Meet me - $escaped\""))
        assertFalse(response.contains("<script>alert(1)</script>"))
        assertFalse(response.contains("og:image"))
        assertFalse(response.contains("participant"))
        assertFalse(response.contains("raw_text"))
    }

    @Test
    fun `browser and crawler get the same SPA and trusted canonical regardless headers or query`() {
        val expected = html("/rooms/$first")
        val actual =
            mvc
                .perform(
                    get("/rooms/$first?redirect=https://evil.example")
                        .header("User-Agent", "facebookexternalhit/1.1 Kakao")
                        .header("Host", "evil.example")
                        .header("X-Forwarded-Host", "evil.example")
                        .header("X-Forwarded-Proto", "http")
                        .header("Authorization", "Bearer ignored")
                        .header("Cookie", "meetme_guest=ignored"),
                ).andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andReturn()
                .response.contentAsString
        assertEquals(expected, actual)
        assertTrue(actual.contains("<div id=\"root\"></div>"))
        assertTrue(actual.contains("src=\"/assets/index-abc.js\""))
        assertTrue(actual.contains("href=\"/assets/index-def.css\""))
        assertTrue(actual.contains("name=\"description\" content=\"공통 설명\""))
    }

    @Test
    fun `invalid missing and nested routes keep generic SPA with no invalid room queries`() {
        for (path in listOf("/rooms/", "/rooms/bad", "/rooms/$first/nested", "/rooms/$first/")) {
            assertTrue(html(path).contains("<title>Meet me — 스마트 일정 조율</title>"))
        }
        assertTrue(reads.isEmpty())
        assertTrue(html("/rooms/zyxwvutsrqponmlkjihgfe").contains("<title>Meet me — 스마트 일정 조율</title>"))
        assertEquals(listOf("zyxwvutsrqponmlkjihgfe"), reads)
    }

    @Test
    fun `HEAD matches GET status type and cache headers without a body`() {
        mvc
            .perform(head("/rooms/$first"))
            .andExpect(status().isOk)
            .andExpect(content().contentType("text/html;charset=UTF-8"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(content().string(""))
    }

    @Test
    fun `metadata lookup failure preserves generic SPA without caching or error details`() {
        val response =
            MockMvcBuilders
                .standaloneSetup(
                    RoomPreviewController(GetRoomPreviewUseCase { error("database details") }, WebAppShell { HTML }),
                ).build()
                .perform(get("/rooms/$first"))
                .andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn()
                .response.contentAsString
        assertTrue(response.contains("<title>Meet me — 스마트 일정 조율</title>"))
        assertTrue(response.contains("<div id=\"root\"></div>"))
        assertFalse(response.contains("database"))
    }

    @Test
    fun `shell failures return bounded generic unavailable response with no-store`() {
        for (controller in listOf(
            RoomPreviewController(GetRoomPreviewUseCase { "가족 모임" }, WebAppShell { error("private upstream details") }),
            RoomPreviewController(GetRoomPreviewUseCase { "가족 모임" }, WebAppShell { "<html>broken</html>" }),
            RoomPreviewController(GetRoomPreviewUseCase { "가족 모임" }, WebAppShell { HTML.replace("</head>", "") }),
            RoomPreviewController(
                GetRoomPreviewUseCase { "가족 모임" },
                WebAppShell { HTML.replace("<title>", "<title>duplicate</title><title>") },
            ),
            RoomPreviewController(GetRoomPreviewUseCase { "가족 모임" }, WebAppShell { HTML.replace("<div id=\"root\"></div>", "") }),
        )) {
            val response =
                MockMvcBuilders
                    .standaloneSetup(controller)
                    .build()
                    .perform(get("/rooms/$first"))
                    .andExpect(status().isServiceUnavailable)
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andReturn()
                    .response.contentAsString
            assertFalse(response.contains("private"))
            assertFalse(response.contains("database"))
            assertFalse(response.contains("가족 모임"))
        }
    }

    private fun html(path: String): String =
        mvc
            .perform(get(path))
            .andExpect(status().isOk)
            .andExpect(content().contentType("text/html;charset=UTF-8"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn()
            .response.contentAsString

    companion object {
        const val HTML = """<!doctype html><html lang="ko"><head><meta charset="UTF-8">
<meta name="description" content="공통 설명"><title>Meet me — 스마트 일정 조율</title>
<link rel="stylesheet" href="/assets/index-def.css"></head><body><div id="root"></div>
<script type="module" src="/assets/index-abc.js"></script></body></html>"""
    }
}
