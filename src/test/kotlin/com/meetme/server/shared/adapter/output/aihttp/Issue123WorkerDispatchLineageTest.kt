package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.application.service.ClaimedPrecheck
import com.meetme.server.precheck.application.service.PreSubmissionCheckWorker
import com.meetme.server.precheck.application.service.PrecheckProcessingService
import com.meetme.server.precheck.domain.PrecheckCandidate
import com.meetme.server.precheck.domain.PrecheckExecution
import com.meetme.server.precheck.domain.PrecheckStatus
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiDispatchModePort
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual worker request propagation only; mocked locked claim is not a PG auth/accounting proof. */
class Issue123WorkerDispatchLineageTest {
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = ["ON", "OFF", "ABSENT"])
    fun `OR01 worker binds immutable claimed execution and original anchor only when mode is ON`(mode: String) {
        val repository = mock(PrecheckRepository::class.java)
        val processing = mock(PrecheckProcessingService::class.java)
        val zone = MeetingTimeZone.of("Asia/Seoul")
        val room =
            MeetingRoom.create(
                MeetingRoomId(UUID(0, 123)),
                InviteCode.fromEntropy(ByteArray(16) { it.toByte() }),
                "Synthetic lineage",
                MeetingMode.EITHER,
                zone,
                SearchDateRange.defaultFrom(dispatchNow, zone),
                ClosurePolicy.of(manualOnly = true),
                dispatchNow,
            )
        val candidate =
            PrecheckCandidate(
                UUID(0, 201),
                room.id.value,
                UUID(0, 202),
                9,
                "  😀 exact original  ",
                "ko-KR",
                dispatchNow,
                dispatchNow.minusSeconds(3600),
                "synthetic-owner-binding",
            )
        val check =
            PrecheckExecution(
                UUID(0, 203),
                candidate,
                PrecheckStatus.CHECKING,
                11,
                3,
                0,
                dispatchNow.plusSeconds(30),
                ownerToken = UUID(0, 204),
                claimedAt = dispatchNow,
            )
        val claim = ClaimedPrecheck(check, room)
        val terminal = PrecheckProviderResult(PrecheckVerdict.CLEAR)
        `when`(repository.unfinishedCheckIds(128)).thenReturn(emptyList())
        `when`(repository.queuedCheckIds(128)).thenReturn(listOf(check.id))
        `when`(processing.claim(check.id)).thenReturn(claim)
        `when`(processing.publish(claim, terminal, null)).thenReturn(PrecheckStatus.CLEAR)
        var received: PrecheckProviderRequest? = null
        var providerEnteredAt = 0L
        val provider =
            PreSubmissionCheckProvider { request ->
                providerEnteredAt = System.nanoTime()
                received = request
                terminal
            }
        val worker =
            PreSubmissionCheckWorker(
                repository,
                processing,
                provider,
                Clock.fixed(dispatchNow, ZoneOffset.UTC),
                dispatchMode =
                    if (mode ==
                        "ABSENT"
                    ) {
                        null
                    } else {
                        AiDispatchModePort { mode == "ON" }
                    },
            )
        assertTrue(worker.runOne())
        verify(processing).publish(claim, terminal, null)
        val request = assertNotNull(received)
        assertEquals(candidate.rawText, request.rawText)
        assertEquals(candidate.referenceAt, request.referenceAt)
        assertEquals(Duration.ofSeconds(15), request.timeout)
        assertEquals(room.id.value, request.roomId)
        assertEquals(check.generation, request.executionGeneration)
        if (mode != "ON") {
            assertNull(request.dispatchContext)
            return
        }
        val context = assertNotNull(request.dispatchContext)
        assertEquals(AiBudgetExecutionKind.PRECHECK, context.execution.kind)
        assertEquals(room.id.value, context.execution.roomId)
        assertEquals(check.id, context.execution.executionId)
        assertEquals(check.generation.toLong(), context.execution.generation)
        assertEquals(check.version, context.execution.version)
        assertEquals(check.ownerToken, context.execution.ownerToken)
        assertEquals(dispatchFramedHash(candidate.rawText), context.execution.sourceFingerprint)
        assertEquals(
            dispatchFramedHash(
                candidate.referenceAt.toString(),
                zone.value.id,
                candidate.locale,
                room.searchRange.startInclusive.toString(),
                room.searchRange.endExclusive.toString(),
            ),
            context.execution.contextFingerprint,
        )
        assertNull(context.finalAttemptId)
        assertEquals(1, context.providerOrdinal)
        assertTrue(
            context.deadlineNanos - providerEnteredAt in 1..Duration.ofSeconds(15).toNanos(),
            "No new provider deadline after work/claim",
        )
    }
}
