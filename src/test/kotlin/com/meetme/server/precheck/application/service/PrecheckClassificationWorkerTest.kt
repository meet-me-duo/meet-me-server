package com.meetme.server.precheck.application.service

import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.precheck.adapter.output.provider.ClassificationCaptureFixture
import com.meetme.server.precheck.application.port.output.AmbiguityAssessment
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckClassificationSnapshot
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.domain.PrecheckCandidate
import com.meetme.server.precheck.domain.PrecheckExecution
import com.meetme.server.precheck.domain.PrecheckStatus
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Issue241: capture is local to a claim and survives result invalidation until normal attempt finalization. */
class PrecheckClassificationWorkerTest {
    @Test
    fun `PCW01 worker supplies an attempt-local capture and publishes metadata separately from the unchanged terminal result`() {
        val terminal = PrecheckProviderResult(PrecheckVerdict.CLEAR, provider = "SYNTHETIC_GENERATOR", model = "terminal-model")
        val f =
            WorkerFixture { request ->
                recordFirst(request)
                terminal
            }
        assertTrue(f.worker.runOne())
        assertNotNull(f.requests.single().classificationCapture)
        val publication = f.publications.single()
        assertSame(terminal, publication.result)
        assertNull(publication.failure)
        assertEquals(expected(), publication.classification)
        assertEquals(listOf("provider-start", "provider-end", "publish"), f.events)
    }

    @ParameterizedTest
    @ValueSource(strings = ["TIMEOUT", "INVALID_RESPONSE", "RATE_LIMITED", "CONFIGURATION", "REFUSED", "BUDGET_DENIED"])
    fun `PCW02 provider failures keep first classification while retaining existing worker failure-code normalization`(code: String) {
        val f =
            WorkerFixture { request ->
                recordFirst(request)
                throw PrecheckProviderException(code)
            }
        assertTrue(f.worker.runOne())
        val publication = f.publications.single()
        assertNull(publication.result)
        assertEquals(if (code in setOf("REFUSED", "BUDGET_DENIED")) "PROVIDER_UNAVAILABLE" else code, publication.failure)
        assertEquals(expected(), publication.classification)
        assertEquals(1, f.requests.size)
    }

    @Test
    fun `PCW03 worker response validation may discard the terminal result but never its known first-stage diagnostic`() {
        val f =
            WorkerFixture { request ->
                recordFirst(request)
                PrecheckProviderResult(PrecheckVerdict.CLEAR, responseBytes = -1)
            }
        assertTrue(f.worker.runOne())
        val publication = f.publications.single()
        assertNull(publication.result)
        assertEquals("INVALID_RESPONSE", publication.failure)
        assertEquals(expected(), publication.classification)
    }

    @Test
    fun `PCW04 publication-expired fallback receives the same first classification and original result without another model call`() {
        val terminal = PrecheckProviderResult(PrecheckVerdict.CLEAR)
        val f =
            WorkerFixture { request ->
                recordFirst(request)
                terminal
            }
        f.rejectPublication = true
        assertTrue(f.worker.runOne())
        assertEquals(1, f.requests.size)
        assertEquals(2, f.publications.size)
        assertEquals(listOf(false, true), f.publications.map { it.rejected })
        f.publications.forEach { publication ->
            assertSame(terminal, publication.result)
            assertNull(publication.failure)
            assertEquals(expected(), publication.classification)
        }
    }

    @Test
    fun `PCW05 a final CLEAR from a provider with no first-stage record must remain unrecorded`() {
        val terminal = PrecheckProviderResult(PrecheckVerdict.CLEAR)
        val f = WorkerFixture { terminal }
        assertTrue(f.worker.runOne())
        val publication = f.publications.single()
        assertSame(terminal, publication.result)
        assertNull(publication.classification)
    }

    @Test
    fun `PCW06 consecutive attempts on one worker never reuse a previous persons classification capture`() {
        var index = 0
        val f =
            WorkerFixture(attempts = 2) { request ->
                if (index++ == 0) recordFirst(request)
                PrecheckProviderResult(PrecheckVerdict.CLEAR)
            }
        assertTrue(f.worker.runOne())
        assertTrue(f.worker.runOne())
        assertEquals(2, f.requests.size)
        assertNotSame(assertNotNull(f.requests[0].classificationCapture), assertNotNull(f.requests[1].classificationCapture))
        assertEquals(expected(), f.publications[0].classification)
        assertNull(f.publications[1].classification)
        assertEquals(f.claims.map { it.check.id }, f.publications.map { it.claim.check.id })
    }

