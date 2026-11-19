package com.meetme.server.precheck.adapter.output.persistence

import com.meetme.server.precheck.application.port.output.AmbiguityAssessment
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationReason
import com.meetme.server.precheck.application.port.output.PrecheckClassificationSnapshot
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.domain.PrecheckCandidate
import com.meetme.server.precheck.domain.PrecheckExecution
import com.meetme.server.precheck.domain.PrecheckStatus
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Independent V33 and durable execution-fence contract. No application scheduler or provider is loaded. */
class PrecheckClassificationSnapshotPostgresTest {
    private val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    private val jdbc = JdbcTemplate(source)
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val repository = JdbcPrecheckRepository(jdbc, mapper)
    private lateinit var claim: PrecheckExecution

    @BeforeEach
    fun historicalSchemaAndClaim() {
        jdbc.execute("DROP SCHEMA public CASCADE")
        jdbc.execute("CREATE SCHEMA public")
        flyway("32").migrate()
        jdbc.update(
            """INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,
                search_end_date,search_range_source,manual_only,collection_status,created_at)
                VALUES (?,'classification-fixture','synthetic classification','REMOTE','Asia/Seoul',
                '2027-01-04','2027-01-11','HOST_SPECIFIED',true,'COLLECTING',?)""",
            ROOM,
            Timestamp.from(AT),
        )
        jdbc.update(
            "INSERT INTO guest_browser_sessions(id,credential_digest,expires_at,created_at) VALUES (?,?,?,?)",
            GUEST,
            "synthetic-classification-digest",
            Timestamp.from(AT.plusSeconds(3600)),
            Timestamp.from(AT),
        )
        jdbc.update(
            """INSERT INTO participants(id,room_id,guest_session_id,role,display_name,joined_at)
                VALUES (?,?,?,'HOST','Synthetic',?)""",
            PARTICIPANT,
            ROOM,
            GUEST,
            Timestamp.from(AT),
        )
        claim =
            PrecheckExecution(
                CHECK,
                PrecheckCandidate(CANDIDATE, ROOM, PARTICIPANT, 1, "synthetic input", "ko-KR", AT, AT, "synthetic-owner"),
                PrecheckStatus.CHECKING,
                1,
                1,
                0,
                AT.plusSeconds(30),
                ownerToken = OWNER,
                claimedAt = AT.plusSeconds(1),
            )
        repository.insert(claim.candidate, claim)
        repository.startAttempt(claim, AT.plusSeconds(1))
    }

    @Test
    fun `CSP01 additive migration keeps every old execution field and leaves its decision unknown`() {
        repository.finishAttempt(claim, RESULT, AT.plusSeconds(2))
        val before = executionWithoutClassification()
        assertEquals(2, flyway().migrate().migrationsExecuted)
        flyway().validate()
        assertEquals(
            "34",
            flyway()
                .info()
                .current()
                .version.version,
        )
        assertEquals(before, executionWithoutClassification())
        assertNull(snapshot())
    }

    @Test
    fun `CSP02 all actual classification facts and effective routing survive a fresh JDBC reader`() {
        upgrade()
        val expected = classification()
        repository.finishAttempt(claim, RESULT, AT.plusSeconds(2), expected)
        val fresh = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
        val json =
            fresh.queryForObject(
                "SELECT classification_snapshot::text FROM precheck_executions WHERE check_id=?",
                String::class.java,
                CHECK,
            )
        assertEquals(mapper.valueToTree(expected), mapper.readTree(requireNotNull(json)))
        assertEquals("GEMINI", scalar("SELECT provider FROM precheck_executions WHERE check_id=?", CHECK))
        assertEquals("synthetic-generator", scalar("SELECT model FROM precheck_executions WHERE check_id=?", CHECK))
        assertEquals(17L, jdbc.queryForObject("SELECT input_tokens FROM precheck_executions WHERE check_id=?", Long::class.java, CHECK))
        assertEquals(
            AT.plusSeconds(2),
            jdbc.queryForObject("SELECT finished_at FROM precheck_executions WHERE check_id=?", Timestamp::class.java, CHECK)!!.toInstant(),
        )
    }

    @Test
    fun `CSP03 a terminal failure preserves classification without inventing missing routing evidence`() {
        upgrade()
        val expected = classification().copy(effectiveAssessment = null)
        repository.finishAttempt(claim.copy(failureCode = "PROVIDER_UNAVAILABLE"), null, AT.plusSeconds(2), expected)
        assertEquals(mapper.valueToTree(expected), snapshot())
        assertNull(scalar("SELECT provider FROM precheck_executions WHERE check_id=?", CHECK))
        assertNull(scalar("SELECT model FROM precheck_executions WHERE check_id=?", CHECK))
        assertEquals("PROVIDER_UNAVAILABLE", scalar("SELECT failure_code FROM precheck_executions WHERE check_id=?", CHECK))
    }

