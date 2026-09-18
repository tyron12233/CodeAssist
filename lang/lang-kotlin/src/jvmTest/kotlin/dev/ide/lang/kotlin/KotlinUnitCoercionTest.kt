package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Kotlin's coercion of a lambda's result to `Unit`: when the value of a lambda-taking generic call is
 * expected to be `Unit`, the block's last expression is DISCARDED rather than typed into the call's result.
 * `suspend fun main(): Unit = coroutineScope { launch { … } }` is valid Kotlin: `coroutineScope`'s `<R>` is
 * fixed to `Unit` by the expected type, not to the trailing `launch`'s `Job`, which the editor was reporting
 * as "Type mismatch: inferred type is Job but Unit was expected" over the whole body.
 */
class KotlinUnitCoercionTest {

    private fun diagnose(code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private val IMPORTS = "package demo\nimport kotlinx.coroutines.*\n"

    @Test fun expressionBodyUnitAcceptsJobReturningBlock() {
        val diags = diagnose(
            IMPORTS + "suspend fun main(): Unit = coroutineScope {\n" +
                "    launch { println(\"x\") }\n" +
                "}\n",
        )
        assertTrue(
            diags.none { it.code == "kt.typeMismatch" },
            "a Unit-expected coroutineScope whose block ends in launch must not be a mismatch; got $diags",
        )
    }

    @Test fun reportedProducerConsumerShapeIsClean() {
        // The reported buffer verbatim: a `Unit` expression body whose block ends in a `launch`, with the
        // whole 15-line body underlined by that one mismatch.
        val diags = diagnose(
            IMPORTS + "import kotlinx.coroutines.channels.Channel\n" +
                "suspend fun main(): Unit = coroutineScope {\n" +
                "    val channel = Channel<Int>()\n" +
                "    launch {\n" +
                "        repeat(5) { index ->\n" +
                "            delay(100)\n" +
                "            channel.send(index * 2)\n" +
                "        }\n" +
                "    }\n" +
                "    launch {\n" +
                "        repeat(5) {\n" +
                "            val received = channel.receive()\n" +
                "            println(received)\n" +
                "        }\n" +
                "    }\n" +
                "}\n",
        )
        assertTrue(diags.isEmpty(), "the reported producer/consumer main must diagnose clean; got $diags")
    }

    @Test fun runBlockingBlockResultIsCoercedToo() {
        val diags = diagnose(IMPORTS + "fun main(): Unit = runBlocking {\n    launch { }\n}\n")
        assertTrue(diags.none { it.code == "kt.typeMismatch" }, "runBlocking has the same shape; got $diags")
    }

    @Test fun nonUnitExpectedTypeStillBindsTheBlockResult() {
        // Coercion applies only where `Unit` is expected: a `Job` return keeps binding `<R>` from the block,
        // so the declaration is satisfied (and typing it `Unit` would have false-flagged it).
        val diags = diagnose(IMPORTS + "suspend fun f(): Job = coroutineScope {\n    launch { }\n}\n")
        assertTrue(diags.none { it.code == "kt.typeMismatch" }, "`<R>` must still bind to Job; got $diags")
    }

    @Test fun wrongDeclaredTypeThroughABlockIsStillReported() {
        val diags = diagnose(IMPORTS + "suspend fun f(): String = coroutineScope {\n    launch { }\n}\n")
        assertTrue(
            diags.any { it.code == "kt.typeMismatch" },
            "a Job block result under a String return is a real mismatch; got $diags",
        )
    }

    @Test fun unitReturningBlockAcceptsANonUnitBody() {
        // The same rule in a lambda whose functional return is DECLARED `Unit`: `forEach`'s `(T) -> Unit` block
        // ending in `add` (a `Boolean`) is valid: the result is discarded, not fitted to Unit.
        val diags = diagnose(IMPORTS + "fun f(xs: List<Int>, out: MutableList<Int>) { xs.forEach { out.add(it) } }\n")
        assertTrue(diags.isEmpty(), "a Boolean-bodied Unit block must diagnose clean; got $diags")
    }

    @Test fun returnedBlockResultIsCoercedInAUnitFunction() {
        // The `return` form of the same shape. A block body with no declared type returns `Unit`, so the
        // returned `coroutineScope`'s `<R>` is `Unit` too, and `return <a Unit value>` is legal there.
        val diags = diagnose(
            IMPORTS + "suspend fun f() {\n    return coroutineScope {\n        launch { }\n    }\n}\n",
        )
        assertTrue(diags.none { it.code == "kt.typeMismatch" }, "a returned Unit-coerced block is legal; got $diags")
    }

    @Test fun returningARealValueFromAUnitFunctionIsStillReported() {
        val diags = diagnose(IMPORTS + "fun f() {\n    return listOf(1)\n}\n")
        assertTrue(
            diags.any { it.code == "kt.typeMismatch" },
            "returning a List from a Unit function is a real mismatch; got $diags",
        )
    }

    @Test fun realMismatchInExpressionBodyStillReported() {
        val diags = diagnose(IMPORTS + "fun f(): Int = \"s\"\n")
        assertTrue(diags.any { it.code == "kt.typeMismatch" }, "a real mismatch must still be reported; got $diags")
    }

    @Test fun unitExpressionBodyOfPlainValueStillReported() {
        // Coercion to Unit applies to a LAMBDA's result, not to a function's expression body: `fun f(): Unit = 5`
        // is a genuine error and must stay reported.
        val diags = diagnose(IMPORTS + "fun f(): Unit = 5\n")
        assertTrue(diags.any { it.code == "kt.typeMismatch" }, "`fun f(): Unit = 5` is a real mismatch; got $diags")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Marker.kt" to "package demo\nclass Marker"))
        val analyzer = KotlinSourceAnalyzer(
            fakeContext(
                srcDir,
                libJars = listOf(stdlibJarPath(), TestJars.onClasspath("kotlinx/coroutines/Deferred.class")),
            ),
        )
    }
}
