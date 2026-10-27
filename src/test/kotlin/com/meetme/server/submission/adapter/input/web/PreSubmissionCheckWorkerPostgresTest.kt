package com.meetme.server.submission.adapter.input.web

import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchResult
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.service.GeminiBatchProcessor
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.application.service.PreSubmissionCheckWorker
import com.meetme.server.precheck.domain.PrecheckChoice
import com.meetme.server.shared.application.port.output.RateLimitDecision
import com.meetme.server.shared.application.port.output.RateLimitPort
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.security.KeyPairGenerator
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Independent PS02/03/05-12/19/21-24/31 and approved D1/D2, real PG + HTTP + deterministic synthetic provider. */
@SpringBootTest(properties = ["meetme.reliability.enabled=false", "meetme.data-retention.enabled=false"])
@AutoConfigureMockMvc
@Import(PreSubmissionCheckWorkerPostgresTest.IsolatedSecurity::class)
class PreSubmissionCheckWorkerPostgresTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var credentials: GuestCredentialPort

    @Autowired private lateinit var worker: PreSubmissionCheckWorker

    @Autowired private lateinit var finalProcessor: GeminiBatchProcessor

    @MockitoBean private lateinit var parser: NaturalLanguageParserPort

    private val json = ObjectMapper()

    @BeforeEach
    fun clean() {
        NOW.set(START)
        REQUESTS.clear()
        ACTION.set { PrecheckProviderResult(PrecheckVerdict.CLEAR) }
        jdbc.execute("TRUNCATE meeting_rooms, guest_browser_sessions, account_users CASCADE")
    }

    @Test
    fun `PD01 PD02 저장만으로는 마감하지 않고 두번째 CLEAR 게시만 정확 accepted batch를 동결한다`() {
        val room = room(expected = 2)
        val first = join(room)
        val second = join(room)
        create(room, first, "첫 완료 조건")
        create(room, second, "두번째 완료 조건")
        assertTrue(worker.runOne())
        val partial = listOf(current(room, first), current(room, second))
        assertEquals(1, partial.count { it.path("submission_complete").booleanValue() })
        roomView(room).andExpect(jsonPath("$.collection_status").value("COLLECTING"))
        assertEquals(1, count("submission_versions"))
        assertTrue(worker.runOne())
        roomView(room)
            .andExpect(jsonPath("$.collection_status").value("CLOSED"))
            .andExpect(jsonPath("$.closure_reason").value("EXPECTED_PARTICIPANTS"))
        assertEquals(setOf("첫 완료 조건", "두번째 완료 조건"), frozenTexts().toSet())
        assertEquals(1, count("coordination_runs"))
        assertEquals(2, REQUESTS.size)
    }

    @Test
    fun `PD03 PD05 첫 입력 확인대기는 수동 마감때 미반영이며 AI를 기다리지 않는다`() {
        val room = room()
        val guest = join(room)
        create(room, guest, RAW)
        close(room)
        roomView(room)
            .andExpect(jsonPath("$.collection_status").value("CLOSED"))
            .andExpect(jsonPath("$.public_status").value("INSUFFICIENT_PARTICIPANTS"))
        assertEquals(0, count("submission_batch_items"))
        assertEquals(0, count("coordination_runs"))
        val current = current(room, guest)
        assertFalse(current.path("submission_complete").booleanValue())
        assertFalse(current.path("applied_to_result").booleanValue())
        assertEquals(RAW, current.path("candidate").path("raw_text").stringValue())
        worker.runOne()
        assertEquals(0, count("submission_versions"))
        assertEquals(0, REQUESTS.size)
    }

    @Test
    fun `PD04 이전 완료본 뒤 pending 수정과 최초 미확인은 마감에서 제외하고 이전본만 동결한다`() {
        val room = room()
        val first = join(room)
        val second = join(room)
        val unconfirmed = join(room)
        create(room, first, "직전 확인된 원문")
        worker.runOne()
        create(room, second, "두번째 확인된 원문")
        worker.runOne()
        create(room, first, "마감 때 아직 확인중인 수정", revision = 1)
        create(room, unconfirmed, "최초 미확인 원문")
        close(room)
        assertEquals(setOf("직전 확인된 원문", "두번째 확인된 원문"), frozenTexts().toSet())
        val current = current(room, first)
        assertEquals("마감 때 아직 확인중인 수정", current.path("candidate").path("raw_text").stringValue())
        assertEquals("직전 확인된 원문", current.path("accepted").path("raw_text").stringValue())
        assertFalse(current.path("submission_complete").booleanValue())
        assertTrue(current.path("applied_to_result").booleanValue())
        assertFalse(current(room, unconfirmed).path("applied_to_result").booleanValue())
        worker.runOne()
        worker.runOne()
        assertEquals(2, REQUESTS.size, "closed pending work must not spend additional provider calls")
        assertEquals(setOf("직전 확인된 원문", "두번째 확인된 원문"), frozenTexts().toSet())
    }

    @Test
    fun `PD03 질문 미응답 NEEDS_EDIT 실패 취소는 최초 완료 집계가 아니다`() {
        listOf("NEEDS_CHOICE", "NEEDS_EDIT", "FAILED", "CANCELLED").forEach { terminal ->
            val room = room()
            val guest = join(room)
            var candidate = create(room, guest, AMBIGUOUS)
            ACTION.set {
                when (terminal) {
                    "NEEDS_CHOICE" -> question()
                    "NEEDS_EDIT" -> PrecheckProviderResult(PrecheckVerdict.NEEDS_EDIT)
                    else -> throw PrecheckProviderException("CONNECTION_FAILED")
                }
            }
            if (terminal == "CANCELLED") {
                mutate(room, guest, candidate, "cancel", expectedStatus = 200)
            } else {
                worker.runOne()
            }
            candidate = current(room, guest)
            assertEquals(terminal, candidate.path("check").path("status").stringValue())
            close(room)
            assertFalse(current(room, guest).path("applied_to_result").booleanValue())
            assertEquals(0, count("submission_versions"))
        }
    }

    @Test
    fun `PS03 질문 제안은 source 원문을 자동 치환하지 않으며 최종 분석 작업을 만들지 않는다`() {
        val room = room()
        val guest = join(room)
        ACTION.set { question() }
        create(room, guest, AMBIGUOUS)
        worker.runOne()
        val snapshot = current(room, guest)
        assertEquals("NEEDS_CHOICE", snapshot.path("check").path("status").stringValue())
        assertEquals(AMBIGUOUS, snapshot.path("candidate").path("raw_text").stringValue())
        assertEquals(
            2,
            snapshot
                .path("check")
                .path("question")
                .path("options")
                .size(),
        )
        assertEquals(1, REQUESTS.size)
        assertEquals(0, count("submission_versions"))
        assertEquals(0, count("coordination_runs"))
    }

    @Test
    fun `PS05 PS31 다음날 A 선택은 실제 새 시각과 source 의미 anchor를 분리하고 재검사까지 미완료다`() {
        val room = room()
        val guest = join(room)
        ACTION.set { question() }
        create(room, guest, AMBIGUOUS)
        worker.runOne()
        val question = current(room, guest)
        val oldReference = question.path("candidate").path("interpretation_reference_at").stringValue()
        NOW.set(START.plus(Duration.ofDays(1)))
        val response =
            mutate(
                room,
                guest,
                question,
                "answer",
                mapOf(
                    "question_id" to
                        question
                            .path("check")
                            .path("question")
                            .path("id")
                            .stringValue(),
                    "choice_id" to "AM",
                ),
            )
        assertEquals(START.plus(Duration.ofDays(1)), Instant.parse(response.path("candidate").path("created_at").stringValue()))
        assertEquals(oldReference, response.path("candidate").path("interpretation_reference_at").stringValue())
        assertEquals("내일 오전 8시 온라인 가능", response.path("candidate").path("raw_text").stringValue())
        assertFalse(response.path("submission_complete").booleanValue())
        assertEquals(0, count("submission_versions"))
        ACTION.set { PrecheckProviderResult(PrecheckVerdict.CLEAR) }
        worker.runOne()
        assertEquals(START, REQUESTS.last().referenceAt)
        val done = current(room, guest)
        assertEquals(oldReference, done.path("accepted").path("interpretation_reference_at").stringValue())
        assertTrue(done.path("submission_complete").booleanValue())
    }

    @Test
    fun `PS06 PS31 직접 수정은 새 작성일을 anchor로 쓰며 새 재검사를 통과해야 한다`() {
        val room = room()
        val guest = join(room)
        ACTION.set { question() }
        create(room, guest, AMBIGUOUS)
        worker.runOne()
        val snapshot = current(room, guest)
        NOW.set(START.plus(Duration.ofDays(1)))
        val response = mutate(room, guest, snapshot, "answer", mapOf("direct_text" to "내일 저녁 온라인 가능"))
        assertEquals(NOW.get(), Instant.parse(response.path("candidate").path("interpretation_reference_at").stringValue()))
        assertFalse(response.path("submission_complete").booleanValue())
        ACTION.set { PrecheckProviderResult(PrecheckVerdict.NEEDS_EDIT) }
        worker.runOne()
        assertEquals(NOW.get(), REQUESTS.last().referenceAt)
        assertEquals("NEEDS_EDIT", current(room, guest).path("check").path("status").stringValue())
        assertEquals(0, count("submission_versions"))
    }

    @Test
    fun `PS05 잘못된 question choice와 stale version은 새 candidate나 budget을 만들지 않는다`() {
        val room = room()
        val guest = join(room)
        ACTION.set { question() }
        create(room, guest, AMBIGUOUS)
        worker.runOne()
        val before = current(room, guest)
        assertEquals(
            409,
            mutateResult(room, guest, before, "answer", mapOf("question_id" to UUID.randomUUID(), "choice_id" to "AM")).response.status,
        )
        assertEquals(
            409,
            mutateResult(
                room,
                guest,
                before,
                "answer",
                mapOf(
                    "question_id" to
                        before
                            .path("check")
                            .path("question")
                            .path("id")
                            .stringValue(),
                    "choice_id" to "UNKNOWN",
                ),
            ).response.status,
        )
        assertEquals(before.path("candidate"), current(room, guest).path("candidate"))
        assertEquals(1, REQUESTS.size)
    }

    @Test
    fun `PS11 이전 revision AI 응답은 새 원문 accepted와 질문을 덮어쓰지 못한다`() {
        val room = room()
        val guest = join(room)
        create(room, guest, RAW)
        withBlockedProvider {
            create(room, guest, "더 최신 원문", revision = 1)
        }
        val snapshot = current(room, guest)
        assertEquals("더 최신 원문", snapshot.path("candidate").path("raw_text").stringValue())
        assertEquals("QUEUED", snapshot.path("check").path("status").stringValue())
        assertTrue(snapshot.path("accepted").isNull)
        assertEquals(0, count("submission_versions"))
    }

    @Test
    fun `PS12 마감이 먼저 선형화되면 늦은 CLEAR는 완료와 batch를 만들지 않는다`() {
        val room = room()
        val guest = join(room)
        create(room, guest, RAW)
        withBlockedProvider { close(room) }
        val snapshot = current(room, guest)
        assertFalse(snapshot.path("submission_complete").booleanValue())
        assertFalse(snapshot.path("applied_to_result").booleanValue())
        assertEquals(0, count("submission_versions"))
        assertEquals(0, count("submission_batch_items"))
        roomView(room).andExpect(jsonPath("$.collection_status").value("CLOSED"))
    }

    @Test
    fun `PS12 정확 deadline 도착 이후 CLEAR 게시와 retry answer는 거부된다`() {
        val room = room()
        val guest = join(room)
        create(room, guest, RAW)
        jdbc.update(
            "UPDATE meeting_rooms SET manual_only=false,submission_deadline=? WHERE id=?",
            Timestamp.from(START.plusSeconds(5)),
            room.id,
        )
        withBlockedProvider { NOW.set(START.plusSeconds(5)) }
        val snapshot = current(room, guest)
        assertFalse(snapshot.path("submission_complete").booleanValue())
        assertEquals(0, count("submission_versions"))
        assertEquals(409, mutateResult(room, guest, snapshot, "retry").response.status)
        assertEquals(409, mutateResult(room, guest, snapshot, "answer", mapOf("direct_text" to "늦은 수정")).response.status)
    }

    @Test
    fun `PS07 PS12 취소가 먼저면 늦은 CLEAR 무시하고 원문과 CANCELLED를 보존한다`() {
        val room = room()
        val guest = join(room)
        create(room, guest, RAW)
        withBlockedProvider { mutate(room, guest, current(room, guest), "cancel", expectedStatus = 200) }
        val snapshot = current(room, guest)
        assertEquals(RAW, snapshot.path("candidate").path("raw_text").stringValue())
        assertEquals("CANCELLED", snapshot.path("check").path("status").stringValue())
        assertFalse(snapshot.path("submission_complete").booleanValue())
        assertEquals(0, count("submission_versions"))
    }

    @Test
    fun `PS12 CLEAR가 먼저면 취소는 409이며 이미 수용한 완료본을 철회하지 않는다`() {
        val room = room()
        val guest = join(room)
        create(room, guest, RAW)
        worker.runOne()
        val done = current(room, guest)
        assertEquals(409, mutateResult(room, guest, done, "cancel").response.status)
        assertTrue(current(room, guest).path("submission_complete").booleanValue())
        assertEquals(1, count("submission_versions"))
    }

    @Test
    fun `PS08 기술실패는 실패로 남고 원문을 완료로 삼지 않으며 사용량을 만들어내지 않는다`() {
        val room = room()
        val guest = join(room)
        ACTION.set { throw PrecheckProviderException("CONNECTION_FAILED") }
        create(room, guest, RAW)
        worker.runOne()
        val failure = current(room, guest)
        assertEquals("FAILED", failure.path("check").path("status").stringValue())
        assertEquals("CONNECTION_FAILED", failure.path("check").path("failure_code").stringValue())
        assertEquals(RAW, failure.path("candidate").path("raw_text").stringValue())
        assertFalse(failure.path("submission_complete").booleanValue())
        assertEquals(0, count("submission_versions"))
    }

    @Test
    fun `PS21 명시 retry는 한번이고 같은 key 재전송은 추가 호출이나 execution을 만들지 않는다`() {
        val room = room()
        val guest = join(room)
        ACTION.set { throw PrecheckProviderException("CONNECTION_FAILED") }
        create(room, guest, RAW)
        worker.runOne()
        val failed = current(room, guest)
        val key = UUID.randomUUID()
        val retry = mutate(room, guest, failed, "retry", key = key)
        mutate(room, guest, failed, "retry", key = key)
        assertEquals(retry.path("check").path("id"), current(room, guest).path("check").path("id"))
        worker.runOne()
        assertEquals(2, REQUESTS.size)
        val failedAgain = current(room, guest)
        assertFalse(failedAgain.path("check").path("retry_allowed").booleanValue())
        assertEquals(409, mutateResult(room, guest, failedAgain, "retry").response.status)
        assertEquals(2, REQUESTS.size)
    }

    @Test
    fun `PS21 새 revision 취소 reload로 물리 호출 여섯번 상한을 초기화할 수 없다`() {
        val room = room()
        val guest = join(room)
        ACTION.set { throw PrecheckProviderException("CONNECTION_FAILED") }
        repeat(6) { index ->
            create(room, guest, "검사 원문 $index", revision = index)
            worker.runOne()
            current(room, guest)
        }
        create(room, guest, "상한 뒤에도 보존할 수정", revision = 6)
        worker.runOne()
        val snapshot = current(room, guest)
        assertEquals(6, REQUESTS.size)
        assertEquals("상한 뒤에도 보존할 수정", snapshot.path("candidate").path("raw_text").stringValue())
        assertEquals("LIMIT_REACHED", snapshot.path("check").path("status").stringValue())
        assertFalse(snapshot.path("submission_complete").booleanValue())
        assertEquals(0, count("submission_versions"))
    }

    @Test
    fun `PS24 queue wait를 포함한 30초 deadline은 새 worker 실행으로 초기화되지 않는다`() {
        val room = room()
        val guest = join(room)
        create(room, guest, RAW)
        NOW.set(START.plusSeconds(30))
        worker.runOne()
        assertEquals(0, REQUESTS.size)
        assertEquals("TIMED_OUT", current(room, guest).path("check").path("status").stringValue())
        worker.runOne()
        assertEquals(0, REQUESTS.size)
        assertEquals(0, count("submission_versions"))
    }

    @Test
    fun `PS19 claim이 먼저 완료되면 옛 guest의 진행중 결과와 replay는 접근과 완료를 만들지 않는다`() {
        val room = room()
        val guest = join(room)
        val key = UUID.randomUUID()
        create(room, guest, RAW, key = key)
        val member = account()
        withBlockedProvider {
            mvc
                .perform(
                    post("/api/rooms/${room.invite}/claim")
                        .cookie(guest.cookie)
                        .header(HttpHeaders.AUTHORIZATION, member.bearer)
                        .header("Origin", ORIGIN),
                ).andExpect(status().isOk)
        }
        val replay = performCreate(room, guest, RAW, key = key)
        assertTrue(replay.response.status in setOf(401, 403))
        assertFalse(replay.response.contentAsString.contains(RAW))
        assertEquals(0, count("submission_versions"))
    }

    @Test
    fun `PS22 concurrent worker들은 동일 execution을 중복 호출하지 않는다`() {
        val room = room()
        val guest = join(room)
        create(room, guest, RAW)
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        ACTION.set {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            PrecheckProviderResult(PrecheckVerdict.CLEAR)
        }
        try {
            val first = executor.submit<Boolean> { worker.runOne() }
            check(entered.await(10, TimeUnit.SECONDS))
            val second = executor.submit<Boolean> { worker.runOne() }
            assertFalse(second.get(10, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(first.get(10, TimeUnit.SECONDS))
            assertEquals(1, REQUESTS.size)
            assertEquals(1, count("submission_versions"))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `PS21 질문 발행 세번은 새 revision으로 초기화되지 않고 네번째는 직접 수정 안내다`() {
        val room = room()
        val guest = join(room)
        ACTION.set { question() }
        repeat(3) { index ->
            create(room, guest, "$AMBIGUOUS $index", revision = index)
            worker.runOne()
            assertEquals("NEEDS_CHOICE", current(room, guest).path("check").path("status").stringValue())
        }
        create(room, guest, "$AMBIGUOUS 3", revision = 3)
        worker.runOne()
        val snapshot = current(room, guest)
        assertEquals("NEEDS_EDIT", snapshot.path("check").path("status").stringValue())
        assertEquals("QUESTION_LIMIT_REACHED", snapshot.path("check").path("failure_code").stringValue())
        assertTrue(snapshot.path("check").path("question").isNull)
        assertEquals(4, REQUESTS.size)
        assertEquals(0, count("submission_versions"))
    }

    @Test
    fun `PS06 A 선택 후에도 모호하면 추가 질문 반복 대신 NEEDS_EDIT이며 완료가 아니다`() {
        val room = room()
        val guest = join(room)
        ACTION.set { question() }
        create(room, guest, AMBIGUOUS)
        worker.runOne()
        val before = current(room, guest)
        mutate(
            room,
            guest,
            before,
            "answer",
            mapOf(
                "question_id" to
                    before
                        .path("check")
                        .path("question")
                        .path("id")
                        .stringValue(),
                "choice_id" to "AM",
            ),
        )
        // A second structurally valid synthetic ambiguity result exercises the no-repeat policy.
        // Both proposals must differ from the now-selected source; identical-source validation has its own oracle.
        ACTION.set {
            PrecheckProviderResult(
                PrecheckVerdict.NEEDS_CHOICE,
                "오전과 오후 중 어느 뜻인가요?",
                "8시",
                listOf(
                    PrecheckChoice("AM", "오전 8시", "내일 오전 8시 온라인 참여 가능"),
                    PrecheckChoice("PM", "오후 8시", "내일 오후 8시 온라인 참여 가능"),
                ),
            )
        }
        worker.runOne()
        val snapshot = current(room, guest)
        assertEquals("NEEDS_EDIT", snapshot.path("check").path("status").stringValue())
        assertEquals("RECHECK_REQUIRES_EDIT", snapshot.path("check").path("failure_code").stringValue())
        assertFalse(snapshot.path("submission_complete").booleanValue())
        assertEquals(0, count("submission_versions"))
    }

    @Test
    fun `PS20 직접 답변 501 코드포인트도 거부하고 기존 질문 source를 보존한다`() {
        val room = room()
        val guest = join(room)
        ACTION.set { question() }
        create(room, guest, AMBIGUOUS)
        worker.runOne()
        val before = current(room, guest)
        val result = mutateResult(room, guest, before, "answer", mapOf("direct_text" to "😀".repeat(501)))
        assertEquals(400, result.response.status)
        assertEquals("SUBMISSION_TEXT_TOO_LONG", tree(result).path("code").stringValue())
        assertEquals(before.path("candidate"), current(room, guest).path("candidate"))
        assertEquals(before.path("check"), current(room, guest).path("check"))
        assertEquals(1, REQUESTS.size)
    }

    @Test
    fun `PS22 worker 세개가 다른 작업을 경쟁해도 합계 provider in flight는 둘 이하다`() {
        val room = room()
        repeat(3) { create(room, join(room), "동시성 합성 $it") }
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        ACTION.set {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            PrecheckProviderResult(PrecheckVerdict.CLEAR)
        }
        try {
            val first = executor.submit<Boolean> { worker.runOne() }
            val second = executor.submit<Boolean> { worker.runOne() }
            check(entered.await(10, TimeUnit.SECONDS))
            val third = executor.submit<Boolean> { worker.runOne() }
            assertFalse(third.get(10, TimeUnit.SECONDS))
            assertEquals(2, REQUESTS.size)
            release.countDown()
            assertTrue(first.get(10, TimeUnit.SECONDS))
            assertTrue(second.get(10, TimeUnit.SECONDS))
            worker.runOne()
            assertEquals(3, REQUESTS.size)
            assertEquals(3, count("submission_versions"))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `PS31 frozen 최종 배치도 A 선택 source 날짜와 DIRECT 새 날짜를 구분해 내일을 이동시키지 않는다`() {
        val room = room()
        val choiceOwner = join(room)
        val directOwner = join(room)
        ACTION.set { question() }
        create(room, choiceOwner, AMBIGUOUS)
        create(room, directOwner, AMBIGUOUS)
        worker.runOne()
        worker.runOne()
        val first = current(room, choiceOwner)
        val second = current(room, directOwner)
        NOW.set(START.plus(Duration.ofDays(1)))
        mutate(
            room,
            choiceOwner,
            first,
            "answer",
            mapOf(
                "question_id" to
                    first
                        .path("check")
                        .path("question")
                        .path("id")
                        .stringValue(),
                "choice_id" to "AM",
            ),
        )
        mutate(room, directOwner, second, "answer", mapOf("direct_text" to "내일 오전 8시 온라인 가능"))
        ACTION.set { PrecheckProviderResult(PrecheckVerdict.CLEAR) }
        worker.runOne()
        worker.runOne()
        close(room)
        val captured = mutableListOf<NaturalLanguageBatchRequest>()
        Mockito.`when`(parser.parse(anyValue())).thenAnswer { invocation ->
            val request = invocation.getArgument<NaturalLanguageBatchRequest>(0)
            captured += request
            NaturalLanguageBatchResult(
                request.inputs.map { input ->
                    StructuredSubmissionResult(
                        SubmissionVersionId(UUID.fromString(input.inputRef)),
                        listOf(
                            StructuredCondition.TimeWindow(
                                TimePolarity.AVAILABLE,
                                input.referenceDate.plusDays(1),
                                null,
                                LocalTime.of(8, 0),
                                LocalTime.of(9, 0),
                            ),
                        ),
                        null,
                    )
                },
                ParserUsage(10, 10, 100),
            )
        }
        val batchId = requireNotNull(jdbc.queryForObject("SELECT id FROM submission_batches WHERE room_id=?", UUID::class.java, room.id))
        finalProcessor.process(SubmissionBatchId(batchId))
        val request = captured.single()
        assertEquals(2, request.inputs.size)
        val referencesByParticipant =
            request.inputs.associate { input ->
                val participant =
                    jdbc.queryForObject(
                        "SELECT h.participant_id FROM submission_versions v JOIN submission_heads h ON h.id=v.submission_id WHERE v.id=?",
                        UUID::class.java,
                        UUID.fromString(input.inputRef),
                    )
                participant to input.referenceDate
            }
        assertEquals(START.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(), referencesByParticipant[choiceOwner.id])
        assertEquals(NOW.get().atZone(ZoneId.of("Asia/Seoul")).toLocalDate(), referencesByParticipant[directOwner.id])
        assertEquals(0, count("candidates"), "different intended tomorrow dates must not invent an overlapping candidate")
    }

    private fun <T> anyValue(): T = Mockito.any<T>()

    @Test
    fun `PS21 PS27 기존 V13 질문 세개는 보존하고 신규 질문 한도에도 포함한다`() {
        val room = room()
        val guest = join(room)
        repeat(3) { revision ->
            create(room, guest, "기존 이력 합성 $revision", revision = revision)
            worker.runOne()
        }
        val versions = jdbc.queryForList("SELECT id FROM submission_versions ORDER BY revision", UUID::class.java)
        val proposal =
            json.writeValueAsString(
                mapOf(
                    "excerpt" to "8시",
                    "question" to "오전과 오후 중 어느 뜻인가요?",
                    "optionA" to "오전 8시",
                    "optionB" to "오후 8시",
                ),
            )
        versions.forEach { id ->
            jdbc.update(
                "INSERT INTO submission_clarifications(question_id,source_version_id,proposal,created_at) VALUES (?,?,?::jsonb,?)",
                UUID.randomUUID(),
                id,
                proposal,
                Timestamp.from(START),
            )
        }
        val oldRows =
            jdbc.queryForList(
                "SELECT question_id,source_version_id,proposal::text,created_at FROM submission_clarifications ORDER BY question_id",
            )
        ACTION.set { question() }
        create(room, guest, AMBIGUOUS, revision = 3)
        worker.runOne()
        val current = current(room, guest)
        assertEquals("NEEDS_EDIT", current.path("check").path("status").stringValue())
        assertEquals("QUESTION_LIMIT_REACHED", current.path("check").path("failure_code").stringValue())
        assertEquals(
            oldRows,
            jdbc.queryForList(
                "SELECT question_id,source_version_id,proposal::text,created_at FROM submission_clarifications ORDER BY question_id",
            ),
        )
        assertEquals(3, count("submission_versions"))
    }

    private fun question() =
        PrecheckProviderResult(
            PrecheckVerdict.NEEDS_CHOICE,
            "오전과 오후 중 어느 뜻인가요?",
            "8시",
            listOf(PrecheckChoice("AM", "오전 8시", "내일 오전 8시 온라인 가능"), PrecheckChoice("PM", "오후 8시", "내일 오후 8시 온라인 가능")),
        )

    private fun close(room: Room) {
        mvc
            .perform(
                post(
                    "/api/rooms/${room.invite}/close",
                ).header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(AccountPrincipal(room.host.id, "합성 계정"))}")
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"confirm_early\":true}"),
            ).andExpect(status().isOk)
    }

    private fun frozenTexts(): List<String> =
        jdbc
            .queryForList(
                "SELECT v.raw_text FROM submission_batch_items i JOIN submission_versions v ON v.id=i.submission_version_id",
                String::class.java,
            ).map { requireNotNull(it) }

    private fun mutate(
        room: Room,
        guest: Guest,
        snapshot: JsonNode,
        action: String,
        extra: Map<String, Any> = emptyMap(),
        key: UUID = UUID.randomUUID(),
        expectedStatus: Int = 202,
    ): JsonNode {
        val result = mutateResult(room, guest, snapshot, action, extra, key)
        assertEquals(expectedStatus, result.response.status, result.response.contentAsString)
        return tree(result)
    }

    private fun mutateResult(
        room: Room,
        guest: Guest,
        snapshot: JsonNode,
        action: String,
        extra: Map<String, Any> = emptyMap(),
        key: UUID = UUID.randomUUID(),
    ): MvcResult {
        val payload =
            mapOf<String, Any>(
                "request_id" to key,
                "expected_revision" to snapshot.path("candidate").path("revision").intValue(),
                "expected_check_version" to snapshot.path("check").path("version").longValue(),
            ) + extra
        return mvc
            .perform(
                post("${path(room)}/${snapshot.path("check").path("id").stringValue()}/$action")
                    .cookie(guest.cookie)
                    .header("Origin", ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)),
            ).andReturn()
    }

    private fun withBlockedProvider(actionWhileProviderBlocked: () -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        ACTION.set {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            PrecheckProviderResult(PrecheckVerdict.CLEAR)
        }
        try {
            val future = executor.submit<Boolean> { worker.runOne() }
            check(entered.await(10, TimeUnit.SECONDS)) { "provider was not called before barrier timeout" }
            actionWhileProviderBlocked()
            release.countDown()
            assertTrue(future.get(10, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun assertPending(
        node: JsonNode,
        raw: String,
        revision: Int,
    ) {
        assertEquals(raw, node.path("candidate").path("raw_text").stringValue())
        assertEquals(revision, node.path("candidate").path("revision").intValue())
        assertTrue(node.path("accepted").isNull)
        assertFalse(node.path("submission_complete").booleanValue())
        assertEquals("QUEUED", node.path("check").path("status").stringValue())
    }

    private fun account(): Account {
        val principal = AccountPrincipal(UUID.randomUUID(), "합성 계정")
        jdbc.update(
            "INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,?)",
            principal.accountId,
            principal.displayName,
            Timestamp.from(NOW.get()),
        )
        return Account(principal.accountId, "Bearer ${tokens.issue(principal)}")
    }

    private fun room(expected: Int? = null): Room {
        val host = account()
        val payload = linkedMapOf<String, Any>("purpose" to "독립 제출전 확인", "meeting_mode" to "EITHER", "manual_only" to (expected == null))
        if (expected != null) payload["expected_participants"] = expected
        val result =
            mvc
                .perform(
                    post("/api/rooms")
                        .header(HttpHeaders.AUTHORIZATION, host.bearer)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)),
                ).andExpect(status().isCreated)
                .andReturn()
        val invite = tree(result).path("invite_code").stringValue()
        val id = requireNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code=?", UUID::class.java, invite))
        return Room(id, invite, host)
    }

    private fun join(
        room: Room,
        name: String = "합성 게스트",
        password: String? = null,
    ): Guest {
        val payload = linkedMapOf<String, Any>("display_name" to name)
        if (password != null) payload["password"] = password
        val result =
            mvc
                .perform(
                    post("/api/rooms/${room.invite}/participants")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)),
                ).andExpect(status().isCreated)
                .andReturn()
        val cookie = cookie(result)
        val id =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT p.id FROM participants p JOIN guest_browser_sessions s ON p.guest_session_id=s.id WHERE p.room_id=? AND s.credential_digest=?",
                    UUID::class.java,
                    room.id,
                    credentials.digest(cookie.value),
                ),
            )
        return Guest(id, cookie)
    }

    private fun reaccess(
        room: Room,
        owner: Guest,
        password: String?,
        level: String,
    ): Guest {
        val result =
            mvc
                .perform(reaccessRequest(room, owner, password))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.level").value(level))
                .andReturn()
        return Guest(owner.id, cookie(result))
    }

    private fun reaccessRequest(
        room: Room,
        owner: Guest,
        password: String?,
    ): MockHttpServletRequestBuilder {
        val payload = linkedMapOf<String, Any>("participant_id" to owner.id)
        if (password != null) payload["password"] = password
        return post("/api/rooms/${room.invite}/guest-records/reaccess")
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(payload))
    }

    private fun create(
        room: Room,
        guest: Guest,
        text: String,
        revision: Int = 0,
        key: UUID = UUID.randomUUID(),
    ): JsonNode =
        tree(
            assertDoesNotThrow<MvcResult> {
                mvc
                    .perform(
                        createRequest(room, guest, text, revision, key),
                    ).andExpect(status().isAccepted)
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                    .andReturn()
            },
        )

    private fun performCreate(
        room: Room,
        guest: Guest,
        text: String,
        revision: Int = 0,
        key: UUID = UUID.randomUUID(),
    ): MvcResult = mvc.perform(createRequest(room, guest, text, revision, key)).andReturn()

    private fun createRequest(
        room: Room,
        guest: Guest,
        text: String,
        revision: Int = 0,
        key: UUID = UUID.randomUUID(),
    ): MockHttpServletRequestBuilder =
        post(path(room))
            .cookie(guest.cookie)
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(mapOf("request_id" to key, "expected_revision" to revision, "raw_text" to text)))

    private fun current(
        room: Room,
        guest: Guest,
    ): JsonNode =
        tree(
            assertDoesNotThrow<MvcResult> {
                mvc
                    .perform(
                        get("${path(room)}/current").cookie(guest.cookie),
                    ).andExpect(status().isOk)
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                    .andReturn()
            },
        )

    private fun roomView(room: Room) = mvc.perform(get("/api/rooms/${room.invite}").header(HttpHeaders.AUTHORIZATION, room.host.bearer))

    private fun path(room: Room) = "/api/rooms/${room.invite}/submission/checks"

    private fun tree(result: MvcResult): JsonNode = json.readTree(result.response.contentAsByteArray)

    private fun cookie(result: MvcResult): Cookie {
        val header = requireNotNull(result.response.getHeader(HttpHeaders.SET_COOKIE))
        return Cookie("meet_me_guest", header.substringAfter('=').substringBefore(';'))
    }

    private fun count(table: String): Int = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private fun concurrently(
        first: () -> MvcResult,
        second: () -> MvcResult,
    ): List<MvcResult> {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks =
                listOf(first, second).map { action ->
                    executor.submit<MvcResult> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        action()
                    }
                }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            return tasks.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    private data class Account(
        val id: UUID,
        val bearer: String,
    )

    private data class Room(
        val id: UUID,
        val invite: String,
        val host: Account,
    )

    private data class Guest(
        val id: UUID,
        val cookie: Cookie,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class IsolatedSecurity {
        @Bean @Primary
        fun precheckSyntheticProvider(): PreSubmissionCheckProvider =
            PreSubmissionCheckProvider { request ->
                REQUESTS += request
                ACTION.get().invoke(request)
            }

        @Bean @Primary
        fun precheckTestClock(): Clock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this

                override fun instant(): Instant = NOW.get()
            }

        @Bean @Primary
        fun precheckTestTokens(clock: Clock): AccessTokenPort =
            Rs256AccessTokenAdapter(
                KEYS.private,
                mapOf("precheck-test" to KEYS.public),
                "precheck-test",
                clock,
                "https://synthetic.invalid",
                "meetme-web",
            )

        @Bean @Primary
        fun precheckTestLimiter(): RateLimitPort = RateLimitPort { _, _, _ -> RateLimitDecision(true, Duration.ZERO) }
    }

    companion object {
        private const val ORIGIN = "https://app.meet-me.co.kr"
        private const val AMBIGUOUS = "내일 8시 온라인 가능"
        private const val RAW = "외부 노출 금지 합성 원문 107"
        private const val PASSWORD = "synthetic-password-107"
        private val REQUESTS = CopyOnWriteArrayList<PrecheckProviderRequest>()
        private val ACTION =
            AtomicReference<
                (
                    PrecheckProviderRequest,
                ) -> PrecheckProviderResult,
            >({ PrecheckProviderResult(PrecheckVerdict.CLEAR) })
        private val START = Instant.parse("2026-10-17T12:00:00Z")
        private val NOW = AtomicReference(START)
        private val KEYS = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("meetme.anonymous.allowed-origins") { ORIGIN }
            registry.add("meetme.anonymous.cookie-secure") { true }
        }

        @JvmStatic @AfterAll
        fun stop() = postgres.stop()
    }
}
