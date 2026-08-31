package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An operator's OWN type parameters are inferred from its operands, so the result type is the substituted one.
 *
 * An operator convention (`ctx[key]`, `greeter(name)`) looks its member up by name rather than going through
 * the call path, and that lookup only substituted the RECEIVER's type arguments — enough for `Map<K, V>.get`
 * (whose `V` comes from the receiver) but not for a method-level parameter. `CoroutineContext`'s
 * `operator fun <E : Element> get(key: Key<E>): E?` therefore handed back a bare `E?`.
 *
 * The reported shape, which needs that AND the companion rule below:
 *
 *     suspend fun printName() { println(coroutineContext[CoroutineName]?.name) }
 *
 * `CoroutineName` in argument position is the CLASS name, but as a value it denotes the class's COMPANION
 * (`CoroutineName.Key`, the `CoroutineContext.Key<CoroutineName>`). A name reference types as its classifier —
 * which is what `Type.NESTED` / `Type.CONST` qualifier resolution needs — so unification projects the companion
 * when the class itself is not a subtype of the parameter's generic classifier.
 */
class KotlinGenericOperatorInferenceTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Main.kt", code) }.items.map { it.label }

    private fun diagnose(code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Main.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private val head =
        "package demo\nimport kotlin.coroutines.coroutineContext\nimport kotlinx.coroutines.CoroutineName\n"

    // ---- the reported case ----

    @Test fun coroutineContextKeyLookupOffersTheElementsMembers() {
        assertTrue(
            "name" in labels(head + "suspend fun printName() { coroutineContext[CoroutineName]?.| }"),
            "coroutineContext[CoroutineName] is a CoroutineName?, so `name` must be offered",
        )
    }

    @Test fun coroutineContextKeyLookupIsNotFlagged() {
        val code = head + "suspend fun printName() { println(coroutineContext[CoroutineName]?.name) }"
        assertTrue(
            diagnose(code).none { it.severity.name == "ERROR" },
            "the reported line must resolve cleanly; got ${diagnose(code)}",
        )
    }

    @Test fun aCompanionKeyOfAnotherElementTypeResolvesToo() {
        // `Job`'s companion is its Key as well; `isActive` is a Job member, so the lookup must type as Job?.
        assertTrue(
            "isActive" in labels(
                head + "import kotlinx.coroutines.Job\nsuspend fun f() { coroutineContext[Job]?.| }",
            ),
            "coroutineContext[Job] is a Job?, so `isActive` must be offered",
        )
    }

    // ---- the operator rule itself, over source declarations ----

    @Test fun genericGetOperatorInfersItsOwnTypeParameter() {
        assertTrue(
            "label" in labels("package demo\nfun f(k: Key<Tag>) { val c: Ctx = ctx(); c[k]?.| }"),
            "`get(key: Key<E>): E?` must infer E = Tag from the index expression",
        )
    }

    @Test fun anExplicitCallOfTheGenericGetOperatorInfersItToo() {
        assertTrue(
            "label" in labels("package demo\nfun f(k: Key<Tag>) { val c: Ctx = ctx(); c.get(k)?.| }"),
            "the same operator called by name must infer E the same way",
        )
    }

    @Test fun genericInvokeOperatorInfersItsOwnTypeParameter() {
        assertTrue(
            "label" in labels("package demo\nfun f(k: Key<Tag>) { val p = Picker(); p(k)?.| }"),
            "`invoke(key: Key<E>): E?` must infer E = Tag from the argument",
        )
    }

    // ---- control: an operator whose type arguments come from the RECEIVER is unchanged ----

    @Test fun receiverTypeArgumentsStillDriveGet() {
        assertTrue(
            "label" in labels("package demo\nfun f() { mapOf(1 to Tag(\"a\"))[1]?.| }"),
            "Map<K, V>.get takes V from the receiver's type arguments; that path must still work",
        )
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Ctx.kt" to """
                    package demo
                    interface El
                    class Tag(val label: String) : El
                    interface Key<E : El>
                    interface Ctx { operator fun <E : El> get(key: Key<E>): E? }
                    class Picker { operator fun <E : El> invoke(key: Key<E>): E? = null }
                    fun ctx(): Ctx = TODO()
                """.trimIndent(),
            ),
        )
        val analyzer = KotlinSourceAnalyzer(
            fakeContext(
                srcDir,
                libJars = listOf(stdlibJarPath(), TestJars.onClasspath("kotlinx/coroutines/CoroutineName.class")),
            ),
        )
    }
}
