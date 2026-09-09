package dev.ide.interp.compose

import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.walk
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A capitalized call whose name is ALSO a type must reach the top-level FACTORY function when the type itself
 * cannot be constructed. `androidx.compose.ui.text.font.FontFamily` is a `sealed class` with
 * `fun FontFamily(vararg fonts: Font)` beside it, and the reported bug rendered a Material 3 theme's `Type.kt`
 * as `InstantiationException: Can't instantiate abstract class androidx.compose.ui.text.font.FontFamily`.
 *
 * Two independent defects produced it, one per test here:
 *  1. the ARGUMENT `Font(…)` typed as a non-existent type, because `resolveTypeName` import-qualifies a bare
 *     name against imports and a Kotlin import names CALLABLES too — so an import of a package that has a
 *     top-level `Font(…)` and no `Font` class produced a "type" nothing binds to, and every `FontFamily`
 *     overload lost applicability;
 *  2. with the overloads tied out, the lowering fabricated a reflective CONSTRUCTOR for any known type name,
 *     abstract ones included.
 */
class FactoryFunctionOverTypeNameTest {

    /**
     * End-to-end: the reported shape must lower to the `FontFamilyKt` factory, never to a constructor of the
     * sealed `FontFamily`. `androidx.compose.ui.text.platform` stands in for the Android-only
     * `androidx.compose.ui.text.googlefonts` here — both are packages that export a top-level `Font(…)` and no
     * `Font` class, which is the only property the defect turned on.
     */
    @Test
    fun factoryFunctionWinsOverTheSealedTypeItShadows() {
        val call = lowerFontFamilyCall(
            """
            package demo
            import androidx.compose.ui.text.font.FontFamily
            import androidx.compose.ui.text.platform.Font
            val bodyFontFamily = FontFamily(Font("font.ttf"))
            """.trimIndent(),
        )
        assertEquals(
            DispatchKind.TOP_LEVEL, call.dispatch,
            "`FontFamily(Font(…))` must call the factory function, not construct the sealed class",
        )
        val callee = assertNotNull(call.callee as? ResolvedCallable.Library)
        assertEquals("androidx.compose.ui.text.font.FontFamilyKt", callee.ownerFqn)
    }

    /**
     * The argument's type must actually be INFERRED, not merely tolerated as unknown: only a known
     * `androidx.compose.ui.text.font.Font` argument narrows `FontFamily`'s four same-arity overloads
     * (`vararg Font` / `List<Font>` / `Typeface` / `String`) to the vararg one. Asserting the chosen overload
     * — rather than just "some factory" — is what keeps the type-inference half of the fix honest.
     */
    @Test
    fun theFactoryArgumentTypeNarrowsTheOverloadSet() {
        val call = lowerFontFamilyCall(
            """
            package demo
            import androidx.compose.ui.text.font.FontFamily
            import androidx.compose.ui.text.platform.Font
            val bodyFontFamily = FontFamily(Font("font.ttf"))
            """.trimIndent(),
        )
        val callee = assertNotNull(call.callee as? ResolvedCallable.Library)
        assertEquals(0, callee.varargParamIndex, "must pick `FontFamily(vararg fonts: Font)`; got $callee")
        assertEquals(
            listOf("androidx.compose.ui.text.font.Font"), callee.paramTypes.map { it?.qualifiedName },
            "the `Font(…)` argument must type as a real Font, which is what narrows the overloads",
        )
    }

    /** Lower [code] and return its single `FontFamily(…)` call. */
    private fun lowerFontFamilyCall(code: String): RNode.Call {
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(previewSymbolService()).program(parsed)
        val entry = assertNotNull(program["bodyFontFamily/0"], "the property must lower; keys=${program.keys}")
        assertTrue(
            entry.diagnostics.isEmpty(),
            "the property must lower cleanly; got ${entry.diagnostics.map { it.reason }}",
        )
        var found: RNode.Call? = null
        entry.body.walk { if (it is RNode.Call && it.callee.displayName == "FontFamily") found = it }
        return assertNotNull(found, "`FontFamily(…)` must lower to a Call")
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = F()
        override val version: Long = 1
        override fun length(): Int = text.length
    }

    private class F : VirtualFile {
        override val path = "Type.kt"; override val name = "Type.kt"; override val isDirectory = false
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
