package dev.ide.kotlin.syntax.port

import dev.ide.kotlin.syntax.KotlinSyntax
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.collectDescendantsOfType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the port probe, rather than only compiling it.
 *
 * That the copied file compiles is the cheap half of the answer and the misleading one: a facade can satisfy
 * every type and still hand back the wrong node. `KotlinControlFlow` is real analysis with real expectations
 * — which statements are unreachable, which locals are read before assignment — so making it produce the
 * right answers over the vendored tree is what actually says the bridge works.
 *
 * It runs on the iOS simulator too, which is the point of the exercise: this is `:lang-kotlin`'s own code,
 * unedited apart from imports, executing off the JVM.
 */
class PortProbeTest {

    private fun bodyOf(source: String): KtBlockExpression {
        val file = KotlinSyntax.parseFile(source)
        val function = file.declarations.filterIsInstance<KtNamedFunction>().last()
        return assertNotNull(function.bodyBlockExpression, "expected a block body in:\n$source")
    }

    private val flow = KotlinControlFlow(KotlinResolver())

    @Test
    fun deadCodeAfterAReturnIsFound() {
        val dead = flow.deadStatements(bodyOf("fun f(): Int {\n    return 1\n    val unreachable = 2\n}"))
        assertEquals(1, dead.size, "the statement after `return` is unreachable; got $dead")
        assertTrue(dead.single().text.contains("unreachable"))
    }

    @Test
    fun codeAfterAConditionalReturnIsNotDead() {
        // The conservative direction, and the one that matters: a false positive here would grey out live code.
        val dead = flow.deadStatements(bodyOf("fun f(a: Boolean): Int {\n    if (a) return 1\n    return 2\n}"))
        assertTrue(dead.isEmpty(), "nothing after a conditional return is unreachable; got $dead")
    }

    @Test
    fun deadCodeAfterThrowAndAfterBreak() {
        assertEquals(
            1,
            flow.deadStatements(bodyOf("fun f() {\n    throw RuntimeException()\n    val x = 1\n}")).size,
        )
        val inLoop = bodyOf("fun f() {\n    while (true) {\n        break\n        val x = 1\n    }\n}")
        val loopBody = inLoop.collectDescendantsOfType<KtBlockExpression>().last()
        assertEquals(1, flow.deadStatements(loopBody).size)
    }

    @Test
    fun aLocalReadBeforeItIsAssignedIsFound() {
        val reads = flow.uninitializedReads(bodyOf("fun f() {\n    val x: Int\n    println(x)\n    x = 1\n}"))
        assertEquals(listOf("x"), reads.map { it.text }, "`x` is read before assignment")
    }

    @Test
    fun aLocalAssignedBeforeUseIsNotReported() {
        val reads = flow.uninitializedReads(bodyOf("fun f() {\n    val x: Int\n    x = 1\n    println(x)\n}"))
        assertTrue(reads.isEmpty(), "`x` is assigned before it is read; got ${reads.map { it.text }}")
    }

    @Test
    fun lateinitIsNotAnUninitializedRead() {
        // This one goes through the renamed-token shim: PSI's LATEINIT_KEYWORD is LATEINIT_MODIFIER upstream.
        val reads = flow.uninitializedReads(bodyOf("fun f() {\n    lateinit var x: String\n    println(x)\n}"))
        assertTrue(reads.isEmpty(), "a `lateinit` local is not an uninitialized read; got ${reads.map { it.text }}")
    }
}
