package dev.codeassist.ndk

import dev.ide.lang.highlight.HighlightKind
import dev.ide.lang.highlight.HighlightModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Reading the daemon's `tokens` answer into editor ranges. */
class ClangSemanticTokensTest {

    private fun spelled(output: String, text: String) =
        ClangSemanticTokens.parse(output, text).map { text.substring(it.range.start, it.range.end) to it.kind.id }

    @Test
    fun readsKindsAndRanges() {
        val text = "int count = 0;\nint main() { return count; }\n"
        val tokens = ClangSemanticTokens.parse("4 5 field SD\n19 4 function D\n35 5 field S\n", text)
        assertEquals(listOf("count", "main", "count"), tokens.map { text.substring(it.range.start, it.range.end) })
        assertEquals(HighlightKind.FIELD, tokens[0].kind)
        assertEquals(setOf(HighlightModifier.STATIC, HighlightModifier.DECLARATION), tokens[0].modifiers)
        assertEquals(HighlightKind.FUNCTION, tokens[1].kind)
        assertEquals(setOf(HighlightModifier.STATIC), tokens[2].modifiers)
    }

    @Test
    fun noModifiersIsADash() {
        val text = "x"
        assertEquals(emptySet<HighlightModifier>(), ClangSemanticTokens.parse("0 1 localVariable -", text).single().modifiers)
        assertEquals(setOf(HighlightModifier.READONLY, HighlightModifier.DEPRECATED),
            ClangSemanticTokens.parse("0 1 constant RX", text).single().modifiers)
    }

    /** clang counts UTF-8 bytes; the editor counts chars. An accent before a token must not shift it. */
    @Test
    fun byteOffsetsAfterNonAsciiTextAreConverted() {
        val text = "// café 😀\nint value = 1;\n"
        // "// café 😀\n" is 3 + 5 (é is 2 bytes) + 1 + 4 (😀) + 1 = 14 bytes; "int " adds 4.
        assertEquals(listOf("value" to "localVariable"), spelled("18 5 localVariable D\n", text))
    }

    @Test
    fun aMacroKeepsItsOwnKind() {
        val text = "#define MAX 3\nint a = MAX;\n"
        assertEquals(listOf("MAX" to ClangSemanticTokens.MACRO_KIND, "MAX" to ClangSemanticTokens.MACRO_KIND),
            spelled("8 3 cppMacro D\n22 3 cppMacro -\n", text))
    }

    @Test
    fun malformedOrOutOfRangeLinesAreSkipped() {
        val text = "int a;"
        assertEquals(emptyList<Pair<String, String>>(), spelled("garbage\n4 1\n99 3 field -\n4 x field -\n", text))
        // An offset that lands inside a multi-byte character is not a token boundary.
        assertEquals(emptyList<Pair<String, String>>(), spelled("1 1 field -\n", "é"))
    }
}