    @ParameterizedTest
    @ValueSource(strings = ["wrong_owner", "null_owner", "wrong_generation", "wrong_check", "not_started"])
    fun `CSP04 an unmatched or unstarted attempt cannot change any execution field`(kind: String) {
        upgrade()
        if (kind == "not_started") {
            jdbc.update("UPDATE precheck_executions SET started_at=NULL WHERE check_id=?", CHECK)
        }
        val before = executionWithoutClassification()
        val other =
            when (kind) {
                "wrong_owner" -> claim.copy(ownerToken = UUID(0, 999))
                "null_owner" -> claim.copy(ownerToken = null)
                "wrong_generation" -> claim.copy(generation = 2)
                "wrong_check" -> claim.copy(id = UUID(0, 998))
                else -> claim
            }
        repository.finishAttempt(other, RESULT, AT.plusSeconds(2), classification())
        assertEquals(before, executionWithoutClassification())
        assertNull(snapshot())
    }

    @Test
    fun `CSP05 retry generations retain independent first decisions`() {
        upgrade()
        val second = claim.copy(generation = 2, ownerToken = UUID(0, 997), deadlineAt = AT.plusSeconds(33))
        jdbc.update(
            """INSERT INTO precheck_executions(check_id,generation,queued_at,deadline_at,started_at,owner_token)
                VALUES (?,2,?,?,?,?)""",
            CHECK,
            Timestamp.from(AT.plusSeconds(3)),
            Timestamp.from(AT.plusSeconds(33)),
            Timestamp.from(AT.plusSeconds(4)),
            second.ownerToken,
        )
        val firstDecision = classification()
        val secondDecision =
            classification().copy(
                assessment = AmbiguityAssessment.CLEAR,
                reason = null,
                confidence = 0.95,
                semanticAssessment = AmbiguityAssessment.CLEAR,
                probabilities = mapOf(AmbiguityAssessment.CLEAR to 0.95, AmbiguityAssessment.NEEDS_REVIEW to 0.05),
                effectiveAssessment = AmbiguityAssessment.CLEAR,
            )
        repository.finishAttempt(claim, RESULT, AT.plusSeconds(2), firstDecision)
        repository.finishAttempt(second, RESULT, AT.plusSeconds(5), secondDecision)
        assertEquals(mapper.valueToTree(firstDecision), snapshot(1))
        assertEquals(mapper.valueToTree(secondDecision), snapshot(2))
    }

    @Test
    fun `CSP06 duplicate completion keeps the first nonnull decision and original execution metadata`() {
        upgrade()
        val first = classification()
        repository.finishAttempt(claim, RESULT, AT.plusSeconds(2), first)
        val before = scalar("SELECT to_jsonb(e)::text FROM precheck_executions e WHERE check_id=?", CHECK)
        repository.finishAttempt(
            claim,
            RESULT.copy(provider = "OPENAI", model = "different-generator", inputTokens = 99),
            AT.plusSeconds(9),
            first.copy(
                assessment = AmbiguityAssessment.CLEAR,
                reason = null,
                confidence = 1.0,
                semanticAssessment = AmbiguityAssessment.CLEAR,
                probabilities = mapOf(AmbiguityAssessment.CLEAR to 1.0),
                effectiveAssessment = AmbiguityAssessment.CLEAR,
            ),
        )
        assertEquals(before, scalar("SELECT to_jsonb(e)::text FROM precheck_executions e WHERE check_id=?", CHECK))
        repository.finishAttempt(claim, null, AT.plusSeconds(10), null)
        assertEquals(mapper.valueToTree(first), snapshot())
    }

    @Test
    fun `CSP07 old three argument completions remain compatible and null capture cannot invent a verdict`() {
        upgrade()
        repository.finishAttempt(claim, RESULT, AT.plusSeconds(2))
        val before = executionWithoutClassification()
        repository.finishAttempt(claim, null, AT.plusSeconds(3), null)
        assertEquals(before, executionWithoutClassification())
        assertNull(snapshot())
    }

    @Test
    fun `CSP08 a late first snapshot may fill a null decision without changing previously stored generator facts`() {
        upgrade()
        repository.finishAttempt(claim, RESULT, AT.plusSeconds(2))
        val before = executionWithoutClassification()
        repository.finishAttempt(claim, null, AT.plusSeconds(3), classification())
        assertEquals(before, executionWithoutClassification())
        assertEquals(mapper.valueToTree(classification()), snapshot())
    }

