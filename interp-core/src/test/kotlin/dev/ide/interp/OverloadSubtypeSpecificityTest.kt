package dev.ide.interp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Kotlin's most-specific-overload rule by SUBTYPING: when no overload's parameter EXACTLY equals the argument
 * type but subtyping still orders them, the nearest supertype wins. The preview lowerer's `chooseCallee` used to
 * narrow only by exact-type match, so these tied out to "unresolved/ambiguous call" and blanked the preview even
 * though the editor resolver (`bestOverload`/`paramMoreSpecific`) resolves them. Sibling of the member-vs-extension
 * `addAll` gap.
 */
class OverloadSubtypeSpecificityTest {

    @Test
    fun mostSpecificBySubtypeWinsWhenNeitherParamIsExact() {
        // `listOf(...)` is a List; neither `Iterable` nor `Collection` is an exact match, but `Collection` is more
        // specific (a subtype of `Iterable`), so real kotlin picks `pick(Collection)`.
        val code = """
            fun pick(x: Iterable<String>): String = "iterable"
            fun pick(x: Collection<String>): String = "collection"
            fun box(): String = pick(listOf("a", "b"))
        """.trimIndent()
        assertEquals("collection", runProgram(code, "box/0", emptyList()))
    }

    @Test
    fun mostSpecificBySubtypeWinsForCharSequenceVsAny() {
        // A String argument: `String` is neither `CharSequence` nor `Any` exactly, but `CharSequence` is nearer.
        // (An exact `String` overload was already handled; `CharSequence` vs `Any` is the case exact-only missed.)
        val code = """
            fun tag(x: Any): String = "any"
            fun tag(x: CharSequence): String = "charseq"
            fun box(): String = tag("hi")
        """.trimIndent()
        assertEquals("charseq", runProgram(code, "box/0", emptyList()))
    }
}
