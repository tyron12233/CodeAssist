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
 * `a..b` must lower for every common element type — not just `Int`. `Float`/`Double` (`0f..50f`, a Slider's
 * `valueRange`) resolve through the stdlib `rangeTo` EXTENSION; `Int`/`Long`/`Char` construct their range type.
 * Before the fix `0f..50f` lowered to `unsupported("range over kotlin.Float")` and blanked the preview.
 */
class RangeLoweringTest {

    private fun lowerFirstFn(code: String): dev.ide.lang.kotlin.interp.ResolvedFunction {
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = listOf(stdlibJarPath()))
        val kt = KotlinParserHost.parse("Main.kt", code)
        val parsed = KotlinParsedFile(kt, FakeFile("Main.kt"), 0)
        return assertNotNull(KotlinTreeResolver(kt, parsed, service).lowerFirstFunction())
    }

    private fun rangeCall(code: String): RNode.Call {
        val fn = lowerFirstFn(code)
        assertTrue(fn.isComplete, "`$code` must lower cleanly; diags=${fn.diagnostics.map { it.reason }}")
        var found: RNode.Call? = null
        fn.body.walk { if (it is RNode.Call && (it.callee.displayName.contains("Range") || it.callee.displayName == "rangeTo")) found = it }
        return assertNotNull(found, "the range must lower to a Call")
    }

    @Test fun floatRangeLowersToRangeToExtension() {
        val call = rangeCall("package demo\nfun f(): Any = 0f..50f\n")
        assertTrue(call.dispatch == DispatchKind.EXTENSION, "a Float range is the `rangeTo` extension, was ${call.dispatch}")
    }

    @Test fun doubleRangeLowersToRangeToExtension() {
        val call = rangeCall("package demo\nfun f(): Any = 0.0..50.0\n")
        assertTrue(call.dispatch == DispatchKind.EXTENSION, "a Double range is the `rangeTo` extension, was ${call.dispatch}")
    }

    @Test fun intRangeLowersToIntRangeConstructor() {
        val call = rangeCall("package demo\nfun f(): Any = 0..50\n")
        assertTrue(call.dispatch == DispatchKind.CONSTRUCTOR, "an Int range constructs IntRange, was ${call.dispatch}")
    }

    @Test fun longRangeLowersToLongRangeConstructor() {
        val call = rangeCall("package demo\nfun f(): Any = 0L..50L\n")
        assertTrue(call.dispatch == DispatchKind.CONSTRUCTOR, "a Long range constructs LongRange, was ${call.dispatch}")
    }

    @Test fun charRangeLowersToCharRangeConstructor() {
        val call = rangeCall("package demo\nfun f(): Any = 'a'..'z'\n")
        assertTrue(call.dispatch == DispatchKind.CONSTRUCTOR, "a Char range constructs CharRange, was ${call.dispatch}")
    }
}
