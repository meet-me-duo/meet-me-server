package com.meetme.server.submission.domain.dsl

import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.support.ScheduleDslSyntheticFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduleDslValidatorTest {
    private val f = ScheduleDslSyntheticFixture
    private val validator = ScheduleDslValidator()

    @Test
    fun `DSLV01 nested graph preserves claims structure and verified operator evidence through restoration`() {
        val claims = listOf(f.claim("a", 18, 22), f.claim("b", 19, 21))
        val nodes = listOf(f.ref("ar", "a"), f.ref("br", "b"), f.node("root", ScheduleDslOperator.ALL_OF, "ar", "br"))
        val p = f.program(nodes, "root")
        val actual = validator.validate(f.proposal(claims, p), f.ir.snapshot())
        assertEquals(f.envelope(claims, p), actual)
        assertEquals(actual, validator.validateRestored(actual, f.ir.snapshot()))
        assertTrue(!actual.toString().contains(f.ir.FIRST_TEXT))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "dangling",
            "cycle",
            "duplicate_id",
            "unreachable",
            "empty_operator",
            "duplicate_child",
            "wrong_role",
            "uncovered_negative",
            "hidden_negative",
            "operator_quote",
            "operator_revision",
            "operator_span",
            "node_limit",
            "depth_limit",
        ],
    )
    fun `DSLV02 malformed program is withheld without dropping claim meaning or trusted peer`(defect: String) {
        var claims = listOf(f.claim("a", 18, 22), f.claim("b", 19, 21))
        var nodes = listOf(f.ref("ar", "a"), f.ref("br", "b"), f.node("root", ScheduleDslOperator.ALL_OF, "ar", "br"))
        var root = "root"
        var history = emptyList<String>()
        val expected =
            when (defect) {
                "dangling" -> {
                    nodes = nodes.dropLast(1) + nodes.last().copy(children = listOf("ar", "missing"))
                    ScheduleDslProblem.REFERENCE
                }
                "cycle" -> {
                    nodes = nodes.dropLast(1) + nodes.last().copy(children = listOf("ar", "root"))
                    ScheduleDslProblem.REFERENCE
                }
                "duplicate_id" -> {
                    nodes = nodes + nodes.first()
                    ScheduleDslProblem.REFERENCE
                }
                "unreachable" -> {
                    nodes = nodes + f.ref("unused", "a")
                    ScheduleDslProblem.REFERENCE
                }
                "empty_operator" -> {
                    nodes = nodes.dropLast(1) + nodes.last().copy(children = emptyList())
                    ScheduleDslProblem.SHAPE
                }
                "duplicate_child" -> {
                    nodes = nodes.dropLast(1) + nodes.last().copy(children = listOf("ar", "ar"))
                    ScheduleDslProblem.SHAPE
                }
                "wrong_role" -> {
                    claims = listOf(claims.first().copy(effect = IrEffect.PREFERRED), claims.last())
                    ScheduleDslProblem.ROLE
                }
                "uncovered_negative" -> {
                    claims = claims + f.claim("negative", 19, 20, IrEffect.UNAVAILABLE)
                    ScheduleDslProblem.REFERENCE
                }
                "hidden_negative" -> {
                    claims = claims + f.claim("negative", 19, 20, IrEffect.UNAVAILABLE)
                    history = listOf("negative")
                    ScheduleDslProblem.REFERENCE
                }
                "operator_quote" -> {
                    nodes =
                        nodes.dropLast(1) + nodes.last().copy(evidence = listOf(f.ir.evidence().copy(quote = "not the trusted source")))
                    ScheduleDslProblem.OPERATOR_EVIDENCE
                }
                "operator_revision" -> {
                    nodes = nodes.dropLast(1) + nodes.last().copy(evidence = listOf(f.ir.evidence().copy(revision = 999)))
                    ScheduleDslProblem.OPERATOR_EVIDENCE
                }
                "operator_span" -> {
                    nodes =
                        nodes.dropLast(1) +
                        nodes.last().copy(evidence = listOf(f.ir.evidence().copy(endCodePoint = f.ir.FIRST_TEXT.length)))
                    ScheduleDslProblem.OPERATOR_EVIDENCE
                }
                "node_limit" -> {
                    val leaves = (0..123).map { f.ref("leaf$it", if (it % 2 == 0) "a" else "b") }
                    val groups =
                        (0..3).map { g ->
                            f.node("group$g", ScheduleDslOperator.ALL_OF, *(g * 31 until (g + 1) * 31).map { "leaf$it" }.toTypedArray())
                        }
                    nodes = leaves + groups + f.node("root", ScheduleDslOperator.ALL_OF, "group0", "group1", "group2", "group3")
                    ScheduleDslProblem.COMPLEXITY
                }
                else -> {
                    nodes =
                        listOf(f.ref("ar", "a"), f.ref("br", "b")) +
                        (1..8).map { i -> f.node("n$i", ScheduleDslOperator.ALL_OF, if (i == 1) "ar" else "n${i - 1}", "br") }
                    root = "n8"
                    ScheduleDslProblem.COMPLEXITY
                }
            }
        val actual = validator.validate(f.proposal(claims, f.program(nodes, root, history = history)), f.ir.snapshot())
        assertEquals(f.ir.envelope(claims), actual.claims)
        assertEquals(null, actual.programs.first().program)
        assertTrue(expected in actual.programs.first().problems)
        assertTrue(
            actual.programs
                .last()
                .problems
                .isEmpty(),
        )
        assertTrue(actual.programs.last().program != null)
        assertEquals(actual, validator.validateRestored(actual, f.ir.snapshot()))
    }

    @Test
    fun `DSLV03 shared DAG with consistent role and depth eight remains valid`() {
        val claims = listOf(f.claim("a", 18, 22), f.claim("b", 19, 21))
        val nodes =
            listOf(f.ref("ar", "a"), f.ref("br", "b")) +
                (1..7).map { i -> f.node("n$i", ScheduleDslOperator.ALL_OF, if (i == 1) "ar" else "n${i - 1}", "br") }
        val program = f.program(nodes, "n7")
        assertEquals(f.envelope(claims, program), validator.validate(f.proposal(claims, program), f.ir.snapshot()))
    }

    @Test
    fun `DSLV04 superseded history preserves meaning without hiding an active negative`() {
        val old = f.claim("old", 19, 20, IrEffect.UNAVAILABLE)
        val active = f.claim("active", 18, 22).copy(supersedes = listOf("old"))
        val program = f.program(listOf(f.ref("root", "active")), "root", history = listOf("old"))
        val claims = listOf(old, active)
        assertEquals(f.envelope(claims, program), validator.validate(f.proposal(claims, program), f.ir.snapshot()))
    }
}
