package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.KotlinTreeResolver
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.walk
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A qualified call `Type.Nested(args)` where `Nested` is a NESTED CLASS is a CONSTRUCTOR of that nested class
 * (`GridCells.Fixed(2)`) — not a member of the outer type. The preview lowerer must resolve it to a CONSTRUCTOR
 * call, not fail with "unresolved/ambiguous call `Nested` (candidates=0, recv=Type)" which blanks the preview.
 * (Covers the SOURCE nested case here; the library case — `GridCells.Fixed(2)` against real Compose — is in
 * interp-compose's `LibraryNestedClassConstructorTest`.)
 */
class NestedClassConstructorLoweringTest {

    private fun lowerFirstFn(code: String): dev.ide.lang.kotlin.interp.ResolvedFunction {
        val dir = tempProject(mapOf("Main.kt" to code)) // a real source root so `Outer` resolves to `demo.Outer`
        val service = KotlinSymbolService(sourceRoots = listOf(DiskFile(dir)), classpathJars = emptyList())
        val kt = KotlinParserHost.parse("Main.kt", code)
        val parsed = KotlinParsedFile(kt, DiskFile(dir.resolve("Main.kt")), 0)
        return assertNotNull(KotlinTreeResolver(kt, parsed, service).lowerFirstFunction())
    }

    @Test
    fun sourceNestedClassCallLowersToConstructor() {
        val fn = lowerFirstFn("package demo\nclass Outer { class Inner(val n: Int) }\nfun f() { val x = Outer.Inner(2) }\n")
        assertTrue(fn.isComplete, "`Outer.Inner(2)` must lower cleanly; diags=${fn.diagnostics}")
        var ctor: RNode.Call? = null
        fn.body.walk { if (it is RNode.Call && it.callee.displayName == "Inner") ctor = it }
        val call = assertNotNull(ctor, "the `Inner(2)` call must lower to a Call")
        assertTrue(call.dispatch == DispatchKind.CONSTRUCTOR, "a nested-class call must be a CONSTRUCTOR, was ${call.dispatch}")
    }
}