    @ParameterizedTest
    @ValueSource(strings = ["[]", "1", "{}", "{\"version\":2}", "oversize"])
    fun `CSP09 PostgreSQL rejects nonobject unversioned future or oversized diagnostic records atomically`(invalid: String) {
        upgrade()
        repository.finishAttempt(claim, RESULT, AT.plusSeconds(2), classification())
        val before = snapshot()
        val value = if (invalid == "oversize") "{\"version\":1,\"padding\":\"${"한".repeat(1400)}\"}" else invalid
        val rejected =
            assertFailsWith<DataIntegrityViolationException> {
                jdbc.update("UPDATE precheck_executions SET classification_snapshot=CAST(? AS jsonb) WHERE check_id=?", value, CHECK)
            }
        assertEquals("23514", (rejected.mostSpecificCause as SQLException).sqlState)
        assertEquals(before, snapshot())
    }

    @Test
    fun `CSP10 the 4096 byte JSONB boundary is inclusive and 4097 bytes is rejected`() {
        upgrade()
        repository.finishAttempt(claim, RESULT, AT.plusSeconds(2), classification())
        val empty = "{\"version\":1,\"padding\":\"\"}"
        val baseBytes = jdbc.queryForObject("SELECT octet_length(CAST(? AS jsonb)::text)", Int::class.java, empty)!!
        val exact = "{\"version\":1,\"padding\":\"${"x".repeat(4096 - baseBytes)}\"}"
        jdbc.update("UPDATE precheck_executions SET classification_snapshot=CAST(? AS jsonb) WHERE check_id=?", exact, CHECK)
        assertEquals(
            4096,
            jdbc.queryForObject(
                "SELECT octet_length(classification_snapshot::text) FROM precheck_executions WHERE check_id=?",
                Int::class.java,
                CHECK,
            ),
        )
        val overflow = exact.replace("\"}", "x\"}")
        val rejected =
            assertFailsWith<DataIntegrityViolationException> {
                jdbc.update("UPDATE precheck_executions SET classification_snapshot=CAST(? AS jsonb) WHERE check_id=?", overflow, CHECK)
            }
        assertEquals("23514", (rejected.mostSpecificCause as SQLException).sqlState)
        assertEquals(
            4096,
            jdbc.queryForObject(
                "SELECT octet_length(classification_snapshot::text) FROM precheck_executions WHERE check_id=?",
                Int::class.java,
                CHECK,
            ),
        )
    }

    private fun upgrade() {
        flyway().migrate()
    }

    private fun snapshot(generation: Int = 1) =
        scalar("SELECT classification_snapshot::text FROM precheck_executions WHERE check_id=? AND generation=?", CHECK, generation)
            ?.let(mapper::readTree)

    private fun executionWithoutClassification(): String =
        requireNotNull(
            scalar(
                "SELECT (to_jsonb(e)-'classification_snapshot')::text FROM precheck_executions e WHERE check_id=? AND generation=1",
                CHECK,
            ),
        )

    private fun scalar(
        sql: String,
        vararg values: Any,
    ): String? = jdbc.queryForObject(sql, String::class.java, *values)

    private fun flyway(target: String? = null): Flyway {
        val configuration = Flyway.configure().dataSource(source)
        target?.let(configuration::target)
        return configuration.load()
    }

    private fun classification() =
        PrecheckClassificationSnapshot(
            assessment = AmbiguityAssessment.INDETERMINATE,
            reason = AmbiguityClassificationReason.LOW_CONFIDENCE,
            confidence = 0.51,
            semanticAssessment = AmbiguityAssessment.NEEDS_REVIEW,
            probabilities = mapOf(AmbiguityAssessment.CLEAR to 0.49, AmbiguityAssessment.NEEDS_REVIEW to 0.51),
            provider = "JEV",
            model = "synthetic-classifier",
            effectiveAssessment = AmbiguityAssessment.NEEDS_REVIEW,
        )

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }
        private val AT = Instant.parse("2027-01-04T00:00:00Z")
        private val ROOM = UUID(0, 901)
        private val GUEST = UUID(0, 902)
        private val PARTICIPANT = UUID(0, 903)
        private val CANDIDATE = UUID(0, 904)
        private val CHECK = UUID(0, 905)
        private val OWNER = UUID(0, 906)
        private val RESULT =
            PrecheckProviderResult(
                PrecheckVerdict.NEEDS_CHOICE,
                inputTokens = 17,
                outputTokens = 3,
                provider = "GEMINI",
                model = "synthetic-generator",
            )

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
