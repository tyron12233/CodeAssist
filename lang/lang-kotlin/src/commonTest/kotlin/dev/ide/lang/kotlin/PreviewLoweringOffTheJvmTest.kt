package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.lang.kotlin.interp.KotlinComposePreviews
import dev.ide.lang.kotlin.interp.KotlinTreeResolver
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.children
import dev.ide.lang.kotlin.interp.reachableSourceFunctions
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.vfs.VirtualFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The first half of Compose preview, on whatever platform this runs on: finding a `@Preview` and lowering
 * its body to the tree an interpreter can walk.
 *
 * The preview pipeline is four stages — discover, lower, interpret, render — and only the last two need the
 * host's Compose runtime. These two are pure source analysis, and until now they lived in `jvmMain` for no
 * reason anyone had tested: the tree resolver reads the vendored multiplatform parser (`:kotlin-syntax`) and
 * the same symbol service the editor runs, neither of which is JVM-bound any more.
 *
 * Compiling for a target proves nothing about whether it runs, so this is deliberately end to end: source on
 * disk, a real symbol service over it, and the lowered tree's shape asserted — including that a cross-
 * function call is REACHED, which is what the preview gate refuses a broken helper on.
 */
class PreviewLoweringOffTheJvmTest {

    private val dir = scratchPath("preview-lowering-${nowSuffix()}")

    @AfterTest
    fun cleanUp() {
        deleteTree(dir)
    }

    /**
     * A file shaped like a real preview: an annotated composable that calls a helper in the same file.
     *
     * `@Preview`/`@Composable` are matched by SIMPLE NAME (that is how the discovery is written, so it needs
     * no androidx on the classpath), which is what lets this run with no Compose artifacts at all.
     */
    private val source = """
        package demo

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        @Preview(name = "Light", showBackground = true)
        @Composable
        fun HelloPreview() {
            val value = passThrough(42)
            Greeting(value)
        }

        @Preview
        @Composable
        fun BrokenPreview() {
            Greeting(doubled(21))
        }

        @Composable
        fun Greeting(value: Int) {
        }

        fun passThrough(n: Int): Int = n

        // A call to something that does not exist anywhere: the lowering cannot produce a node for it on any
        // platform, so this is the stand-in for a helper the interpreter must not be handed, and what the
        // preview gate exists to refuse.
        fun doubled(n: Int): Int = noSuchHelper(n)
    """.trimIndent()

    private fun service(): KotlinSymbolService {
        writeSourceFile(dir, "Hello.kt", source)
        return KotlinSymbolService(
            sourceRoots = listOf<VirtualFile>(DiskSourceFile(dir)),
            classpathJars = emptyList(),
        )
    }

    @Test
    fun aPreviewIsFoundWithItsAnnotationArguments() {
        val kt = assertNotNull(KotlinParserHost.parse("Hello.kt", source))

        val previews = KotlinComposePreviews.find(kt)

        assertEquals(listOf("HelloPreview", "BrokenPreview"), previews.map { it.functionName })
        val preview = previews.first()
        assertEquals("Light", preview.label, "the `name` argument is the label the selector shows")
        assertTrue(preview.config.showBackground, "and the render-affecting arguments are parsed")
        assertEquals(0, preview.arity, "a plain preview takes no parameters")
    }

    @Test
    fun thePreviewBodyLowersToATreeAnInterpreterCouldWalk() {
        val kt = assertNotNull(KotlinParserHost.parse("$dir/Hello.kt", source))
        service().use { service ->
            val parsed = KotlinParsedFile(kt, DiskSourceFile("$dir/Hello.kt"), 1)
            val resolver = KotlinTreeResolver(kt, parsed, service)
            val fn = kt.declarations.filterIsInstance<KtNamedFunction>().first { it.name == "HelloPreview" }

            val lowered = resolver.lowerFunction(fn)

            assertEquals("HelloPreview", lowered.name)
            assertTrue(lowered.isComplete, "no unsupported nodes: ${lowered.diagnostics.map { it.reason }}")
            // The body is a local `val` bound to a call, then a call to the other composable. Both calls
            // resolve to SOURCE callees, which is what makes them interpretable rather than dispatched.
            val calls = lowered.body.calls()
            assertEquals(
                listOf("passThrough", "Greeting"),
                calls.map { (it.callee as ResolvedCallable.Source).displayName.substringBefore('(') },
                "both callees resolve to project source",
            )
        }
    }

    /**
     * The preview gate's question: what else has to lower before this one may render.
     *
     * A preview whose entry merely calls a broken helper must be refused rather than thrown out of mid-render,
     * so the reachability walk is part of the contract and not an optimisation. It is also the one place the
     * lowering used an `IdentityHashMap`, which is why it is asserted here rather than taken on trust.
     */
    @Test
    fun theWalkReachesEveryFunctionThePreviewCalls() {
        val kt = assertNotNull(KotlinParserHost.parse("$dir/Hello.kt", source))
        service().use { service ->
            val parsed = KotlinParsedFile(kt, DiskSourceFile("$dir/Hello.kt"), 1)
            val resolver = KotlinTreeResolver(kt, parsed, service)
            val functions = kt.declarations.filterIsInstance<KtNamedFunction>()
                .associate { it.name!! to resolver.lowerFunction(it) }
            val program = functions.values.associateBy { "${it.name}/${it.params.size}" }

            val reached = reachableSourceFunctions(functions.getValue("HelloPreview"), program, emptyList())

            assertEquals(
                setOf("HelloPreview", "passThrough", "Greeting"),
                reached.map { it.name }.toSet(),
                "the entry plus everything it calls, and nothing it does not",
            )
            assertTrue(
                reached.all { it.isComplete },
                "and all of it lowered cleanly, which is the gate; incomplete: " +
                    reached.filterNot { it.isComplete }.map { "${it.name}: ${it.diagnostics.map { d -> d.reason }}" },
            )

            // And the other way round: a preview whose helper did NOT lower is refused before it renders,
            // rather than throwing out of the interpreter mid-composition.
            val broken = reachableSourceFunctions(functions.getValue("BrokenPreview"), program, emptyList())
            assertEquals(setOf("BrokenPreview", "Greeting", "doubled"), broken.map { it.name }.toSet())
            assertTrue(broken.any { !it.isComplete }, "the gate sees the helper it cannot interpret")
        }
    }

    /** Every call in a lowered body, depth first. */
    private fun RNode?.calls(): List<RNode.Call> {
        if (this == null) return emptyList()
        val here = if (this is RNode.Call) listOf(this) else emptyList()
        return here + children().flatMap { it.calls() }
    }
}
