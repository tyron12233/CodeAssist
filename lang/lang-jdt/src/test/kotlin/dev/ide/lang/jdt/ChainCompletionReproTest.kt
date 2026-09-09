package dev.ide.lang.jdt

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Member completion after a method chain whose receiver contains a lambda.
 *
 * The completion technique splices a marker at the caret. A bare `recv.__marker__` is an illegal Java
 * expression-statement (JLS 14.8); ecj's statement recovery salvages a partial node for simple receivers,
 * but discards the entire statement when the receiver contains a lambda — so `…map(it -> …).` used to
 * resolve no type and offer nothing. The fix adds a method-call splice (`recv.__marker__()`, a legal
 * statement-expression) as a member-access fallback. These tests pin the chain at progressive depths.
 */
class ChainCompletionReproTest {

    private fun complete(code: String): List<String> {
        val (analyzer, dir) = workspaceWith()
        return try {
            completeLabels(analyzer, dir.resolve("app/T.java"), code)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun wrap(expr: String) = "package app; class T { void m() { $expr|CARET| } }"

    @Test
    fun completesAfterStaticCallResult() {
        // List.of(1) -> List<Integer>
        assertTrue("stream" in complete(wrap("java.util.List.of(1).")), "expected List members incl stream")
    }

    @Test
    fun completesAfterStreamResult() {
        // .stream() -> Stream<Integer>
        assertTrue("map" in complete(wrap("java.util.List.of(1).stream().")), "expected Stream members incl map")
    }

    @Test
    fun completesAfterGenericMapWithLambda() {
        // The reported bug: .map(it -> it.toString()) -> Stream<String>
        val labels = complete(wrap("java.util.List.of(1).stream().map(it -> it.toString())."))
        assertTrue(labels.containsAll(listOf("map", "filter", "collect")), "expected Stream members after lambda: $labels")
    }

    @Test
    fun lambdaInEarlierStatementDoesNotBreakNextCompletion() {
        // A lambda anywhere in the body used to wipe the whole body during recovery, killing later completion.
        val labels = complete(
            "package app; class T { void m() { java.util.List.of(1).stream().map(it -> it.toString()).count(); String s = \"\"; s.|CARET| } }",
        )
        assertTrue(labels.containsAll(listOf("length", "substring", "isEmpty")), "expected String members: $labels")
    }

    @Test
    fun blockBodyLambdaAlsoCompletes() {
        val labels = complete(wrap("java.util.List.of(1).stream().map(it -> { return it.toString(); })."))
        assertTrue("collect" in labels, "expected Stream members after block-body lambda: $labels")
    }
}
