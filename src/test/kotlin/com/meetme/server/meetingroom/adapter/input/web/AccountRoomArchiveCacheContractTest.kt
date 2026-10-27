package com.meetme.server.meetingroom.adapter.input.web

import com.meetme.server.meetingroom.application.port.input.AccountRoomsResult
import com.meetme.server.meetingroom.application.port.input.RoomOwnershipUseCase
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** #109: personal archive content must never be stored by a browser or intermediary cache. */
class AccountRoomArchiveCacheContractTest {
    @Test
    fun `account room archive explicitly forbids response caching`() {
        val ownership = mock(RoomOwnershipUseCase::class.java)
        `when`(ownership.accountRooms()).thenReturn(AccountRoomsResult(emptyList()))
        MockMvcBuilders
            .standaloneSetup(RoomOwnershipController(ownership))
            .build()
            .perform(get("/api/account/rooms"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
    }
}