    @Test
    fun `PCW07 unexpected later provider failure keeps the observation and does not expose its message as a code`() {
        val f =
            WorkerFixture { request ->
                recordFirst(request)
                error("synthetic private provider body must not become a failure code")
            }
        assertTrue(f.worker.runOne())
        val publication = f.publications.single()
        assertNull(publication.result)
        assertEquals("PROVIDER_UNAVAILABLE", publication.failure)
        assertEquals(expected(), publication.classification)
    }

    private fun recordFirst(request: PrecheckProviderRequest) {
        request.classificationCapture?.classified(ClassificationCaptureFixture.first())
        request.classificationCapture?.routed(AmbiguityAssessment.INDETERMINATE)
    }

    private fun expected(): PrecheckClassificationSnapshot {
        val first = ClassificationCaptureFixture.first()
        return PrecheckClassificationSnapshot(
            assessment = first.assessment,
            reason = first.reason,
            confidence = first.confidence,
            semanticAssessment = first.semanticAssessment,
            probabilities = first.probabilities,
            provider = first.provider,
            model = first.model,
            effectiveAssessment = AmbiguityAssessment.INDETERMINATE,
        )
    }

    private data class Publication(
        val claim: ClaimedPrecheck,
        val result: PrecheckProviderResult?,
        val failure: String?,
        val classification: PrecheckClassificationSnapshot?,
        val rejected: Boolean,
    )

    private class WorkerFixture(
        attempts: Int = 1,
        val response: (PrecheckProviderRequest) -> PrecheckProviderResult,
    ) {
        val requests = mutableListOf<PrecheckProviderRequest>()
        val publications = mutableListOf<Publication>()
        val events = mutableListOf<String>()
        var rejectPublication = false
        private val room =
            MeetingRoom.create(
                MeetingRoomId(ClassificationCaptureFixture.room),
                InviteCode.fromEntropy(ByteArray(16)),
                "Synthetic capture worker",
                MeetingMode.EITHER,
                MeetingTimeZone.of("Asia/Seoul"),
                SearchDateRange.explicit(
                    ClassificationCaptureFixture.request().searchStartDate,
                    ClassificationCaptureFixture.request().searchEndDate,
                ),
                ClosurePolicy.of(manualOnly = true),
                ClassificationCaptureFixture.anchor,
            )
        val claims =
            (0 until attempts).map { index ->
                val candidate =
                    PrecheckCandidate(
                        UUID(0, 1000L + index),
                        room.id.value,
                        UUID(0, 2000L + index),
                        1,
                        ClassificationCaptureFixture.RAW,
                        "ko-KR",
                        ClassificationCaptureFixture.anchor,
                        ClassificationCaptureFixture.anchor,
                        "synthetic-owner-$index",
                    )
                ClaimedPrecheck(
                    PrecheckExecution(
                        UUID(0, 3000L + index),
                        candidate,
                        PrecheckStatus.CHECKING,
                        2,
                        1,
                        0,
                        ClassificationCaptureFixture.anchor.plusSeconds(30),
                        ownerToken = UUID(0, 4000L + index),
                        claimedAt = ClassificationCaptureFixture.anchor,
                    ),
                    room,
                )
            }
        private val claimed = mutableSetOf<UUID>()
        private val repository =
            mock(
                PrecheckRepository::class.java,
                Answer<Any?> { call ->
                    when (call.method.name) {
                        "unfinishedCheckIds" -> emptyList<UUID>()
                        "inFlight" -> 0
                        "queuedCheckIds" -> claims.map { it.check.id }
                        else -> throw AssertionError("Unexpected worker repository I/O: ${call.method.name}")
                    }
                },
            )
        private val processing =
            mock(
                PrecheckProcessingService::class.java,
                Answer<Any?> { call ->
                    when (call.method.name) {
                        "claim" -> claims.singleOrNull { it.check.id == call.arguments[0] && claimed.add(it.check.id) }
                        "publish", "rejectPublication" -> {
                            val rejected = call.method.name == "rejectPublication"
                            events += if (rejected) "reject" else "publish"
                            publications +=
                                Publication(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), rejected)
                            if (!rejected && rejectPublication) throw PrecheckPublicationExpired()
                            PrecheckStatus.CLEAR
                        }
                        else -> throw AssertionError("Unexpected processing operation: ${call.method.name}")
                    }
                },
            )
        val worker =
            PreSubmissionCheckWorker(
                repository,
                processing,
                PreSubmissionCheckProvider { request ->
                    requests += request
                    events += "provider-start"
                    try {
                        response(request)
                    } finally {
                        events += "provider-end"
                    }
                },
                Clock.fixed(ClassificationCaptureFixture.anchor, ZoneOffset.UTC),
            )
    }
}
