package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.OpenAiProperties
import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.precheck.application.port.output.PrecheckResponseSizeContract
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.application.service.ClaimedPrecheck
import com.meetme.server.precheck.application.service.PreSubmissionCheckWorker
import com.meetme.server.precheck.application.service.PrecheckProcessingService
import com.meetme.server.precheck.domain.PrecheckCandidate
import com.meetme.server.precheck.domain.PrecheckExecution
import com.meetme.server.precheck.domain.PrecheckStatus
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Public worker publication tests with synthetic claims and mocked persistence; no DB or provider dispatch. */
class PrecheckResponseSizeContractWorkerTest {
    private val anchor = Instant.parse("2027-02-01T00:00:00Z")
    private val raw = "Synthetic Tuesday availability starts at 8."

    @Test
    fun `WS01 result provider labels and payload metadata cannot upgrade the default legacy cap`() {
        val forged = question(rawBytes = 16_447, payloadBytes = 253)
        publication(PreSubmissionCheckProvider { forged }, forged, allowed = false)
        val legacyBoundary = question(rawBytes = 16_384, payloadBytes = 253)
        publication(PreSubmissionCheckProvider { legacyBoundary }, legacyBoundary, allowed = true)
    }

    @Test
    fun `WS02 trusted bounded generator publishes the unchanged full envelope byte count`() {
        listOf(
            question(rawBytes = 16_447, payloadBytes = 253),
            question(rawBytes = 65_536, payloadBytes = 16_384),
        ).forEach { result ->
            publication(trusted(result), result, allowed = true)
            assertTrue(requireNotNull(result.responseBytes) > 16_384)
        }
    }

    @Test
    fun `WS03 trusted contract does not accept missing negative excessive or contradictory sizes`() {
        listOf(
            question(16_447, null),
            question(null, 253),
            question(-1, 0),
            question(16_447, -1),
            question(65_537, 253),
            question(16_447, 16_385),
            question(10, 11),
            question(512, null),
        ).forEach { result -> publication(trusted(result), result, allowed = false) }
    }

    @Test
    fun `WS04 size authority comes from actual Luna wiring and follows the TwoStage generator`() {
        val legacy = PreSubmissionCheckProvider { PrecheckProviderResult(PrecheckVerdict.CLEAR) }
        assertEquals(PrecheckResponseSizeContract.LEGACY_16_KIB, legacy.responseSizeContract())
        val luna =
            LunaPreSubmissionCheckProvider.runtime(
                OpenAiProperties(apiKey = "synthetic-not-a-credential"),
                JsonMapper.builder().build(),
                PrecheckProviderRuntimeProperties(enabled = true),
            )
        assertEquals(PrecheckResponseSizeContract.LUNA_64_KIB_ENVELOPE_16_KIB_PAYLOAD, luna.responseSizeContract())
        val classifier = AmbiguityClassificationPort { error("Contract lookup must not classify") }
        assertEquals(
            luna.responseSizeContract(),
            TwoStagePreSubmissionCheckProvider(classifier, luna).responseSizeContract(),
        )
        assertEquals(
            PrecheckResponseSizeContract.LEGACY_16_KIB,
            TwoStagePreSubmissionCheckProvider(classifier, legacy).responseSizeContract(),
        )
    }

    @Test
    fun `WS05 classification CLEAR can omit generator sizes but an unsized generated question cannot`() {
        val clear = PrecheckProviderResult(PrecheckVerdict.CLEAR, provider = "SYNTHETIC_CLASSIFIER")
        publication(trusted(clear), clear, allowed = true)
        val unsizedQuestion = question(null, null)
        publication(trusted(unsizedQuestion), unsizedQuestion, allowed = false)
    }

    @Test
    fun `WS06 trusted envelope size never overrides the existing question and source guards`() {
        listOf(
            question(16_447, 253).copy(prompt = "q".repeat(161)),
            question(16_447, 253).copy(sourceExcerpt = "9"),
        ).forEach { result -> publication(trusted(result), result, allowed = false) }
    }

    private fun question(
        rawBytes: Int?,
        payloadBytes: Int?,
    ) = PrecheckProviderResult(
        PrecheckVerdict.NEEDS_CHOICE,
        prompt = "Does 8 mean morning or evening?",
        sourceExcerpt = "8",
        inputTokens = 7,
        outputTokens = 2,
        responseBytes = rawBytes,
        provider = "OPENAI",
        model = "gpt-6-luna",
        responsePayloadBytes = payloadBytes,
    )

    private fun trusted(result: PrecheckProviderResult): PreSubmissionCheckProvider =
        object : PreSubmissionCheckProvider {
            override fun responseSizeContract(): PrecheckResponseSizeContract =
                PrecheckResponseSizeContract.LUNA_64_KIB_ENVELOPE_16_KIB_PAYLOAD

            override fun check(request: PrecheckProviderRequest): PrecheckProviderResult = result
        }

    private fun publication(
        provider: PreSubmissionCheckProvider,
        result: PrecheckProviderResult,
        allowed: Boolean,
    ) {
        val repository = mock(PrecheckRepository::class.java)
        val processing = mock(PrecheckProcessingService::class.java)
        val room =
            MeetingRoom.create(
                MeetingRoomId(UUID(0, 501)),
                InviteCode.fromEntropy(ByteArray(16) { it.toByte() }),
                "synthetic response size",
                MeetingMode.EITHER,
                MeetingTimeZone.of("Asia/Seoul"),
                SearchDateRange.defaultFrom(anchor, MeetingTimeZone.of("Asia/Seoul")),
                ClosurePolicy.of(manualOnly = true),
                anchor,
            )
        val candidate =
            PrecheckCandidate(
                UUID(0, 502),
                room.id.value,
                UUID(0, 503),
                1,
                raw,
                "en-US",
                anchor,
                anchor,
                "synthetic-owner-binding",
                evidence = InputEvidence(1, anchor),
            )
        val check =
            PrecheckExecution(
                UUID(0, 504),
                candidate,
                PrecheckStatus.CHECKING,
                4,
                2,
                0,
                anchor.plusSeconds(30),
                claimedAt = anchor,
            )
        val claim = ClaimedPrecheck(check, room)
        `when`(repository.unfinishedCheckIds(128)).thenReturn(emptyList())
        `when`(repository.queuedCheckIds(128)).thenReturn(listOf(check.id))
        `when`(processing.claim(check.id)).thenReturn(claim)
        val acceptedStatus = if (result.verdict == PrecheckVerdict.CLEAR) PrecheckStatus.CLEAR else PrecheckStatus.NEEDS_CHOICE
        `when`(processing.publish(claim, result, null)).thenReturn(acceptedStatus)
        `when`(processing.publish(claim, null, "INVALID_RESPONSE")).thenReturn(PrecheckStatus.FAILED)
        val worker = PreSubmissionCheckWorker(repository, processing, provider, Clock.fixed(anchor, ZoneOffset.UTC))
        assertTrue(worker.runOne())
        verify(processing).publish(claim, if (allowed) result else null, if (allowed) null else "INVALID_RESPONSE")
    }
}
