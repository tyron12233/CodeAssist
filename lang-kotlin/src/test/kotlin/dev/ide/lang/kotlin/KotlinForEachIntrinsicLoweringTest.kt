package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.KotlinTreeResolver
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.walk
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `list.forEach { … }` / `forEachIndexed` must lower to a CollectionsKt-owned EXTENSION Call — the exact shape
 * the interpreter's inline `forEach` intrinsic (`Interpreter.evalInlineIntrinsic`) requires so a
 * composable-emitting loop body (`Column { list.forEach { Row { … } } }`) renders into the ambient composition.
 * On device the call otherwise resolves to `java.lang.Iterable.forEach(Consumer)` (a MEMBER) or ties out to
 * ambiguous — both bypass the intrinsic and the loop's composables never render. A user's own same-named
 * extension must NOT be canonicalized.
 */
class KotlinForEachIntrinsicLoweringTest {

    private fun lower(service: KotlinSymbolService, code: String): ResolvedFunction {
        val kt = KotlinParserHost.parse("Use.kt", code)
        val parsed = KotlinParsedFile(kt, FakeFile("Use.kt"), 0)
        return assertNotNull(KotlinTreeResolver(kt, parsed, service).lowerFirstFunction())
    }

    private fun callNamed(fn: ResolvedFunction, name: String): RNode.Call {
        var found: RNode.Call? = null
        fn.body.walk { if (it is RNode.Call && it.callee.displayName == name) found = it }
        return assertNotNull(found, "the `$name` call must lower to a Call")
    }

    @Test
    fun listForEachLowersToTheInlineExtensionIntrinsicShape() {
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = listOf(stdlibJarPath()))
        val fn = lower(service, "fun use(xs: List<Int>) { xs.forEach { println(it) } }")
        assertTrue(fn.isComplete, "forEach over a List must lower; diags=${fn.diagnostics.map { it.reason }}")
        val call = callNamed(fn, "forEach")
        assertEquals(DispatchKind.EXTENSION, call.dispatch, "forEach must dispatch EXTENSION for the intrinsic")
        val callee = assertNotNull(call.callee as? ResolvedCallable.Library, "forEach must be a library callee")
        assertEquals("kotlin.collections.CollectionsKt", callee.ownerFqn, "owner must be the intrinsic facade")
        assertTrue(callee.isInline, "the intrinsic facade callee is inline")
    }

    @Test
    fun listForEachIndexedLowersToTheInlineExtensionIntrinsicShape() {
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = listOf(stdlibJarPath()))
        val fn = lower(service, "fun use(xs: List<Int>) { xs.forEachIndexed { i, e -> println(i + e) } }")
        assertTrue(fn.isComplete, "forEachIndexed over a List must lower; diags=${fn.diagnostics.map { it.reason }}")
        val call = callNamed(fn, "forEachIndexed")
        assertEquals(DispatchKind.EXTENSION, call.dispatch)
        assertEquals("kotlin.collections.CollectionsKt", (call.callee as ResolvedCallable.Library).ownerFqn)
    }

    @Test
    fun userDefinedForEachExtensionIsNotHijacked() {
        // A same-named user extension must resolve to the user's own SOURCE declaration, not be rewritten to the
        // CollectionsKt intrinsic (the canonicalization is gated on a genuine kotlin.collections candidate).
        // Backed by a real source root so the source extension resolves; `use` is first so lowerFirstFunction
        // lowers it (not the extension declaration).
        val dir = tempProject(
            mapOf(
                "Use.kt" to """
                    package demo
                    fun use(b: Box) { b.forEach { } }
                    class Box
                    fun Box.forEach(action: () -> Unit) { action() }
                """.trimIndent(),
            ),
        )
        val service = KotlinSymbolService(sourceRoots = listOf(DiskFile(dir)), classpathJars = listOf(stdlibJarPath()))
        val text = Files.readString(dir.resolve("Use.kt"))
        val kt = KotlinParserHost.parse("Use.kt", text)
        val parsed = KotlinParsedFile(kt, DiskFile(dir.resolve("Use.kt")), 0)
        val fn = assertNotNull(KotlinTreeResolver(kt, parsed, service).lowerFirstFunction())
        val callee = callNamed(fn, "forEach").callee
        assertTrue(
            callee is ResolvedCallable.Source && callee.declId.startsWith("demo."),
            "a user forEach extension must stay a source callee, not be canonicalized; got $callee",
        )
    }
}
