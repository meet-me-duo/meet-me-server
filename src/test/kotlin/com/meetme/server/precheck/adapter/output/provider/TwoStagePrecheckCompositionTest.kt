package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.OpenAiProperties
import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
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
import com.meetme.server.shared.application.port.output.AiCallBudgetPort
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TS05/11 actual production configuration, key is synthetic, intercepted HTTP opens no socket. */
class TwoStagePrecheckCompositionTest {
    @ParameterizedTest
    @ValueSource(strings = ["ABSENT", "false"])
    fun `TS11 default or explicit selector OFF preserves the actual disabled Gemini provider`(selector: String) {
        val outbound = AtomicInteger()
        val classifications = AtomicInteger()
        val budget = TwoStageFakeBudget()
        var runner =
            runner(outbound)
                .withBean(
                    AmbiguityClassificationPort::class.java,
                    Supplier {
                        AmbiguityClassificationPort {
                            classifications.incrementAndGet()
                            TwoStageFixtures.clear()
                        }
                    },
                ).withBean(AiCallBudgetPort::class.java, Supplier { budget })
        if (selector != "ABSENT") runner = runner.withPropertyValues("meetme.precheck.decision-routing.enabled=$selector")
        runner.run { context ->
            assertNull(context.startupFailure)
            assertFalse(context.getBean(PrecheckProviderRuntimeProperties::class.java).enabled)
            assertFalse(context.getBean(PrecheckDecisionRoutingProperties::class.java).enabled)
            val provider = context.getBean(PreSubmissionCheckProvider::class.java)
            assertIs<LunaPreSubmissionCheckProvider>(provider)
            assertEquals(
                "CONFIGURATION",
                assertFailsWith<PrecheckProviderException> { provider.check(TwoStageFixtures.request()) }.failureCode,
            )
            assertEquals(0, classifications.get())
            assertEquals(0, outbound.get())
            assertTrue(budget.requests.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `TS11 both flags ON remain default-denied with or without a classifier bean`(classifierBean: Boolean) {
        val outbound = AtomicInteger()
        val classifications = AtomicInteger()
        var runner =
            runner(
                outbound,
            ).withPropertyValues("meetme.precheck.decision-routing.enabled=true", "meetme.precheck.provider.enabled=true")
        if (classifierBean) {
            runner =
                runner.withBean(
                    AmbiguityClassificationPort::class.java,
                    Supplier {
                        AmbiguityClassificationPort {
                            classifications.incrementAndGet()
                            TwoStageFixtures.clear()
                        }
                    },
                )
        }
        runner.run { context ->
            assertNull(context.startupFailure)
            assertTrue(context.getBean(PrecheckProviderRuntimeProperties::class.java).enabled)
            assertTrue(context.getBean(PrecheckDecisionRoutingProperties::class.java).enabled)
            val provider = context.getBean(PreSubmissionCheckProvider::class.java)
            assertIs<TwoStagePreSubmissionCheckProvider>(provider)
            assertFailsWith<PrecheckProviderException> { provider.check(TwoStageFixtures.request()) }
            assertEquals(0, classifications.get())
            assertEquals(0, outbound.get())
        }
    }

    @Test
    fun `TS11 routing flag and injected approvals cannot bypass the existing global provider OFF`() {
        val outbound = AtomicInteger()
        val classifications = AtomicInteger()
        val budget = TwoStageFakeBudget()
        runner(outbound)
            .withPropertyValues("meetme.precheck.decision-routing.enabled=true", "meetme.precheck.provider.enabled=false")
            .withBean(
                AmbiguityClassificationPort::class.java,
                Supplier {
                    AmbiguityClassificationPort {
                        classifications.incrementAndGet()
                        TwoStageFixtures.clear()
                    }
                },
            ).withBean(AiCallBudgetPort::class.java, Supplier { budget })
            .run { context ->
                assertNull(context.startupFailure)
                assertFalse(context.getBean(PrecheckProviderRuntimeProperties::class.java).enabled)
                val provider = context.getBean(PreSubmissionCheckProvider::class.java)
                assertFailsWith<PrecheckProviderException> { provider.check(TwoStageFixtures.request()) }
                assertEquals(0, classifications.get())
                assertEquals(0, outbound.get())
                assertTrue(budget.requests.isEmpty())
            }
    }

    @Test
    fun `TS11 only an explicit synthetic budget and classifier allow the opted in composition to classify`() {
        val outbound = AtomicInteger()
        val classifications = AtomicInteger()
        val budget = TwoStageFakeBudget()
        runner(outbound)
            .withPropertyValues("meetme.precheck.decision-routing.enabled=true", "meetme.precheck.provider.enabled=true")
            .withBean(
                AmbiguityClassificationPort::class.java,
                Supplier {
                    AmbiguityClassificationPort {
                        classifications.incrementAndGet()
                        TwoStageFixtures.clear()
                    }
                },
            ).withBean(AiCallBudgetPort::class.java, Supplier { budget })
            .run { context ->
                assertNull(context.startupFailure)
                val provider = context.getBean(PreSubmissionCheckProvider::class.java)
                assertEquals(PrecheckVerdict.CLEAR, provider.check(TwoStageFixtures.request()).verdict)
                assertEquals(1, classifications.get())
                assertEquals(1, budget.held(TwoStageFixtures.roomId))
                assertEquals(0, outbound.get())
            }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 7])
    fun `TS05 actual worker forwards the claimed room check generation and unchanged interpretation anchor`(generation: Int) {
        val repository = mock(PrecheckRepository::class.java)
        val processing = mock(PrecheckProcessingService::class.java)
        val input = TwoStageFixtures.request()
        val room =
            MeetingRoom.create(
                MeetingRoomId(TwoStageFixtures.roomId),
                InviteCode.fromEntropy(ByteArray(16) { it.toByte() }),
                "합성 worker scope",
                MeetingMode.EITHER,
                MeetingTimeZone.of("Asia/Seoul"),
                SearchDateRange.defaultFrom(TwoStageFixtures.anchor, MeetingTimeZone.of("Asia/Seoul")),
                ClosurePolicy.of(manualOnly = true),
                TwoStageFixtures.anchor,
            )
        val candidate =
            PrecheckCandidate(
                UUID.randomUUID(),
                room.id.value,
                UUID.randomUUID(),
                3,
                input.rawText,
                input.locale,
                input.referenceAt.minusSeconds(10),
                input.referenceAt,
                "synthetic-owner-binding",
            )
        val check =
            PrecheckExecution(
                input.checkId,
                candidate,
                PrecheckStatus.CHECKING,
                4,
                generation,
                0,
                TwoStageFixtures.anchor.plusSeconds(30),
                claimedAt = TwoStageFixtures.anchor,
            )
        val claim = ClaimedPrecheck(check, room)
        val result = PrecheckProviderResult(PrecheckVerdict.CLEAR)
        `when`(repository.unfinishedCheckIds(128)).thenReturn(emptyList())
        `when`(repository.queuedCheckIds(128)).thenReturn(listOf(check.id))
        `when`(processing.claim(check.id)).thenReturn(claim)
        `when`(processing.publish(claim, result, null)).thenReturn(PrecheckStatus.CLEAR)
        var captured: PrecheckProviderRequest? = null
        val worker =
            PreSubmissionCheckWorker(
                repository,
                processing,
                PreSubmissionCheckProvider {
                    captured = it
                    result
                },
                Clock.fixed(TwoStageFixtures.anchor, ZoneOffset.UTC),
            )
        assertTrue(worker.runOne())
        val sent = requireNotNull(captured)
        assertEquals(room.id.value, sent.roomId)
        assertEquals(check.id, sent.checkId)
        assertEquals(generation, sent.executionGeneration)
        assertEquals(candidate.rawText, sent.rawText)
        assertEquals(candidate.referenceAt, sent.referenceAt)
        assertEquals(room.timeZone.value, sent.timeZone)
        assertEquals(room.searchRange.startInclusive, sent.searchStartDate)
        assertEquals(room.searchRange.endExclusive, sent.searchEndDate)
        assertTrue(sent.timeout > Duration.ZERO && sent.timeout <= Duration.ofSeconds(15))
        verify(processing).publish(claim, result, null)
    }

    private fun runner(outbound: AtomicInteger) =
        ApplicationContextRunner()
            .withUserConfiguration(PrecheckProviderConfiguration::class.java)
            .withBean(OpenAiProperties::class.java, Supplier { OpenAiProperties(apiKey = "synthetic-key-no-runtime-secret") })
            .withBean(ObjectMapper::class.java, Supplier { JsonMapper.builder().build() })
            .withBean(
                PrecheckProviderHttpTransport::class.java,
                Supplier {
                    PrecheckProviderHttpTransport(
                        OkHttpClient
                            .Builder()
                            .addInterceptor { chain ->
                                outbound.incrementAndGet()
                                Response
                                    .Builder()
                                    .request(chain.request())
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(503)
                                    .message("synthetic no network")
                                    .body("{}".toResponseBody())
                                    .build()
                            }.build(),
                    )
                },
            )
}
